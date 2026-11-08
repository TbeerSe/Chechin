package com.example.checkin

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

data class Insight(val text: String, val score: Double, val hint: Hint? = null)

/** How to test this insight as a 14-day experiment: which habit to change and in which direction. */
class Hint(val habitId: String, val reduce: Boolean)

internal class Feature(val key: String, val label: String, val isBool: Boolean, val read: (DayEntry) -> Double?)

fun tagLabel(tag: String): String = REASONS.firstOrNull { it.id == tag }?.label ?: tag

object Analyzer {
    const val MIN_DAYS = 7

    private fun fmt(x: Double) = "%.1f".format(x)

    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        if (n < 3) return null
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx < 1e-9 || syy < 1e-9) return null
        return sxy / sqrt(sxx * syy)
    }

    private fun pearsonArr(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) { val dx = a[i] - ma; val dy = b[i] - mb; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
        return if (sxx < 1e-9 || syy < 1e-9) 0.0 else sxy / sqrt(sxx * syy)
    }

    /** Spearman rank correlation (Pearson on ranks; ties get the average rank). Better for 1-10 scales. */
    fun spearman(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size < 3) return null
        return pearson(ranksOf(xs), ranksOf(ys))
    }

    /** Average ranks (ties share the mean rank). Computed once per series, not on every permutation. */
    internal fun ranksOf(list: List<Double>): List<Double> {
        val sorted = list.withIndex().sortedBy { it.value }
        val ranks = DoubleArray(list.size)
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j < sorted.size && sorted[j].value == sorted[i].value) j++
            val rank = (i + 1 + j) / 2.0
            for (k in i until j) ranks[sorted[k].index] = rank
            i = j
        }
        return ranks.toList()
    }

    /** Two-sided permutation p-value for a statistic under shuffling of xs. */
    private fun permutationP(xs: DoubleArray, ys: DoubleArray, stat: (DoubleArray, DoubleArray) -> Double, perms: Int = 999): Double {
        val observed = abs(stat(xs, ys))
        val rnd = java.util.Random(42)
        val copy = xs.copyOf()
        var extreme = 0
        for (p in 0 until perms) {
            for (i in copy.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = copy[i]; copy[i] = copy[j]; copy[j] = t
            }
            if (abs(stat(copy, ys)) >= observed - 1e-12) extreme++
        }
        return (extreme + 1).toDouble() / (perms + 1)
    }

    private fun meanDiff(xs: DoubleArray, ys: DoubleArray): Double {
        var so = 0.0; var no = 0; var sf = 0.0; var nf = 0
        for (i in xs.indices) {
            if (xs[i] > 0.5) { so += ys[i]; no++ } else { sf += ys[i]; nf++ }
        }
        if (no == 0 || nf == 0) return 0.0
        return so / no - sf / nf
    }

    /** Benjamini-Hochberg: returns q-values aligned with the input p-value order. */
    internal fun bhFdr(pvals: List<Double>): List<Double> {
        val m = pvals.size
        val order = pvals.indices.sortedBy { pvals[it] }
        val q = DoubleArray(m)
        var running = 1.0
        for (rank in m downTo 1) {
            val idx = order[rank - 1]
            running = minOf(running, pvals[idx] * m / rank)
            q[idx] = running.coerceAtMost(1.0)
        }
        return q.toList()
    }

    fun stddev(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val mean = v.average()
        return sqrt(v.map { (it - mean) * (it - mean) }.average())
    }

    private fun buildFeatures(entries: List<DayEntry>): List<Feature> {
        val l = mutableListOf<Feature>()
        METRICS.forEach { m -> l += Feature("m:${m.id}", m.title, false) { it.metrics[m.id] } }
        HABITS.forEach { h -> l += Feature("h:${h.id}", h.label, true) { if (it.habits[h.id] == true) 1.0 else 0.0 } }
        val allTags = entries.flatMap { it.tags }.filter { it != "demo" }.distinct()
        allTags.forEach { t -> l += Feature("t:$t", tagLabel(t), true) { if (t in it.tags) 1.0 else 0.0 } }
        return l
    }

    private fun inRange(date: String, from: LocalDate, to: LocalDate): Boolean {
        val d = LocalDate.parse(date)
        return !d.isBefore(from) && !d.isAfter(to)
    }

    private class Candidate(
        val text: String,
        val score: Double,
        val p: Double,
        val hint: Hint?
    )

    fun generate(all: List<DayEntry>): List<Insight> {
        val entries = all.sortedBy { it.date }
        if (entries.size < MIN_DAYS) return emptyList()
        val byDate = entries.associateBy { it.date }
        val feats = buildFeatures(entries)
        val out = mutableListOf<Insight>()
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<Candidate>()

        // Every candidate is tested and all p-values go through FDR together,
        // so ~150 pairs don't produce a wall of false "findings".
        val fdrP = mutableListOf<Double>()

        for (target in listOf("mood", "energy", "anxiety")) {
            val tLabel = METRICS.firstOrNull { it.id == target }?.title?.lowercase() ?: continue
            for (lag in 0..1) for (f in feats) {
                if (f.key == "m:$target") continue
                if (lag == 1 && !f.isBool) continue
                val xs = ArrayList<Double>()
                val ys = ArrayList<Double>()
                for (e in entries) {
                    val src = if (lag == 0) e else byDate[LocalDate.parse(e.date).minusDays(1).toString()]
                    val x = if (src == null) null else f.read(src)
                    val y = e.metrics[target]
                    if (x != null && y != null) { xs.add(x); ys.add(y) }
                }
                val n = xs.size
                if (n < 8) continue
                val onVals = ArrayList<Double>()
                val offVals = ArrayList<Double>()
                if (f.isBool) {
                    for (i in 0 until n) if (xs[i] > 0.5) onVals.add(ys[i]) else offVals.add(ys[i])
                    if (onVals.size < 3 || offVals.size < 3) continue
                }
                val dedup = if (!f.isBool && lag == 0)
                    "num:" + listOf(target, f.key.removePrefix("m:")).sorted().joinToString("|")
                else "$target|$lag|${f.key}"
                if (!seen.add(dedup)) continue

                val p: Double
                val text: String
                val score: Double
                val hint: Hint?
                if (f.isBool) {
                    val xa = xs.toDoubleArray(); val ya = ys.toDoubleArray()
                    val diff = meanDiff(xa, ya)
                    val pooled = stddev(onVals + offVals)
                    val d = if (pooled > 1e-9) abs(diff) / pooled else 0.0
                    // Gate on the scatter of the DIFFERENCE OF MEANS (not of single answers):
                    // with ~15 days per group even a pure-noise gap reaches 0.5 SD fairly often.
                    val se = if (pooled > 1e-9) pooled * sqrt(1.0 / onVals.size + 1.0 / offVals.size) else 1.0
                    if (abs(diff) < 2.2 * se) continue
                    p = permutationP(xa, ya, ::meanDiff)
                    val lead = if (lag == 0) "В дни, когда отмечено «${f.label}»" else "На следующий день после «${f.label}»"
                    text = "$lead: $tLabel в среднем ${fmt(onVals.average())} против ${fmt(offVals.average())} в остальные дни " +
                        "(дней: ${onVals.size} и ${offVals.size}, различие ≈${fmt(abs(diff))} балла, ~${fmt(d)} СКО)."
                    score = minOf(1.0, d / 2.0)
                    // An experiment always measures MOOD, so only a mood finding may suggest one (for "anxiety" higher is worse
                    // and the suggested direction would be inverted).
                    hint = if (target == "mood" && lag == 0 && f.key.startsWith("h:")) Hint(f.key.removePrefix("h:"), reduce = onVals.average() < offVals.average()) else null
                } else {
                    // Daily averages: a day with several records is one point (see aggregateDays).
                    val rr = spearman(xs, ys) ?: continue
                    if (abs(rr) < 0.3) continue
                    // Spearman = Pearson on ranks; permuting the precomputed ranks gives exactly the same
                    // statistic as re-ranking after each shuffle, without 999 sorts per pair.
                    val xa = ranksOf(xs).toDoubleArray(); val ya = ranksOf(ys).toDoubleArray()
                    p = permutationP(xa, ya, { a, b -> abs(pearsonArr(a, b)) })
                    val dir = if (rr > 0) "выше" else "ниже"
                    text = "Чем больше «${f.label}», тем $dir $tLabel (связь ${fmt(rr)}, дней: $n)."
                    score = abs(rr)
                    hint = null
                }
                fdrP.add(p)
                candidates.add(Candidate("Предварительно: $text", score, p, hint))
            }
        }

        if (candidates.isNotEmpty()) {
            val q = bhFdr(fdrP)
            for (i in candidates.indices) {
                if (q[i] > 0.05) continue
                out.add(Insight(candidates[i].text, candidates[i].score, candidates[i].hint))
            }
        }

        // weekday pattern: best-vs-worst of 7 groups is an extreme-value scan, so it gets the
        // same permutation discipline - shuffle moods across weekday labels (group sizes preserved)
        // and see how often a gap this large appears by chance
        if (entries.size >= 21) {
            val names = mapOf(
                DayOfWeek.MONDAY to "понедельник", DayOfWeek.TUESDAY to "вторник", DayOfWeek.WEDNESDAY to "среда",
                DayOfWeek.THURSDAY to "четверг", DayOfWeek.FRIDAY to "пятница", DayOfWeek.SATURDAY to "суббота",
                DayOfWeek.SUNDAY to "воскресенье"
            )
            val pairs = entries.mapNotNull { e -> e.metrics["mood"]?.let { it to (LocalDate.parse(e.date).dayOfWeek.value - 1) } }
            val byDow = pairs.groupBy { it.second }.filter { it.value.size >= 2 }
            if (byDow.size >= 4 && pairs.size >= 20) {
                fun gapOf(values: DoubleArray, labels: IntArray): Double {
                    val sums = DoubleArray(7); val cnts = DoubleArray(7)
                    for (i in values.indices) { sums[labels[i]] += values[i]; cnts[labels[i]] += 1.0 }
                    // Only weekdays with >= 2 days of data: the same set that is named as best/worst below.
                    val means = (0 until 7).filter { cnts[it] >= 2 }.map { sums[it] / cnts[it] }
                    return means.maxOrNull()!! - means.minOrNull()!!
                }
                val vals = pairs.map { it.first }.toDoubleArray()
                val labels = pairs.map { it.second }.toIntArray()
                val observed = gapOf(vals, labels)
                if (observed >= 1.5) {
                    val rnd = java.util.Random(42)
                    val copy = vals.copyOf()
                    var extreme = 0
                    val perms = 999
                    for (p in 0 until perms) {
                        for (i in copy.size - 1 downTo 1) {
                            val j = rnd.nextInt(i + 1)
                            val t = copy[i]; copy[i] = copy[j]; copy[j] = t
                        }
                        if (gapOf(copy, labels) >= observed - 1e-12) extreme++
                    }
                    val p = (extreme + 1).toDouble() / (perms + 1)
                    if (p <= 0.02) {
                        val bestDow = byDow.maxByOrNull { it.value.map { x -> x.first }.average() }!!.key
                        val worstDow = byDow.minByOrNull { it.value.map { x -> x.first }.average() }!!.key
                        val scatter = stddev(pairs.map { it.first }).coerceAtLeast(0.5)
                        out.add(Insight(
                            "Предварительно: по дням недели лучшее настроение — ${names[DayOfWeek.of(bestDow + 1)]} " +
                                "(${fmt(byDow[bestDow]!!.map { x -> x.first }.average())}), худшее — ${names[DayOfWeek.of(worstDow + 1)]} " +
                                "(${fmt(byDow[worstDow]!!.map { x -> x.first }.average())}), разница ${fmt(observed)} " +
                                "при обычном разбросе ${fmt(scatter)}.",
                            minOf(1.0, observed / (2.0 * scatter))
                        ))
                    }
                }
            }
        }

        // trend - same discipline as the pairs: effect size in SD units plus a permutation test,
        // because a week-to-week difference of means has its own (much smaller) scatter
        val today = LocalDate.now()
        val last = entries.filter { inRange(it.date, today.minusDays(6), today) }.mapNotNull { it.metrics["mood"] }
        val prev = entries.filter { inRange(it.date, today.minusDays(13), today.minusDays(7)) }.mapNotNull { it.metrics["mood"] }
        if (last.size >= 3 && prev.size >= 3) {
            val d = last.average() - prev.average()
            val scatter = stddev(last + prev)
            val eff = if (scatter > 1e-9) abs(d) / scatter else 0.0
            if (eff >= 0.4 && abs(d) >= 1.0) {
                val xs = DoubleArray(last.size + prev.size) { i -> if (i < last.size) 1.0 else 0.0 }
                val ys = DoubleArray(last.size + prev.size) { i -> if (i < last.size) last[i] else prev[i - last.size] }
                val p = permutationP(xs, ys, ::meanDiff)
                if (p <= 0.02) {
                    val w = if (d > 0) "выросло" else "снизилось"
                    out.add(Insight(
                        "Предварительно: за последние 7 дней настроение $w (${fmt(last.average())} против ${fmt(prev.average())} неделей ранее; " +
                            "изменение ${fmt(abs(d))}, это ~${fmt(eff)} обычного разброса).",
                        0.9
                    ))
                }
            }
        }

        return out.sortedByDescending { it.score }.take(12)
    }
}
