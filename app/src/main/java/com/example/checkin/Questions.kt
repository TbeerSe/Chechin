package com.example.checkin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Metric(
    val id: String,
    val title: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val default: Double,
    val emoji: String = "",
    val polarity: Int = 0,      // 1: higher is better, -1: lower is better, 0: neutral
    val hidden: Boolean = false
) {
    val steps: Int get() = (Math.round((max - min) / step).toInt() - 1).coerceAtLeast(0)
}

data class Choice(
    val id: String,
    val label: String,
    val emoji: String = "",
    val hidden: Boolean = false
)

fun defaultMetrics() = listOf(
    Metric("mood", "Настроение", 1.0, 10.0, 1.0, 5.0, "😊", 1),
    Metric("energy", "Энергия", 1.0, 10.0, 1.0, 5.0, "⚡", 1),
    Metric("anxiety", "Тревожность", 1.0, 10.0, 1.0, 3.0, "😰", -1),
    Metric("stress", "Стресс", 1.0, 10.0, 1.0, 3.0, "🔥", -1),
    Metric("focus", "Концентрация", 1.0, 10.0, 1.0, 5.0, "🎯", 1),
    Metric("sleep_hours", "Сон (часы)", 0.0, 12.0, 0.5, 7.0, "😴", 0),
    Metric("sleep_quality", "Качество сна", 1.0, 10.0, 1.0, 5.0, "🌙", 1)
)

fun defaultHabits() = listOf(
    Choice("exercise", "Спорт / тренировка", "🏃"),
    Choice("outdoors", "Прогулка / был на улице", "🌳"),
    Choice("caffeine", "Кофе / энергетики", "☕"),
    Choice("alcohol", "Алкоголь", "🍷"),
    Choice("social", "Живое общение", "👥"),
    Choice("screens_late", "Экран перед сном", "📱"),
    Choice("healthy_food", "Нормально поел(а)", "🥗"),
    Choice("meditation", "Медитация / отдых", "🧘")
)

fun defaultReasons() = listOf(
    Choice("work", "Работа", "💼"),
    Choice("study", "Учёба", "📚"),
    Choice("family", "Семья", "🏠"),
    Choice("friends", "Друзья", "👫"),
    Choice("relationship", "Отношения", "❤️"),
    Choice("health", "Здоровье", "🩺"),
    Choice("money", "Деньги", "💰"),
    Choice("weather", "Погода", "🌦"),
    Choice("conflict", "Ссора", "💢"),
    Choice("news", "Новости", "📰"),
    Choice("hobby", "Хобби", "🎨"),
    Choice("tired", "Усталость", "🥱")
)

/** Current (user-editable) configuration. Persisted by Store. */
object Cfg {
    var metrics by mutableStateOf(defaultMetrics())
    var habits by mutableStateOf(defaultHabits())
    var reasons by mutableStateOf(defaultReasons())
}

val METRICS: List<Metric> get() = Cfg.metrics
val HABITS: List<Choice> get() = Cfg.habits
val REASONS: List<Choice> get() = Cfg.reasons

fun isCustomId(id: String) = id.startsWith("u_")
fun newCustomId() = "u_" + java.lang.Long.toString(System.currentTimeMillis(), 36)