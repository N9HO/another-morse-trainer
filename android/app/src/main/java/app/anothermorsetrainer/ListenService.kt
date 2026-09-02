package app.anothermorsetrainer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Runs the Listen & Learn loop as a foreground service so it keeps playing with
 * the app backgrounded or the screen locked — the hands-free use the iOS app
 * supports via UIBackgroundModes. Owns the [MorsePlayer] + [SpeechPlayer] and an
 * ongoing notification with Pause/Resume + Stop actions. State is published via
 * [ListenState] for the in-app UI.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: MorsePlayer
    private lateinit var speech: SpeechPlayer
    private var loopJob: Job? = null
    private var tickJob: Job? = null
    private var sessionRecorded = false
    private val rng = Random(SystemClock.elapsedRealtimeNanos())
    private var focusObservation: AudioFocus.Observation? = null
    /**
     * Set when focus — not the user — paused the loop, so regaining focus
     * resumes only what it interrupted. Without it, hanging up a call would
     * restart a session the user had deliberately paused before answering.
     */
    private var pausedByFocus = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        player = MorsePlayer()
        speech = SpeechPlayer(this)
        ensureChannel(this)
        // Idempotent, and needed here as well as in MainActivity: a Resume tapped
        // on the notification after the activity has gone can start this service
        // in a process where onCreate never ran.
        AudioFocus.init(this)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // A start while stopped begins a fresh session; a start while
                // running is just a config change (content/gap chip) mid-session.
                if (!ListenState.running) {
                    ListenState.itemsHeard = 0
                    ListenState.activeSeconds = 0
                    ListenState.limitSeconds = Settings.practiceDuration.seconds
                    ListenState.finishedNote = null
                    sessionRecorded = false
                    startTicker()
                }
                startInForeground()
                startLoop()
            }
            ACTION_TOGGLE -> {
                if (ListenState.running) {
                    if (ListenState.paused) resume() else pause()
                }
            }
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun startLoop() {
        loopJob?.cancel()
        // Held for the whole session, not just while a tone renders: the speech
        // and the gaps between items are part of the lesson too.
        AudioFocus.acquire(this)
        ListenState.running = true
        ListenState.paused = false
        loopJob = scope.launch { runLoop() }
        updateNotification()
    }

    private suspend fun runLoop() {
        try {
            // Cancellation propagates through the suspend points below (they throw
            // CancellationException), exiting the loop and running the finally.
            while (true) {
                val item = nextListenItem(ListenState.contentSel, rng)
                ListenState.display = ""
                ListenState.playing = true
                updateNotification()
                awaitPlay(player, item.playable)
                delay(ListenState.gapSel.ms)
                ListenState.display = item.display
                ListenState.playing = false
                updateNotification()
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
        player.stop()
        speech.stop()
        ListenState.paused = true
        ListenState.playing = false
        updateNotification()
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
                    ListenState.finishedNote = getString(R.string.listen_session_complete, ListenState.itemsHeard)
                    stopEverything()
                    break
                }
            }
        }
    }

    /**
     * Hands-free listening still counts as practice: record the session once
     * (streak + practice time) with each completed item as a heard "answer".
     */
    private fun recordSession() {
        if (sessionRecorded) return
        sessionRecorded = true
        if (ListenState.itemsHeard > 0) {
            Stats.record(
                mode = "Listen",
                attempts = ListenState.itemsHeard,
                correct = ListenState.itemsHeard,
                bestTtrMs = null,
                durationSeconds = ListenState.activeSeconds
            )
        }
    }

    private fun stopEverything() {
        loopJob?.cancel()
        tickJob?.cancel()
        player.stop()
        speech.stop()
        recordSession()
        ListenState.running = false
        ListenState.paused = false
        ListenState.playing = false
        ListenState.display = ""
        pausedByFocus = false
        AudioFocus.release(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        focusObservation?.let { AudioFocus.removeObserver(it) }
        focusObservation = null
        AudioFocus.release(this)
        player.release()
        speech.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Foreground notification ----

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        // The foreground-service notification is exempt from the runtime
        // POST_NOTIFICATIONS gate, but NotificationManagerCompat.notify still
        // checks it on API 33+, so guard to avoid a SecurityException.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val text = when {
            ListenState.paused -> getString(R.string.listen_notification_paused)
            ListenState.playing -> getString(R.string.listen_notification_listening)
            ListenState.display.isNotEmpty() -> ListenState.display
            else -> getString(R.string.listen_hands_free_practice)
        }
        val toggleLabel = if (ListenState.paused) getString(R.string.listen_resume) else getString(R.string.listen_pause)
        val toggleIcon = if (ListenState.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_morse)
            .setContentTitle(getString(R.string.mode_listen_and_learn))
            .setContentText(text)
            .setContentIntent(activityIntent())
            .addAction(toggleIcon, toggleLabel, serviceIntent(ACTION_TOGGLE, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.common_stop), serviceIntent(ACTION_STOP, 2))
            .setOngoing(!ListenState.paused)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, ListenService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun activityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ID = "listen_playback"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "app.anothermorsetrainer.listen.START"
        const val ACTION_TOGGLE = "app.anothermorsetrainer.listen.TOGGLE"
        const val ACTION_STOP = "app.anothermorsetrainer.listen.STOP"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.listen_channel_name))
                .setDescription(getString(R.string.listen_channel_description))
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, ListenService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun toggle(context: Context) {
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_TOGGLE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))
        }
    }
}
