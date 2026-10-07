package app.anothermorsetrainer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.concurrent.thread

/**
 * A continuous, low-level noise floor played underneath practice (issue #29).
 *
 * Two levels of "on" since #331: the inaudible keep-alive floor follows the
 * app on screen, while audible band noise sounds only while a practice screen
 * is open ([setPractising]) or for a short [preview] after the level is
 * changed in Settings — never on Home, Settings, Stats or the other menus.
 *
 * Two jobs in one control. The one that prompted it: Bluetooth earbuds power
 * their receiver down during digital silence and take a moment to wake, which
 * clips the first character of a transmission and quietly wrecks accuracy stats
 * — the user hits Replay, and the round is scored slow either way. [MorsePlayer]
 * makes this worse than it has to be: it builds a fresh static-buffer
 * [AudioTrack] per sound and releases it afterwards, so between drills there is
 * no stream at all. A trickle of real audio from a *separate*, always-open
 * stream never lets the link idle. The other job: it is band noise, so
 * practising against it is more like copying off the air than off a silent tone
 * generator.
 *
 * A process-wide singleton like [Settings]: every screen builds its own
 * [MorsePlayer], and a noise floor that stopped and restarted on each navigation
 * would defeat the point. [MainActivity] drives it with the foreground — it
 * exists to keep the link awake while you practise, and hissing on in the
 * background would be a bug, not a feature.
 *
 * The Apple apps get the same setting from `MorsePlayer.setNoiseLevel`, which
 * needs no second stream: their persistent `AVAudioSourceNode` is already
 * running, so the noise goes straight into its render callback.
 */
object BackgroundNoise {

    private const val SAMPLE_RATE = 44_100
    /** Written per pass — ~93 ms, small enough to react, big enough to be cheap. */
    private const val CHUNK = 4096
    /** Per-sample gain step: ~40 ms to cross the full range, so no clicks. */
    private const val GAIN_STEP = 0.0006f

    /**
     * Bumped on every start and stop. A feeder runs only while its own
     * generation is still current, so a stream that is fading out can never be
     * mistaken for the live one, and a stop followed straight by a start does
     * not leave two threads fighting over the setting.
     */
    private var generation = 0
    /** True between [start] and the moment its feeder finishes. */
    private var active = false
    /** Target amplitude; the feeder ramps towards it so changes don't click. */
    @Volatile private var target = 0f
    /** True while the app is on screen. */
    private var foreground = false
    /** True while another app holds audio focus — a call, or music taking over. */
    private var yielded = false
    /**
     * True while a practice screen is open — a mode, game or lesson that plays
     * Morse (#331). The app root sets it from the route. Band noise is only
     * audible while it is set (or a [preview] runs); Home, Settings, Stats and
     * the other menus get at most the inaudible keep-alive floor.
     */
    private var practising = false
    /** True for [PREVIEW_MS] after the Band noise level is changed in Settings. */
    private var previewing = false
    /** Bumped on every preview start and end, so a stale timer cannot end a newer one. */
    private var previewGeneration = 0

    /** How long the Settings preview sounds: long enough to judge the level. */
    private const val PREVIEW_MS = 2_500L

    init {
        // A listener, never a holder. The keep-alive floor runs the whole time
        // the app is on screen, home screen and settings list included, so
        // taking focus for it would pause the user's music just to browse a
        // menu. But it must still get out of the way: hissing under a phone
        // call is exactly the "talks through calls" complaint, and the
        // quietest bug to miss.
        AudioFocus.observe { event ->
            when (event) {
                AudioFocus.Event.LOST, AudioFocus.Event.LOST_TRANSIENT ->
                    synchronized(this) { yielded = true }
                AudioFocus.Event.REGAINED ->
                    synchronized(this) { yielded = false }
            }
            refresh()
        }
    }

    /** The app came to the foreground: start the floor if the setting calls for one. */
    fun onForeground() {
        synchronized(this) { foreground = true }
        refresh()
    }

    /** The app went away: silence the floor until it comes back. */
    fun onBackground() {
        synchronized(this) { foreground = false }
        refresh()
    }

    /** A practice screen opened (true) or closed (false); see [practising]. */
    fun setPractising(on: Boolean) {
        synchronized(this) { practising = on }
        refresh()
    }

