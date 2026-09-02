package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.PracticeStreak
import app.anothermorsetrainer.morsekit.SessionHistory
import app.anothermorsetrainer.morsekit.SessionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/** One finished practice session, as shown on the Stats screen. */
data class SessionSummary(
    val mode: String,
    val epochDay: Long,        // LocalDate.toEpochDay() — day-granular is enough for the list
    val attempts: Int,
    val correct: Int,
    val bestTtrMs: Int?,       // fastest correct recognition this session, if any
    /** Character speed the session was sent at; 0 for records saved before this field. */
    val characterWpm: Int = 0,
    /** Median correct recognition time this session, if any — feeds speed bands. */
    val medianTtrMs: Int? = null,
    /** The full [SessionRecord]'s id in [Stats.history]; null before details existed. */
    val recordId: String? = null
) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts
}

/** Lifetime recognition data for one character. */
data class CharAgg(val attempts: Int, val correct: Int, val ttrsMs: List<Int>) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts

    /** Median of the recent correct recognition times, or null if never copied correctly. */
    val medianMs: Int?
        get() {
            if (ttrsMs.isEmpty()) return null
            val s = ttrsMs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }
}

/**
 * Persisted progress: the daily [PracticeStreak], lifetime totals, a best
 * recognition time, and a bounded list of recent sessions. Process-wide
 * singleton like [Settings]; recomposes Stats readers when [record] runs.
 *
 * Surfaces the ported [PracticeStreak] (issue #20) and stands in for the iOS
 * app's SessionHistory persistence — kept deliberately lightweight (aggregates
 * + a recent list), not the full per-character ICR chart yet.
 */
object Stats {
    private lateinit var prefs: SharedPreferences
    private var streak = PracticeStreak()

    var currentStreak by mutableStateOf(0); private set
    var longestStreak by mutableStateOf(0); private set
    var totalSessions by mutableStateOf(0); private set
    var totalAttempts by mutableStateOf(0); private set
    var totalCorrect by mutableStateOf(0); private set
    var totalPracticeSeconds by mutableStateOf(0); private set
    var bestTtrMs by mutableStateOf<Int?>(null); private set
    var recent by mutableStateOf<List<SessionSummary>>(emptyList()); private set
    /** Lifetime per-character recognition data, keyed by the single character. */
    var charStats by mutableStateOf<Map<String, CharAgg>>(emptyMap()); private set
    /**
     * Full per-session detail records (timestamp, duration, speeds, and the
     * per-character results behind the session recognition chart), newest
     * first, bounded by [SessionHistory.limit]. The iOS SessionHistory twin.
     */
    var history by mutableStateOf<List<SessionRecord>>(emptyList()); private set

    val overallAccuracy: Double get() = if (totalAttempts == 0) 0.0 else totalCorrect.toDouble() / totalAttempts

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_stats", Context.MODE_PRIVATE)
        totalSessions = prefs.getInt("sessions", 0)
        totalAttempts = prefs.getInt("attempts", 0)
        totalCorrect = prefs.getInt("correct", 0)
        totalPracticeSeconds = prefs.getInt("practiceSecs", 0)
        bestTtrMs = prefs.getInt("bestTtr", -1).takeIf { it >= 0 }

        val sc = prefs.getInt("streakCurrent", 0)
        val sl = prefs.getInt("streakLongest", 0)
        val sd = prefs.getLong("streakDay", -1L).takeIf { it >= 0 }?.let { LocalDate.ofEpochDay(it) }
        streak = PracticeStreak(current = sc, longest = sl, lastPracticeDay = sd)
        refreshStreak()

