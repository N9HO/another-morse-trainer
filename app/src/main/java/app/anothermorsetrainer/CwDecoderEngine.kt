package app.anothermorsetrainer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.anothermorsetrainer.morsekit.cw.CwDecoder
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Live CW (Morse) audio decoder: taps the microphone and runs the ported
 * Carrier Wave core (morsekit/cw) over the incoming PCM, publishing decoded
 * text plus speed/pitch telemetry.
 *
 * Port of the iOS `CWDecoderEngine` (AVAudioEngine tap → [AudioRecord] on a
 * dedicated capture thread). The decoders are fed only from that thread; the
 * published fields are Compose [mutableStateOf], safe to write from it. The
 * decoders are created before the thread starts and torn down after it stops,
 * so they never see two threads at once.
 */
class CwDecoderEngine {

    var decodedText by mutableStateOf("")
        private set
    var wpm by mutableStateOf(0f)
        private set
    var toneHz by mutableStateOf(0f)
        private set
    var tonePresent by mutableStateOf(false)
        private set
    var inputLevel by mutableStateOf(0f)     // mic RMS, 0…~1
        private set
    var isListening by mutableStateOf(false)
        private set
    var micDenied by mutableStateOf(false)
        private set

    /** Keep the rolling transcript bounded so a long listen can't grow forever. */
    private val transcriptLimit = 800

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var core: CoreBox? = null

    // ---- Control ----

    /** Start decoding (the screen asks for the mic permission first). */
    fun start(context: Context) {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micDenied = true
            return
        }
        micDenied = false

        // 48 kHz float capture; fall back through common rates if a device
        // refuses (the core itself needs at least 6 kHz).
        var rec: AudioRecord? = null
        var rate = 0
        for (candidate in intArrayOf(48_000, 44_100, 16_000)) {
            val minBytes = AudioRecord.getMinBufferSize(
                candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            if (minBytes <= 0) continue
            // UNPROCESSED skips AGC/noise suppression — both hostile to a
            // steady CW tone. Devices without it approximate (API 24+).
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    candidate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    maxOf(minBytes, 4 * 2048 * 4)
                )
            } catch (_: IllegalArgumentException) {
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r; rate = candidate; break
            }
            r?.release()
        }
        val audio = rec ?: return

        val box = CoreBox()
        if (!box.createDecoder(rate)) {
            audio.release()
            return
        }
        core = box
        record = audio

        running = true
        val t = Thread {
            val buffer = FloatArray(2048)
            audio.startRecording()
            while (running) {
                val n = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                box.feed(buffer, n)
                val update = box.drain() ?: continue
                apply(update)
            }
        }
        t.name = "cw-decoder-capture"
        thread = t
        t.start()
        isListening = true
    }

    fun stop() {
        running = false
        // Stop the record BEFORE joining: the capture thread may be parked in
        // a blocking read, and stop() is what unblocks it. Release only after
        // the thread is out, so no read ever races the native teardown.
        record?.let { try { it.stop() } catch (_: IllegalStateException) {} }
        thread?.join(500)
        thread = null
        record?.release()
        record = null
        core?.destroyDecoder()
        core = null
        isListening = false
        tonePresent = false
        inputLevel = 0f
    }

    fun clear() {
        decodedText = ""
    }

    private fun apply(update: CoreBox.Update) {
        if (update.text.isNotEmpty()) {
            var text = decodedText + update.text
            if (text.length > transcriptLimit) text = text.takeLast(transcriptLimit)
            decodedText = text
        }
        wpm = update.wpm
        toneHz = update.toneHz
        tonePresent = update.tonePresent
        inputLevel = update.level
    }
}

/**
 * Owns the decoder cores and everything the capture thread touches. All fields
 * are read and written on the capture thread while the engine runs;
 * create/destroy happen from the main thread only while no capture is running.
 *
 * Two core instances run side by side, exactly as on iOS. The ported core's
 * pitch search is built for radio-fed audio: it locks onto the first sustained
 * tone it hears and never re-arms. On an open phone microphone that tone is
 * usually some ambient sound, and a decoder parked a hundred hertz off pitch
 * turns real CW into a spray of high-confidence junk characters — or nothing.
 * So next to the `primary` decoder (whose text and telemetry feed the UI) a
 * `scout` runs on the same samples, reset back to fresh acquisition whenever
 * it has copied nothing for a while — and only during quiet, because a decoder
 * that recalibrates its noise floor inside a continuous tone goes deaf for
 * seconds. The referee is physical, not statistical (junk decode and real copy
 * look alike symbol-for-symbol): the box runs its own Goertzel at each slot's
 * locked pitch, and when the scout's pitch has been carrying several times the
 * primary's energy and the scout is actually decoding it, the scout holds the
 * real signal and the two swap roles.
 */
