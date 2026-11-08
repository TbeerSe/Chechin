@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.example.checkin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")

// ------------------------------------------------------------ Theme
private val GoodC = Color(0xFF3BAA7E)
private val MidC = Color(0xFFF2B84B)
private val BadC = Color(0xFFE5736A)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B5BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF1B1A5E),
    secondary = Color(0xFF8E7CC3),
    background = Color(0xFFF6F4FF),
    onBackground = Color(0xFF1C1B2A),
    surface = Color.White,
    onSurface = Color(0xFF1C1B2A),
    surfaceVariant = Color(0xFFEDEAFB),
    onSurfaceVariant = Color(0xFF5F5C75),
    outlineVariant = Color(0xFFDAD6F0)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA9A7FF),
    onPrimary = Color(0xFF1B1A5E),
    primaryContainer = Color(0xFF34336B),
    onPrimaryContainer = Color(0xFFE3E1FF),
    secondary = Color(0xFFC3B5F0),
    background = Color(0xFF12111A),
    onBackground = Color(0xFFE8E6F5),
    surface = Color(0xFF1D1B29),
    onSurface = Color(0xFFE8E6F5),
    surfaceVariant = Color(0xFF2A2840),
    onSurfaceVariant = Color(0xFFBDBAD6),
    outlineVariant = Color(0xFF3A3856)
)

@Composable
fun CheckinAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}

// ------------------------------------------------------------ Helpers
private fun withEmoji(emoji: String, text: String) = if (emoji.isBlank()) text else "$emoji $text"

@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            (if (selected) "✓ " else "") + text,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

// ------------------------------------------------------------ Config (custom questions)
private class EditState(val kind: Int, val id: String?, val name: String, val polarity: Int)

private fun applyEdit(store: Store, e: EditState, name: String, pol: Int) {
    when (e.kind) {
        0 -> Cfg.metrics = if (e.id == null)
            Cfg.metrics + Metric(newCustomId(), name, 1.0, 10.0, 1.0, 5.0, "📊", pol)
        else Cfg.metrics.map { if (it.id == e.id) it.copy(title = name, polarity = pol) else it }

        1 -> Cfg.habits = if (e.id == null)
            Cfg.habits + Choice(newCustomId(), name)
        else Cfg.habits.map { if (it.id == e.id) it.copy(label = name) else it }

        else -> Cfg.reasons = if (e.id == null)
            Cfg.reasons + Choice(newCustomId(), name)
        else Cfg.reasons.map { if (it.id == e.id) it.copy(label = name) else it }
    }
    store.persist()
}

@Composable
private fun ConfigRow(label: String, hidden: Boolean, custom: Boolean, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val base = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (hidden) base.copy(alpha = 0.4f) else base)
        TextButton(onClick = onEdit) { Text("✏️") }
        TextButton(onClick = onToggle) { Text(if (hidden) "🙈" else "👁") }
        if (custom) TextButton(onClick = onDelete) { Text("🗑") }
    }
}

@Composable
private fun ItemDialog(
    title: String, initialName: String, withPolarity: Boolean, initialPolarity: Int,
    onDismiss: () -> Unit, onOk: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var pol by remember { mutableStateOf(initialPolarity) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                if (withPolarity) {
                    Text("Что означает большее значение?", style = MaterialTheme.typography.labelLarge)
                    Chip("Лучше, когда выше", pol == 1) { pol = 1 }
                    Chip("Лучше, когда ниже", pol == -1) { pol = -1 }
                    Chip("Нейтрально", pol == 0) { pol = 0 }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onOk(name.trim(), pol) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun ConfigScreen(store: Store, onBack: () -> Unit) {
    var edit by remember { mutableStateOf<EditState?>(null) }
    // Deleting is irreversible for the setting (stored values stay in the file but disappear from the UI).
    var confirmDel by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        ScreenTitle("Мои вопросы")
        Text(
            "✏️ переименовать · 👁 показан / 🙈 скрыт (нажми, чтобы переключить) · 🗑 удалить (только свои). " +
                "Скрытые пункты пропадают из опроса, но их прошлые данные сохраняются и участвуют в анализе.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SoftCard {
            SectionTitle("Показатели")
            Cfg.metrics.forEach { m ->
                ConfigRow(
                    label = withEmoji(m.emoji, m.title), hidden = m.hidden, custom = isCustomId(m.id),
                    onEdit = { edit = EditState(0, m.id, m.title, m.polarity) },
                    onToggle = { Cfg.metrics = Cfg.metrics.map { x -> if (x.id == m.id) x.copy(hidden = !x.hidden) else x }; store.persist() },
                    onDelete = { confirmDel = m.title to { Cfg.metrics = Cfg.metrics.filter { x -> x.id != m.id }; store.persist() } }
                )
            }
            TextButton(onClick = { edit = EditState(0, null, "", 0) }) { Text("＋ Добавить показатель (шкала 1–10)") }
        }

        SoftCard {
            SectionTitle("Привычки и события")
            Cfg.habits.forEach { h ->
                ConfigRow(
                    label = withEmoji(h.emoji, h.label), hidden = h.hidden, custom = isCustomId(h.id),
                    onEdit = { edit = EditState(1, h.id, h.label, 0) },
                    onToggle = { Cfg.habits = Cfg.habits.map { x -> if (x.id == h.id) x.copy(hidden = !x.hidden) else x }; store.persist() },
                    onDelete = { confirmDel = h.label to { Cfg.habits = Cfg.habits.filter { x -> x.id != h.id }; store.persist() } }
                )
            }
            TextButton(onClick = { edit = EditState(1, null, "", 0) }) { Text("＋ Добавить привычку") }
        }

        SoftCard {
            SectionTitle("Что повлияло на день")
            Cfg.reasons.forEach { r ->
                ConfigRow(
                    label = withEmoji(r.emoji, r.label), hidden = r.hidden, custom = isCustomId(r.id),
                    onEdit = { edit = EditState(2, r.id, r.label, 0) },
                    onToggle = { Cfg.reasons = Cfg.reasons.map { x -> if (x.id == r.id) x.copy(hidden = !x.hidden) else x }; store.persist() },
                    onDelete = { confirmDel = r.label to { Cfg.reasons = Cfg.reasons.filter { x -> x.id != r.id }; store.persist() } }
                )
            }
            TextButton(onClick = { edit = EditState(2, null, "", 0) }) { Text("＋ Добавить причину") }
        }
        Spacer(Modifier.height(8.dp))
    }

    confirmDel?.let { (name, action) ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("Удалить «$name»?") },
            text = { Text("Пункт исчезнет из опроса и из экспорта. Уже сохранённые значения останутся в файле, но видны не будут. Чтобы просто убрать из опроса, лучше скрыть (🙈).") },
            confirmButton = { TextButton(onClick = { action(); confirmDel = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("Отмена") } }
        )
    }

    edit?.let { e ->
        ItemDialog(
            title = if (e.id == null) "Новый пункт" else "Изменить",
            initialName = e.name,
            withPolarity = e.kind == 0,
            initialPolarity = e.polarity,
            onDismiss = { edit = null },
            onOk = { name, pol -> applyEdit(store, e, name, pol); edit = null }
        )
    }
}