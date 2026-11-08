@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.example.checkin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val RUS = Locale("ru")
private val OK_C = Color(0xFF3BAA7E)
private val MID_C = Color(0xFFF2B84B)
private val BAD_C = Color(0xFFE5736A)
val PRIMARY_IDS = listOf("mood", "energy", "anxiety", "sleep_hours")

// ------------------------------------------------------------ shared helpers (used by all screens)
internal fun fmtM(m: Metric, v: Double) = if (m.step >= 1.0) v.toInt().toString() else "%.1f".format(v)

internal fun colorFor(m: Metric, v: Double, primary: Color): Color {
    if (m.polarity == 0) return primary
    var t = ((v - m.min) / (m.max - m.min)).toFloat().coerceIn(0f, 1f)
    if (m.polarity < 0) t = 1f - t
    return if (t < 0.5f) lerp(BAD_C, MID_C, t * 2f) else lerp(MID_C, OK_C, (t - 0.5f) * 2f)
}

internal fun faceOf(v: Double?) = when {
    v == null -> "🙂"
    v <= 2 -> "😣"
    v <= 4 -> "😕"
    v <= 6 -> "😐"
    v <= 8 -> "🙂"
    else -> "😄"
}

internal fun emojiFor(t: String) = when {
    t.contains("за последние 7 дней") -> "📈"
    t.contains("дням недели") -> "📅"
    t.contains("следующий день") -> "🔁"
    t.startsWith("Предварительно: Чем") -> "🔗"
    t.contains("Чем больше") -> "🔗"
    else -> "💡"
}

internal fun withEm(emoji: String, text: String) = if (emoji.isBlank()) text else "$emoji $text"

internal fun nowTime(): String {
    val t = LocalTime.now()
    return hhmm(t.hour, t.minute)
}

internal fun dateTitle(d: LocalDate) =
    d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", RUS)) + ", " + d.dayOfWeek.getDisplayName(TextStyle.SHORT, RUS)

internal fun habitLabel(id: String) = HABITS.firstOrNull { it.id == id }?.label ?: id

// ------------------------------------------------------------ Root
@Composable
fun AppRoot(store: Store) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var dateStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var dayView by rememberSaveable { mutableStateOf<String?>(null) }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var loadErrorShown by rememberSaveable { mutableStateOf(false) }
    // The app can stay alive in the background across midnight: a screen that still says "today = yesterday"
    // would file the next record under the wrong date.
    var openedDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Nav.resumeTick) {
        val now = LocalDate.now().toString()
        if (now != openedDay) {
            if (dateStr == openedDay) { dateStr = now; editId = null }
            openedDay = now
        }
    }
    BackHandler(enabled = showConfig && tab == 3) { showConfig = false }
    BackHandler(enabled = dayView != null && tab == 1) { dayView = null }

    val tabs = listOf("📝" to "Сегодня", "📅" to "История", "📈" to "Статистика", "⚙️" to "Ещё")
    // With edge-to-edge the window is not resized by the keyboard, so the keyboard insets are applied here:
    // the content is lifted above the keyboard and the bottom bar is hidden while typing.
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!imeVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        // Switching tabs drops any open sub-screen of the other tabs (config, day view, test).
                        onClick = { tab = i; showConfig = false; dayView = null; Nav.openTest = false },
                        icon = { Text(icon) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer)
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CheckinScreen(
                    store, LocalDate.parse(dateStr), editId,
                    onDateChange = { d -> dateStr = d.toString(); editId = null },
                    onEditChange = { id -> editId = id }
                )
                1 -> {
                    val dv = dayView
                    if (dv == null) {
                        HistoryScreen(store) { d -> dayView = d.toString() }
                    } else {
                        DayScreen(
                            store, LocalDate.parse(dv),
                            onBack = { dayView = null },
                            onEdit = { id -> dateStr = dv; editId = id; tab = 0 },
                            onNew = { dateStr = dv; editId = null; tab = 0 }
                        )
                    }
                }
                2 -> StatsScreen(store)
                else -> if (Nav.openTest) TestHost(store) { Nav.openTest = false }
                else if (showConfig) ConfigScreen(store) { showConfig = false }
                else SettingsScreen(store) { showConfig = true }
            }
        }
    }

    // The data file could not be read: say so explicitly instead of silently starting empty.
    val loadError = store.loadError
    if (loadError != null && !loadErrorShown) {
        AlertDialog(
            onDismissRequest = { loadErrorShown = true; store.acknowledgeLoadError() },
            title = { Text(if (store.recoveredFromBackup) "Данные восстановлены из копии" else "Не удалось прочитать данные") },
            text = {
                Text(
                    if (store.recoveredFromBackup)
                        "Файл с записями был повреждён, поэтому загружена последняя автоматическая копия " +
                            "(в ней могут отсутствовать самые свежие записи). Повреждённый файл сохранён как «$loadError»."
                    else
                        "Файл с записями повреждён. Ничего не удалено: его копия сохранена как «$loadError» " +
                            "во внутренней памяти приложения, а также есть автоматические копии checkin_backup_0..2.json. " +
                            "Попробуй импортировать одну из них (Ещё → Данные и бэкап → Импорт)."
                )
            },
            confirmButton = { TextButton(onClick = { loadErrorShown = true; store.acknowledgeLoadError() }) { Text("Понятно") } }
        )
    }
}
