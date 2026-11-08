package com.example.checkin

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** CSV export: one row per record, columns for every metric / habit plus tags and note. */
object CsvExport {
    internal fun esc(s: String): String {
        // A cell starting with = + - @ (or tab / CR) is executed as a formula by spreadsheet programs.
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** Fixed '.' decimal separator: with the device locale (ru) "6,5" would split the CSV cell in two. */
    internal fun num(m: Metric, v: Double): String =
        if (m.step >= 1.0) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

    fun build(store: Store): String {
        val metrics = Cfg.metrics
        val habits = Cfg.habits
        val sb = StringBuilder()
        sb.append("date,time")
        metrics.forEach { sb.append(',').append(esc(it.title)) }
        habits.forEach { sb.append(',').append(esc(it.label)) }
        sb.append(",tags,note\n")
        val sorted = store.entries.sortedWith(compareBy<DayEntry>({ it.date }, { it.time }, { it.id }))
        for (e in sorted) {
            sb.append(e.date).append(',').append(e.time)
            metrics.forEach { m -> sb.append(',').append(e.metrics[m.id]?.let { num(m, it) } ?: "") }
            habits.forEach { h -> sb.append(',').append(if (e.habits[h.id] == true) "1" else "") }
            sb.append(',').append(esc(e.tags.filter { it != "demo" }.joinToString(";")))
            sb.append(',').append(esc(e.note.replace("\r", " ").replace("\n", " ")))
            sb.append('\n')
        }
        return sb.toString()
    }
}

/** A short printable report for a psychologist / doctor: period, averages, insights, records. */
object PdfReport {
    fun build(store: Store, days: Int = 30): ByteArray {
        val today = LocalDate.now()
        val from = today.minusDays(days - 1L)
        val entries = store.entries
            .filter { !LocalDate.parse(it.date).isBefore(from) }
            .sortedWith(compareBy<DayEntry>({ it.date }, { it.time }, { it.id }))
        val daily = aggregateDays(entries)

        val lines = mutableListOf<String>()
        lines.add("Checkin — отчёт для специалиста")
        lines.add("Сформирован: $today, период: $from — $today")
        lines.add("Записей: ${entries.size}, дней с записями: ${daily.size}")
        lines.add("")
        lines.add("Средние значения за день:")
        val used = Cfg.metrics.filter { m -> daily.any { it.metrics.containsKey(m.id) } }
        used.forEach { m ->
            val v = daily.mapNotNull { it.metrics[m.id] }
            if (v.isNotEmpty()) lines.add("  ${m.title}: %.1f (мин %.1f, макс %.1f)".format(v.average(), v.minOrNull() ?: 0.0, v.maxOrNull() ?: 0.0))
        }
        val habits = Cfg.habits.filter { h -> daily.any { it.habits[h.id] == true } }
        if (habits.isNotEmpty()) {
            lines.add("")
            lines.add("Привычки (дней с отметкой):")
            habits.forEach { h -> lines.add("  ${h.label}: ${daily.count { it.habits[h.id] == true }}") }
        }
        val insights = Analyzer.generate(daily)
        if (insights.isNotEmpty()) {
            lines.add("")
            lines.add("Автоматические закономерности (предварительно, корреляции, не причины):")
            insights.forEach { lines.add("  - ${it.text}") }
        }
        lines.add("")
        lines.add("Записи:")
        for (e in entries) {
            val mtxt = used.mapNotNull { m -> e.metrics[m.id]?.let { m.title + " " + (if (m.step >= 1.0) it.toInt().toString() else "%.1f".format(it)) } }
                .joinToString(", ")
            val htxt = e.habits.filter { it.value }.keys.map { habitLabel(it) }.joinToString(", ")
            val ttxt = e.tags.filter { it != "demo" }.map { tagLabel(it) }.joinToString(", ")
            lines.add("${e.date} ${e.time} — $mtxt" + (if (htxt.isNotEmpty()) " | $htxt" else "") + (if (ttxt.isNotEmpty()) " | $ttxt" else ""))
            if (e.note.isNotBlank()) {
                lines.add("    " + e.note.replace("\n", " "))   // wrapped to the page width when drawn
            }
        }
        lines.add("")
        lines.add("Данные самонаблюдения, не медицинский документ.")

        return render(lines)
    }

    /** Word-wraps one logical line to [maxW]; continuation lines keep the indent. Never splits an emoji. */
    private fun wrapLine(line: String, paint: Paint, maxW: Float): List<String> {
        if (line.isEmpty()) return listOf("")
        val indent = line.takeWhile { it == ' ' }
        val out = mutableListOf<String>()
        var rest = line
        var first = true
        while (rest.isNotEmpty()) {
            val avail = if (first) maxW else maxW - paint.measureText(indent)
            var n = paint.breakText(rest, true, avail, null)
            if (n <= 0) n = 1
            if (n < rest.length) {
                val sp = rest.lastIndexOf(' ', n - 1)
                if (sp > n / 2) n = sp + 1
                if (n > 0 && Character.isHighSurrogate(rest[n - 1])) n -= 1
                if (n <= 0) n = minOf(2, rest.length)
            }
            val piece = rest.substring(0, n).trimEnd()
            out.add(if (first) piece else indent + piece)
            rest = rest.substring(n).trimStart()
            first = false
        }
        return out
    }

    private fun render(lines: List<String>): ByteArray {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 11f; typeface = Typeface.DEFAULT }
        val titlePaint = Paint().apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        val pageW = 595
        val pageH = 842
        val margin = 40
        val leading = 17f
        var pageNo = 0
        var y = 0f
        var page: PdfDocument.Page? = null
        var canvas: android.graphics.Canvas? = null

        fun newPage() {
            val info = PdfDocument.PageInfo.Builder(pageW, pageH, ++pageNo).create()
            page = doc.startPage(info)
            canvas = page!!.canvas
            y = margin + leading
        }
        fun closePage() { page?.let { doc.finishPage(it) } }

        val maxW = (pageW - 2 * margin).toFloat()
        newPage()
        lines.forEachIndexed { i, line ->
            val p = if (i == 0) titlePaint else paint
            for (part in wrapLine(line, p, maxW)) {
                if (y > pageH - margin) { closePage(); newPage() }
                canvas!!.drawText(part, margin.toFloat(), y, p)
                y += leading
            }
        }
        closePage()
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
