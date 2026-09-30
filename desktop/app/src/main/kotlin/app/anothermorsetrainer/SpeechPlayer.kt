package app.anothermorsetrainer

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Speaks the English answer aloud for the hands-free "Listen & Learn" mode.
 *
 * Ported from the iOS MorseTrainerApp/SpeechPlayer.swift by way of the Android
 * port: wraps the platform speech synthesiser and reports completion on the
 * UI thread so the listen loop can chain play-Morse → pause → speak → next.
 * Like the iOS version it takes no [AudioFocus] holding of its own: its only
 * owner is [ListenService], which holds focus for the whole session — speech,
 * tones and the gaps between them alike.
 *
 * Desktop: the JVM has no speech synthesiser and the app bundles no speech
 * library, so this drives the one the operating system already has, one
 * short-lived process per utterance:
 * - **Windows:** Windows PowerShell 5.1 (`powershell.exe`, part of every
 *   Windows 10/11 install) with .NET's `System.Speech` SAPI synthesiser. The
 *   text travels in an environment variable, never inside the command line,
 *   so nothing in it can be read as PowerShell.
 * - **Linux:** `spd-say -w` (speech-dispatcher) if it is on the PATH,
 *   otherwise `espeak-ng` (text on stdin). Neither is guaranteed to be
 *   installed; without one [isReady] stays false and read-back is silent.
 *
 * **Inside the Flatpak sandbox neither Linux tool is present** (the runtime
 * ships no speech engine and the sandbox cannot see the host's), so Listen &
 * Learn's read-back is silent there: the answer is shown as text only.
 *
 * Availability is probed once, on a background thread, when the player is
 * constructed; [onReady] then runs on the UI thread, as the Android engine's
 * init callback did. Starting a process per utterance costs a fraction of a
 * second on Windows before speech begins; the loop simply waits for it.
 */
class SpeechPlayer {

    private enum class Engine { WINDOWS_SAPI, SPD_SAY, ESPEAK_NG }

    private val main = MainHandler()

    @Volatile var isReady: Boolean = false
        private set

    /** Invoked (on the UI thread) when the engine finishes initialising. */
    var onReady: (() -> Unit)? = null

    @Volatile private var engine: Engine? = null
    @Volatile private var released = false

    // Touched from the UI thread (speak/stop) and the utterance thread, so
    // guarded by [lock]. [generation] retires an utterance that [stop] or a
    // newer [speak] superseded, so its exit never fires the wrong completion.
    private val lock = Any()
    private var completion: (() -> Unit)? = null
    private var process: Process? = null
    private var generation = 0

    init {
        Thread({
            val found = detect()
            if (found != null && !released) {
                engine = found
                isReady = true
                main.post { onReady?.invoke() }
            }
        }, "amt-speech-probe").apply { isDaemon = true }.start()
    }

    /** Speak [text] and call [onDone] (on the UI thread) when finished. */
    fun speak(text: String, onDone: () -> Unit) {
        val trimmed = text.trim()
        val eng = engine
        if (!isReady || eng == null || trimmed.isEmpty()) { onDone(); return }
        val gen = synchronized(lock) {
            // QUEUE_FLUSH, as on Android: a new utterance cuts off the old one.
            killLocked()
            generation += 1
            completion = onDone
            generation
        }
        Thread({ run(eng, trimmed, gen) }, "amt-speech").apply { isDaemon = true }.start()
    }

    fun stop() {
        val wasSpeaking = synchronized(lock) {
            completion = null
            generation += 1
            val speaking = process != null
            killLocked()
            speaking
        }
        if (wasSpeaking && engine == Engine.SPD_SAY) cancelSpeechDispatcher()
    }

    /** Free the engine. Call from the owner's onDispose. */
    fun release() {
        released = true
        stop()
        isReady = false
    }

    // ---- Utterances ----

    private fun run(eng: Engine, text: String, gen: Int) {
        val builder = when (eng) {
            Engine.WINDOWS_SAPI -> ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "\$s.Speak(\$env:$TEXT_ENV)"
            ).also { it.environment()[TEXT_ENV] = text }
            // A leading space keeps text that starts with '-' from being read
            // as an option; spd-say ignores it when speaking.
            Engine.SPD_SAY -> ProcessBuilder("spd-say", "-w", if (text.startsWith("-")) " $text" else text)
            Engine.ESPEAK_NG -> ProcessBuilder("espeak-ng", "--stdin")
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)

        val p = runCatching { builder.start() }.getOrNull()
        if (p == null) { finish(gen); return }
        val current = synchronized(lock) {
            if (gen == generation) { process = p; true } else false
        }
        if (!current) { p.destroy(); return }
        // espeak-ng reads the text from stdin; the others get an empty,
        // closed stdin so nothing waits on it.
        runCatching {
            val stdin = p.outputStream
            if (eng == Engine.ESPEAK_NG) stdin.write(text.toByteArray(Charsets.UTF_8))
            stdin.close()
        }
        runCatching { p.waitFor() }
        finish(gen)
    }

    /** The utterance [gen] is over (spoken, failed or killed): complete it on the UI thread. */
    private fun finish(gen: Int) {
        main.post {
            val done = synchronized(lock) {
                if (gen != generation) return@synchronized null
                process = null
                val d = completion
                completion = null
                d
            }
            done?.invoke()
        }
    }

    private fun killLocked() {
        process?.let { p ->
            // Windows: kill the PowerShell tree, which ends the synthesiser in it.
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            p.destroyForcibly()
        }
        process = null
    }

    /**
     * Killing `spd-say -w` only stops it waiting; speech-dispatcher keeps
     * speaking the message. `spd-say -C` cancels it (and, being a whole-server
     * cancel, anything else queued at that moment — acceptable for a short
     * stop between items).
     */
    private fun cancelSpeechDispatcher() {
        Thread({
            runCatching {
                ProcessBuilder("spd-say", "-C")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
        }, "amt-speech-cancel").apply { isDaemon = true }.start()
    }

    // ---- Detection ----

    private fun detect(): Engine? = when {
        AppDirs.isWindows -> if (windowsSpeechWorks()) Engine.WINDOWS_SAPI else null
        AppDirs.isLinux -> when {
            onPath("spd-say") -> Engine.SPD_SAY
            onPath("espeak-ng") -> Engine.ESPEAK_NG
            else -> null
        }
        else -> null
    }

    /** True when Windows PowerShell can load System.Speech and has at least one voice. */
    private fun windowsSpeechWorks(): Boolean = runCatching {
        val p = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Add-Type -AssemblyName System.Speech; " +
                "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "if (\$s.GetInstalledVoices().Count -gt 0) { exit 0 } else { exit 1 }"
        )
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        runCatching { p.outputStream.close() }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            false
        } else {
            p.exitValue() == 0
        }
    }.getOrDefault(false)

    private fun onPath(name: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            dir.isNotEmpty() && File(dir, name).let { it.isFile && it.canExecute() }
        }
    }

    private companion object {
        /** The environment variable the Windows utterance reads its text from. */
        const val TEXT_ENV = "AMT_SPEAK_TEXT"
    }
}
