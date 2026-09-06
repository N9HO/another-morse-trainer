package app.anothermorsetrainer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
 *
 * It also owns a [MediaSessionCompat] (#184): a Bluetooth head unit or the lock
 * screen only shows track info that comes through a media session, so without
 * one a car display kept whatever the previous app had left there. The session
 * carries the same title the notification shows — the revealed item, or
 * "Listening…" while the code plays — the "Morse Trainer · Listen & Learn"
 * line, and the app mark as artwork, mirroring iOS's `updateNowPlaying`. Its
 * play/pause/stop callbacks (Bluetooth buttons, the car's controls) route to the
 * same pause/resume/stop the notification actions use.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: MorsePlayer
    private lateinit var speech: SpeechPlayer
    private var loopJob: Job? = null
    private var tickJob: Job? = null
    private var sessionRecorded = false
    private val rng = Random(SystemClock.elapsedRealtimeNanos())
    private val picker = ListenPicker(rng)
    private var focusObservation: AudioFocus.Observation? = null
    /**
     * Set when focus — not the user — paused the loop, so regaining focus
     * resumes only what it interrupted. Without it, hanging up a call would
     * restart a session the user had deliberately paused before answering.
     */
    private var pausedByFocus = false
    private var mediaSession: MediaSessionCompat? = null
    /** The app mark as Now Playing artwork; static, so rendered once. */
    private val artwork: Bitmap? by lazy { renderArtwork() }

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
        mediaSession = MediaSessionCompat(this, "ListenService").apply {
            setSessionActivity(activityIntent())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (ListenState.running && ListenState.paused) resume()
                }

                override fun onPause() {
                    if (ListenState.running && !ListenState.paused) pause()
                }

                override fun onStop() {
                    if (ListenState.running) stopEverything()
                }
            })
        }
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
                val item = picker.next(ListenState.contentSel)
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
                mode = "Listen",
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
        player.stop()
        speech.stop()
        recordSession()
        ListenState.running = false
        ListenState.paused = false
        ListenState.playing = false
        ListenState.display = ""
        pausedByFocus = false
        AudioFocus.release(this)
        // Inactive, not released: a Resume from the notification can restart
        // the loop in this same service instance (START_NOT_STICKY only stops
        // the *system* restarting it), and the head unit should let go of us
        // the moment the session ends rather than showing a stopped item.
        mediaSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        mediaSession?.release()
        mediaSession = null
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
        updateMediaSession()
        // The foreground-service notification is exempt from the runtime
        // POST_NOTIFICATIONS gate, but NotificationManagerCompat.notify still
        // checks it on API 33+, so guard to avoid a SecurityException.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    // ---- Media session (lock screen, Bluetooth / car displays) ----

    /** Same title rule as iOS: the revealed item once revealed, "Listening…" until then. */
    private fun nowPlayingTitle(): String =
        if (ListenState.display.isEmpty()) getString(R.string.listen_notification_listening) else ListenState.display

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, nowPlayingTitle())
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.listen_now_playing_artist))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.app_name))
        artwork?.let {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
        }
        session.setMetadata(metadata.build())
        val state = if (ListenState.paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (ListenState.paused) 0f else 1f)
                .build()
        )
        session.isActive = ListenState.running
    }

    /**
     * The launcher icon as a 512×512 bitmap. On API 26+ that is the adaptive
     * icon; its layers are drawn oversized so the 72dp safe zone fills the
     * square — full-bleed navy with the teal ring, like the iOS mark — instead
     * of a masked icon floating in transparency. Below 26 the combined
     * fallback vector is already a full square.
     */
    private fun renderArtwork(): Bitmap? {
        val drawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return null
        val size = ARTWORK_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            // The adaptive canvas is 108dp with the visible 72dp in the middle:
            // scale by 108/72 and centre, so the bleed falls off the bitmap.
            val inset = size * 18 / 72
            for (layer in listOfNotNull(drawable.background, drawable.foreground)) {
                layer.setBounds(-inset, -inset, size + inset, size + inset)
                layer.draw(canvas)
            }
        } else {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        return bitmap
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
            // A media notification: the session token is what puts the item
            // and artwork on the lock screen and, over Bluetooth, a car's
            // display (#184). Both actions stay in the collapsed view.
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .also { style -> mediaSession?.let { style.setMediaSession(it.sessionToken) } }
                    .setShowActionsInCompactView(0, 1)
            )
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
        /** Now Playing artwork edge; head units downscale, none want more. */
        private const val ARTWORK_SIZE_PX = 512
        const val ACTION_START = "app.anothermorsetrainer.listen.START"
        const val ACTION_TOGGLE = "app.anothermorsetrainer.listen.TOGGLE"
        const val ACTION_STOP = "app.anothermorsetrainer.listen.STOP"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.listen_channel_name))
                .setDescription(context.getString(R.string.listen_channel_description))
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