private class CoreBox {

    class Update(
        val text: String,
        val wpm: Float,
        val toneHz: Float,
        val tonePresent: Boolean,
        val level: Float
    )

    /**
     * One core instance plus everything its callbacks write into, and the
     * box's own energy meter at the pitch this instance is locked to. Rolling
     * one-second buckets hold decoded-symbol counts and summed Goertzel
     * magnitudes.
     */
    private class Slot {
        companion object { const val BUCKETS = 12 }

        var decoder: CwDecoder? = null
        var pendingText = StringBuilder()
        var wpm = 0f
        var tonePresent = false
        val bucketSymbols = IntArray(BUCKETS)
        val bucketMag = FloatArray(BUCKETS)
        var head = 0
        var samplesSinceReset = 0
        /** Samples since the last decoded character (spaces don't count) —
         * "long ago" meaning not in the middle of copying anything. */
        var samplesSinceSymbol = Int.MAX_VALUE / 2
        /** Pitch the magnitude meter is tuned to, and its Goertzel coefficient. */
        var meterHz = 0f
        var meterCoeff = 0f

        fun advanceBucket() {
            head = (head + 1) % BUCKETS
            bucketSymbols[head] = 0
            bucketMag[head] = 0f
        }

        /** Symbol count over the last [n] one-second buckets (current included). */
        fun symbols(n: Int): Int {
            var total = 0
            for (i in 0 until n) total += bucketSymbols[(head - i + BUCKETS) % BUCKETS]
            return total
        }

        /** Summed magnitude at [meterHz] over the last [n] buckets. */
        fun magnitude(n: Int): Float {
            var total = 0f
            for (i in 0 until n) total += bucketMag[(head - i + BUCKETS) % BUCKETS]
            return total
        }

        /** Retune the energy meter when the core re-locks; magnitudes gathered
         * at the old pitch would poison the comparison, so they go too. */
        fun retuneMeter(toneHz: Float, rate: Int) {
            if (kotlin.math.abs(toneHz - meterHz) <= 1f) return
            meterHz = toneHz
            meterCoeff = 2f * cos(2f * Math.PI.toFloat() * toneHz / rate)
            bucketMag.fill(0f)
        }

        /** Back to fresh acquisition: recalibrate the noise floor and re-arm
         * the pitch search. Everything counted so far belonged to the old lock. */
        fun reset() {
            decoder?.reset()
            pendingText.setLength(0)
            bucketSymbols.fill(0)
            bucketMag.fill(0f)
            samplesSinceReset = 0
            samplesSinceSymbol = Int.MAX_VALUE / 2
        }
    }

    private companion object {
        /** An idle scout re-arms after this long without a single decoded symbol… */
        const val SCOUT_IDLE_SECONDS = 5
        /** …but only once the room has been quiet this many consecutive buffers
         * (≈350 ms at the usual buffer size — a word gap, not an inter-character
         * gap), so recalibration never happens inside a tone… */
        const val QUIET_BUFFERS = 8
        /** …unless nothing has been decoded for this long, when it resets anyway
         * rather than stay parked forever under wall-to-wall sound. */
        const val SCOUT_FORCE_SECONDS = 20
        /** A rescue swap needs the scout's pitch to carry this multiple of the
         * primary's energy (judged over [MAG_SECONDS])… */
        const val SWAP_MAG_RATIO = 3f
        /** …with at least this much absolute signal, over this window, and with
         * the scout actually decoding it (symbols within [SCOUT_IDLE_SECONDS]). */
        const val SWAP_MAG_FLOOR = 0.02f
        const val MAG_SECONDS = 4
        /** The two pitches must genuinely differ; equal locks mean the primary
         * already sits on the strongest tone. */
        const val SWAP_MIN_HZ_APART = 40f
        /** A long-idle primary re-arms too (quiet-gated like the scout). */
        const val PRIMARY_IDLE_SECONDS = 12
        /** And swaps are rate-limited, so two live signals can't ping-pong. */
        const val SWAP_HOLD_SECONDS = 10
    }

