package app.anothermorsetrainer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Speaks the English answer aloud for the hands-free "Listen & Learn" mode.
 *
 * Ported from the iOS MorseTrainerApp/SpeechPlayer.swift: wraps the platform
 * speech synthesiser (Android [TextToSpeech]) and reports completion on the
 * main thread so the listen loop can chain play-Morse → pause → speak → next.
 * Like the iOS version it does not touch audio focus — [MorsePlayer] owns the
 * tone output.
 */
class SpeechPlayer(context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile var isReady: Boolean = false
        private set

    /** Invoked (on the main thread) when the engine finishes initialising. */
    var onReady: (() -> Unit)? = null

    private var completion: (() -> Unit)? = null
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            bestEnglishVoice()?.let { tts.voice = it }
            isReady = true
            main.post { onReady?.invoke() }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
            override fun onError(utteranceId: String?, errorCode: Int) = finish()

            private fun finish() {
                main.post {
                    val done = completion
                    completion = null
                    done?.invoke()
                }
            }
        })
    }

    /** Speak [text] and call [onDone] (on the main thread) when finished. */
    fun speak(text: String, onDone: () -> Unit) {
        val trimmed = text.trim()
        if (!isReady || trimmed.isEmpty()) { onDone(); return }
        completion = onDone
        val id = "amt-${counter++}"
        tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        completion = null
        if (isReady) tts.stop()
    }

    /** Free the engine. Call from the owner's onDispose/onDestroy. */
    fun release() {
        completion = null
        tts.stop()
        tts.shutdown()
    }

    /**
     * Pick the best installed English voice, mirroring the iOS scoring: prefer
     * higher-quality, en-US, on-device voices.
     */
    private fun bestEnglishVoice(): Voice? {
        val voices = try { tts.voices } catch (_: Exception) { null } ?: return null
        fun score(v: Voice): Int {
            var s = 0
            s += v.quality                                   // TextToSpeech quality constants ascend with fidelity
            if (v.locale.language == "en") s += 200
            if (v.locale.country == "US") s += 50
            if (!v.isNetworkConnectionRequired) s += 100     // on-device works offline / no latency
            return s
        }
        return voices.filter { it.locale.language == "en" }.maxByOrNull { score(it) }
    }
}
