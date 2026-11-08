@file:OptIn(ExperimentalLayoutApi::class)

package com.example.checkin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val RUW = Locale("ru")
private val UP_OK = Color(0xFF3BAA7E)
private val DOWN_BAD = Color(0xFFE5736A)

private fun inWeek(date: String, from: LocalDate, to: LocalDate): Boolean {
    val d = LocalDate.parse(date)
    return !d.isBefore(from) && !d.isAfter(to)
}

private fun avgOf(entries: List<DayEntry>, id: String): Double? {
    val v = entries.mapNotNull { it.metrics[id] }
    return if (v.isEmpty()) null else v.average()
}

private fun f1(x: Double) = "%.1f".format(x)

private fun dayLabel(date: String): String {
    val d = LocalDate.parse(date)
    return d.dayOfWeek.getDisplayName(TextStyle.SHORT, RUW) + ", " + d.dayOfMonth + "." + "%02d".format(d.monthValue)
}

/** Weekly summary. Takes all records; days are averaged over their records. */
@Composable
fun WeekSummaryCard(all: List<DayEntry>) {
    val today = LocalDate.now()
    val daily = remember(all) { aggregateDays(all) }
    val cur = daily.filter { inWeek(it.date, today.minusDays(6), today) }
    val prev = daily.filter { inWeek(it.date, today.minusDays(13), today.minusDays(7)) }
    val recordsCount = all.count { inWeek(it.date, today.minusDays(6), today) }
    SoftCard {
        SectionTitle("Итог недели")
        if (cur.isEmpty()) {
            Text("За последние 7 дней записей нет. Отметь хотя бы один раз, и здесь появится сводка.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Дней с записями: ${cur.size} из 7, всего " + recWord(recordsCount), style = MaterialTheme.typography.bodyMedium)
            val shown = METRICS.filter { m ->
                !m.hidden && (m.id in listOf("mood", "energy", "anxiety", "sleep_hours") || isCustomId(m.id))
            }
            shown.forEach { m ->
                val a = avgOf(cur, m.id)
                if (a != null) {
                    val b = avgOf(prev, m.id)
                    val delta = if (b == null) null else a - b
                    var tint: Color? = null
                    var arrow = ""
                    if (delta != null) {
                        arrow = if (delta > 0.04) "↑" else if (delta < -0.04) "↓" else "→"
                        if (m.polarity != 0 && Math.abs(delta) > 0.04) {
                            tint = if ((delta > 0) == (m.polarity > 0)) UP_OK else DOWN_BAD
                        }
                    }
                    val title = if (m.emoji.isBlank()) m.title else m.emoji + " " + m.title
                    val tail = if (delta != null) "  " + arrow + " " + f1(Math.abs(delta)) else ""
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f))
                        Text(
                            f1(a) + tail,
                            color = tint ?: MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (prev.isEmpty()) {
                Text("Стрелки появятся, когда будут записи за предыдущую неделю.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Значения: среднее за день, потом за неделю. Стрелки: изменение к прошлой неделе. Зелёный: стало лучше, красный: хуже.", style = MaterialTheme.typography.bodySmall)
            }

            val withMood = cur.filter { it.metrics["mood"] != null }
            if (withMood.size >= 2) {
                val best = withMood.maxByOrNull { it.metrics["mood"] ?: 0.0 }
                val worst = withMood.minByOrNull { it.metrics["mood"] ?: 0.0 }
                if (best != null && worst != null && best.date != worst.date) {
                    Text(
                        "Лучший день: " + dayLabel(best.date) + " (" + f1(best.metrics["mood"] ?: 0.0) + "), " +
                            "самый тяжёлый: " + dayLabel(worst.date) + " (" + f1(worst.metrics["mood"] ?: 0.0) + ").",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            val hc = HABITS.map { h -> h to cur.count { it.habits[h.id] == true } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(3)
            if (hc.isNotEmpty()) {
                Text(
                    "Чаще всего отмечено (дней): " + hc.joinToString(", ") { it.first.label + " ×" + it.second },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun HistoryDeleteDialog(store: Store, id: String?, onDone: () -> Unit) {
    if (id == null) return
    val e = store.byId(id)
    val what = if (e == null) "Запись" else "Запись за " + e.date + ", " + e.time
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Удалить запись?") },
        text = { Text(what + " будет удалена. Это нельзя отменить.") },
        confirmButton = { TextButton(onClick = { store.delete(id); onDone() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } }
    )
}
