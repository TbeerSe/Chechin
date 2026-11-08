package com.example.checkin

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

const val SCHEMA_VERSION = 5

/** One check-in record. A day can have any number of them (morning, noon, evening...).
 *  A metric missing from [metrics] means "not asked / not answered" - never a default value. */
data class DayEntry(
    val id: String,
    val date: String,                    // yyyy-MM-dd
    val time: String,                    // HH:mm
    val metrics: Map<String, Double>,
    val habits: Map<String, Boolean>,
    val tags: List<String>,
    val note: String,
    val updatedAt: Long
)

fun newEntryId(): String = "e" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

/** True if [entries] already hold a record for [date] in the same part of the day as [time]. */
fun hasEntryInPart(entries: List<DayEntry>, date: String, time: String): Boolean {
    val part = dayPart(time)
    return entries.any { it.date == date && dayPart(it.time) == part }
}

/** Parts of the day: 0 morning, 1 day, 2 evening, 3 night. */
val DAY_PARTS = listOf("утро (с 5 до 11)", "день (с 11 до 17)", "вечер (с 17 до 23)", "ночь (с 23 до 5)")

/** Machine-readable HH:mm, independent of the device locale (digits, separators). */
fun hhmm(h: Int, m: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)

fun dayPart(time: String): Int {
    val h = time.substringBefore(":").toIntOrNull() ?: 12
    return when {
        h in 5..10 -> 0
        h in 11..16 -> 1
        h in 17..22 -> 2
        else -> 3
    }
}

/** Sleep metrics are only asked in the morning - daytime/evening entries can't know last night's sleep. */
fun isSleepMetric(id: String) = id == "sleep_hours" || id == "sleep_quality"

/** Metrics offered in the entry form for a given record time. */
fun metricsForTime(time: String): List<Metric> =
    METRICS.filter { !it.hidden && (dayPart(time) == 0 || !isSleepMetric(it.id)) }

fun recWord(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val w = if (m100 in 11..14) "записей" else when (m10) {
        1 -> "запись"
        in 2..4 -> "записи"
        else -> "записей"
    }
    return "$n $w"
}

private val ENTRY_ORDER = compareBy<DayEntry>({ it.date }, { it.time }, { it.id })

/**
 * One synthetic entry per day (averages of metrics, union of habits and tags) - used for statistics.
 * Only metrics actually recorded participate; a day with fewer answers never invents defaults.
 */
fun aggregateDays(entries: List<DayEntry>): List<DayEntry> =
    entries.groupBy { it.date }.map { (date, list) ->
        val sorted = list.sortedWith(ENTRY_ORDER)
        val ids = sorted.flatMap { it.metrics.keys }.distinct()
        val metrics = ids.associateWith { id -> sorted.mapNotNull { it.metrics[id] }.average() }
        val doneHabits = sorted.flatMap { e -> e.habits.filter { it.value }.keys }.distinct()
        val habits = doneHabits.associateWith { true }
        val tags = sorted.flatMap { it.tags }.distinct()
        val note = sorted.map { it.note }.filter { it.isNotBlank() }.joinToString("\n")
        DayEntry("d$date", date, sorted.last().time, metrics, habits, tags, note, sorted.maxOf { it.updatedAt })
    }.sortedBy { it.date }

fun DayEntry.toJson(): JSONObject {
    val e = this
    val o = JSONObject()
    o.put("id", e.id)
    o.put("date", e.date)
    o.put("time", e.time)
    val mo = JSONObject(); e.metrics.forEach { (k, v) -> if (v.isFinite()) mo.put(k, v) }
    o.put("metrics", mo)
    val ho = JSONObject(); e.habits.forEach { (k, v) -> ho.put(k, v) }
    o.put("habits", ho)
    o.put("tags", JSONArray(e.tags))
    o.put("note", e.note)
    o.put("updatedAt", e.updatedAt)
    return o
}

fun TestResult.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id)
    o.put("instrument", instrument)
    o.put("takenAt", takenAt)
    o.put("activeSec", activeSec)
    o.put("answers", JSONArray(answers))
    return o
}

