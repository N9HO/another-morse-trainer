package app.anothermorsetrainer.morsekit

/** What can happen to the Sending Analyzer's microphone. Raw codes are what the fixture uses. */
enum class MicEvent(val code: String) {
    /** Permission held and the capture opened. */
    START_GRANTED("startGranted"),
    /** The system refused the microphone. */
    START_DENIED("startDenied"),
    /** Permission held but the capture could not be opened. */
    START_FAILED("startFailed"),
    /** A call or another app took the audio (Android: focus lost). */
    INTERRUPTION_BEGAN("interruptionBegan"),
    /** The input device went away or the capture died. */
    ROUTE_LOST("routeLost"),
    MEDIA_SERVICES_RESET("mediaServicesReset"),
    /** The interruption is over. The microphone does not resume by itself. */
    INTERRUPTION_ENDED("interruptionEnded"),
    /** The screen left, or the input was switched to the key. */
    STOP("stop");

    companion object {
        fun fromCode(code: String): MicEvent? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The microphone input's two flags and how [MicEvent]s move them, as the iOS
 * `SendingMicInput` does (row-22/24 of the parity audit). Pure; the capture
 * classes keep their Compose state in step with [after]. Pinned by
 * `fixtures/gap-analyzer-mic-state.json`. Twin of Swift `MicInputState`.
 */
data class MicInputState(val isListening: Boolean = false, val micDenied: Boolean = false) {

    fun after(event: MicEvent): MicInputState = when (event) {
        MicEvent.START_GRANTED -> if (isListening) this else MicInputState(isListening = true, micDenied = false)
        MicEvent.START_DENIED -> if (isListening) this else copy(micDenied = true)
        MicEvent.START_FAILED -> if (isListening) this else MicInputState(isListening = false, micDenied = false)
        MicEvent.INTERRUPTION_BEGAN, MicEvent.ROUTE_LOST, MicEvent.MEDIA_SERVICES_RESET ->
            if (isListening) copy(isListening = false) else this
        MicEvent.INTERRUPTION_ENDED -> this
        MicEvent.STOP -> copy(isListening = false)
    }
}
