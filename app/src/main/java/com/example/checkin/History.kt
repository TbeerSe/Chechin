@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.example.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

// ------------------------------------------------------------ "On this day a month ago"
@Composable
private fun MonthAgoCard(store: Store) {
    val d = LocalDate.now().minusMonths(1)
    val recs = store.forDate(d.toString())
    if (recs.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    SoftCard {
        SectionTitle("В этот день месяц назад · " + d.format(DateTimeFormatter.ofPattern("d MMMM", RUS)))
        recs.forEach { e ->
            val mood = e.metrics["mood"]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(faceOf(mood), fontSize = 22.sp)
                Column {
                    Text(e.time, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    val tags = e.tags.filter { it != "demo" }.map { tagLabel(it) }
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = primary)
                }
            }
        }
    }
}

// ------------------------------------------------------------ History (list of days)
@Composable
fun HistoryScreen(store: Store, onOpen: (LocalDate) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = if (query.isBlank()) store.entries.toList() else store.search(query)
    val groups = all.groupBy { it.date }
    val dates = groups.keys.sortedDescending()
    val primary = MaterialTheme.colorScheme.primary
    val moodMetric = METRICS.firstOrNull { it.id == "mood" }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("История") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                placeholder = { Text("Поиск по заметкам, тегам, привычкам…") }
            )
        }
        if (dates.isEmpty()) {
            item {
                SoftCard {
                    Text(if (query.isBlank()) "Пока нет записей. Заполни первую на вкладке «Сегодня» 🌱" else "По запросу «$query» ничего не найдено.")
                }
            }
        } else {
            if (query.isBlank()) item { MonthAgoCard(store) }
            item { Text("Нажми на день, чтобы увидеть все его записи.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(dates, key = { it }) { date ->
            val recs = groups[date] ?: emptyList()
            val d = LocalDate.parse(date)
            val avg = aggregateDays(recs).firstOrNull()
            val mood = avg?.metrics?.get("mood")
            val mc = if (mood != null && moodMetric != null) colorFor(moodMetric, mood, primary) else primary
            val times = recs.sortedBy { it.time }.map { it.time }
            val timesText = if (times.size > 6) times.take(6).joinToString(" · ") + " …" else times.joinToString(" · ")
            SoftCard(Modifier.clip(RoundedCornerShape(24.dp)).clickable { onOpen(d) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(mc.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text(faceOf(mood), fontSize = 24.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            d.format(DateTimeFormatter.ofPattern("d MMMM", RUS)) + ", " + d.dayOfWeek.getDisplayName(TextStyle.SHORT, RUS),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            recWord(recs.size) + " · " + timesText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val shown = METRICS.filter { m ->
                            !m.hidden && recs.any { it.metrics.containsKey(m.id) } && (m.id in PRIMARY_IDS || isCustomId(m.id))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            shown.take(6).forEach { m ->
                                val vals = recs.mapNotNull { it.metrics[m.id] }
                                if (vals.isNotEmpty()) {
                                    val a = vals.average()
                                    val lo = vals.minOrNull() ?: a
                                    val hi = vals.maxOrNull() ?: a
                                    val label = if (vals.size > 1 && fmtM(m, lo) != fmtM(m, hi)) fmtM(m, lo) + "–" + fmtM(m, hi) else fmtM(m, a)
                                    Pill(m.title + " " + label, colorFor(m, a, primary))
                                }
                            }
                        }
                        val tg = recs.flatMap { it.tags }.filter { it != "demo" }.distinct().map { tagLabel(it) }
                        if (tg.isNotEmpty()) Text(tg.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = primary)
                        val lastNote = recs.sortedBy { it.time }.map { it.note }.lastOrNull { it.isNotBlank() }
                        if (lastNote != null) Text(lastNote, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        if (recs.any { "demo" in it.tags }) Text("тестовые данные", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ Day (all records of one day)
@Composable
fun DayScreen(store: Store, date: LocalDate, onBack: () -> Unit, onEdit: (String) -> Unit, onNew: () -> Unit) {
    val list = store.forDate(date.toString())
    val primary = MaterialTheme.colorScheme.primary
    val moodMetric = METRICS.firstOrNull { it.id == "mood" }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    HistoryDeleteDialog(store, pendingDelete) { pendingDelete = null }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ История") }
        ScreenTitle(dateTitle(date))
        if (list.isEmpty()) {
            SoftCard { Text("В этот день записей нет.") }
        }
        list.forEach { e ->
            val mood = e.metrics["mood"]
            val mc = if (mood != null && moodMetric != null) colorFor(moodMetric, mood, primary) else primary
            SoftCard(Modifier.clip(RoundedCornerShape(24.dp)).clickable { onEdit(e.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(mc.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text(faceOf(mood), fontSize = 24.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(e.time, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        val shown = METRICS.filter { m ->
                            !m.hidden && e.metrics.containsKey(m.id) && (m.id in PRIMARY_IDS || isCustomId(m.id))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            shown.take(6).forEach { m ->
                                val v = e.metrics[m.id]
                                if (v != null) Pill(m.title + " " + fmtM(m, v), colorFor(m, v, primary))
                            }
                        }
                        val done = e.habits.filter { it.value }.keys.map { habitLabel(it) }
                        if (done.isNotEmpty()) Text(done.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        val tg = e.tags.filter { it != "demo" }.map { tagLabel(it) }
                        if (tg.isNotEmpty()) Text(tg.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = primary)
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onEdit(e.id) }) { Text("Изменить") }
                    TextButton(onClick = { pendingDelete = e.id }) { Text("Удалить") }
                }
            }
        }
        Button(
            onClick = onNew,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp)
        ) { Text("＋ Добавить запись в этот день", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
    }
}
