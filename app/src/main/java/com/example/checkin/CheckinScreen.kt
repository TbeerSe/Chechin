@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.example.checkin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

// ------------------------------------------------------------ Check-in
@Composable
fun CheckinScreen(
    store: Store,
    date: LocalDate,
    editId: String?,
    onDateChange: (LocalDate) -> Unit,
    onEditChange: (String?) -> Unit
) {
    val ctx = LocalContext.current
    val dateStr = date.toString()
    val dayEntries = store.forDate(dateStr)
    val existing = if (editId == null) null else store.byId(editId)
    val seed = existing ?: dayEntries.lastOrNull()
    val primary = MaterialTheme.colorScheme.primary
    val isToday = date == LocalDate.now()
    val canNext = date.isBefore(LocalDate.now())

    key(dateStr, editId) {
        // Only the values the user actually touched are stored. An untouched slider saves nothing,
        // so "I didn't answer" never becomes "slept 7 hours, mood 5". Hence a NEW record starts with
        // empty sliders; habits and tags may be carried over from the day's last record (see `seed`).
        val metrics = remember {
            mutableStateMapOf<String, Double>().apply {
                existing?.metrics?.let { putAll(it) }
            }
        }
        val habits = remember { mutableStateMapOf<String, Boolean>().apply { seed?.habits?.let { putAll(it) } } }
        val tags = remember {
            mutableStateListOf<String>().apply {
                seed?.tags?.filter { !it.startsWith("#") && it != "demo" }?.let { addAll(it) }
            }
        }
        var note by remember { mutableStateOf(existing?.note ?: "") }
        var time by remember { mutableStateOf(existing?.time ?: (if (isToday) nowTime() else "12:00")) }
        var confirmDelete by remember { mutableStateOf(false) }
        var timeDialog by remember { mutableStateOf(false) }
        var timeTouched by remember { mutableStateOf(false) }
        // Coming back to a screen left open for hours: a fresh record gets the current time, not the old one.
        LaunchedEffect(Nav.resumeTick) { if (existing == null && isToday && !timeTouched) time = nowTime() }
        var quickDialog by remember { mutableStateOf(false) }
        var noteExpanded by remember { mutableStateOf(false) }
        val noteScope = rememberCoroutineScope()
        val noteRequester = remember { BringIntoViewRequester() }
        BackHandler(enabled = noteExpanded) { noteExpanded = false }

        // Sleep can only be judged in the morning: in the afternoon/evening the slider simply isn't offered.
        val shownMetrics = metricsForTime(time)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6C6CE5), Color(0xFF9B7FD9))))
                    .padding(vertical = 18.dp, horizontal = 6.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onDateChange(date.minusDays(1)) }) {
                        Text("‹", color = Color.White, fontSize = 30.sp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(faceOf(metrics["mood"]), fontSize = 46.sp)
                        Text(
                            if (existing != null) "Запись в $time" else if (isToday) "Как ты сейчас?" else "Как это было?",
                            color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                        )
                        Text(dateTitle(date), color = Color.White.copy(alpha = 0.85f))
                    }
                    TextButton(onClick = { onDateChange(date.plusDays(1)) }, enabled = canNext) {
                        Text("›", color = Color.White.copy(alpha = if (canNext) 1f else 0.3f), fontSize = 30.sp)
                    }
                }
            }

            if (dayEntries.isNotEmpty()) {
                SoftCard {
                    SectionTitle("Записи за этот день")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dayEntries.forEach { e -> Chip(e.time, e.id == editId) { onEditChange(e.id) } }
                        Chip("＋ Новая", editId == null) { onEditChange(null) }
                    }
                }
            }
            if (existing != null) Pill("Редактируешь запись. Чтобы добавить ещё одну, нажми «＋ Новая»", primary)

            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🕒 Время записи", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { timeDialog = true }, shape = RoundedCornerShape(16.dp)) { Text(time) }
                }
                Text(
                    "Сон спрашивается только в утренних записях: днём и вечером его просто нет в опросе.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SoftCard {
                SectionTitle("Состояние")
                if (shownMetrics.isEmpty()) Text("Все показатели скрыты — настрой их в «Мои вопросы».")
                shownMetrics.forEach { m ->
                    val v = metrics[m.id]
                    val shown = v ?: m.default
                    val c = if (v == null) MaterialTheme.colorScheme.onSurfaceVariant else colorFor(m, shown, primary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(withEm(m.emoji, m.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.18f)) {
                            Text(
                                if (v == null) "—" else fmtM(m, shown),
                                Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = c, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Slider(
                        value = shown.toFloat(),
                        onValueChange = { nv -> metrics[m.id] = Math.round(nv * 10) / 10.0 },
                        valueRange = m.min.toFloat()..m.max.toFloat(),
                        steps = m.steps,
                        colors = SliderDefaults.colors(
                            thumbColor = c, activeTrackColor = c, inactiveTrackColor = c.copy(alpha = 0.2f),
                            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
                        )
                    )
                }
            }

            SoftCard {
                SectionTitle("Что было")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HABITS.filter { !it.hidden }.forEach { h ->
                        Chip(withEm(h.emoji, h.label), habits[h.id] == true) { habits[h.id] = !(habits[h.id] ?: false) }
                    }
                }
            }

            SoftCard {
                SectionTitle("Что повлияло")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    REASONS.filter { !it.hidden }.forEach { r ->
                        Chip(withEm(r.emoji, r.label), r.id in tags) { if (r.id in tags) tags.remove(r.id) else tags.add(r.id) }
                    }
                }
            }

            SoftCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Заметка")
                    TextButton(onClick = { noteExpanded = true }) { Text("Развернуть ⤢") }
                }
                // Fixed height: long text scrolls inside the field, the caret line always stays visible.
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Что-то от себя (слова с # станут тегами)") },
                    modifier = Modifier.fillMaxWidth()
                        .bringIntoViewRequester(noteRequester)
                        .onFocusEvent { st ->
                            if (st.isFocused) noteScope.launch {
                                delay(350) // wait for the keyboard animation
                                noteRequester.bringIntoView()
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    minLines = 5,
                    maxLines = 7
                )
            }

            Button(
                onClick = {
                    val hashtags = Regex("#[\\p{L}\\p{N}_]+").findAll(note).map { it.value.lowercase() }.distinct().toList()
                    // Sleep belongs to the morning record only: moving a record out of the morning must not leave
                    // sleep values attached to it. Everything else (including hidden metrics) is kept as is.
                    val merged = (existing?.metrics ?: emptyMap()) + metrics
                    val mergedMetrics = if (dayPart(time) == 0) merged else merged.filterKeys { !isSleepMetric(it) }
                    val id = existing?.id ?: newEntryId()
                    store.save(
                        DayEntry(id, dateStr, time, mergedMetrics, habits.toMap(), tags.toList() + hashtags, note.trim(), System.currentTimeMillis())
                    )
                    Toast.makeText(ctx, "Сохранено ✓", Toast.LENGTH_SHORT).show()
                    if (existing == null) onEditChange(id)
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text(if (existing == null) "Сохранить запись" else "Сохранить изменения", style = MaterialTheme.typography.titleMedium) }

            OutlinedButton(
                onClick = { quickDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) { Text("⚡ Быстрая запись — только настроение") }

            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить эту запись") }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (timeDialog) {
            val st = rememberTimePickerState(
                initialHour = time.substringBefore(":").toIntOrNull() ?: 12,
                initialMinute = time.substringAfter(":").toIntOrNull() ?: 0,
                is24Hour = true
            )
            AlertDialog(
                onDismissRequest = { timeDialog = false },
                title = { Text("Время записи") },
                text = { TimePicker(state = st) },
                confirmButton = {
                    TextButton(onClick = { time = hhmm(st.hour, st.minute); timeTouched = true; timeDialog = false }) { Text("ОК") }
                },
                dismissButton = { TextButton(onClick = { timeDialog = false }) { Text("Отмена") } }
            )
        }

        if (quickDialog) {
            AlertDialog(
                onDismissRequest = { quickDialog = false },
                title = { Text("⚡ Быстрая запись") },
                text = { Text("Один тап — одна запись только с настроением, без вопросов и опроса.") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(2 to "😣", 4 to "😕", 5 to "😐", 7 to "🙂", 9 to "😄").forEach { (mood, emoji) ->
                            TextButton(onClick = {
                                store.save(
                                    DayEntry(
                                        newEntryId(), dateStr, if (isToday) nowTime() else "12:00",
                                        mapOf("mood" to mood.toDouble()), emptyMap(), emptyList(), "", System.currentTimeMillis()
                                    )
                                )
                                Toast.makeText(ctx, "Сохранено ✓", Toast.LENGTH_SHORT).show()
                                quickDialog = false
                            }) { Text(emoji, fontSize = 22.sp) }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { quickDialog = false }) { Text("Отмена") } }
            )
        }

        if (confirmDelete && existing != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Удалить запись?") },
                text = { Text("Запись за $dateStr, ${existing.time} будет удалена.") },
                confirmButton = {
                    TextButton(onClick = { store.delete(existing.id); confirmDelete = false; onEditChange(null) }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
            )
        }

        if (noteExpanded) {
            val focus = remember { FocusRequester() }
            var tfv by remember { mutableStateOf(TextFieldValue(note, TextRange(note.length))) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Заметка")
                        TextButton(onClick = { noteExpanded = false }) { Text("Готово ✓") }
                    }
                    OutlinedTextField(
                        value = tfv,
                        onValueChange = { tfv = it; note = it.text },
                        label = { Text("Что-то от себя (слова с # станут тегами)") },
                        modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }
    }
}
