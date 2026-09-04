package app.anothermorsetrainer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The app's one audio-focus holder.
 *
 * Until this existed, nothing in the app ever called `requestAudioFocus` —
 * `grep` for it returned nothing — while [ListenService] ran as a
 * `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` service. The consequences were the
 * obvious ones: the trainer talked straight over whatever music was playing,
 * and it kept talking through a phone call, because an [android.media.AudioTrack]
 * with no focus request is simply another voice in the mixer. It also never
 * heard about anything: a call could take the speaker and the Listen loop would
 * carry on stepping through items nobody could hear.
 *
 * Reference-counted, because several things make sound and their lifetimes
 * overlap — a [MorsePlayer] per screen, [SidetoneGenerator] during sending
 * practice, [ListenService] in the background. Each holder [acquire]s and
 * [release]s; focus is requested on the first holder and abandoned after the
 * last, so navigating between two screens that both play does not drop and
 * re-take focus in between (which the system would show as a stutter in
 * whatever it made way for).
 *
 * **Deliberately not folded into [BackgroundNoise]**, which is the other
 * process-wide audio singleton and the obvious place to put this. The noise
 * floor runs the whole time the app is on screen — including the home screen and
 * the settings list — and taking exclusive focus to browse a menu would be
 * wrong. [BackgroundNoise] is a *listener* here, not a holder.
 *
 * **A note on the gain type, because it is a real difference from the Apple
 * side.** The iOS app configures `.duckOthers`, which turns other audio down
 * rather than off. Drills ask for [AudioManager.AUDIOFOCUS_GAIN] — others pause.
 * That is the Android idiom for a media-playback foreground service, and it is
 * also the better answer for what this app does: copying Morse under music is
 * measurably harder, so ducking would leave the reported problem half-fixed. The
 * two ports differ here on purpose.
 *
 * **The repeater is the one exception, and it is the same on both ports.** iOS
 * claims a `repeaterMix` session (`mixWithOthers` + `duckOthers`) for the Vail
 * repeater so a radio app can keep running alongside it; here the repeater's
 * sidetone acquires with [Gain.DUCK], which asks for
 * [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK] — other audio turns down,
 * not off. Only the repeater path passes it; every drill keeps the default.
 */
object AudioFocus {

    /** How much to take from other apps while a holder is making sound. */
    enum class Gain {
        /** Others pause — the drills. */
        EXCLUSIVE,
        /** Others turn down and carry on — the repeater, so a radio app survives. */
        DUCK,
    }

    /** What happened to focus. Delivered on the main thread. */
    enum class Event {
        /** Gone for good — another app took over. Stop, do not wait to resume. */
        LOST,
        /** Something short (a call, a navigation prompt). Pause and expect [REGAINED]. */
        LOST_TRANSIENT,
        /** Ours again after a [LOST_TRANSIENT]. */
        REGAINED,
    }

    /** Handle for an [observe] subscription. */
    class Observation internal constructor(internal val id: Int)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    /** Identity set: an owner acquiring twice still only counts once. */
    private val holders = mutableSetOf<Any>()
    private var held = false
    /** The gain the current request was made with; meaningless while not [held]. */
    private var heldGain = Gain.EXCLUSIVE
    /** API 26+ only; the pre-26 path abandons by listener instead. */
    private var request: AudioFocusRequest? = null
    private val listeners = mutableMapOf<Int, (Event) -> Unit>()
    private var nextListenerId = 0

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // The system has already taken it; there is nothing to abandon,
                // only bookkeeping to correct so a later acquire re-requests.
                synchronized(this) { held = false }
                broadcast(Event.LOST)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> broadcast(Event.LOST_TRANSIENT)
            AudioManager.AUDIOFOCUS_GAIN -> {
                synchronized(this) { held = true }
                broadcast(Event.REGAINED)
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK is handled for us: the request
            // below does not set willPauseWhenDucked, so the framework attenuates
            // our tracks at the mixer and restores them afterwards.
        }
    }

    /** Call once, from [MainActivity.onCreate], alongside the other stores. */
    fun init(context: Context) {
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Say that [owner] is about to make sound. Idempotent per owner. Returns
     * whether focus is currently held — callers may play regardless (a denied
     * request is not a reason to break practice), but it is worth logging.
     *
     * [gain] is what to ask of other apps; the default pauses them. A holder
     * asking for [Gain.EXCLUSIVE] while focus is held with [Gain.DUCK] upgrades
     * the request in place; the reverse never downgrades a drill mid-run.
     */
    @Synchronized
    fun acquire(owner: Any, gain: Gain = Gain.EXCLUSIVE): Boolean {
        holders.add(owner)
        if (!held) {
            requestFocus(gain)
        } else if (gain == Gain.EXCLUSIVE && heldGain == Gain.DUCK) {
            abandonFocus()
            requestFocus(gain)
        }
        return held
    }

    /** [owner] has stopped making sound. Focus is abandoned once nobody is left. */
    @Synchronized
    fun release(owner: Any) {
        if (!holders.remove(owner)) return
        if (holders.isEmpty()) abandonFocus()
    }

    @Synchronized
    fun observe(listener: (Event) -> Unit): Observation {
        nextListenerId += 1
        listeners[nextListenerId] = listener
        return Observation(nextListenerId)
    }

    @Synchronized
    fun removeObserver(observation: Observation) {
        listeners.remove(observation.id)
    }

    private fun requestFocus(gain: Gain) {
        val manager = audioManager ?: return
        val focusGain = when (gain) {
            Gain.EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN
            Gain.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val built = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .setWillPauseWhenDucked(false)
                .build()
            request = built
            manager.requestAudioFocus(built)
        } else {
            // minSdk is 24, so two versions still need the pre-O call.
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, focusGain
            )
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        heldGain = gain
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { manager.abandonAudioFocusRequest(it) }
            request = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
        held = false
    }

    private fun broadcast(event: Event) {
        val snapshot = synchronized(this) { listeners.values.toList() }
        mainHandler.post { snapshot.forEach { it(event) } }
    }
}
