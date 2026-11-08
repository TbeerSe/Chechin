package com.example.checkin

import java.time.LocalDate
import kotlin.random.Random

/** Generates fake data with planted relations (for testing statistics). Tagged "demo". 2-3 records per day. */
fun generateDemo(store: Store, days: Int = 60): Int {
    val rnd = Random(System.nanoTime())
    fun noise(s: Double) = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * s
    fun sc(x: Double) = Math.round(x).toDouble().coerceIn(1.0, 10.0)
    var prevExercise = false
    var prevAlcohol = false
    val batch = mutableListOf<DayEntry>()
    val today = LocalDate.now()
    val times = listOf("09:10", "14:30", "21:15")
    val partShift = listOf(-0.4, 0.3, 0.1)
    for (i in days downTo 1) {
        val date = today.minusDays(i.toLong()).toString()
        val exercise = rnd.nextDouble() < 0.4
        val alcohol = rnd.nextDouble() < 0.2
        val caffeine = rnd.nextDouble() < 0.6
        val social = rnd.nextDouble() < 0.5
        val screens = rnd.nextDouble() < 0.5
        val work = rnd.nextDouble() < 0.6
        val conflict = rnd.nextDouble() < 0.12
        val sleep = (Math.round((6.8 + noise(2.0)) * 2) / 2.0).coerceIn(4.0, 10.0)
        val stress = sc(4.0 + (if (work) 1.5 else 0.0) + (if (conflict) 1.5 else 0.0) + noise(1.5))
        val mood = 5.5 + 0.7 * (sleep - 7) + (if (exercise) 0.9 else 0.0) - (if (prevAlcohol) 1.2 else 0.0) - 0.4 * (stress - 4)
        val energy = 5.5 + 0.6 * (sleep - 7) + (if (prevExercise) 1.0 else 0.0)
        val anxiety = 4.0 + 0.5 * (stress - 4) + (if (caffeine) 1.0 else 0.0)
        val focus = 5.0 + 0.4 * (sleep - 7)
        val sq = sc(6.0 + 0.4 * (sleep - 7) + noise(1.5))
        val tags = mutableListOf("demo")
        if (work) tags.add("work")
        if (conflict) tags.add("conflict")
        if (rnd.nextDouble() < 0.3) tags.add("family")
        if (social) tags.add("friends")
        if (store.forDate(date).isEmpty()) {
            val count = 2 + rnd.nextInt(2)
            for (k in 0 until count) {
                val habits = mapOf(
                    "exercise" to (exercise && k > 0), "alcohol" to (alcohol && k == 2),
                    "caffeine" to (caffeine && k > 0), "social" to (social && k > 0), "screens_late" to (screens && k == 2)
                )
                batch.add(DayEntry(
                    newEntryId(), date, times[k],
                    mapOf(
                        "mood" to sc(mood + partShift[k] + noise(1.2)),
                        "energy" to sc(energy + (if (k == 2) -0.8 else 0.0) + noise(1.2)),
                        "anxiety" to sc(anxiety + (if (k == 2) 0.4 else 0.0) + noise(1.2)),
                        "stress" to sc(stress + noise(0.8)),
                        "focus" to sc(focus + noise(1.2)),
                        "sleep_hours" to sleep,
                        "sleep_quality" to sq
                    ),
                    habits, tags, "", System.currentTimeMillis() + k
                ))
            }
        }
        prevExercise = exercise
        prevAlcohol = alcohol
    }
    store.saveAll(batch)
    return batch.size
}
