package app.anothermorsetrainer.vail

/**
 * A record of sent/received activity (a keyed tone burst or a chat message) for
 * the timeline visualizer. Times are in local clock ms — the same clock used to
 * schedule RX audio. Translated from MorseTrainerApp/SignalEvent.swift.
 */
data class SignalEvent(
    val callsign: String,
    val startLocalMs: Long,
    val kind: Kind,
    val origin: Origin
) {
    sealed class Kind {
        /** A keyed tone burst; [durationMs] is how long the key was down. */
        data class Tone(val durationMs: Int, val midiNote: Int?) : Kind()
        /** A chat message — drawn as a point marker. */
        data class Chat(val text: String) : Kind()
    }

    enum class Origin { SENT, RECEIVED }

    /** End time (ms) for layout; chat events are zero-duration markers. */
    val endLocalMs: Long
        get() = when (val k = kind) {
            is Kind.Tone -> startLocalMs + k.durationMs
            is Kind.Chat -> startLocalMs
        }
}

/** One chat line in the repeater channel. */
data class ChatLine(val text: String, val callsign: String?, val timestampMs: Long)
