package com.example.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.abs

internal enum class Verdict { NONE, BETTER, WORSE }

/**
 * Mood is the outcome, and higher mood is always better, whichever way the habit was pushed
 * ("less of a habit that lowers mood" and "more of a habit that raises it" both succeed when mood goes up).
 * [scatter] is the ordinary day-to-day spread of mood.
 */
internal fun verdictOf(diff: Double, scatter: Double): Verdict = when {
    abs(diff) < 0.5 * scatter -> Verdict.NONE
    diff > 0 -> Verdict.BETTER
    else -> Verdict.WORSE
}

/** Closes the loop between "found a connection" and "did something about it". */
object Experiments {
    const val LENGTH_DAYS = 14L

    fun createFromHint(store: Store, hint: Hint, label: String): Experiment {
        val start = LocalDate.now()
        val x = Experiment(
            id = newEntryId(), title = label, habitId = hint.habitId, reduce = hint.reduce,
            startDate = start.toString(), endDate = start.plusDays(LENGTH_DAYS - 1).toString(),
            status = 0, verdict = null, createdAt = System.currentTimeMillis()
        )
        store.addExperiment(x)
        return x
    }

    fun cancel(store: Store, x: Experiment) {
        store.updateExperiment(x.copy(status = 2))
    }

    /** Days with records in [from, to]. */
    private fun daysWithData(store: Store, from: LocalDate, to: LocalDate): List<DayEntry> =
        store.entries.filter { !LocalDate.parse(it.date).isBefore(from) && !LocalDate.parse(it.date).isAfter(to) }
            .let { aggregateDays(it) }

    private fun avgMood(list: List<DayEntry>): Double? {
        val v = list.mapNotNull { it.metrics["mood"] }
        return if (v.isEmpty()) null else v.average()
    }

    /**
     * Verdict for a finished experiment: compare the mood during it with the 14 days before it,
     * in units of the ordinary scatter (SD), and check that the habit really moved in the intended
     * direction compared with the 14 days before.
     */
    fun finish(store: Store, x: Experiment): String {
        val start = LocalDate.parse(x.startDate)
        val end = LocalDate.parse(x.endDate)
        val before = daysWithData(store, start.minusDays(LENGTH_DAYS), start.minusDays(1))
        val during = daysWithData(store, start, end)
        val b = avgMood(before)
        val d = avgMood(during)
        val habitLabel = HABITS.firstOrNull { it.id == x.habitId }?.label ?: "привычка"

        val marked = during.count { it.habits[x.habitId] == true }
        val markedBefore = before.count { it.habits[x.habitId] == true }
        val shareDuring = if (during.isEmpty()) 0.0 else marked.toDouble() / during.size
        val shareBefore = if (before.isEmpty()) null else markedBefore.toDouble() / before.size

        val parts = mutableListOf<String>()
        parts.add(
            "«$habitLabel» отмечено в $marked из ${during.size} дней с записями" +
                (if (shareBefore != null) " (за 14 дней до: $markedBefore из ${before.size})." else ".")
        )
        if (during.isNotEmpty()) {
            // The habit must actually have moved in the intended direction, otherwise nothing was tested.
            val moved = when {
                shareBefore == null -> if (x.reduce) true else shareDuring >= 0.5
                x.reduce -> shareDuring < shareBefore
                else -> shareDuring > shareBefore
            }
            if (!moved) {
                parts.add(
                    if (x.reduce) "Привычка не стала реже, чем до эксперимента, так что результат нельзя считать проверкой."
                    else "Привычка не стала чаще, чем до эксперимента, так что результат нельзя считать проверкой."
                )
            }
        }
        if (b == null || d == null) {
            parts.add(if (d == null) "В дни эксперимента нет записей о настроении — сравнить не с чем." else "Нет данных за 14 дней до эксперимента — сравнить не с чем.")
        } else {
            val scatter = Analyzer.stddev(before.mapNotNull { it.metrics["mood"] } + during.mapNotNull { it.metrics["mood"] }).coerceAtLeast(0.5)
            val diff = d - b
            parts.add(
                "Настроение: %.1f против %.1f до эксперимента (разница %.1f, обычный разброс %.1f).".format(d, b, diff, scatter)
            )
            parts.add(
                when (verdictOf(diff, scatter)) {
                    Verdict.NONE -> "Вывод: видимых изменений нет — разница в пределах обычного разброса. Можно продлить эксперимент или попробовать другое."
                    Verdict.BETTER -> "Вывод: настроение стало лучше 🎉 Это согласуется с тем, что показывала статистика."
                    Verdict.WORSE -> "Вывод: настроение стало хуже. Возможно, на него влияет что-то другое — смотри другие закономерности."
                }
            )
        }
        parts.add("Это самонаблюдение, а не доказательство: за те же 14 дней могли измениться и другие обстоятельства.")
        return parts.joinToString("\n\n")
    }

