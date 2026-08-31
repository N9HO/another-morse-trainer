package app.anothermorsetrainer.morsekit

/**
 * Performance history for a single character: how often it was answered
 * correctly and how long recognition took (time-to-recognize, "TTR").
 *
 * Translated from MorseKit/Stats.swift. Swift `struct` + `mutating func` →
 * Kotlin `class` with ordinary methods. The custom `Codable` (which encodes
 * `Character` as a `String`) would become a kotlinx.serialization adapter;
 * here the fields are plain and TTR is a `Double` of seconds.
 */
class CharacterStats(
    val character: Char,
    /** Most recent attempts, newest last. Bounded so old data ages out. */
    attempts: List<Attempt> = emptyList()
) {
    val attempts: MutableList<Attempt> = attempts.toMutableList()

    /** A single answered round: was it [correct], and how long ([ttr], seconds). */
    data class Attempt(val correct: Boolean, val ttr: Double)

    companion object {
        const val historyLimit = 20
    }

    fun record(correct: Boolean, ttr: Double) {
        attempts.add(Attempt(correct, ttr))
        if (attempts.size > historyLimit) {
            repeat(attempts.size - historyLimit) { attempts.removeAt(0) }
        }
    }

    /** Attempts from the most recent window used for mastery decisions. */
    fun recent(k: Int): List<Attempt> = attempts.takeLast(k)

    /** Median TTR over correct answers in the recent window (null if none). */
    fun medianTTR(window: Int = 5): Double? {
        val times = recent(window).filter { it.correct }.map { it.ttr }.sorted()
        if (times.isEmpty()) return null
        val mid = times.size / 2
        return if (times.size % 2 == 0) (times[mid - 1] + times[mid]) / 2 else times[mid]
    }

    fun accuracy(window: Int = 5): Double {
        val recent = recent(window)
        if (recent.isEmpty()) return 0.0
        return recent.count { it.correct }.toDouble() / recent.size
    }

    /**
     * A character is "mastered" when recent answers are reliably correct and
     * fast enough — this is the gate for introducing a new character.
     */
    fun isMastered(ttrThreshold: Double, window: Int = 5, requiredAccuracy: Double = 0.9): Boolean {
        if (recent(window).size < window) return false
        if (accuracy(window) < requiredAccuracy) return false
        val median = medianTTR(window) ?: return false
        return median <= ttrThreshold
    }
}
