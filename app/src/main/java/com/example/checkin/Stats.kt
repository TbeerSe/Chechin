@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.example.checkin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private fun streakOf(entries: List<DayEntry>): Int {
    val set = entries.map { it.date }.toSet()
    var d = LocalDate.now()
    if (d.toString() !in set) d = d.minusDays(1)
    var c = 0
    while (d.toString() in set) { c++; d = d.minusDays(1) }
    return c
}

/** Gentle, non-diagnostic nudge when the mood stays low for a while. */
@Composable
private fun LowMoodSupportCard(daily: List<DayEntry>) {
    val today = LocalDate.now()
    val recent = daily.filter { !LocalDate.parse(it.date).isBefore(today.minusDays(14)) }
    var streak = 0
    // Count from the latest day with a record, not from today: on a bad day people tend to skip the
    // entry, and an empty today must not hide the card. A record older than 3 days is too stale to show.
    var d = recent.maxOfOrNull { it.date }?.let { LocalDate.parse(it) } ?: return   // ISO dates sort as strings
    if (d.isBefore(today.minusDays(3))) return
    while (true) {
        val e = recent.firstOrNull { it.date == d.toString() }
        if (e == null || (e.metrics["mood"] ?: 99.0) > 3.0) break
        streak++
        d = d.minusDays(1)
    }
    if (streak >= 5) {
        SoftCard {
            SectionTitle("🫂 Стоит позаботиться о себе")
            Text(
                "Настроение держится низким уже несколько дней подряд. Это не диагноз — просто сигнал " +
                    "прислушаться к себе: выспись, выйди на воздух, напиши близкому. Если так продолжается " +
                    "дольше пары недель или совсем тяжело — имеет смысл поговорить со специалистом или " +
                    "позвонить на телефон доверия. Просить о поддержке — нормально. " +
                    "Если появляются мысли навредить себе, не оставайся с этим один: набери 112 или местную кризисную линию.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, value: String, label: String) {
    Card(
        modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TimeOfDayCard(all: List<DayEntry>) {
    SoftCard {
        SectionTitle("Время суток")
        val rows = (0..3).map { p -> p to all.filter { dayPart(it.time) == p } }.filter { it.second.size >= 3 }
        if (rows.size < 2) {
            Text(
                "Появится, когда будет хотя бы по 3 записи в двух разных частях суток (утро, день, вечер, ночь).",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val mm = listOf("mood", "energy", "anxiety").mapNotNull { id -> METRICS.firstOrNull { it.id == id && !it.hidden } }
            rows.forEach { (p, rs) ->
                val parts = mm.mapNotNull { m ->
                    val v = rs.mapNotNull { it.metrics[m.id] }
                    if (v.isEmpty()) null else m.title.lowercase() + " " + "%.1f".format(v.average())
                }
                Text(
                    DAY_PARTS[p].replaceFirstChar { it.uppercase() } + " · " + recWord(rs.size) + ": " + parts.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            val moodByPart = rows.mapNotNull { (p, rs) ->
                val v = rs.mapNotNull { it.metrics["mood"] }
                if (v.size < 3) null else p to v.average()
            }
            if (moodByPart.size >= 2) {
                val best = moodByPart.maxByOrNull { it.second }
                val worst = moodByPart.minByOrNull { it.second }
                if (best != null && worst != null && best.second - worst.second >= 0.5) {
                    Text(
                        "Настроение обычно выше всего: " + DAY_PARTS[best.first] + ", ниже всего: " + DAY_PARTS[worst.first] + ".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text("Это средние по записям, а не причины.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatsScreen(store: Store) {
    val raw = store.entries.toList()
    val daily = remember(raw) { aggregateDays(raw) }
    val insightsState by produceState<List<Insight>?>(initialValue = null, daily) {
        value = withContext(Dispatchers.Default) { Analyzer.generate(daily) }
    }
    val insights = insightsState ?: emptyList()
    var metricId by rememberSaveable { mutableStateOf("mood") }
    var chartDays by rememberSaveable { mutableStateOf(30) }
    var scatterX by rememberSaveable { mutableStateOf("energy") }

    // Close out experiments whose 14 days have passed.
    // Keyed on Unit, not raw.size: opening Stats without creating a new record must still
    // finalize an expired experiment.
    LaunchedEffect(Unit) { Experiments.maybeAutoFinish(store) }

    val visible = METRICS.filter { !it.hidden }
    val m = METRICS.firstOrNull { it.id == metricId } ?: visible.firstOrNull() ?: METRICS.first()
    val today = LocalDate.now()
    val byDate = daily.associateBy { it.date }
    val values = (chartDays - 1 downTo 0).map { byDate[today.minusDays(it.toLong()).toString()]?.metrics?.get(m.id) }
    val known = values.filterNotNull()
    val moodWeek = (0..6).mapNotNull { byDate[today.minusDays(it.toLong()).toString()]?.metrics?.get("mood") }
    val moodMetric = METRICS.firstOrNull { it.id == "mood" }

    // scatter: one chosen metric (x) against mood (y), raw records
    val scatterPoints = remember(raw, scatterX) {
        raw.mapNotNull { e ->
            val x = e.metrics[scatterX]
            val y = e.metrics["mood"]
            if (x != null && y != null) x to y else null
        }
    }
    val sxMetric = METRICS.firstOrNull { it.id == scatterX }
    val scatterR = remember(scatterPoints) {
        Analyzer.spearman(scatterPoints.map { it.first }, scatterPoints.map { it.second })
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle("Статистика")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Modifier.weight(1f), "${raw.size}", "записей")
            Tile(Modifier.weight(1f), "${streakOf(daily)}", "дней подряд")
            Tile(Modifier.weight(1f), if (moodWeek.isEmpty()) "–" else "%.1f".format(moodWeek.average()), "настр. за 7 дн.")
        }

        LowMoodSupportCard(daily)
        ExperimentsSection(store)
        WeekSummaryCard(raw)
        TimeOfDayCard(raw)
        NotesWordsCard(raw)

        SoftCard {
            SectionTitle("Календарь настроения")
            MoodHeatmap(daily, weeks = 12, moodMetric = moodMetric, modifier = Modifier.fillMaxWidth())
            Text("12 недель: цвет — среднее настроение за день, серый — нет записей.", style = MaterialTheme.typography.bodySmall)
        }

        SoftCard {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { mm -> Chip(withEm(mm.emoji, mm.title), mm.id == m.id) { metricId = mm.id } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 дней", 90 to "90 дней", 365 to "Год").forEach { (d, label) ->
                    Chip(label, chartDays == d) { chartDays = d }
                }
            }
            Text("Среднее за день · шкала ${fmtM(m, m.min)}–${fmtM(m, m.max)}", style = MaterialTheme.typography.labelLarge)
            LineChart(values, m.min, m.max, Modifier.fillMaxWidth().height(180.dp))
            if (known.isNotEmpty()) {
                Text("Среднее: ${"%.1f".format(known.average())} · дней с данными: ${known.size}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("За выбранный период данных по этому показателю нет.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (sxMetric != null && sxMetric.id != "mood" && scatterPoints.size >= 8) {
            SoftCard {
                SectionTitle("Связь двух шкал")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    visible.filter { it.id != "mood" }.forEach { mm ->
                        Chip(withEm(mm.emoji, mm.title), mm.id == scatterX) { scatterX = mm.id }
                    }
                }
                val xMin = sxMetric.min; val xMax = sxMetric.max
                ScatterChart(scatterPoints, xMin, xMax, 1.0, 10.0, Modifier.fillMaxWidth().height(200.dp))
                Text(
                    "Каждая точка — одна запись (${sxMetric.title.lowercase()} × настроение)" +
                        (scatterR?.let { ", связь по Спирмену %.2f".format(it) } ?: "") +
                        ". Совпадение точек на шкале 1–10 — это нормально, шкалы дискретные.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        SectionTitle("Закономерности")
        if (daily.size < Analyzer.MIN_DAYS) {
            SoftCard {
                Text("Нужно минимум ${Analyzer.MIN_DAYS} дней с записями, осталось ещё ${Analyzer.MIN_DAYS - daily.size}. Для надёжных выводов лучше 3–4 недели 🌱")
            }
        } else if (insightsState == null) {
            SoftCard { Text("Считаю…") }
        } else if (insights.isEmpty()) {
            SoftCard { Text("Пока явных связей не найдено. Это нормально: данных мало или зависимости слабые.") }
        } else {
            insights.forEach { insight ->
                SoftCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(emojiFor(insight.text), fontSize = 22.sp)
                        Text(insight.text, Modifier.weight(1f))
                    }
                    val hint = insight.hint
                    if (hint != null && HABITS.any { it.id == hint.habitId }) {
                        val habitLabel = HABITS.first { it.id == hint.habitId }.label
                        HabitExperimentButton(store, hint, if (hint.reduce) "Меньше «$habitLabel»" else "Больше «$habitLabel»")
                    }
                }
            }
        }
        Text(
            "Пометка «предварительно» означает: связь выдержала проверку на случайность (перестановочный тест " +
                "с поправкой на множественные сравнения) и по размеру эффекта, но это всё равно не причинность. " +
                "Если за день несколько записей, для закономерностей берётся их среднее. Соседние дни похожи друг на друга, " +
                "поэтому уверенность по таким оценкам завышена — это повод проверить, а не вывод.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
