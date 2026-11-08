package com.example.checkin

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Builds a human-readable JSON for pasting/attaching to an LLM chat. Nothing is sent by the app itself. */
object AiExport {
    private val RU = Locale("ru")

    private const val PROMPT =
        "Ты — внимательный помощник по самонаблюдению. Ниже — личный дневник состояния за период (поле records: " +
            "каждая запись имеет дату и время, в течение одного дня записей может быть несколько) и автоматическая сводка (autoSummary). " +
            "Это самодиагностика, а не медицинские данные. " +
            "Задача: 1) кратко опиши общую картину; 2) найди закономерности и связи между привычками, факторами, сном и " +
            "состоянием (учитывай эффект следующего дня); 3) посмотри динамику внутри дня и различия по времени суток " +
            "(утро, день, вечер, ночь, см. byTimeOfDay); 4) отметь необычные дни и возможные причины; " +
            "5) оцени, достаточно ли данных для выводов; 6) предложи 3 небольших эксперимента на ближайшие 2 недели " +
            "и скажи, какие данные стоит добавить в дневник. " +
            "Правила: не ставь диагнозов и не давай медицинских назначений; корреляция не равна причинности — " +
            "формулируй осторожно; опирайся на конкретные даты, время и числа; если данных мало — так и скажи; " +
            "если видишь признаки серьёзного ухудшения состояния, мягко посоветуй обратиться к специалисту. " +
            "Числа, посчитанные приложением (средние, сводку, автоматические закономерности, баллы теста), считай готовыми и не пересчитывай; " +
            "оценивай и критикуй их интерпретацию, предлагай альтернативные объяснения и эксперименты. " +
            "Отвечай по-русски, структурированно и без воды."

    private const val TEST_PROMPT =
        " В поле personalityTest дан результат самоотчётного опросника личности (IPIP-NEO-120) как фоновая информация. " +
            "Правила работы с ним: сначала проанализируй дневник, не опираясь на тест; затем отдельным разделом укажи, " +
            "что дополнительно можно предположить с учётом черт. Не объясняй наблюдаемые закономерности чертой личности, " +
            "если их не подтверждают данные дневника. Баллы это сырые средние (1–5) без нормативных данных: не называй черты " +
            "высокими или низкими относительно населения без оговорок. Тест это самоотчёт с погрешностью, русский перевод " +
            "не валидирован, фасеты менее надёжны, чем домены. Черты относительно устойчивы, а дневник описывает состояния."

    private const val ITEMS_PROMPT =
        " Если в personalityTest есть items, это ответы по отдельным пунктам (answer от 1 до 5, key + или - показывает направление пункта): " +
            "используй их только чтобы увидеть разброс внутри фасета и проверить, на чём держится балл. " +
            "Не делай выводов о человеке из одного пункта."

