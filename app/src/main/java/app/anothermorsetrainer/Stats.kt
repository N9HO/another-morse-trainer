package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.PracticeStreak
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

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
    val medianTtrMs: Int? = null
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
    }

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

    /** Record a just-finished session and persist. No-op for empty sessions. */
    fun record(
        mode: String,
        attempts: Int,
        correct: Int,
        bestTtrMs: Int?,
        durationSeconds: Int = 0,
        characterWpm: Int = 0,
        medianTtrMs: Int? = null,
        today: LocalDate = LocalDate.now()
    ) {
        if (attempts <= 0) return
        streak.record(today)
        refreshStreak()

        totalSessions += 1
        totalAttempts += attempts
        totalCorrect += correct
        if (durationSeconds > 0) totalPracticeSeconds += durationSeconds
        if (bestTtrMs != null && (this.bestTtrMs == null || bestTtrMs < this.bestTtrMs!!)) {
            this.bestTtrMs = bestTtrMs
        }
        recent = (listOf(
            SessionSummary(mode, today.toEpochDay(), attempts, correct, bestTtrMs, characterWpm, medianTtrMs)
        ) + recent).take(50)
        persist()
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
            )
        }
        return arr.toString()
    }

    private fun parseRecent(json: String): List<SessionSummary> {
        val out = ArrayList<SessionSummary>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
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
                    medianTtrMs = o.optInt("med", -1).takeIf { it >= 0 }
                )
            )
        }
        return out
    }

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

    private fun parseChars(json: String): Map<String, CharAgg> {
        val out = LinkedHashMap<String, CharAgg>()
        val obj = JSONObject(json)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val ch = keys.next()
            val o = obj.getJSONObject(ch)
            val arr = o.getJSONArray("ttrs")
            val ttrs = ArrayList<Int>(arr.length())
            for (i in 0 until arr.length()) ttrs.add(arr.getInt(i))
            out[ch] = CharAgg(o.getInt("att"), o.getInt("cor"), ttrs)
        }
        return out
    }
}
