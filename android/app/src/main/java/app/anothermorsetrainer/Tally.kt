package app.anothermorsetrainer

import androidx.compose.runtime.saveable.Saver
import app.anothermorsetrainer.morsekit.SessionRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mutable per-session tally, accumulated as the learner answers.
 *
 * It also survives process death: every screen that owns one keeps it under
 * `rememberSaveable` with [TallySaver], so a session Android reclaims in the
 * background comes back with its count, its times and its start intact, and
 * `Stats.record` on the way out sees the whole session rather than nothing.
 * The codec is JSON in a string because the saved-instance-state bundle wants
 * primitives, and because a string is what a unit test can round-trip.
 */
internal class Tally(
    /** Wall-clock when this session began. A restored tally keeps its original start. */
    val startedAtMs: Long = System.currentTimeMillis()
) {
    var attempts = 0
    var correct = 0
    var bestMs: Int? = null
    /** Correct recognition times this session — the median feeds speed-band stats. */
    private val ttrsMs = mutableListOf<Int>()
    /** Whole seconds of practice elapsed since the session began. */
    fun elapsedSeconds(): Int = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()

    /** Note one correct answer's recognition time (and keep the fastest). */
    fun noteCorrectMs(ms: Int) {
        if (ms <= 0) return
        ttrsMs.add(ms)
        if (bestMs == null || ms < bestMs!!) bestMs = ms
    }

    /** Median correct recognition time this session, or null if none recorded. */
    fun medianMs(): Int? {
        if (ttrsMs.isEmpty()) return null
        val s = ttrsMs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Per-character outcomes this session (single-character drills only). */
    private val charOutcomes = LinkedHashMap<String, MutableList<Pair<Boolean, Int>>>()

    /** Note one single-character answer for the session's recognition chart. */
    fun noteChar(character: String, correct: Boolean, ms: Int) {
        charOutcomes.getOrPut(character) { mutableListOf() }.add(correct to ms)
    }

    /** This session's per-character results, for the detail record. */
    fun charResults(): List<SessionRecord.CharResult> = charOutcomes.map { (ch, results) ->
        val times = results.filter { it.first && it.second > 0 }.map { it.second }.sorted()
        val medianMs = when {
            times.isEmpty() -> null
            times.size % 2 == 1 -> times[times.size / 2]
            else -> (times[times.size / 2 - 1] + times[times.size / 2]) / 2
        }
        SessionRecord.CharResult(
            character = ch,
            attempts = results.size,
            correct = results.count { it.first },
            medianTTR = medianMs?.let { it / 1000.0 }
        )
    }

    // ---- Surviving process death ----

    /** The whole tally as JSON, for the saved-instance-state bundle. */
    fun encode(): String {
        val chars = JSONObject()
        for ((ch, results) in charOutcomes) {
            val arr = JSONArray()
            for ((ok, ms) in results) arr.put(JSONArray().put(if (ok) 1 else 0).put(ms))
            chars.put(ch, arr)
        }
        return JSONObject()
            .put("attempts", attempts)
            .put("correct", correct)
            .put("bestMs", bestMs ?: -1)
            .put("startedAtMs", startedAtMs)
            .put("ttrsMs", JSONArray(ttrsMs))
            .put("chars", chars)
            .toString()
    }

    companion object {
        /**
         * The inverse of [encode]. Null on anything unreadable, which
         * `rememberSaveable` takes as "could not restore" and answers with a
         * fresh tally — a stale bundle must never crash the launch.
         */
        fun decode(json: String): Tally? = runCatching {
            val obj = JSONObject(json)
            val tally = Tally(startedAtMs = obj.getLong("startedAtMs"))
            tally.attempts = obj.getInt("attempts")
            tally.correct = obj.getInt("correct")
            tally.bestMs = obj.getInt("bestMs").takeIf { it >= 0 }
            val ttrs = obj.getJSONArray("ttrsMs")
            for (i in 0 until ttrs.length()) tally.ttrsMs.add(ttrs.getInt(i))
            val chars = obj.getJSONObject("chars")
            for (ch in chars.keys()) {
                val arr = chars.getJSONArray(ch)
                val results = tally.charOutcomes.getOrPut(ch) { mutableListOf() }
                for (i in 0 until arr.length()) {
                    val pair = arr.getJSONArray(i)
                    results.add((pair.getInt(0) == 1) to pair.getInt(1))
                }
            }
            tally
        }.getOrNull()
    }
}

/** [Tally] in the saved-instance-state bundle; see the class comment. */
internal val TallySaver: Saver<Tally, String> = Saver(
    save = { it.encode() },
    restore = { Tally.decode(it) }
)

/**
 * A nullable session length for the bundle. `null` means "no limit" and is
 * saved as -1, because a null in a saved state is indistinguishable from a
 * state that was never saved.
 */
internal val OptionalIntSaver: Saver<Int?, Int> = Saver(
    save = { it ?: -1 },
    restore = { if (it < 0) null else it }
)