    private fun testBlock(r: TestResult, withFacets: Boolean, withItems: Boolean): JSONObject {
        val o = JSONObject()
        o.put("instrument", "IPIP-NEO-120 (Johnson, 2014), Russian translation, not validated")
        o.put("taken", java.time.Instant.ofEpochMilli(r.takenAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())
        o.put("scoring", "Mean of item answers 1-5 after reversing negatively keyed items. No norms applied.")
        val doms = JSONArray()
        for (d in 0 until 5) {
            val x = JSONObject()
            x.put("name", Ipip.DOMAIN_EN[d])
            x.put("nameRu", Ipip.DOMAIN_RU[d])
            x.put("mean", Math.round(Ipip.domainMean(d, r.answers) * 100) / 100.0)
            x.put("sum24to120", Ipip.domainSum(d, r.answers))
            x.put("alpha", Ipip.DOMAIN_ALPHA[d])
            doms.put(x)
        }
        o.put("domains", doms)
        if (withFacets) {
            val facets = JSONArray()
            for (g in 0 until 30) {
                val x = JSONObject()
                x.put("domain", Ipip.DOMAIN_EN[g / 6])
                x.put("name", Ipip.FACET_EN[g])
                x.put("nameRu", Ipip.FACET_RU[g])
                x.put("mean", Math.round(Ipip.facetMean(g, r.answers) * 100) / 100.0)
                x.put("alpha", Ipip.FACET_ALPHA[g])
                facets.put(x)
            }
            o.put("facets", facets)
        }
        if (withItems) {
            val arr = JSONArray()
            for (item in Ipip.ITEMS) {
                val x = JSONObject()
                x.put("n", item.n)
                x.put("facet", Ipip.FACET_EN[item.facet])
                x.put("item", item.en)
                x.put("key", if (item.key > 0) "+" else "-")
                x.put("answer", r.answers[item.n - 1])
                arr.put(x)
            }
            o.put("itemsNote", "Answer scale: 1 = Very Inaccurate ... 5 = Very Accurate. For key - items the scored value is 6 minus the answer.")
            o.put("items", arr)
        }
        val v = JSONObject()
        v.put("inconsistency", Math.round(Ipip.inconsistency(r.answers) * 100) / 100.0)
        v.put("inconsistencyNote", "Mean |a+b-6| over 29 opposite-keyed item pairs; random answering gives about 1.6, lower is more consistent.")
        v.put("mostFrequentAnswerShare", Math.round(Ipip.modeShare(r.answers) * 100) / 100.0)
        o.put("responseQuality", v)
        return o
    }

    private fun num(m: Metric, v: Double): Any = if (v % 1.0 == 0.0 && Math.abs(v) < 1e9) v.toInt() else v

    private fun labelOf(id: String) =
        HABITS.firstOrNull { it.id == id }?.label ?: REASONS.firstOrNull { it.id == id }?.label ?: id

    fun build(store: Store, days: Int, includeNotes: Boolean, includeTest: Boolean = false): String {
        val today = LocalDate.now()
        val from = if (days > 0) today.minusDays(days - 1L) else null
        val entries = store.entries
            .filter { from == null || !LocalDate.parse(it.date).isBefore(from) }
            .sortedWith(compareBy<DayEntry>({ it.date }, { it.time }, { it.id }))
        val daily = aggregateDays(entries)

        val root = JSONObject()
        root.put("format", "checkin-for-ai")
        root.put("generated", today.toString())
        val lastTest = if (includeTest) store.results.lastOrNull { Ipip.isValid(it.answers) } else null
        root.put("instructions", if (lastTest != null) PROMPT + TEST_PROMPT + (if (AiOptions.items) ITEMS_PROMPT else "") else PROMPT)

        val period = JSONObject()
        period.put("from", entries.firstOrNull()?.date ?: "")
        period.put("to", entries.lastOrNull()?.date ?: "")
        period.put("daysWithRecords", daily.size)
        period.put("records", entries.size)
        period.put("notesIncluded", includeNotes)
        root.put("period", period)

        val used = METRICS.filter { m -> entries.any { it.metrics.containsKey(m.id) } }
        val scales = JSONArray()
        used.forEach { m ->
            val o = JSONObject()
            o.put("name", m.title)
            o.put("range", if (m.step >= 1.0) "${m.min.toInt()}-${m.max.toInt()}" else "${m.min}-${m.max}")
            o.put("meaning", when (m.polarity) {
                1 -> "чем выше, тем лучше"
                -1 -> "чем выше, тем хуже"
                else -> "нейтральная шкала"
            })
            scales.put(o)
        }
        root.put("scales", scales)

        val summary = JSONObject()
        val avg = JSONObject()
        used.forEach { m ->
            val v = daily.mapNotNull { it.metrics[m.id] }
            if (v.isNotEmpty()) avg.put(m.title, Math.round(v.average() * 10) / 10.0)
        }
        summary.put("averagesPerDay", avg)
        val tod = JSONObject()
        for (p in 0..3) {
            val rs = entries.filter { dayPart(it.time) == p }
            if (rs.isEmpty()) continue
            val o = JSONObject()
            o.put("records", rs.size)
            for (id in listOf("mood", "energy", "anxiety")) {
                val m = METRICS.firstOrNull { it.id == id } ?: continue
                val v = rs.mapNotNull { it.metrics[id] }
                if (v.isNotEmpty()) o.put(m.title, Math.round(v.average() * 10) / 10.0)
            }
            tod.put(DAY_PARTS[p], o)
        }
        summary.put("byTimeOfDay", tod)
        val freq = JSONObject()
        HABITS.forEach { h ->
            val c = daily.count { it.habits[h.id] == true }
            if (c > 0) freq.put(h.label, c)
        }
        summary.put("habitDays", freq)
        val factorFreq = JSONObject()
        REASONS.forEach { r ->
            val c = daily.count { r.id in it.tags }
            if (c > 0) factorFreq.put(r.label, c)
        }
        summary.put("factorDays", factorFreq)
        val findings = JSONArray()
        Analyzer.generate(daily).forEach { findings.put(it.text) }
        summary.put("autoFindings", findings)
        summary.put("autoFindingsNote", "Простая статистика на устройстве по средним значениям за день (корреляции), не доказанные причины.")
        root.put("autoSummary", summary)

        val arr = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
            val d = LocalDate.parse(e.date)
            o.put("date", e.date)
            o.put("time", e.time)
            o.put("weekday", d.dayOfWeek.getDisplayName(TextStyle.FULL, RU))
            used.forEach { m -> e.metrics[m.id]?.let { o.put(m.title, num(m, it)) } }
            val done = JSONArray()
            e.habits.filter { it.value }.keys.forEach { done.put(labelOf(it)) }
            if (done.length() > 0) o.put("habits", done)
            val factors = JSONArray()
            val tags = JSONArray()
            e.tags.forEach { t ->
                when {
                    t == "demo" -> {}
                    t.startsWith("#") -> tags.put(t)
                    else -> factors.put(labelOf(t))
                }
            }
            if (factors.length() > 0) o.put("factors", factors)
            // Hashtags are lifted out of the note text: with notes switched off they must not leave the device either.
            if (includeNotes && tags.length() > 0) o.put("tags", tags)
            if (includeNotes && e.note.isNotBlank()) o.put("note", e.note)
            arr.put(o)
        }
        root.put("records", arr)
        if (lastTest != null) root.put("personalityTest", testBlock(lastTest, AiOptions.facets, AiOptions.items))
        // Compact (no pretty-printing): the text goes into an LLM context, where indentation is pure waste.
        return root.toString()
    }
}