    fun maybeAutoFinish(store: Store) {
        val today = LocalDate.now()
        for (x in store.experiments.toList()) {
            if (x.status == 0 && LocalDate.parse(x.endDate).isBefore(today)) {
                store.updateExperiment(x.copy(status = 1, verdict = finish(store, x)))
            }
        }
    }
}

@Composable
fun ExperimentsSection(store: Store) {
    var confirmCancel by remember { mutableStateOf<Experiment?>(null) }
    val today = LocalDate.now()
    val all = store.experiments.toList()
    val active = all.filter { it.status == 0 }.sortedBy { it.startDate }
    val done = all.filter { it.status == 1 }.sortedByDescending { it.endDate }.take(3)

    SoftCard {
        SectionTitle("Эксперименты")
        Text(
            "Закрывают круг: берёшь найденную связь и проверяешь её на себе 14 дней. Отмечай привычку как обычно, " +
                "вердикт приложение подведёт само в конце срока.",
            style = MaterialTheme.typography.bodySmall
        )
        if (active.isEmpty() && done.isEmpty()) {
            Text(
                "Пока пусто. Нажми «Проверить за 14 дней» у закономерности ниже — например, если приложение " +
                    "заметило, что настроение ниже после алкоголя.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        active.forEach { x ->
            val start = LocalDate.parse(x.startDate)
            val end = LocalDate.parse(x.endDate)
            val day = (java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1).coerceIn(1, Experiments.LENGTH_DAYS)
            val habitLabel = HABITS.firstOrNull { it.id == x.habitId }?.label ?: x.habitId
            val marked = store.entries.filter { it.date >= x.startDate && it.date <= x.endDate && it.date <= today.toString() }
                .map { it.date }.distinct()
                .count { date -> store.forDate(date).any { it.habits[x.habitId] == true } }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "🔬 ${x.title} — день $day из ${Experiments.LENGTH_DAYS} (до ${"%02d.%02d".format(end.dayOfMonth, end.monthValue)})",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Text(
                    "Цель: отмечать «$habitLabel» ${if (x.reduce) "реже" else "чаще"}. Отмечено в $marked днях.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { confirmCancel = x }) { Text("Отменить эксперимент") }
            }
        }
        done.forEach { x ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("✅ ${x.title} (${x.startDate} — ${x.endDate})", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(x.verdict ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    confirmCancel?.let { x ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Отменить эксперимент?") },
            text = { Text("«${x.title}» будет закрыт без вердикта.") },
            confirmButton = { TextButton(onClick = { Experiments.cancel(store, x); confirmCancel = null }) { Text("Отменить эксперимент") } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Назад") } }
        )
    }
}

@Composable
fun HabitExperimentButton(store: Store, hint: Hint, label: String) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }) { Text("🔬 Проверить за 14 дней") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Эксперимент на 14 дней") },
            text = {
                Text(
                    "Приложение будет отслеживать «$label», а в конце срока сравнит настроение с предыдущими 14 днями " +
                        "и покажет вердикт: стало лучше, хуже или не видно. Отмечай привычку в записях как обычно."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Experiments.createFromHint(store, hint, label)
                    confirm = false
                }) { Text("Начать") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Не сейчас") } }
        )
    }
}