    /**
     * The Band noise level was just picked in Settings: let it sound for
     * [PREVIEW_MS] so the level can be judged, then fall quiet again (#331).
     * Mid-session the noise is already audible and simply takes the new level.
     */
    fun preview() {
        val (mine, on) = synchronized(this) {
            previewGeneration += 1
            previewing = Settings.bandNoise != BackgroundNoiseLevel.OFF
            previewGeneration to previewing
        }
        refresh()
        if (!on) return
        thread(name = "amt-band-noise-preview", isDaemon = true) {
            try { Thread.sleep(PREVIEW_MS) } catch (_: InterruptedException) {}
            endPreview(mine)
        }
    }

    /** Cut a running preview short — Settings is being left. */
    fun endPreview() {
        val mine = synchronized(this) { previewGeneration }
        endPreview(mine)
    }

    private fun endPreview(mine: Int) {
        synchronized(this) {
            if (previewGeneration != mine || !previewing) return
            previewGeneration += 1
            previewing = false
        }
        refresh()
    }

    /**
     * Re-read [Settings.backgroundNoise] — the level derived from the keep-alive
     * switch and the band-noise picker (issue #169) — call after the user
     * changes either. Band noise itself only sounds while [practising] or
     * [previewing]; otherwise the keep-alive switch alone decides (#331).
     */
    @Synchronized
    fun refresh() {
        val wanted = BackgroundNoiseLevel.effective(
            Settings.bluetoothKeepAlive, Settings.bandNoise,
            bandNoiseAudible = practising || previewing
        )
        val level = if (foreground && !yielded) wanted.amplitude else 0f
        target = level
        if (level > 0f) start() else stop()
    }

    private fun start() {
        if (active) return
        active = true
        generation += 1
        val mine = generation
        thread(name = "amt-background-noise", isDaemon = true) { feed(mine) }
    }

    private fun stop() {
        if (!active) return
        active = false
        generation += 1   // the live feeder sees this and fades itself out
    }

    @Synchronized
    private fun isCurrent(mine: Int): Boolean = generation == mine

    /**
     * Owns the streaming track for as long as this generation is current. Writes
     * are blocking, so the loop paces itself against playback and the thread
     * spends nearly all of its time parked.
     */
    private fun feed(mine: Int) {
        // Parked in a blocking write nearly all the time; the priority is for
        // the moments it wakes, so a chunk is not queued behind a Compose frame
        // and the floor never drops out — which is the one thing it is for. A
        // refused priority is the old behaviour, not a failure.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: SecurityException) {}
        val track = openTrack()
        if (track == null) {
            // No audio route, or the device refused the format. The floor is a
            // comfort, never a requirement — practice carries on without it.
            synchronized(this) { if (generation == mine) active = false }
            return
        }

        var state = 0x2545F4914F6CDD1DuL
        var lowpassed = 0f
        var gain = 0f
        val buffer = FloatArray(CHUNK)

        fun fill(toward: Float, step: Float) {
            for (i in 0 until CHUNK) {
                // Cheap LCG white noise, one-pole lowpassed: raw white hiss is
                // harsh and fatiguing, real band noise is softer. The top 32
                // bits are taken as a *signed* int so the noise swings both ways
                // — the low half alone would be a DC offset, not a sound.
                state = state * 6364136223846793005uL + 1442695040888963407uL
                val white = (state shr 32).toInt() / Int.MAX_VALUE.toFloat()
                lowpassed += 0.2f * (white - lowpassed)
                gain += (toward - gain).coerceIn(-step, step)
                buffer[i] = lowpassed * gain
            }
        }

        try {
            track.play()
            // The same −6 dB on the built-in speaker as MorsePlayer (#259), so
            // the tone-to-noise balance is the same on every route — as on
            // iOS, where the noise is mixed into the tone's own source node.
            SpeakerHeadroom.follow(track)
            while (isCurrent(mine)) {
                fill(target, GAIN_STEP)
                if (track.write(buffer, 0, CHUNK, AudioTrack.WRITE_BLOCKING) < 0) break
            }
            // Fade out so stopping is as quiet as starting.
            while (gain > 0f) {
                fill(0f, GAIN_STEP)
                if (track.write(buffer, 0, CHUNK, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } catch (_: IllegalStateException) {
            // Track died under us (route change, audio policy). Nothing to salvage.
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            synchronized(this) { if (generation == mine) active = false }
        }
    }

    private fun openTrack(): AudioTrack? = try {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        // getMinBufferSize reports an error as a negative value; fall back to a
        // couple of chunks rather than handing AudioTrack.Builder nonsense.
        val bufferBytes = if (minBytes > 0) maxOf(minBytes, CHUNK * 4) else CHUNK * 4 * 2
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (_: Exception) {
        null
    }
}
