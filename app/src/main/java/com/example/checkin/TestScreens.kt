@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.example.checkin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Simple navigation flag: Settings opens the test, AppRoot shows it. */
object Nav {
    var openTest by mutableStateOf(false)
    /** Bumped on every onStart: lets screens notice "the app came back to the foreground". */
    var resumeTick by mutableStateOf(0)
}

/** Options for the AI export that are not stored in Settings state. */
object AiOptions {
    var facets by mutableStateOf(false)
    var items by mutableStateOf(false)
}

private fun f2(x: Double) = "%.2f".format(x)

private fun fmtDateTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ru")))

@Composable
private fun ScoreBar(value: Double, height: Dp) {
    val frac = ((value - 1.0) / 4.0).toFloat().coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

// ------------------------------------------------------------ entry card in Settings
@Composable
fun TestEntryCard(store: Store) {
    val last = store.results.lastOrNull()
    SoftCard {
        SectionTitle("🧠 Тест личности (Big Five)")
        Text(
            "IPIP-NEO-120: 120 утверждений, 5 доменов и 30 фасетов. Пункты в общественном достоянии, результаты хранятся только на устройстве.",
            style = MaterialTheme.typography.bodySmall
        )
        if (last != null) {
            Text("Последнее прохождение: " + fmtDateTime(last.takenAt), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { Nav.openTest = true },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
        ) { Text("Открыть") }
    }
}

// ------------------------------------------------------------ AI export toggle (under "notes")
@Composable
fun AiTestToggleRow(store: Store, checked: Boolean, onChange: (Boolean) -> Unit) {
    val has = store.results.isNotEmpty()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Добавить результаты теста личности", Modifier.weight(1f))
        Switch(checked = checked && has, onCheckedChange = onChange, enabled = has)
    }
    if (!has) {
        Text("Результатов пока нет: пройди тест во вкладке «Ещё».", style = MaterialTheme.typography.bodySmall)
    }
    if (checked && has) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Добавить и 30 фасетов (они менее надёжны)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = AiOptions.facets, onCheckedChange = { AiOptions.facets = it })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Добавить ответы по каждому пункту", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = AiOptions.items, onCheckedChange = { AiOptions.items = it })
        }
        if (AiOptions.items) {
            Text(
                "⚠️ Среди пунктов есть личные (самооценка, настроение, политические взгляды). Включай, только если готов их передать.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            "Передаётся последний результат. В промпт добавляется просьба сначала анализировать дневник без теста и не объяснять закономерности чертой без подтверждения данными.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ------------------------------------------------------------ host
@Composable
fun TestHost(store: Store, onClose: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(0) } // 0 menu, 1 intro, 2 questions, 3 result, 4 about
    var resultId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = true) { if (mode == 0) onClose() else mode = 0 }

    when (mode) {
        1 -> IntroScreen(onStart = { mode = 2 }, onBack = { mode = 0 })
        2 -> QuestionScreen(store, onExit = { mode = 0 }, onFinished = { id -> resultId = id; mode = 3 })
        3 -> {
            val r = resultId?.let { id -> store.results.firstOrNull { it.id == id } }
            if (r == null) {
                LaunchedEffect(Unit) { mode = 0 }
            } else {
                ResultScreen(store, r, onBack = { mode = 0 }, onDeleted = { resultId = null; mode = 0 })
            }
        }
        4 -> AboutScreen(onBack = { mode = 0 })
        else -> MenuScreen(
            store,
            onClose = onClose,
            onNew = { store.clearDraft(); mode = 1 },
            onContinue = { mode = 2 },
            onOpen = { id -> resultId = id; mode = 3 },
            onAbout = { mode = 4 }
        )
    }
}

