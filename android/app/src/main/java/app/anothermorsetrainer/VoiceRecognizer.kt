package app.anothermorsetrainer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Lets the learner speak their answer instead of tapping it, ported from the
 * iOS VoiceRecognizer (SFSpeechRecognizer → Android [SpeechRecognizer]).
 *
 * One-shot: [start] listens for a single phrase, biased toward the drill's
 * answer options, and hands the recognised candidate strings back on the main
 * thread. SpeechRecognizer must be created and driven on the main thread, so
 * call these from Compose callbacks.
 */
class VoiceRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Listen for one spoken answer. [hints] (the current options) bias
     * recognition on Android 13+. [onResult] gets the ranked candidate strings;
     * [onError] fires on failure/timeout. Exactly one of the two is called.
     */
    fun start(hints: List<String>, onResult: (List<String>) -> Unit, onError: () -> Unit) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        var delivered = false

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                if (delivered) return
                delivered = true
                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                onResult(list)
            }
            override fun onError(error: Int) {
                if (delivered) return
                delivered = true
                onError()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 6)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hints.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
            }
        }
        r.startListening(intent)
    }

    fun cancel() {
        recognizer?.cancel()
    }

    /** Free the recognizer. Call from the owner's onDispose/onDestroy. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}
