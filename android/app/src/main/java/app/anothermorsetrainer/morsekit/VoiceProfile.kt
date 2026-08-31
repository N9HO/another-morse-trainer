package app.anothermorsetrainer.morsekit

/**
 * A lightweight, per-user "voice profile" that learns how an individual tends
 * to be transcribed when answering by voice.
 *
 * Apple's Speech framework does not expose true per-user acoustic-model
 * training, so this is a deliberately simple, transparent, fully-offline
 * alternative: every time the learner confirms (via "Did you say X?") or
 * corrects (by picking the right answer) what they said, we remember that this
 * transcript maps to that answer. Over time [VoiceMatcher] can short-circuit
 * straight to the answer the user reliably means — personalization without any
 * model training.
 *
 * Translated from MorseKit/VoiceProfile.swift. The Swift struct was `Codable`
 * so the app could persist it in UserDefaults; here we keep a plain holder.
 * Real persistence on Android would serialize [corrections] via
 * kotlinx.serialization (no serialization plugin is added in this port).
 *
 * Mutable like the Swift `mutating` struct: [record] updates [corrections] in
 * place.
 */
class VoiceProfile(
    /** normalized transcript → (answer token → times confirmed) */
    corrections: Map<String, Map<String, Int>> = emptyMap()
) {

    // Internal mutable copy so [record] can update tallies in place.
    private val corrections: MutableMap<String, MutableMap<String, Int>> =
        corrections.mapValuesTo(mutableMapOf()) { (_, tally) -> tally.toMutableMap() }

    /** Record that [heard] was confirmed to mean [answer]. */
    fun record(heard: String, answer: String) {
        val key = VoiceMatcher.normalize(heard)
        if (key.isEmpty() || answer.isEmpty()) return
        val tally = corrections.getOrPut(key) { mutableMapOf() }
        tally[answer] = (tally[answer] ?: 0) + 1
    }

    /**
     * The answer this user most often means by [heard], if any has been
     * confirmed at least once. Ties break deterministically by token.
     */
    fun suggestion(heard: String): String? {
        val key = VoiceMatcher.normalize(heard)
        val tally = corrections[key]
        if (tally == null || tally.isEmpty()) return null
        // Swift `max { a, b in a.value != b.value ? a.value < b.value : a.key > b.key }`
        // picks the highest count, breaking ties toward the lexicographically
        // smallest token.
        return tally.entries.maxWithOrNull { a, b ->
            if (a.value != b.value) a.value.compareTo(b.value) else b.key.compareTo(a.key)
        }?.key
    }

    val isEmpty: Boolean get() = corrections.isEmpty()

    /** Snapshot of the learned corrections (e.g. for persistence). */
    fun snapshot(): Map<String, Map<String, Int>> =
        corrections.mapValues { (_, tally) -> tally.toMap() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceProfile) return false
        return snapshot() == other.snapshot()
    }

    override fun hashCode(): Int = snapshot().hashCode()
}