    private var primary = Slot()
    private var scout: Slot? = null
    private var rate = 0
    private var scratch = ShortArray(0)
    private var samplesIntoBucket = 0
    private var samplesSinceSwap = 0
    private var toneHz = 0f
    private var level = 0f
    /** Slow-rising minimum tracker of buffer RMS: what "quiet" means right now. */
    private var noiseLevel = 1f
    private var quietStreak = 0
    private var reported = Update("", -1f, -1f, false, -1f)

    fun createDecoder(inputRate: Int): Boolean {
        destroyDecoder()
        rate = inputRate
        samplesIntoBucket = 0
        // Start with the swap hold already elapsed: the hold exists to stop
        // ping-ponging between two live signals, not to delay the first rescue.
        samplesSinceSwap = SWAP_HOLD_SECONDS * rate
        toneHz = 0f; level = 0f
        noiseLevel = 1f
        quietStreak = 0
        reported = Update("", -1f, -1f, false, -1f)

        fun configFor(slot: Slot): CwDecoder.Config {
            val cfg = CwDecoder.Config()
            cfg.inputRateHz = inputRate
            // Pick a decimation factor that divides the hardware rate exactly,
            // so the Goertzel bins sit where the config says they do
            // (48 k → 8 k, 44.1 k → 7.35 k, …).
            val factor = maxOf(1, (inputRate / 8000.0).roundToInt())
            cfg.targetRateHz = maxOf(3000, inputRate / factor)
            cfg.inputChannels = 1
            cfg.onSymbol = { text, _ ->
                slot.pendingText.append(text)
                // Word spaces are punctuation, not evidence of copy: they
                // arrive by timeout even in dead air, so they don't refresh
                // the counters.
                if (text != " ") {
                    slot.bucketSymbols[slot.head] += 1
                    slot.samplesSinceSymbol = 0
                }
            }
            cfg.onStatus = { wpm, _, tonePresent ->
                slot.wpm = wpm
                slot.tonePresent = tonePresent
            }
            return cfg
        }

        primary = Slot()
        primary.decoder = CwDecoder.create(configFor(primary)) ?: return false

        // The scout is a resilience layer; if it can't allocate, the primary
        // still decodes the way the firmware does.
        val second = Slot()
        second.decoder = CwDecoder.create(configFor(second))
        scout = if (second.decoder != null) second else null
        return true
    }

    fun destroyDecoder() {
        primary.decoder = null
        scout = null
    }

    /** Convert one float buffer to int16 PCM, meter it, and push it through
     * both cores. Runs on the capture thread. */
    fun feed(data: FloatArray, count: Int) {
        val decoder = primary.decoder ?: return
        if (count <= 0) return
        if (scratch.size < count) scratch = ShortArray(count)

        primary.retuneMeter(decoder.toneHz, rate)
        scout?.let { s -> s.decoder?.let { s.retuneMeter(it.toneHz, rate) } }
        val pCoeff = primary.meterCoeff
        val sCoeff = scout?.meterCoeff ?: 0f
        var p1 = 0f; var p2 = 0f; var s1 = 0f; var s2 = 0f
        var energy = 0f
        for (i in 0 until count) {
            val raw = data[i]
            val sample = if (raw.isFinite()) raw.coerceIn(-1f, 1f) else 0f
            energy += sample * sample
            scratch[i] = (sample * 32000).toInt().toShort()
            val p0 = sample + pCoeff * p1 - p2
            p2 = p1; p1 = p0
            val s0 = sample + sCoeff * s1 - s2
            s2 = s1; s1 = s0
        }
        level = sqrt(energy / count)
        val norm = count.toFloat()
        primary.bucketMag[primary.head] +=
            sqrt(maxOf(0f, p1 * p1 + p2 * p2 - pCoeff * p1 * p2)) / norm
        scout?.let {
            it.bucketMag[it.head] +=
                sqrt(maxOf(0f, s1 * s1 + s2 * s2 - sCoeff * s1 * s2)) / norm
        }

        decoder.feed(scratch, count)
        scout?.decoder?.feed(scratch, count)
        toneHz = decoder.toneHz
        advanceClock(count)
        supervise()
    }

