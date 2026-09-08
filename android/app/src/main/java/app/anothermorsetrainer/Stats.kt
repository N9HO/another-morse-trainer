package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.ActivityLedger
import app.anothermorsetrainer.morsekit.PracticeStreak
import app.anothermorsetrainer.morsekit.SessionHistory
import app.anothermorsetrainer.morsekit.ModeBests
import app.anothermorsetrainer.morsekit.SessionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    val recordId: String? = null,
    /** The mode's own score, as [SessionRecord.score]; null where the mode has none or the row predates it. */
    val score: Int? = null
) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts

    /**
     * Whether the session graded any answers — false for Listen & Learn and
     * Stories, whose [attempts] are items heard (#183). Same rule as
     * [SessionRecord.isScored].
     */
    val isScored: Boolean get() = SessionRecord.isScoredMode(mode) && attempts > 0
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

    var currentStreak by mutableIntStateOf(0); private set
    var longestStreak by mutableIntStateOf(0); private set
    var totalSessions by mutableIntStateOf(0); private set
    var totalAttempts by mutableIntStateOf(0); private set
    var totalCorrect by mutableIntStateOf(0); private set
    var totalPracticeSeconds by mutableIntStateOf(0); private set
    var bestTtrMs by mutableStateOf<Int?>(null); private set
    /**
     * Best [SessionRecord.score] per mode string, for the modes that have one
     * (docs/high-scores-design.md, step 1). Kept like [bestTtrMs], not derived
     * from [recent], so it outlives the capped lists. Rules in
     * `fixtures/mode-bests.json`, applied by [ModeBests]. The iOS twin is
     * `SessionHistory.bestScores`.
     */
    var bestScores by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var recent by mutableStateOf<List<SessionSummary>>(emptyList()); private set
    /** Lifetime per-character recognition data, keyed by the single character. */
    var charStats by mutableStateOf<Map<String, CharAgg>>(emptyMap()); private set
    /**
     * Full per-session detail records (timestamp, duration, speeds, and the
     * per-character results behind the session recognition chart), newest
     * first, bounded by [SessionHistory.limit]. The iOS SessionHistory twin.
     */
    var history by mutableStateOf<List<SessionRecord>>(emptyList()); private set
    /**
     * Seconds practiced per local calendar day, for the Stats screen's
     * activity grid (#181). Outlives [history]'s cap: it keeps
     * [ActivityLedger.CAP_DAYS] days. Replaced, not mutated, on each record
     * so Compose readers see the change.
     */
    var activity by mutableStateOf(ActivityLedger()); private set

    /** Lifetime accuracy over every *scored* answer; passive sessions are left out (#183). */
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
        // The ledger arrived after the history: on the first launch that has
        // none, seed it from the sessions still in the list so the grid is not
        // blank for someone with months of practice behind them. Saved at
        // once, so a reset (which clears prefs) is not re-seeded from nothing.
        val storedActivity = prefs.getString("activity", null)
        activity = if (storedActivity != null) parseActivity(storedActivity) else seedActivity(history)
        if (storedActivity == null) prefs.edit { putString("activity", encodeActivity(activity)) }
        // Same first-launch seed for the per-mode bests: the records still in
        // the list carry no scores from before the field existed, so the seed
        // is empty for an old install and only fills as scored runs are played.
        val storedBests = prefs.getString("bestScores", null)
        bestScores = if (storedBests != null) parseBestScores(storedBests) else ModeBests.seed(history)
        if (storedBests == null) prefs.edit { putString("bestScores", encodeBestScores(bestScores)) }
    }

    /** A ledger rebuilt from the sessions still in [history]: their local day and logged duration. */
    internal fun seedActivity(sessions: List<SessionRecord>, zone: ZoneId = ZoneId.systemDefault()): ActivityLedger {
        val ledger = ActivityLedger()
        for (r in sessions) {
            ledger.record(r.date.atZone(zone).toLocalDate(), r.durationSeconds?.roundToInt() ?: 0)
        }
        return ledger
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
        prefs.edit { putString("chars", encodeChars(charStats)) }
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
        score: Int? = null,
        today: LocalDate = LocalDate.now()
    ): Int? {
        if (attempts <= 0) return null
        val firstToday = streak.record(today)
        refreshStreak()

        totalSessions += 1
        // A passive session's attempts are items heard, not answers: they
        // count as a session and as practice time, never toward accuracy (#183).
        if (SessionRecord.isScoredMode(mode)) {
            totalAttempts += attempts
            totalCorrect += correct
        }
        if (durationSeconds > 0) totalPracticeSeconds += durationSeconds
        activity = ActivityLedger(activity.days).also { it.record(today, durationSeconds) }
        if (bestTtrMs != null && (this.bestTtrMs == null || bestTtrMs < this.bestTtrMs!!)) {
            this.bestTtrMs = bestTtrMs
        }
        bestScores = ModeBests.fold(bestScores, mode, score)
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
            activeCharacters = activeCharacters,
            score = score
        )
        history = (listOf(record) + history).take(SessionHistory.limit)
        recent = (listOf(
            SessionSummary(
                mode, today.toEpochDay(), attempts, correct, bestTtrMs, characterWpm,
                medianTtrMs, record.id.toString(), score
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
        bestScores = emptyMap()
        recent = emptyList()
        charStats = emptyMap()
        history = emptyList()
        activity = ActivityLedger()
        prefs.edit {
            clear()
            // An absent "activity" key means "seed from history" at the next
            // launch; write the empty ledger so a wipe stays a wipe. Likewise
            // the bests map.
            putString("activity", encodeActivity(activity))
            putString("bestScores", encodeBestScores(bestScores))
        }
    }

    /**
     * Count today toward the practice streak without logging a session (#155).
     *
     * Daily Dit is practice — a guess on the day's puzzle is showing up — but
     * it is one puzzle, not a session. Pushing a synthetic session through
     * [record] to move the streak would put a row in the history list and skew
     * every lifetime average the stats screen draws from, so the streak moves
     * on its own here. Idempotent within a day, so it is cheap to call on every
     * guess.
     */
    fun recordPracticeDay(today: LocalDate = LocalDate.now()) {
        if (!streak.record(today)) return   // already counted today
        refreshStreak()
        persist()
    }

    private fun refreshStreak() {
        currentStreak = streak.display()
        longestStreak = streak.longest
    }

    private fun persist() {
        prefs.edit {
            putInt("sessions", totalSessions)
            putInt("attempts", totalAttempts)
            putInt("correct", totalCorrect)
            putInt("practiceSecs", totalPracticeSeconds)
            putInt("bestTtr", bestTtrMs ?: -1)
            putInt("streakCurrent", streak.current)
            putInt("streakLongest", streak.longest)
            putLong("streakDay", streak.lastPracticeDay?.toEpochDay() ?: -1L)
            putString("recent", encodeRecent(recent))
            putString("history", encodeHistory(history))
            putString("activity", encodeActivity(activity))
            putString("bestScores", encodeBestScores(bestScores))
        }
    }

    /** Mode string → best score, a flat JSON object. */
    internal fun encodeBestScores(bests: Map<String, Int>): String {
        val o = JSONObject()
        for ((mode, score) in bests) o.put(mode, score)
        return o.toString()
    }

    /** Guarded like [parseRecent]: a wrecked document is an empty map, a bad entry is skipped. */
    internal fun parseBestScores(json: String): Map<String, Int> = runCatching {
        val o = JSONObject(json)
        val out = LinkedHashMap<String, Int>()
        for (key in o.keys()) {
            runCatching { out[key] = o.getInt(key) }
        }
        out
    }.getOrDefault(emptyMap())

    /** The ledger as a JSON object of ISO day → whole seconds. */
    private fun encodeActivity(ledger: ActivityLedger): String {
        val obj = JSONObject()
        for ((day, secs) in ledger.days) obj.put(day.toString(), secs)
        return obj.toString()
    }

    /** The activity ledger, guarded the same way as [parseRecent]: one bad day is dropped, the rest kept. */
    internal fun parseActivity(json: String): ActivityLedger = runCatching {
        val ledger = ActivityLedger()
        val obj = JSONObject(json)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            runCatching { ledger.record(LocalDate.parse(key), obj.getInt(key)) }
        }
        ledger
    }.getOrDefault(ActivityLedger())

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
                    // Scores are never negative, so -1 is "none", as for ttr/med.
                    .put("score", s.score ?: -1)
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
    internal fun parseRecent(json: String): List<SessionSummary> = runCatching {
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
                    recordId = o.optString("id", "").takeIf { it.isNotEmpty() },
                    // Absent on rows saved before per-mode bests existed.
                    score = o.optInt("score", -1).takeIf { it >= 0 }
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
                    .put("score", r.score ?: -1)
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
                    activeCharacters = o.optString("active", "").map { it.toString() },
                    score = o.optInt("score", -1).takeIf { it >= 0 }
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
