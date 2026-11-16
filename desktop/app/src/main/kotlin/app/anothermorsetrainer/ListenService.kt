package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.SessionClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Runs the Listen & Learn loop — the hands-free use the iOS app supports via
 * UIBackgroundModes and the Android app via a foreground service. Owns the
 * [MorsePlayer] + [SpeechPlayer]. State is published via [ListenState] for the
 * in-app UI.
 *
 * Desktop: a plain in-process object running the same loop on a coroutine
 * scope on the UI thread. A desktop app keeps running when its window is
 * minimised, so there is no foreground service, no ongoing notification with
 * Pause/Resume + Stop actions, and no media session (#184's lock-screen and
 * car-display metadata): the Listen screen's own controls are the only ones.
 * The players are created on the first [start] and kept for the process's
 * life, as the Android service kept them for its own.
 */
object ListenService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MorsePlayer? = null
    private var speech: SpeechPlayer? = null
    private var loopJob: Job? = null
    private var tickJob: Job? = null
    private var sessionRecorded = false
    private val rng = Random(System.nanoTime())
    private val picker = ListenPicker(rng)
    private var focusObservation: AudioFocus.Observation? = null
    /**
     * Set when focus — not the user — paused the loop, so regaining focus
     * resumes only what it interrupted. Without it, hanging up a call would
     * restart a session the user had deliberately paused before answering.
     */
    private var pausedByFocus = false

    /** Desktop: the Android service's onCreate, run once on the first [start]. */
    private fun ensureCreated() {
        if (player != null) return
        player = MorsePlayer()
        speech = SpeechPlayer()
        AudioFocus.init()
        observeAudioFocus()
    }

    /**
     * The loop is a chain of coroutine delays; none of them know whether a sound
     * was audible. A call used to leave it stepping through items in silence and
     * counting every one as heard, so the session summary claimed practice that
     * never happened.
     */
    private fun observeAudioFocus() {
        focusObservation = AudioFocus.observe { event ->
            when (event) {
                AudioFocus.Event.LOST ->
                    if (ListenState.running) stopEverything()
                AudioFocus.Event.LOST_TRANSIENT ->
                    if (ListenState.running && !ListenState.paused) {
                        pausedByFocus = true
                        pause()
                    }
                AudioFocus.Event.REGAINED ->
                    if (pausedByFocus && ListenState.running && ListenState.paused) resume()
            }
        }
    }

    /**
     * A start while stopped begins a fresh session; a start while running is
     * just a config change (content/gap chip) mid-session. (Android's
     * ACTION_START.)
     */
    fun start() {
        ensureCreated()
        if (!ListenState.running) {
            ListenState.itemsHeard = 0
            ListenState.activeSeconds = 0
            ListenState.limitSeconds = Settings.practiceDuration.seconds
            ListenState.finishedNote = null
            sessionRecorded = false
            startTicker()
        }
        startLoop()
    }

    /** Pause or resume a running session (Android's ACTION_TOGGLE). */
    fun toggle() {
        if (ListenState.running) {
            if (ListenState.paused) resume() else pause()
        }
    }

    /** End the session and record it (Android's ACTION_STOP). */
    fun stop() {
        if (player == null) return
        stopEverything()
    }

    /**
     * The session timer menu's "Add N minutes" (iOS `addSessionTime`, #379;
     * Android's ACTION_ADD_TIME). The limit is kept as listened seconds, so
     * the time left is [ListenState.limitSeconds] less
     * [ListenState.activeSeconds]; the arithmetic on it is [SessionClock]'s,
     * pinned by fixtures/gap-timers-session-clock.json. Paused, the ticker
     * does not count, so the frozen time left simply grows and resumes from
     * there; on an "Until I stop" session the added seconds start a
     * countdown the ticker then enforces.
     */
    fun addTime(seconds: Int) {
        if (!ListenState.running) return
        val left = SessionClock.added(ListenState.timeLeftSeconds, seconds) ?: return
        ListenState.limitSeconds = ListenState.activeSeconds + left
    }

    /**
     * "Subtract 1 minute" (iOS `reduceSessionTime`; Android's
     * ACTION_SUBTRACT_TIME): the time left drops, and reaching zero ends the
     * session at once — paused or not — the way running out of time does.
     */
    fun subtractTime(seconds: Int) {
        if (!ListenState.running || seconds <= 0) return
        val current = ListenState.timeLeftSeconds ?: return
        val left = SessionClock.reduced(current, seconds) ?: 0
        if (left <= 0) {
            ListenState.finishedNote = AppStrings.get(R.string.listen_session_complete, ListenState.itemsHeard)
            stopEverything()
        } else {
            ListenState.limitSeconds = ListenState.activeSeconds + left
        }
    }

    /** "Remove time limit" (Android's ACTION_REMOVE_LIMIT): keep listening open-ended. */
    fun removeLimit() {
        if (ListenState.running) ListenState.limitSeconds = null
    }

    private fun startLoop() {
        loopJob?.cancel()
        // Held for the whole session, not just while a tone renders: the speech
        // and the gaps between items are part of the lesson too.
        AudioFocus.acquire(this)
        ListenState.running = true
        ListenState.paused = false
        loopJob = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        val player = player ?: return
        val speech = speech ?: return
        try {
            // Cancellation propagates through the suspend points below (they throw
            // CancellationException), exiting the loop and running the finally.
            while (true) {
                val item = picker.next(ListenState.loopContent, ListenState.readbackSel, EngineStore.current().engine.activeCharacters, Settings.cw77Personal())
                ListenState.display = ""
                ListenState.playing = true
                awaitPlay(player, item.playable)
                delay(ListenState.gapSel.ms)
                ListenState.display = item.display
                ListenState.playing = false
                awaitSpeak(speech, item.spoken)
                ListenState.itemsHeard += 1
                delay(700)
            }
        } finally {
            player.stop()
            speech.stop()
        }
    }

    private fun pause() {
        loopJob?.cancel()
        player?.stop()
        speech?.stop()
        ListenState.paused = true
        ListenState.playing = false
    }

    private fun resume() {
        pausedByFocus = false
        ListenState.paused = false
        startLoop()
    }

    /**
     * The session clock: counts listened seconds (pauses excluded) and, when a
     * session length is configured, ends the loop itself when time is up.
     */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(1000)
                if (ListenState.paused) continue
                ListenState.activeSeconds += 1
                val limit = ListenState.limitSeconds ?: continue
                if (ListenState.activeSeconds >= limit) {
                    ListenState.finishedNote = AppStrings.get(R.string.listen_session_complete, ListenState.itemsHeard)
                    stopEverything()
                    break
                }
            }
        }
    }

    /**
     * Hands-free listening still counts as practice: record the session once
     * (streak + practice time) with each completed item as an item heard.
     * Nothing is graded, so `correct` is 0 and "Listen" is a passive mode
     * ([app.anothermorsetrainer.morsekit.SessionRecord.PASSIVE_MODES]): the
     * stats screen shows N/A for its accuracy and leaves it out of the
     * averages (#183).
     */
    private fun recordSession() {
        if (sessionRecorded) return
        sessionRecorded = true
        if (ListenState.itemsHeard > 0) {
            Stats.record(
                // CW 77's Listen style logs under its own passive mode.
                mode = if (ListenState.cw77Session) "CW 77 Listen" else "Listen",
                attempts = ListenState.itemsHeard,
                correct = 0,
                bestTtrMs = null,
                durationSeconds = ListenState.activeSeconds
            )
        }
    }

    private fun stopEverything() {
        loopJob?.cancel()
        tickJob?.cancel()
        player?.stop()
        speech?.stop()
        recordSession()
        ListenState.running = false
        ListenState.paused = false
        ListenState.playing = false
        ListenState.display = ""
        pausedByFocus = false
        AudioFocus.release(this)
    }
}
