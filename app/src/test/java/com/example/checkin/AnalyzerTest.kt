package com.example.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pure-JVM tests: run as ordinary unit tests (./gradlew testDebugUnitTest).
 * Crypto is deliberately covered through the same public contract.
 */
class AnalyzerTest {

    private fun entry(date: String, time: String, mood: Double? = null, metrics: Map<String, Double> = emptyMap()) =
        DayEntry("t$date$time", date, time,
            (if (mood != null) mapOf("mood" to mood) else emptyMap()) + metrics,
            emptyMap(), emptyList(), "", 0L)

    @Test
    fun pearsonPerfectAndInverse() {
        assertEquals(1.0, Analyzer.pearson(listOf(1.0, 2.0, 3.0, 4.0), listOf(2.0, 4.0, 6.0, 8.0))!!, 1e-9)
        assertEquals(-1.0, Analyzer.pearson(listOf(1.0, 2.0, 3.0, 4.0), listOf(8.0, 6.0, 4.0, 2.0))!!, 1e-9)
        assertNull(Analyzer.pearson(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0))) // zero variance
    }

    @Test
    fun spearmanHandlesTies() {
        // monotone with a tie
        val r = Analyzer.spearman(listOf(1.0, 2.0, 2.0, 4.0), listOf(3.0, 5.0, 4.0, 9.0))
        assertNotNull(r)
        assertTrue(r!! > 0.8)
    }

    @Test
    fun dayPartBoundaries() {
        assertEquals(0, dayPart("05:00"))
        assertEquals(0, dayPart("10:59"))
        assertEquals(1, dayPart("11:00"))
        assertEquals(2, dayPart("17:00"))
        assertEquals(2, dayPart("22:59"))
        assertEquals(3, dayPart("23:00"))
        assertEquals(3, dayPart("04:30"))
    }

    @Test
    fun ranksOfAveragesTies() {
        assertEquals(listOf(1.0, 2.5, 2.5, 4.0), Analyzer.ranksOf(listOf(10.0, 20.0, 20.0, 30.0)))
        assertEquals(listOf(3.0, 1.0, 2.0), Analyzer.ranksOf(listOf(9.0, 1.0, 5.0)))
    }

    @Test
    fun aggregateDaysNeverInventsDefaults() {
        val entries = listOf(
            entry("2026-01-01", "09:00", mood = 4.0, metrics = mapOf("sleep_hours" to 6.0)),
            entry("2026-01-01", "21:00", mood = 6.0), // evening entry has no sleep_hours
            entry("2026-01-02", "09:00", mood = 5.0)
        )
        val daily = aggregateDays(entries)
        assertEquals(2, daily.size)
        val d1 = daily.first { it.date == "2026-01-01" }
        assertEquals(5.0, d1.metrics["mood"]!!, 1e-9)
        // sleep_hours averaged only over records that actually have it
        assertEquals(6.0, d1.metrics["sleep_hours"]!!, 1e-9)
        val d2 = daily.first { it.date == "2026-01-02" }
        assertNull(d2.metrics["sleep_hours"])
    }

    @Test
    fun bhFdrControlsSingleStrongSignal() {
        // one tiny p-value among many mediocre ones stays significant after adjustment
        val q = Analyzer.bhFdr(listOf(0.001) + List(50) { 0.2 })
        assertTrue(q[0] < 0.052)
        // a lone "0.04" with 50 tests is exactly what FDR is for: inflated to insignificance
        val q2 = Analyzer.bhFdr(listOf(0.04) + List(49) { 0.5 })
        assertTrue(q2[0] > 0.1)
    }

    @Test
    fun stddevIsPopulationStddev() {
        assertEquals(0.0, Analyzer.stddev(listOf(3.0, 3.0, 3.0)), 1e-9)
        assertEquals(0.8165, Analyzer.stddev(listOf(1.0, 2.0, 3.0)), 1e-3)
    }

    @Test
    fun generateFindsPlantedBoolRelation() {
        // 30 days: on odd days the habit is on and mood is 2 higher -> should surface with q <= 0.1
        val today = LocalDate.now()
        val entries = mutableListOf<DayEntry>()
        for (i in 0 until 30) {
            val date = today.minusDays((30 - i).toLong()).toString()
            val habit = i % 2 == 0
            val mood = (if (habit) 7.0 else 5.0) + (i % 3) * 0.1
            entries.add(
                DayEntry("g$i", date, "21:00", mapOf("mood" to mood),
                    if (habit) mapOf("exercise" to true) else emptyMap(), emptyList(), "", 0L)
            )
        }
        val insights = Analyzer.generate(aggregateDays(entries))
        assertTrue(insights.any { it.text.contains("Спорт") || it.text.contains("exercise") || it.hint != null })
    }

    @Test
    fun generateKeepsNoiseMostlySilent() {
        // A single noise draw can always luck out - that's what "preliminary" means.
        // Across 10 independent noise runs at most 2 may report anything.
        val today = LocalDate.now()
        var leaks = 0
        for (seed in 1..10) {
            val rnd = java.util.Random(seed.toLong())
            val entries = mutableListOf<DayEntry>()
            for (i in 0 until 40) {
                val date = today.minusDays((40 - i).toLong()).toString()
                entries.add(
                    DayEntry("n$seed-$i", date, "21:00",
                        mapOf("mood" to 1.0 + rnd.nextInt(10), "energy" to 1.0 + rnd.nextInt(10)),
                        if (rnd.nextBoolean()) mapOf("exercise" to true) else emptyMap(),
                        emptyList(), "", 0L)
                )
            }
            if (Analyzer.generate(aggregateDays(entries)).isNotEmpty()) leaks++
        }
        assertTrue("noise leaks: $leaks/10", leaks <= 2)
    }

    @Test
    fun reminderPartLogicIgnoresOtherParts() {
        val morning = entry("2026-01-05", "08:30", mood = 6.0)
        assertTrue(hasEntryInPart(listOf(morning), "2026-01-05", "09:15"))
        assertTrue(!hasEntryInPart(listOf(morning), "2026-01-05", "20:00"))
        assertTrue(!hasEntryInPart(listOf(morning), "2026-01-06", "08:30"))
    }

    @Test
    fun entryIdsAreUnique() {
        assertEquals(500, (1..500).map { newEntryId() }.toSet().size)
    }

    // ---- regressions for the audit fixes

    @Test
    fun verdictFollowsMoodDirectionWhetherReducingOrIncreasing() {
        // Mood is the outcome: up is better, regardless of the habit's direction.
        assertEquals(Verdict.BETTER, verdictOf(1.0, 1.0))
        assertEquals(Verdict.WORSE, verdictOf(-1.0, 1.0))
        assertEquals(Verdict.NONE, verdictOf(0.2, 1.0))
    }

    @Test
    fun heatmapLastRowIsTheCurrentWeek() {
        for (offset in 0L..13L) {
            val today = LocalDate.of(2026, 11, 2).plusDays(offset)   // every weekday, two weeks
            val start = heatmapStart(today, 12)
            assertEquals(java.time.DayOfWeek.MONDAY, start.dayOfWeek)
            val lastDay = start.plusDays(12 * 7 - 1L)
            assertTrue("today must be inside the grid", !today.isAfter(lastDay))
            assertTrue("and in the last row", today.isAfter(start.plusDays(11 * 7 - 1L)))
        }
    }

    @Test
    fun csvEscapeNeutralisesFormulasAndQuotesSeparators() {
        assertEquals("'=1+1", CsvExport.esc("=1+1"))
        assertEquals("'@cmd", CsvExport.esc("@cmd"))
        assertEquals("\"a,b\"", CsvExport.esc("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.esc("say \"hi\""))
        assertEquals("plain", CsvExport.esc("plain"))
    }

    @Test
    fun csvNumbersUseDotEvenInRussianLocale() {
        val sleep = Metric("sleep_hours", "Сон", 0.0, 12.0, 0.5, 7.0, "", 0, false)
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("ru", "RU"))
            assertEquals("6.5", CsvExport.num(sleep, 6.5))
            assertEquals("09:05", hhmm(9, 5))
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun sliderStepsSurviveFloatingPointDivision() {
        assertEquals(23, Metric("s", "S", 0.0, 12.0, 0.5, 7.0, "", 0, false).steps)
        assertEquals(8, Metric("m", "M", 1.0, 10.0, 1.0, 5.0, "", 0, false).steps)
        // 0.7 / 0.1 is 6.999999999999999 in floating point: still 7 intervals -> 6 steps (plain toInt() gave 5)
        assertEquals(6, Metric("f", "F", 0.0, 0.7, 0.1, 0.3, "", 0, false).steps)
    }

    @Test
    fun dayPartLabelsMatchBoundaries() {
        assertEquals(0, dayPart("05:00")); assertEquals(0, dayPart("10:59"))
        assertEquals(1, dayPart("11:00")); assertEquals(2, dayPart("17:00"))
        assertEquals(3, dayPart("23:00")); assertEquals(3, dayPart("04:59"))
    }
}
