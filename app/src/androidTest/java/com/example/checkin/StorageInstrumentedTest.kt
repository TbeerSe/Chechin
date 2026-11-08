package com.example.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** org.json is a stub in plain JVM unit tests, so JSON (de)serialisation is checked on a device/emulator. */
@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {

    @Test
    fun entryJsonRoundTripKeepsMissingMetricsMissing() {
        val e = DayEntry("t1", "2026-03-04", "08:15", mapOf("mood" to 7.0, "energy" to 3.0),
            emptyMap(), emptyList(), "", 0L)
        val back = entryFromJson(e.toJson())
        assertEquals(e.date, back.date)
        assertEquals(2, back.metrics.size)
        assertNull(back.metrics["sleep_hours"])
    }

    @Test
    fun nonFiniteMetricValuesAreDroppedNotFatal() {
        val e = DayEntry("t2", "2026-03-04", "08:15", mapOf("mood" to 7.0, "energy" to Double.NaN),
            emptyMap(), emptyList(), "", 0L)
        // must not throw (org.json refuses NaN) - otherwise every save would fail silently
        val back = entryFromJson(e.toJson())
        assertEquals(setOf("mood"), back.metrics.keys)
    }

    @Test
    fun experimentJsonRoundTrip() {
        val x = Experiment("x1", "Меньше алкоголя", "alcohol", true, "2026-01-01", "2026-01-14", 0, null, 123L)
        val back = experimentFromJson(x.toJson())
        assertEquals(x.habitId, back.habitId)
        assertEquals(x.endDate, back.endDate)
        assertNull(back.verdict)
        assertEquals(1, experimentFromJson(x.copy(status = 1, verdict = "ok").toJson()).status)
    }

    @Test
    fun brokenMetricScaleIsRejectedOnImport() {
        fun scale(min: Double, max: Double, step: Double) = JSONObject()
            .put("id", "u_x").put("title", "x").put("min", min).put("max", max)
            .put("step", step).put("default", 5.0)
        for (bad in listOf(scale(1.0, 10.0, 0.0), scale(5.0, 5.0, 1.0), scale(1.0, 10.0, 0.00001))) {
            try { metricFromJson(bad); fail("accepted a broken scale") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(10.0, metricFromJson(scale(1.0, 10.0, 1.0)).max, 0.0)
    }

    @Test
    fun clearPinKeepsUserSettings() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Lock.setPin(ctx, "1234")
        Lock.setDelay(ctx, 300)
        Lock.clearPin(ctx)
        assertEquals(false, Lock.hasPin(ctx))
        assertEquals(300, Lock.delaySec(ctx))
    }
}
