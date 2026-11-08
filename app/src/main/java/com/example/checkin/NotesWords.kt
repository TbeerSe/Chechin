@file:OptIn(ExperimentalLayoutApi::class)

package com.example.checkin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val STOP = setOf(
    "это", "как", "что", "так", "для", "при", "его", "она", "они", "мне", "меня", "было", "были", "есть",
    "все", "всё", "очень", "тоже", "только", "когда", "если", "или", "уже", "ещё", "еще", "там", "тут",
    "потом", "просто", "сегодня", "вчера", "день", "себя", "свои", "своё", "этот", "этого", "чтобы",
    "после", "перед", "через", "между", "который", "которая", "которые", "была", "будет", "нужно", "надо",
    "могу", "можно", "сразу", "опять", "снова", "даже", "хотя", "этой", "этом", "этих", "него", "неё",
    "них", "нас", "вас", "нам", "вам", "ним", "ней", "мои", "мой", "моя", "чуть", "один", "одна", "кажется"
)

private class WordStat(val stem: String, var shown: String) {
    val ids = mutableSetOf<String>()
    var count = 0
}

private fun stemOf(w: String) = if (w.length > 5) w.substring(0, 5) else w

private fun tokens(note: String): List<String> =
    Regex("#[\\p{L}\\p{N}_]+").replace(note, " ").lowercase()
        .split(Regex("[^\\p{L}]+"))
        .filter { it.length >= 3 && it !in STOP }

private fun collect(entries: List<DayEntry>): List<WordStat> {
    val map = HashMap<String, WordStat>()
    val forms = HashMap<String, HashMap<String, Int>>()
    for (e in entries) {
        if (e.note.isBlank()) continue
        for (t in tokens(e.note)) {
            val s = stemOf(t)
            val w = map.getOrPut(s) { WordStat(s, t) }
            w.count++
            w.ids.add(e.id)
            val f = forms.getOrPut(s) { HashMap() }
            f[t] = (f[t] ?: 0) + 1
        }
    }
    for (w in map.values) {
        val best = forms[w.stem]?.maxByOrNull { it.value }
        if (best != null) w.shown = best.key
    }
    return map.values.sortedWith(compareByDescending<WordStat> { it.ids.size }.thenByDescending { it.count })
}

@Composable
fun NotesWordsCard(all: List<DayEntry>) {
    val withNotes = all.filter { it.note.isNotBlank() }
    val words = remember(all) { collect(all) }
    SoftCard {
        SectionTitle("Слова в заметках")
        if (withNotes.size < 5) {
            Text(
                "Нужно хотя бы 5 заметок, сейчас ${withNotes.size}. Пиши пару слов в записях, и здесь появятся повторяющиеся темы.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val top = words.filter { it.ids.size >= 2 }.take(10)
            if (top.isEmpty()) {
                Text("Повторяющихся слов пока нет.", style = MaterialTheme.typography.bodyMedium)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    top.forEach { w -> Pill(w.shown + " · " + w.ids.size, MaterialTheme.colorScheme.primary) }
                }
                Text("Число рядом со словом: в скольких записях оно встречалось.", style = MaterialTheme.typography.bodySmall)
            }

            val rows = words.filter { it.ids.size >= 3 }.mapNotNull { w ->
                val on = withNotes.filter { it.id in w.ids }.mapNotNull { it.metrics["mood"] }
                val off = withNotes.filter { it.id !in w.ids }.mapNotNull { it.metrics["mood"] }
                if (on.size < 3 || off.size < 3) null else Triple(w, on.average(), off.average())
            }.filter { abs(it.second - it.third) >= 1.0 }
                .sortedByDescending { abs(it.second - it.third) }
                .take(4)
            rows.forEach { (w, on, off) ->
                Text(
                    "«" + w.shown + "»: настроение в среднем " + "%.1f".format(on) + " против " + "%.1f".format(off) +
                        " в других записях с заметками (записей со словом: " + w.ids.size + ").",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (rows.isNotEmpty()) {
                Text(
                    "Это совпадения, а не причины: слово могло появиться из-за настроения, а не наоборот.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