fun testFromJson(o: JSONObject): TestResult {
    val a = o.getJSONArray("answers")
    val list = (0 until a.length()).map { a.getInt(it) }
    if (!Ipip.isValid(list)) throw IllegalArgumentException("Некорректные данные теста")
    return TestResult(o.getString("id"), o.optString("instrument", Ipip.ID), o.getLong("takenAt"), list, o.optInt("activeSec", 0))
}

/** A 14-day self-experiment created from an insight: "do more/less of habit X and watch the mood". */
data class Experiment(
    val id: String,
    val title: String,
    val habitId: String,
    val reduce: Boolean,          // true = try to do it less often, false = more often
    val startDate: String,        // yyyy-MM-dd
    val endDate: String,          // start + 13 days, inclusive
    val status: Int,              // 0 active, 1 finished, 2 cancelled
    val verdict: String?,         // filled when finished
    val createdAt: Long
)

fun Experiment.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id); o.put("title", title); o.put("habitId", habitId); o.put("reduce", reduce)
    o.put("startDate", startDate); o.put("endDate", endDate); o.put("status", status)
    o.put("verdict", verdict); o.put("createdAt", createdAt)
    return o
}

fun experimentFromJson(o: JSONObject) = Experiment(
    o.getString("id"), o.getString("title"), o.getString("habitId"), o.optBoolean("reduce", false),
    o.getString("startDate"), o.getString("endDate"), o.optInt("status", 0),
    if (o.isNull("verdict")) null else o.getString("verdict").ifBlank { null }, o.optLong("createdAt", 0L)
)

private fun legacyTime(date: String, updatedAt: Long): String {
    if (updatedAt <= 0L) return "12:00"
    val dt = Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault())
    return if (dt.toLocalDate().toString() == date) hhmm(dt.hour, dt.minute) else "12:00"
}

fun entryFromJson(o: JSONObject): DayEntry {
    val date = o.getString("date")
    LocalDate.parse(date) // validation
    val upd = o.optLong("updatedAt", 0L)
    val id = o.optString("id", "").ifBlank { "l$date" }
    val time = o.optString("time", "").ifBlank { legacyTime(date, upd) }
    val m = mutableMapOf<String, Double>()
    o.optJSONObject("metrics")?.let { mo -> mo.keys().forEach { k -> val v = mo.optDouble(k, Double.NaN); if (v.isFinite()) m[k] = v } }
    val h = mutableMapOf<String, Boolean>()
    o.optJSONObject("habits")?.let { ho -> ho.keys().forEach { k -> h[k] = ho.getBoolean(k) } }
    val tags = mutableListOf<String>()
    o.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) tags.add(a.getString(i)) }
    return DayEntry(id, date, time, m, h, tags, o.optString("note", ""), upd)
}

fun Metric.toJson(): JSONObject {
    val m = this
    val o = JSONObject()
    o.put("id", m.id); o.put("title", m.title)
    o.put("min", m.min); o.put("max", m.max); o.put("step", m.step); o.put("default", m.default)
    o.put("emoji", m.emoji); o.put("polarity", m.polarity); o.put("hidden", m.hidden)
    return o
}

fun metricFromJson(o: JSONObject): Metric {
    val min = o.getDouble("min"); val max = o.getDouble("max")
    val step = o.getDouble("step"); val def = o.getDouble("default")
    // A broken scale (step 0, max <= min, NaN, absurd step count) would crash the slider on every launch.
    if (!min.isFinite() || !max.isFinite() || !step.isFinite() || !def.isFinite() ||
        max <= min || step <= 0.0 || (max - min) / step > 1000.0)
        throw IllegalArgumentException("Некорректная шкала показателя «${o.optString("title")}»")
    return Metric(
        o.getString("id"), o.getString("title"), min, max, step, def.coerceIn(min, max),
        o.optString("emoji", ""), o.optInt("polarity", 0), o.optBoolean("hidden", false)
    )
}