@Composable
private fun MenuScreen(
    store: Store,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onContinue: () -> Unit,
    onOpen: (String) -> Unit,
    onAbout: () -> Unit
) {
    val draft = remember { store.loadDraft() }
    val answered = draft?.answers?.count { it != 0 } ?: 0
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onClose) { Text("‹ Назад") }
        ScreenTitle("Тест личности")
        SoftCard {
            SectionTitle("IPIP-NEO-120 (Big Five)")
            Text(
                "Самоотчётный опросник из 120 утверждений: 5 широких черт (доменов) и 30 аспектов (фасетов). Прохождение занимает около 15–20 минут.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (draft != null && answered > 0) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Продолжить ($answered из 120)")
                }
                OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Начать заново (сбросит сохранённые ответы)")
                }
            } else {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Пройти тест")
                }
            }
        }

        if (store.results.isNotEmpty()) {
            SectionTitle("Прохождения")
            store.results.reversed().forEach { r ->
                SoftCard(Modifier.clip(RoundedCornerShape(24.dp)).clickable { onOpen(r.id) }) {
                    Text(fmtDateTime(r.takenAt), fontWeight = FontWeight.Bold)
                    if (Ipip.isValid(r.answers)) {
                        val parts = (0 until 5).map { d -> Ipip.DOMAIN_RU[d] + " " + f2(Ipip.domainMean(d, r.answers)) }
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("О тесте, источниках и ограничениях")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun IntroScreen(onStart: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        ScreenTitle("Перед началом")
        SoftCard {
            SectionTitle("Как отвечать")
            Text(
                "Опиши себя таким, какой ты обычно сейчас, а не таким, каким хотел бы стать. " +
                    "Сравнивай себя с другими людьми твоего пола и примерно твоего возраста. " +
                    "Отвечай честно и без долгих раздумий: первая реакция обычно точнее. Правильных и неправильных ответов нет.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SoftCard {
            SectionTitle("Практика")
            Text(
                "• 120 утверждений, около 15–20 минут.\n" +
                    "• Можно прерваться: ответы сохраняются на устройстве, и ты продолжишь с того же места.\n" +
                    "• К предыдущему утверждению можно вернуться и изменить ответ.\n" +
                    "• Лучше проходить в обычный день и в ровном состоянии. Результат отражает твоё самовосприятие.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            "Это не диагностический и не клинический инструмент. Результаты нужны для самонаблюдения.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Начать", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun QuestionScreen(store: Store, onExit: () -> Unit, onFinished: (String) -> Unit) {
    val draft = remember { store.loadDraft() }
    val answers = remember {
        mutableStateListOf<Int>().apply { addAll(draft?.answers ?: List(Ipip.COUNT) { 0 }) }
    }
    var idx by remember {
        mutableStateOf(answers.indexOfFirst { it == 0 }.let { if (it < 0) 0 else it })
    }
    var activeMs by remember { mutableStateOf(draft?.activeMs ?: 0L) }
    val startedAt = remember { draft?.startedAt ?: System.currentTimeMillis() }
    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(idx) { shownAt = System.currentTimeMillis() }

    fun choose(v: Int) {
        val now = System.currentTimeMillis()
        activeMs += minOf(now - shownAt, 60_000L)
        answers[idx] = v
        store.saveDraft(IpipDraft(answers.toList(), activeMs, startedAt))
        if (idx < Ipip.COUNT - 1) {
            idx += 1
        } else if (answers.all { it in 1..5 }) {
            val r = TestResult(newEntryId(), Ipip.ID, now, answers.toList(), (activeMs / 1000L).toInt())
            store.addResult(r)
            store.clearDraft()
            onFinished(r.id)
        } else {
            idx = answers.indexOfFirst { it == 0 }.let { if (it < 0) 0 else it }
        }
    }

    val item = Ipip.ITEMS[idx]
    val current = answers[idx]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("‹ Пауза") }
            Spacer(Modifier.weight(1f))
            Text("${idx + 1} / ${Ipip.COUNT}", fontWeight = FontWeight.SemiBold)
        }
        val frac = (idx.toFloat() / Ipip.COUNT).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text("Насколько это описывает тебя?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftCard {
            Text(item.ru, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Оригинал: " + item.en, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (v in 1..5) {
            val selected = current == v
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { choose(v) }
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$v",
                        Modifier.width(28.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        Ipip.SCALE_LABELS[v - 1],
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (idx > 0) idx -= 1 }, enabled = idx > 0) { Text("‹ Назад") }
            TextButton(onClick = { if (idx < Ipip.COUNT - 1 && answers[idx] != 0) idx += 1 }, enabled = idx < Ipip.COUNT - 1 && current != 0) {
                Text("Вперёд ›")
            }
        }
    }
}

@Composable
private fun ResultScreen(store: Store, r: TestResult, onBack: () -> Unit, onDeleted: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val prev = store.results.filter { it.instrument == r.instrument && it.takenAt < r.takenAt }.maxByOrNull { it.takenAt }
    val ok = Ipip.isValid(r.answers)
    val prevOk = prev != null && Ipip.isValid(prev.answers)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        ScreenTitle("Результаты теста")
        Text(
            "IPIP-NEO-120 · " + fmtDateTime(r.takenAt) + " · активное время: " + (r.activeSec / 60) + " мин",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!ok) {
            SoftCard { Text("Данные этого результата повреждены, показать их нельзя.") }
        } else {
            val inc = Ipip.inconsistency(r.answers)
            val share = Ipip.modeShare(r.answers)
            SoftCard {
                SectionTitle("Как читать эти числа")
                Text(
                    "Баллы это средние ответы (от 1 до 5) после учёта обратных пунктов: чем выше число, тем сильнее выражена черта по твоим ответам. " +
                        "Нормативные данные не применялись, поэтому по этим числам нельзя сказать, «высоко» или «низко» относительно других людей. " +
                        "Осмысленнее сравнивать свои фасеты между собой и свои прохождения между собой.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            for (d in 0 until 5) {
                val mean = Ipip.domainMean(d, r.answers)
                val diff = if (prevOk && prev != null) mean - Ipip.domainMean(d, prev.answers) else null
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Ipip.DOMAIN_RU[d], fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(Ipip.DOMAIN_EN[d], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(f2(mean), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    ScoreBar(mean, 10.dp)
                    Text(
                        "Сумма: " + Ipip.domainSum(d, r.answers) + " (допустимо от 24 до 120) · надёжность шкалы α = " + f2(Ipip.DOMAIN_ALPHA[d]),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (diff != null) {
                        Text("К предыдущему прохождению: " + "%+.2f".format(diff), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(Ipip.DOMAIN_DESC[d], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (f in 0 until 6) {
                        val g = d * 6 + f
                        val fm = Ipip.facetMean(g, r.answers)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Ipip.FACET_RU[g], Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(f2(fm), fontWeight = FontWeight.SemiBold)
                            }
                            ScoreBar(fm, 6.dp)
                            Text(
                                Ipip.FACET_EN[g] + " · сумма " + Ipip.facetSum(g, r.answers) + " (4–20) · α = " + f2(Ipip.FACET_ALPHA[g]),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            SoftCard {
                SectionTitle("Качество ответов")
                Text(
                    "Согласованность: " + f2(inc) + ". Это среднее расхождение между 29 парами противоположных по смыслу пунктов. " +
                        "Случайные ответы дают около 1,6, внимательные заметно ниже.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Доля самого частого ответа: " + Math.round(share * 100) + "%",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (inc >= 1.3) {
                    Text(
                        "Ответы мало согласованы между противоположными пунктами. Возможно, тест проходился невнимательно, к результатам стоит относиться с осторожностью.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
                    )
                }
                if (share >= 0.55) {
                    Text(
                        "Больше половины ответов одинаковые. Это может искажать результат.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "Пороги 1,3 и 55% это ориентиры приложения, а не нормы из статьи.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var showAnswers by remember { mutableStateOf(false) }
            SoftCard {
                SectionTitle("Мои ответы по пунктам")
                Text(
                    "Ответы сгруппированы по фасетам, так виден разброс внутри каждого. Обратные пункты помечены: для них в подсчёт идёт 6 минус ответ.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = { showAnswers = !showAnswers },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                ) { Text(if (showAnswers) "Скрыть ответы" else "Показать все 120 ответов") }
                if (showAnswers) {
                    for (g in 0 until 30) {
                        Text(
                            Ipip.FACET_RU[g] + " · " + Ipip.FACET_EN[g],
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                        )
                        for (item in Ipip.ITEMS.filter { it.facet == g }) {
                            val a = r.answers[item.n - 1]
                            Row(verticalAlignment = Alignment.Top) {
                                Text(item.ru, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("$a", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                Ipip.SCALE_LABELS[a - 1] + (if (item.key < 0) " · обратный пункт" else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            SoftCard {
                SectionTitle("Ограничения")
                Text(
                    "• Надёжность доменов высокая (α от 0,81 до 0,90), фасетов ниже (от 0,63 до 0,88). По словам автора, для важных решений об отдельном человеке фасеты не подходят.\n" +
                        "• Русский перевод неофициальный и психометрически не проверен.\n" +
                        "• Фасет «Либерализм» опирается на американские политические понятия и хуже всего переносится в другую культуру (α = 0,63).\n" +
                        "• Это самоотчёт: результат зависит от самовосприятия и состояния в момент прохождения.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить этот результат", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить результат?") },
            text = { Text("Результат за " + fmtDateTime(r.takenAt) + " будет удалён. Это нельзя отменить.") },
            confirmButton = { TextButton(onClick = { store.deleteResult(r.id); confirm = false; onDeleted() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        ScreenTitle("О тесте")
        SoftCard {
            SectionTitle("Что измеряется")
            Text(
                "IPIP-NEO-120 измеряет пять широких черт модели «Большая пятёрка» (нейротизм, экстраверсия, открытость опыту, доброжелательность, добросовестность) " +
                    "и по шесть более узких аспектов внутри каждой, всего 30. На каждый аспект приходится 4 утверждения. " +
                    "Опросник создан как общедоступный аналог коммерческого NEO PI-R. Результаты шкал IPIP-NEO в среднем коррелируют с NEO PI-R на уровне r = 0,66 (0,91 с поправкой на ненадёжность).",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SoftCard {
            SectionTitle("Подсчёт")
            Text(
                "Каждый ответ от 1 до 5. Для обратных пунктов значение заменяется на 6 минус ответ. " +
                    "Балл аспекта это среднее 4 пунктов, балл домена это среднее 24 пунктов. Всё так, как в таблице 1 статьи автора. " +
                    "Пары пунктов для проверки согласованности (29 пар) взяты из приложения А той же статьи.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SoftCard {
            SectionTitle("Перевод")
            Text(
                "Перевод сделан для этого приложения: прямой перевод с оригинала на русский с сохранением смысла и ключа каждого пункта. " +
                    "Обратный перевод и проверка на русскоязычной выборке не проводились, нормы для русскоязычного населения неизвестны. " +
                    "Поэтому под каждым утверждением показан английский оригинал.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SoftCard {
            SectionTitle("Источники")
            Text(
                "Johnson, J. A. (2014). Measuring thirty facets of the Five Factor Model with a 120-item public domain inventory: Development of the IPIP-NEO-120. Journal of Research in Personality, 51, 78–89.\n\n" +
                    "Goldberg, L. R. (1999). A broad-bandwidth, public domain, personality inventory measuring the lower-level facets of several five-factor models. In Personality Psychology in Europe, Vol. 7 (pp. 7–28).\n\n" +
                    "Goldberg, L. R., et al. (2006). The International Personality Item Pool and the future of public-domain personality measures. Journal of Research in Personality, 40, 84–96.\n\n" +
                    "Пункты IPIP находятся в общественном достоянии (ipip.ori.org).",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