        recent = parseRecent(prefs.getString("recent", "[]") ?: "[]")
        charStats = parseChars(prefs.getString("chars", "{}") ?: "{}")
        history = parseHistory(prefs.getString("history", "[]") ?: "[]")
    }

    /** The full detail record behind a session-list row, if it still exists. */
    fun sessionRecord(id: String?): SessionRecord? =
        id?.let { wanted -> history.firstOrNull { it.id.toString() == wanted } }

    /**
     * Record one single-character answer toward the recognition chart. Correct
     * recognition times feed the per-character median; a small rolling window
     * keeps it responsive and the storage bounded.
     */
    fun recordChar(character: String, correct: Boolean, ttrMs: Int?) {
        val cur = charStats[character] ?: CharAgg(0, 0, emptyList())
        val ttrs = if (correct && ttrMs != null && ttrMs > 0) (cur.ttrsMs + ttrMs).takeLast(12) else cur.ttrsMs
        charStats = charStats + (character to CharAgg(cur.attempts + 1, cur.correct + if (correct) 1 else 0, ttrs))
        prefs.edit().putString("chars", encodeChars(charStats)).apply()
    }

    /**
     * Record a just-finished session and persist. No-op for empty sessions.
     *
     * @return the streak day when this record was the day's first practice AND
     *   it landed exactly on a celebrated milestone (3, 7, 14, …) — so the
     *   session summary can fire a one-time celebration — otherwise null.
     */
    fun record(
        mode: String,
        attempts: Int,
        correct: Int,
        bestTtrMs: Int?,
        durationSeconds: Int = 0,
        characterWpm: Int = 0,
        medianTtrMs: Int? = null,
        effectiveWpm: Int = 0,
        charResults: List<SessionRecord.CharResult> = emptyList(),
        activeCharacters: List<String> = emptyList(),
        today: LocalDate = LocalDate.now()
    ): Int? {
        if (attempts <= 0) return null
        val firstToday = streak.record(today)
        refreshStreak()

        totalSessions += 1
        totalAttempts += attempts
        totalCorrect += correct
        if (durationSeconds > 0) totalPracticeSeconds += durationSeconds
        if (bestTtrMs != null && (this.bestTtrMs == null || bestTtrMs < this.bestTtrMs!!)) {
            this.bestTtrMs = bestTtrMs
        }
        // The full detail record (per-session screen); the summary row carries
        // its id so the sessions list can open it.
        val record = SessionRecord(
            id = UUID.randomUUID(),
            date = Instant.now(),
            mode = mode,
            characterWPM = characterWpm,
            effectiveWPM = effectiveWpm,
            attempts = attempts,
            correct = correct,
            fastestTTR = bestTtrMs?.let { it / 1000.0 },
            medianTTR = medianTtrMs?.let { it / 1000.0 },
            durationSeconds = durationSeconds.takeIf { it > 0 }?.toDouble(),
            characters = charResults,
            activeCharacters = activeCharacters
        )
        history = (listOf(record) + history).take(SessionHistory.limit)
        recent = (listOf(
            SessionSummary(
                mode, today.toEpochDay(), attempts, correct, bestTtrMs, characterWpm,
                medianTtrMs, record.id.toString()
            )
        ) + recent).take(50)
        persist()
        return if (firstToday && PracticeStreak.isMilestone(streak.current)) streak.current else null
    }

    /** Wipe all recorded progress (streak, totals, sessions, per-character data). */
    fun reset() {
        streak = PracticeStreak()
        currentStreak = 0
        longestStreak = 0
        totalSessions = 0
        totalAttempts = 0
        totalCorrect = 0
        totalPracticeSeconds = 0
        bestTtrMs = null
        recent = emptyList()
        charStats = emptyMap()
        history = emptyList()
        prefs.edit().clear().apply()
    }

    private fun refreshStreak() {
        currentStreak = streak.display()
        longestStreak = streak.longest
    }

    private fun persist() {
        prefs.edit()
            .putInt("sessions", totalSessions)
            .putInt("attempts", totalAttempts)
            .putInt("correct", totalCorrect)
            .putInt("practiceSecs", totalPracticeSeconds)
            .putInt("bestTtr", bestTtrMs ?: -1)
            .putInt("streakCurrent", streak.current)
            .putInt("streakLongest", streak.longest)
            .putLong("streakDay", streak.lastPracticeDay?.toEpochDay() ?: -1L)
            .putString("recent", encodeRecent(recent))
            .putString("history", encodeHistory(history))
            .apply()
    }

    private fun encodeRecent(list: List<SessionSummary>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("mode", s.mode)
                    .put("day", s.epochDay)
                    .put("att", s.attempts)
                    .put("cor", s.correct)
                    .put("ttr", s.bestTtrMs ?: -1)
                    .put("wpm", s.characterWpm)
                    .put("med", s.medianTtrMs ?: -1)
                    .put("id", s.recordId ?: "")
            )
        }
        return arr.toString()
    }

    /**
     * Parse the recent-sessions list, surviving anything.
     *
     * This threw straight out of [init], which runs from `MainActivity.onCreate`,
     * so a single malformed value in prefs was an unrecoverable launch crash —
     * the app died on every start with no way back but clearing its data. The
     * iOS twin never had this: it decodes with `try? JSONDecoder()`, which
     * yields nil rather than throwing.
     *
     * Two layers on purpose. The outer guard catches a wrecked document; the
     * inner one drops a single bad row and keeps the rest, because losing one
     * session is much better than losing a year of them. [parseChars] and
     * [parseHistory] do the same, so all three behave alike.
     */
    internal fun parseRecent(json: String): List<SessionSummary> = run {
        val out = ArrayList<SessionSummary>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            runCatching {
            val o = arr.getJSONObject(i)
            out.add(
                SessionSummary(
                    mode = o.getString("mode"),
                    epochDay = o.getLong("day"),
                    attempts = o.getInt("att"),
                    correct = o.getInt("cor"),
                    bestTtrMs = o.getInt("ttr").takeIf { it >= 0 },
                    // Absent on records saved before speed-band stats existed.
                    characterWpm = o.optInt("wpm", 0),
                    medianTtrMs = o.optInt("med", -1).takeIf { it >= 0 },
                    recordId = o.optString("id", "").takeIf { it.isNotEmpty() }
                )
            )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun encodeHistory(list: List<SessionRecord>): String {
        val arr = JSONArray()
        for (r in list) {
            val chars = JSONArray()
            for (c in r.characters) {
                chars.put(
                    JSONObject()
                        .put("c", c.character)
                        .put("att", c.attempts)
                        .put("cor", c.correct)
                        .put("med", c.medianMS ?: -1)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", r.id.toString())
                    .put("ts", r.date.toEpochMilli())
                    .put("mode", r.mode)
                    .put("cw", r.characterWPM)
                    .put("ew", r.effectiveWPM)
                    .put("att", r.attempts)
                    .put("cor", r.correct)
                    .put("fast", r.fastestTTR?.let { (it * 1000).roundToInt() } ?: -1)
                    .put("med", r.medianTTR?.let { (it * 1000).roundToInt() } ?: -1)
                    .put("dur", r.durationSeconds?.roundToInt() ?: -1)
                    .put("chars", chars)
                    .put("active", r.activeCharacters.joinToString(""))
            )
        }
        return arr.toString()
    }

    /** Full session records, guarded the same way as [parseRecent]. */
    internal fun parseHistory(json: String): List<SessionRecord> = runCatching {
        val out = ArrayList<SessionRecord>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            runCatching {
            val o = arr.getJSONObject(i)
            val charsArr = o.optJSONArray("chars") ?: JSONArray()
            val chars = ArrayList<SessionRecord.CharResult>(charsArr.length())
            for (j in 0 until charsArr.length()) {
                val c = charsArr.getJSONObject(j)
                chars.add(
                    SessionRecord.CharResult(
                        character = c.getString("c"),
                        attempts = c.getInt("att"),
                        correct = c.getInt("cor"),
                        medianTTR = c.optInt("med", -1).takeIf { it >= 0 }?.let { it / 1000.0 }
                    )
                )
            }
            out.add(
                SessionRecord(
                    id = runCatching { UUID.fromString(o.getString("id")) }.getOrDefault(UUID.randomUUID()),
                    date = Instant.ofEpochMilli(o.getLong("ts")),
                    mode = o.getString("mode"),
                    characterWPM = o.optInt("cw", 0),
                    effectiveWPM = o.optInt("ew", 0),
                    attempts = o.getInt("att"),
                    correct = o.getInt("cor"),
                    fastestTTR = o.optInt("fast", -1).takeIf { it >= 0 }?.let { it / 1000.0 },
                    medianTTR = o.optInt("med", -1).takeIf { it >= 0 }?.let { it / 1000.0 },
                    durationSeconds = o.optInt("dur", -1).takeIf { it >= 0 }?.toDouble(),
                    characters = chars,
                    activeCharacters = o.optString("active", "").map { it.toString() }
                )
            )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun encodeChars(map: Map<String, CharAgg>): String {
        val obj = JSONObject()
        for ((ch, agg) in map) {
            obj.put(
                ch,
                JSONObject()
                    .put("att", agg.attempts)
                    .put("cor", agg.correct)
                    .put("ttrs", JSONArray(agg.ttrsMs))
            )
        }
        return obj.toString()
    }

    /** Lifetime per-character stats, guarded the same way as [parseRecent]. */
    internal fun parseChars(json: String): Map<String, CharAgg> = runCatching {
        val out = LinkedHashMap<String, CharAgg>()
        val obj = JSONObject(json)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val ch = keys.next()
            runCatching {
                val o = obj.getJSONObject(ch)
                val arr = o.getJSONArray("ttrs")
                val ttrs = ArrayList<Int>(arr.length())
                for (i in 0 until arr.length()) ttrs.add(arr.getInt(i))
                out[ch] = CharAgg(o.getInt("att"), o.getInt("cor"), ttrs)
            }
        }
        out
    }.getOrDefault(emptyMap())
}