fun Choice.toJson(): JSONObject {
    val c = this
    val o = JSONObject()
    o.put("id", c.id); o.put("label", c.label); o.put("emoji", c.emoji); o.put("hidden", c.hidden)
    return o
}

fun choiceFromJson(o: JSONObject) = Choice(
    o.getString("id"), o.getString("label"), o.optString("emoji", ""), o.optBoolean("hidden", false)
)

private class Parsed(
    val entries: List<DayEntry>,
    val metrics: List<Metric>?,
    val habits: List<Choice>?,
    val reasons: List<Choice>?,
    val tests: List<TestResult>,
    val experiments: List<Experiment>
)

class Store private constructor(private val ctx: Context) {
    private val file = File(ctx.filesDir, "checkin_data.json")
    private val draftFile = File(ctx.filesDir, "checkin_ipip_draft.json")
    val entries = mutableStateListOf<DayEntry>()
    val results = mutableStateListOf<TestResult>()
    val experiments = mutableStateListOf<Experiment>()

    /** Non-null if the data file could not be read: the UI shows a warning with the recovery-copy name. */
    var loadError: String? = null
        private set

    /** True if the main file was damaged and the data was restored from a rotating backup. */
    var recoveredFromBackup: Boolean = false
        private set

    companion object {
        @Volatile private var instance: Store? = null

        /** One process-wide Store: the Activity and the broadcast receivers must share the same in-memory
         *  state, otherwise each would overwrite the other's changes on disk. */
        fun shared(c: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(c.applicationContext).also { instance = it }
            }

        /** One shared writer thread: file writes never block the UI and always happen in the order they were requested. */
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "checkin-writer") }

        /** Number of rotating local backup snapshots kept next to the main file. */
        private const val BACKUP_SLOTS = 3

        /** Minimum age of the newest snapshot before a new one is taken. */
        private const val BACKUP_MIN_AGE_MS = 20L * 60 * 60 * 1000
    }

    init { load() }

    /** Blocks until every write requested so far has reached the disk (used by receivers before they finish). */
    fun awaitWrites(timeoutMs: Long = 5000) {
        try { writer.submit(Runnable { }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }

    private fun parse(text: String): Parsed {
        val root = JSONObject(text)
        val ver = root.optInt("schemaVersion", 1)
        if (ver > SCHEMA_VERSION) throw IllegalArgumentException("Файл создан более новой версией приложения (schema $ver)")
        val arr = root.getJSONArray("entries")
        val list = (0 until arr.length()).map { entryFromJson(arr.getJSONObject(it)) }
        val cfg = root.optJSONObject("config")
        fun <T> items(key: String, f: (JSONObject) -> T): List<T>? {
            val a = cfg?.optJSONArray(key) ?: return null
            return (0 until a.length()).map { f(a.getJSONObject(it)) }
        }
        val tarr = root.optJSONArray("tests")
        val tests = if (tarr == null) emptyList() else (0 until tarr.length()).map { testFromJson(tarr.getJSONObject(it)) }
        val exparr = root.optJSONArray("experiments")
        val exps = if (exparr == null) emptyList() else (0 until exparr.length()).map { experimentFromJson(exparr.getJSONObject(it)) }
        return Parsed(list, items("metrics") { metricFromJson(it) }, items("habits") { choiceFromJson(it) }, items("reasons") { choiceFromJson(it) }, tests, exps)
    }

    private fun applyConfig(p: Parsed, replace: Boolean) {
        p.metrics?.let { im ->
            Cfg.metrics = if (replace)
                im + defaultMetrics().filter { d -> im.none { x -> x.id == d.id } }.map { it.copy(hidden = true) }
            else
                Cfg.metrics + im.filter { x -> Cfg.metrics.none { c -> c.id == x.id } }
        }
        p.habits?.let { im ->
            Cfg.habits = if (replace)
                im + defaultHabits().filter { d -> im.none { x -> x.id == d.id } }.map { it.copy(hidden = true) }
            else
                Cfg.habits + im.filter { x -> Cfg.habits.none { c -> c.id == x.id } }
        }
        p.reasons?.let { im ->
            Cfg.reasons = if (replace)
                im + defaultReasons().filter { d -> im.none { x -> x.id == d.id } }.map { it.copy(hidden = true) }
            else
                Cfg.reasons + im.filter { x -> Cfg.reasons.none { c -> c.id == x.id } }
        }
    }

    private fun noticePrefs() = ctx.getSharedPreferences("store_notice", Context.MODE_PRIVATE)

    /** The user has seen the "file was damaged" message. */
    fun acknowledgeLoadError() {
        loadError = null
        recoveredFromBackup = false
        noticePrefs().edit().clear().apply()
    }

    private fun load() {
        loadFiles()
        val np = noticePrefs()
        val fresh = loadError
        if (fresh != null) {
            np.edit().putString("file", fresh).putBoolean("recovered", recoveredFromBackup).apply()
        } else {
            // An earlier process met a damaged file but the UI never showed the message
            // (for example the process was started by a notification action).
            val saved = np.getString("file", null)
            if (saved != null) { loadError = saved; recoveredFromBackup = np.getBoolean("recovered", false) }
        }
    }

    private fun loadFiles() {
        // If the activity was just recreated, let any write that is still in flight finish before reading the file.
        try { writer.submit(Runnable { }).get() } catch (_: Exception) {}
        if (!file.exists()) return
        try {
            val p = parse(file.readText(Charsets.UTF_8))
            entries.addAll(p.entries.sortedWith(ENTRY_ORDER))
            results.addAll(p.tests.sortedBy { it.takenAt })
            experiments.addAll(p.experiments.sortedBy { it.createdAt })
            applyConfig(p, true)
        } catch (e: Exception) {
            // Keep the damaged file under a dated name (older corrupt copies are never overwritten)
            // and tell the user instead of silently starting empty.
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val copy = File(ctx.filesDir, "checkin_data.corrupt.$stamp.json")
            try { file.copyTo(copy, overwrite = false) } catch (_: Exception) {}
            loadError = copy.name
            // Try the rotating backups, newest first, so the user does not start from an empty diary.
            for (i in 0 until BACKUP_SLOTS) {
                val b = File(ctx.filesDir, "checkin_backup_$i.json")
                if (!b.exists()) continue
                try {
                    val p = parse(b.readText(Charsets.UTF_8))
                    entries.clear(); results.clear(); experiments.clear()
                    entries.addAll(p.entries.sortedWith(ENTRY_ORDER))
                    results.addAll(p.tests.sortedBy { it.takenAt })
                    experiments.addAll(p.experiments.sortedBy { it.createdAt })
                    applyConfig(p, true)
                    recoveredFromBackup = true
                    break
                } catch (_: Exception) {
                }
            }
        }
    }

    /** @param indent 2 for human-readable exports, 0 for the on-disk copy. */
    fun toJsonString(indent: Int = 2): String =
        buildJsonString(entries.toList(), results.toList(), experiments.toList(),
            Cfg.metrics, Cfg.habits, Cfg.reasons, indent)

    private fun buildJsonString(
        es: List<DayEntry>, ts: List<TestResult>, xs: List<Experiment>,
        ms: List<Metric>, hs: List<Choice>, rs: List<Choice>, indent: Int
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("app", "checkin")
        root.put("exportedAt", System.currentTimeMillis())
        val cfg = JSONObject()
        val ma = JSONArray(); ms.forEach { ma.put(it.toJson()) }
        val ha = JSONArray(); hs.forEach { ha.put(it.toJson()) }
        val ra = JSONArray(); rs.forEach { ra.put(it.toJson()) }
        cfg.put("metrics", ma); cfg.put("habits", ha); cfg.put("reasons", ra)
        root.put("config", cfg)
        val arr = JSONArray()
        es.forEach { arr.put(it.toJson()) }
        root.put("entries", arr)
        val tarr = JSONArray()
        ts.forEach { tarr.put(it.toJson()) }
        root.put("tests", tarr)
        val earr = JSONArray()
        xs.forEach { earr.put(it.toJson()) }
        root.put("experiments", earr)
        return if (indent > 0) root.toString(indent) else root.toString()
    }

    /**
     * The JSON snapshot is built on the writer thread (compact, no pretty-printing - days of data
     * stay cheap), then written through AtomicFile, which fsyncs before rename, so a crash or a
     * dead battery mid-save can at worst lose the very last edit, never the whole diary.
     * After a successful save the three newest snapshots are rotated into checkin_backup_N.json.
     */
    fun persist(wipeSideCopies: Boolean = false) {
        // Snapshot on the calling (UI) thread: iterating SnapshotStateList from the writer
        // thread while the UI mutates it throws ConcurrentModificationException.
        val snapEntries = entries.toList()
        val snapResults = results.toList()
        val snapExps = experiments.toList()
        val snapMetrics = Cfg.metrics
        val snapHabits = Cfg.habits
        val snapReasons = Cfg.reasons
        writer.execute {
            try {
                val json = buildJsonString(snapEntries, snapResults, snapExps,
                    snapMetrics, snapHabits, snapReasons, 0)
                val af = android.util.AtomicFile(file)
                val out = af.startWrite()
                try {
                    out.write(json.toByteArray(Charsets.UTF_8))
                    af.finishWrite(out)
                } catch (e: Exception) {
                    af.failWrite(out)
                    throw e
                }
                if (wipeSideCopies) deleteSideCopies()
                rotateBackups()
            } catch (e: Exception) {
                android.util.Log.e("Store", "persist failed", e)
            }
        }
    }

    /** Backups, damaged-file copies and the test draft all hold diary content. */
    private fun deleteSideCopies() {
        try {
            ctx.filesDir.listFiles()?.filter {
                it.name.startsWith("checkin_backup_") || it.name.startsWith("checkin_data.corrupt.") ||
                    it.name == "checkin_ipip_draft.json"
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.e("Store", "side copy cleanup failed", e)
        }
    }

    private fun rotateBackups() {
        try {
            val dir = ctx.filesDir
            // One snapshot per day: three quick edits (or a "clear all") must not wipe every older copy.
            val newest = File(dir, "checkin_backup_0.json")
            if (newest.exists() && System.currentTimeMillis() - newest.lastModified() < BACKUP_MIN_AGE_MS) return
            File(dir, "checkin_backup_${BACKUP_SLOTS - 1}.json").delete()
            for (i in BACKUP_SLOTS - 2 downTo 0) {
                val f = File(dir, "checkin_backup_$i.json")
                if (f.exists()) f.renameTo(File(dir, "checkin_backup_${i + 1}.json"))
            }
            file.copyTo(File(dir, "checkin_backup_0.json"), overwrite = true)
        } catch (e: Exception) {
            android.util.Log.e("Store", "backup rotation failed", e)
        }
    }

    fun byId(id: String): DayEntry? = entries.firstOrNull { it.id == id }

    /** All records of a day, ordered by time. */
    fun forDate(date: String): List<DayEntry> = entries.filter { it.date == date }.sortedWith(ENTRY_ORDER)

    /** Latest record of a day (or null if the day is empty). */
    fun get(date: String): DayEntry? = forDate(date).lastOrNull()

    /** Records whose note, tags, habits or metric values match the query (for history search). */
    fun search(query: String): List<DayEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries.toList()
        val habitIds = HABITS.filter { it.label.lowercase().contains(q) }.map { it.id }.toSet()
        val metricIds = Cfg.metrics.filter { it.title.lowercase().contains(q) }.map { it.id }.toSet()
        val tagIds = REASONS.filter { it.label.lowercase().contains(q) }.map { it.id }.toSet()
        return entries.filter { e ->
            e.note.lowercase().contains(q) ||
                e.tags.any { it.lowercase().contains(q) || it in tagIds } ||
                e.habits.keys.any { it in habitIds } ||
                e.metrics.keys.any { it.lowercase().contains(q) || it in metricIds }
        }
    }

    private fun upsert(e: DayEntry) {
        val i = entries.indexOfFirst { it.id == e.id }
        if (i >= 0) entries[i] = e else entries.add(e)
    }

    fun save(e: DayEntry) {
        upsert(e)
        entries.sortWith(ENTRY_ORDER)
        persist()
    }

    /** Saves many records with a single disk write. */
    fun saveAll(list: List<DayEntry>) {
        list.forEach { upsert(it) }
        entries.sortWith(ENTRY_ORDER)
        persist()
    }

    fun delete(id: String) {
        entries.removeAll { it.id == id }
        persist()
    }

    fun deleteWhere(pred: (DayEntry) -> Boolean): Int {
        val n = entries.count(pred)
        entries.removeAll(pred)
        persist()
        return n
    }

    /** Removes everything the user wrote: records, test results, experiments, and every on-disk copy of them.
     *  Question/habit settings stay. */
    fun clearAll() {
        entries.clear(); results.clear(); experiments.clear()
        acknowledgeLoadError()
        persist(wipeSideCopies = true)
    }

    /** Returns number of imported/updated records. Throws on invalid file (nothing is changed then). */
    fun import(text: String, replace: Boolean): Int {
        val p = parse(text)
        if (replace) { entries.clear(); results.clear(); experiments.clear() }
        applyConfig(p, replace)
        var n = 0
        for (e in p.entries) {
            val ex = byId(e.id)
            if (ex == null || replace || e.updatedAt >= ex.updatedAt) {
                upsert(e); n++
            }
        }
        for (t in p.tests) {
            if (results.none { it.id == t.id }) { results.add(t); n++ }
        }
        for (x in p.experiments) {
            val i = experiments.indexOfFirst { it.id == x.id }
            if (i < 0) { experiments.add(x); n++ }
            else if (experiments[i].status == 0 && x.status != 0) { experiments[i] = x; n++ }
        }
        results.sortBy { it.takenAt }
        experiments.sortBy { it.createdAt }
        entries.sortWith(ENTRY_ORDER)
        persist()
        return n
    }

    // ---- personality test results
    fun addResult(r: TestResult) {
        results.add(r)
        results.sortBy { it.takenAt }
        persist()
    }

    fun deleteResult(id: String) {
        results.removeAll { it.id == id }
        persist()
    }

    // ---- experiments
    fun addExperiment(x: Experiment) {
        experiments.add(x)
        experiments.sortBy { it.createdAt }
        persist()
    }

    fun updateExperiment(x: Experiment) {
        val i = experiments.indexOfFirst { it.id == x.id }
        if (i >= 0) experiments[i] = x
        persist()
    }

    // ---- unfinished test (kept out of exports)
    fun loadDraft(): IpipDraft? = try {
        awaitWrites(2000)   // a queued save/clear must land before we read
        if (!draftFile.exists()) null else {
            val o = JSONObject(draftFile.readText(Charsets.UTF_8))
            val a = o.getJSONArray("answers")
            val l = (0 until a.length()).map { a.getInt(it) }
            if (l.size != Ipip.COUNT) null else IpipDraft(l, o.optLong("activeMs", 0L), o.optLong("startedAt", System.currentTimeMillis()))
        }
    } catch (e: Exception) {
        null
    }

    fun saveDraft(d: IpipDraft) {
        val o = JSONObject()
        o.put("answers", JSONArray(d.answers))
        o.put("activeMs", d.activeMs)
        o.put("startedAt", d.startedAt)
        val text = o.toString()
        writer.execute {
            try { draftFile.writeText(text, Charsets.UTF_8) } catch (_: Exception) {}
        }
    }

    fun clearDraft() {
        // Through the same single writer as saveDraft: otherwise a queued save lands after the delete
        // and resurrects a finished test's answers.
        writer.execute { try { draftFile.delete() } catch (_: Exception) {} }
    }
}
