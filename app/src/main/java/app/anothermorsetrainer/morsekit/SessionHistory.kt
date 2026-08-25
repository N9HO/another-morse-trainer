package app.anothermorsetrainer.morsekit

import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * A completed practice session, kept so the learner can revisit how it went —
 * in particular the per-character "Instant Character Recognition" chart (#19).
 *
 * Per-character data is only meaningful for single-character recognition drills,
 * so [characters] / [activeCharacters] are empty for word, QSO, story, and exam
 * sessions; those records still carry the aggregate stats.
 *
 * Translated from MorseKit/SessionHistory.swift. Swift `struct` + `Equatable`
 * holder → Kotlin `data class`; Swift `UUID` → [java.util.UUID]; Swift `Date`
 * timestamp → [java.time.Instant]; `TimeInterval` (elapsed seconds) → `Double`.
 * The `Identifiable` conformances drop away (Kotlin has no such protocol); the
 * `id` computed properties are kept as plain getters. Swift `Codable` would map
 * to kotlinx.serialization; here the fields are plain data-class properties.
 */
data class SessionRecord(
    val id: UUID,
    val date: Instant,
    val mode: String,                 // TrainingMode rawValue
    val characterWPM: Int,
    val effectiveWPM: Int,
    val attempts: Int,
    val correct: Int,
    val fastestTTR: Double?,
    val medianTTR: Double?,
    val durationSeconds: Double?,
    /** Per-character results for characters actually drilled this session. */
    val characters: List<CharResult>,
    /**
     * The full single-character set active during the session, so the chart can
     * show a row per learned character (with a blank where one wasn't drilled),
     * matching the reference design.
     */
    val activeCharacters: List<String>
) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts.toDouble()

    /** Per-character recognition result within a single session. */
    data class CharResult(
        val character: String,        // a single character, e.g. "K"
        val attempts: Int,
        val correct: Int,
        /** Median time-to-recognize over *correct* answers; null if never correct. */
        val medianTTR: Double?
    ) {
        val id: String get() = character
        val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts.toDouble()

        /** Median recognition time in whole milliseconds (null if never correct). */
        val medianMS: Int? get() = medianTTR?.let { (it * 1000).roundToInt() }
    }

    /**
     * One row of the recognition chart: a learned character and its result this
     * session (null when it wasn't drilled).
     */
    data class ChartRow(
        val character: String,
        val result: CharResult?
    ) {
        val id: String get() = character
    }

    /**
     * Chart rows — every character active this session plus any drilled, ordered
     * letters-then-digits (matching the reference's A–Z then 0–9 layout). Empty
     * for sessions with no per-character data.
     */
    val chartRows: List<ChartRow>
        get() {
            val names = activeCharacters.filter { it.length == 1 }.toMutableSet()
            for (c in characters) names.add(c.character)
            // uniquingKeysWith { a, _ in a } — keep the first result for a character.
            val byChar = LinkedHashMap<String, CharResult>()
            for (c in characters) if (!byChar.containsKey(c.character)) byChar[c.character] = c
            return names.sortedWith(characterOrder).map { ChartRow(it, byChar[it]) }
        }

    companion object {
        /**
         * Order single characters letters-first (A–Z), then digits (0–9), so the
         * chart reads like the reference image.
         */
        val characterOrder: Comparator<String> = Comparator { lhs, rhs ->
            val lDigit = lhs.firstOrNull()?.isDigit() ?: false
            val rDigit = rhs.firstOrNull()?.isDigit() ?: false
            if (lDigit != rDigit) {
                // letters before digits
                if (lDigit) 1 else -1
            } else {
                lhs.compareTo(rhs)
            }
        }

        /**
         * Round a millisecond value up to a tidy axis ceiling: at least 1000ms,
         * and otherwise the next multiple of 250 so the gridlines stay even.
         */
        fun axisCeilingMS(ms: Int): Int {
            val floored = maxOf(1000, ms)
            return ((floored + 249) / 250) * 250
        }
    }
}