    private fun advanceClock(count: Int) {
        samplesSinceSwap += count
        primary.samplesSinceReset += count
        primary.samplesSinceSymbol += count
        scout?.let {
            it.samplesSinceReset += count
            it.samplesSinceSymbol += count
        }
        samplesIntoBucket += count
        while (samplesIntoBucket >= rate) {
            samplesIntoBucket -= rate
            primary.advanceBucket()
            scout?.advanceBucket()
        }
        // Quiet tracking: the noise reference sinks to the softest buffer and
        // creeps back up a few percent a second, so "quiet" stays honest as
        // conditions drift.
        noiseLevel = minOf(noiseLevel * 1.001f + 1e-6f, maxOf(level, 1e-6f))
        if (level < maxOf(noiseLevel * 2.5f, 0.0025f)) {
            quietStreak += 1
        } else {
            quietStreak = 0
        }
    }

    /** The pitch-lock referee; runs on the capture thread after every buffer. */
    private fun supervise() {
        val scout = this.scout ?: return

        if (kotlin.math.abs(scout.meterHz - primary.meterHz) >= SWAP_MIN_HZ_APART &&
            scout.symbols(SCOUT_IDLE_SECONDS) >= 2 &&
            samplesSinceSwap >= SWAP_HOLD_SECONDS * rate
        ) {
            val scoutMag = scout.magnitude(MAG_SECONDS)
            if (scoutMag > SWAP_MAG_FLOOR &&
                scoutMag >= SWAP_MAG_RATIO * primary.magnitude(MAG_SECONDS)
            ) {
                // The scout's pitch is where the energy actually is, and it is
                // decoding it. Its recent copy is still in pendingText and
                // reaches the UI on this very drain; the deposed primary
                // restarts acquisition from the next quiet stretch.
                val deposed = primary
                primary = scout
                this.scout = deposed
                deposed.reset()
                samplesSinceSwap = 0
                primary.decoder?.let { toneHz = it.toneHz }
                return
            }
        }

        // Re-arm the scout the moment sustained quiet sets in and it isn't
        // mid-copy: its job is to be freshly searching when the next signal
        // starts, and quiet is the safe moment — a reset inside a continuous
        // tone calibrates the noise floor onto the tone and the core goes deaf
        // for seconds (measured on the vendored core). The edge trigger fires
        // once per quiet stretch; the idle rule (with a force fallback for
        // wall-to-wall sound) catches everything else, because a decoder
        // parked on a dead pitch hears nothing forever.
        if (quietStreak == QUIET_BUFFERS &&
            scout.samplesSinceSymbol >= rate &&
            scout.samplesSinceReset >= 2 * rate
        ) {
            scout.reset()
        } else if (scout.symbols(SCOUT_IDLE_SECONDS) == 0 &&
            scout.samplesSinceReset >= SCOUT_IDLE_SECONDS * rate &&
            (quietStreak >= QUIET_BUFFERS ||
                scout.samplesSinceReset >= SCOUT_FORCE_SECONDS * rate)
        ) {
            scout.reset()
        }

        // A long-idle primary re-arms too: nothing is lost (it decoded nothing
        // all window), and its next lock starts from a current noise floor.
        if (primary.symbols(PRIMARY_IDLE_SECONDS) == 0 &&
            primary.samplesSinceReset >= PRIMARY_IDLE_SECONDS * rate &&
            quietStreak >= QUIET_BUFFERS
        ) {
            primary.reset()
        }

        // A scout that shadows the primary's own healthy copy accumulates
        // text nobody will read; keep the backlog bounded.
        if (scout.pendingText.length > 64) {
            val tail = scout.pendingText.takeLast(32).toString()
            scout.pendingText.setLength(0)
            scout.pendingText.append(tail)
        }
    }

    /** What changed since the last drain, or null when nothing worth
     * publishing happened in this buffer. */
    fun drain(): Update? {
        val update = Update(
            primary.pendingText.toString(), primary.wpm, toneHz,
            primary.tonePresent, level
        )
        primary.pendingText.setLength(0)
        val newsworthy = update.text.isNotEmpty() ||
            update.tonePresent != reported.tonePresent ||
            update.wpm != reported.wpm ||
            update.toneHz != reported.toneHz ||
            kotlin.math.abs(update.level - reported.level) > 0.01f
        if (!newsworthy) return null
        reported = update
        return update
    }
}