/**
 * A bounded, newest-first list of completed sessions, persisted between launches.
 *
 * Swift `struct` + `mutating func` → Kotlin `class` with an ordinary [add]
 * method; the backing list is mutable internally and exposed read-only.
 */
class SessionHistory(sessions: List<SessionRecord> = emptyList()) {
    private val _sessions: MutableList<SessionRecord> = sessions.toMutableList()
    val sessions: List<SessionRecord> get() = _sessions

    companion object {
        /** Keep history from growing without bound; oldest sessions age out. */
        const val limit = 100
    }

    /**
     * Add a freshly-completed session to the front, trimming the oldest beyond
     * the cap.
     */
    fun add(record: SessionRecord) {
        _sessions.add(0, record)
        if (_sessions.size > limit) {
            repeat(_sessions.size - limit) { _sessions.removeAt(_sessions.size - 1) }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SessionHistory && other._sessions == _sessions

    override fun hashCode(): Int = _sessions.hashCode()
}

// MARK: - Performance by speed band

/**
 * How you perform at a given character-speed range — accuracy and typical
 * reaction time across every session sent near that speed. This surfaces the
 * thing a recognition learner most wants to see: "I'm solid at 20 WPM but my
 * reaction time falls apart at 30." Aggregated from the persisted session
 * history, so it needs no extra storage. Mirrors cwsignals.com's per-WPM-band
 * mastery readout.
 *
 * Translated from the iOS WPMBandSummary (SessionHistory.swift); times are in
 * whole milliseconds to match the Android stats store.
 */
data class WPMBandSummary(
    /** Inclusive band floor, e.g. 20 for the 20–24 WPM band. */
    val bandLower: Int,
    /** Number of sessions that fell in this band. */
    val sessions: Int,
    /** Total answered questions across those sessions. */
    val attempts: Int,
    /** Total correct answers. */
    val correct: Int,
    /**
     * Attempt-weighted mean of the sessions' median recognition times, in ms
     * (null if no session in the band recorded one).
     */
    val medianMs: Int?
) {
    val bandUpper: Int get() = bandLower + 4
    val id: Int get() = bandLower
    val label: String get() = "$bandLower–$bandUpper"
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts.toDouble()
}

/**
 * Bucket per-session results into 5-WPM speed bands. The live Android session
 * store ([app.anothermorsetrainer.Stats]) is lighter than the iOS
 * SessionHistory, so the summaries take a minimal per-session [Entry] rather
 * than full [SessionRecord]s.
 */
object WPMBands {
    /** The 5-WPM-wide band floor a speed falls into (22 → 20). */
    fun bandLower(wpm: Int): Int = (maxOf(0, wpm) / 5) * 5

    /** One session's contribution: its character speed and aggregate results. */
    data class Entry(
        val wpm: Int,
        val attempts: Int,
        val correct: Int,
        val medianTtrMs: Int?
    )

    /**
     * Performance grouped into 5-WPM speed bands, slowest band first. Only
     * sessions that actually answered something — and recorded their speed —
     * are counted (older persisted sessions predate the speed field).
     */
    fun summarize(entries: List<Entry>): List<WPMBandSummary> {
        val answered = entries.filter { it.attempts > 0 && it.wpm > 0 }
        val grouped = answered.groupBy { bandLower(it.wpm) }
        return grouped.keys.sorted().map { lower ->
            val records = grouped[lower].orEmpty()
            val attempts = records.sumOf { it.attempts }
            val correct = records.sumOf { it.correct }
            // Attempt-weighted mean of the per-session medians we have.
            val timed = records.mapNotNull { r -> r.medianTtrMs?.let { it to r.attempts } }
            val weight = timed.sumOf { it.second }
            val median = if (weight == 0) null
                else (timed.sumOf { it.first.toDouble() * it.second } / weight).roundToInt()
            WPMBandSummary(
                bandLower = lower,
                sessions = records.size,
                attempts = attempts,
                correct = correct,
                medianMs = median
            )
        }
    }
}
