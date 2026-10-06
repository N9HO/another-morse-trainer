package app.anothermorsetrainer

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.PracticeStreak
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncRetry
import app.anothermorsetrainer.morsekit.SyncState
import app.anothermorsetrainer.morsekit.SyncThrottle
import app.anothermorsetrainer.morsekit.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/**
 * Account sync on this port: the app's [SyncEngine] over [AccountClient],
 * `Stats` and the five progress stores, with its own long-lived coroutine
 * scope like [LeaderboardClient]'s, so nothing here blocks a screen and a
 * sync started from one screen finishes after it closes. The iOS twin is the
 * `SyncCoordinator` in the app target.
 *
 * Signing in is optional and off until the user does it. While signed out the
 * hooks only grow the device's own day record. While signed in a finished
 * session, a practice day or a changed progress key is queued and pushed a
 * few seconds later; [onForeground] (every `onStart`, so launch too, at most
 * once every five minutes) pushes and pulls. A failure is silent and
 * retried after [SyncRetry.backoffSeconds].
 *
 * The Compose state below mirrors the engine's for the Settings screen.
 */
object SyncCoordinator {

    /** How often a waiting sign-in polls (the server allows one poll per 2 s). */
    private const val POLL_INTERVAL_MS = 2_500L
    private const val POLL_MAX_INTERVAL_MS = 30_000L
    /** The link works for 15 minutes; a sign-in that never hears back stops a minute after. */
    private const val SIGN_IN_TIMEOUT_MS = 16 * 60_000L
    /** A local change waits this long before it is pushed, so a burst goes in one sync. */
    private const val PUSH_DELAY_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: SyncEngine
    private lateinit var appContext: Context

    /**
     * True once [init] has run. `Stats.record` can fire from `ListenService`
     * in a process restarted without `MainActivity`; the hooks then do nothing.
     */
    val isInitialized: Boolean get() = this::engine.isInitialized

    /** The signed-in account, or null when signed out. */
    var account by mutableStateOf<AccountInfo?>(null)
        private set

    /** When a sync last finished cleanly (epoch ms). */
    var lastSynced by mutableStateOf<Long?>(null)
        private set

    /** "You were signed out. Sign in again to keep syncing." */
    var signedOutBanner by mutableStateOf(false)
        private set

    /** True while a sync runs (Sync now shows it). */
    var syncing by mutableStateOf(false)
        private set

    /** The account's signed-in devices as last fetched, or null before the first fetch. */
    var devices by mutableStateOf<List<AccountDevice>?>(null)
        private set

    /** Where a sign-in is. */
    sealed class SignIn {
        data object Idle : SignIn()
        data object Sending : SignIn()
        /** The link is mailed; polling until it is confirmed. */
        data class Waiting(val email: String) : SignIn()
        /** 410: the link ran out or was already used. */
        data object Expired : SignIn()
        /** The server refused (an invalid email, a 4xx) or there was no reply: one line. */
        data class Failed(val reason: String) : SignIn()
    }

    var signIn by mutableStateOf<SignIn>(SignIn.Idle)
        private set

    private var syncJob: Job? = null
    private var retryJob: Job? = null
    private var signInJob: Job? = null
    private var again = false
    private val throttle = SyncThrottle()

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        AccountClient.init(appContext)
        engine = SyncEngine(AccountClient.api, AccountClient.PrefsKeyValues, AppHost, ::mirror)
        mirror()
    }

    private fun mirror() {
        account = engine.account
        lastSynced = engine.lastSynced
        signedOutBanner = engine.signedOutBanner
    }

    // ---- Hooks (called where the app already records) ----

    /** `Stats.record`: the own-day record grows; while signed in the record is queued. */
    fun sessionRecorded(record: SessionRecord, day: LocalDate, seconds: Int) {
        if (!isInitialized) return
        engine.sessionRecorded(record, day, seconds)
        if (engine.isSignedIn) requestSync(delayMs = PUSH_DELAY_MS)
    }

    /** `Stats.recordPracticeDay`: the day joins the own record with +0; while signed in it is queued. */
    fun practiceDay(day: LocalDate) {
        if (!isInitialized) return
        val wasPending = day in engine.pendingDays
        engine.practiceDay(day)
        if (engine.isSignedIn && !wasPending) requestSync(delayMs = PUSH_DELAY_MS)
    }

    /** A synced store saved [key] ([SyncState.KEYS]). */
    fun stateChanged(key: String) {
        if (!isInitialized) return
        val wasPending = key in engine.pendingState
        engine.stateChanged(key)
        if (!wasPending && key in engine.pendingState) requestSync(delayMs = PUSH_DELAY_MS)
    }

    /**
     * `MainActivity.onStart`: launch and every return to the app. Resets the
     * backoff and syncs, at most once every five minutes ([SyncThrottle],
     * fixture `merge.foregroundThrottle`): a held-back return does nothing,
     * so a scheduled retry stands. Signed out, nothing moves the clock.
     */
    fun onForeground() {
        if (!isInitialized || !engine.isSignedIn) return
        if (!throttle.admit(SyncTrigger.FOREGROUND, SystemClock.elapsedRealtime())) return
        requestSync(resetBackoff = true)
    }

    /** The Sync now button. */
    fun syncNow() = requestSync(resetBackoff = true)

    private fun requestSync(resetBackoff: Boolean = false, delayMs: Long = 0L) {
        if (!isInitialized) return
        if (resetBackoff) {
            engine.resetBackoff()
            retryJob?.cancel()
            retryJob = null
        }
        if (syncJob?.isActive == true) {
            if (delayMs == 0L) again = true
            return
        }
        syncJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            var outcome: SyncOutcome
            syncing = true
            try {
                do {
                    again = false
                    outcome = engine.sync()
                } while (again && outcome == SyncOutcome.OK)
            } finally {
                syncing = false
            }
            if (outcome == SyncOutcome.FAILED) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        val waitMs = SyncRetry.backoffSeconds(engine.failures) * 1000L
        retryJob = scope.launch {
            delay(waitMs)
            requestSync()
        }
    }

    // ---- Sign-in ----

    /** Mail the link, then poll until it is confirmed, refused or runs out. */
    fun startSignIn(email: String) {
        if (!isInitialized) return
        signInJob?.cancel()
        val clean = email.trim()
        signIn = SignIn.Sending
        signInJob = scope.launch {
            val api = AccountClient.api
            val failure = api.startSignIn(clean, AccountClient.deviceName(appContext), AccountClient.PLATFORM)
            if (failure != null) {
                signIn = SignIn.Failed(failure)
                return@launch
            }
            signIn = SignIn.Waiting(clean)
            val deadline = SystemClock.elapsedRealtime() + SIGN_IN_TIMEOUT_MS
            var wait = POLL_INTERVAL_MS
            while (isActive) {
                delay(wait)
                if (SystemClock.elapsedRealtime() > deadline) {
                    api.cancelSignIn()
                    signIn = SignIn.Expired
                    return@launch
                }
                when (val result = api.poll()) {
                    PollResult.Pending -> wait = POLL_INTERVAL_MS
                    is PollResult.SignedIn -> {
                        signIn = SignIn.Idle
                        finishSignIn(result.account)
                        return@launch
                    }
                    PollResult.Expired -> {
                        signIn = SignIn.Expired
                        return@launch
                    }
                    is PollResult.Error -> {
                        val code = result.code
                        // No reply, rate limited or the server's trouble: keep polling, slower.
                        if (api.isSigningIn && (code == null || code == 429 || code >= 500)) {
                            wait = minOf(wait * 2, POLL_MAX_INTERVAL_MS)
                        } else {
                            api.cancelSignIn()
                            signIn = SignIn.Failed(result.reason ?: "HTTP $code")
                            return@launch
                        }
                    }
                }
            }
        }
    }

    /** Cancel (and Back while waiting): stop polling and forget the pending sign-in. */
    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        if (isInitialized) AccountClient.api.cancelSignIn()
        signIn = SignIn.Idle
    }

    /** First sign-in on this device: queue everything local, push it, then restore from the snapshot. */
    private fun finishSignIn(info: AccountInfo?) {
        retryJob?.cancel()
        val prior = syncJob
        syncJob = scope.launch {
            prior?.cancelAndJoin()
            syncing = true
            val outcome = try {
                engine.signedIn(info)
            } finally {
                syncing = false
            }
            if (outcome == SyncOutcome.FAILED) scheduleRetry()
        }
    }

    fun dismissBanner() {
        if (isInitialized) engine.dismissBanner()
    }

    // ---- Account actions (run in this scope, so leaving the screen does not cut them off) ----

    /** Sign out this device: tokens, cursor and queues go; every local record stays. */
    suspend fun signOut() {
        if (!isInitialized) return
        scope.async {
            retryJob?.cancel()
            syncJob?.cancelAndJoin()
            engine.signOut()
            devices = null
        }.await()
    }

    /** `DELETE /v1/me`. Null when the account is gone, else the line to show. */
    suspend fun deleteAccount(): String? {
        if (!isInitialized) return null
        return scope.async {
            retryJob?.cancel()
            syncJob?.cancelAndJoin()
            when (val result = engine.deleteAccount()) {
                is AccountResult.Reply ->
                    if (result.code in 200..299) {
                        devices = null
                        null
                    } else {
                        AccountApi.reasonOf(result.code, result.body)
                    }
                is AccountResult.SignedOut -> null
                is AccountResult.Offline -> result.message ?: "offline"
            }
        }.await()
    }

    /** `PATCH /v1/me`. Null on success, else the server's line. */
    suspend fun updateProfile(callsign: String?, displayName: String?): String? {
        if (!isInitialized) return null
        return scope.async { engine.updateProfile(callsign, displayName) }.await()
    }

    suspend fun refreshDevices() {
        if (!isInitialized || !engine.isSignedIn) return
        scope.async { engine.devices()?.let { devices = it } }.await()
    }

    /** Sign out one device; the current one is a full [signOut]. True when it is gone. */
    suspend fun revokeDevice(device: AccountDevice): Boolean {
        if (!isInitialized) return false
        if (device.current) {
            signOut()
            return true
        }
        return scope.async {
            val ok = engine.revokeDevice(device.id)
            if (ok) devices = devices?.filter { it.id != device.id }
            ok
        }.await()
    }

    // ---- The app's data, as the engine sees it ----

    private object AppHost : SyncHost {
        override fun history(): List<SessionRecord> = Stats.history
        override fun saveHistory(merged: List<SessionRecord>) = Stats.adoptSyncedHistory(merged)
        override fun ledger(): Map<LocalDate, Int> = Stats.activity.days
        override fun saveLedger(days: Map<LocalDate, Int>) = Stats.adoptServerDays(days)
        override fun totals(): SyncMerge.LifetimeTotals = Stats.lifetimeTotals()
        override fun saveTotals(totals: SyncMerge.LifetimeTotals) = Stats.adoptServerTotals(totals)
        override fun streak(): PracticeStreak = Stats.streakState()
        override fun saveStreak(streak: PracticeStreak) = Stats.adoptServerStreak(streak)

        /**
         * The key's wire value, or null for a store never saved on this
         * device, so a fresh install's defaults are not pushed over the
         * account's real progress (the snapshot fills them instead).
         */
        override fun stateValue(key: String): JSONObject? = when (key) {
            SyncState.JOURNEY -> if (JourneyStore.hasSaved) SyncState.journeyToWire(JourneyStore.load()) else null
            SyncState.CHARACTERS -> if (EngineStore.hasSaved) SyncState.charactersToWire(EngineStore.snapshot()) else null
            SyncState.FIRST_FOUR -> if (FirstFourStore.hasSaved) SyncState.firstFourToWire(FirstFourStore.progress) else null
            SyncState.OPERATING_PROCEDURE ->
                if (OperatingProcedureStore.hasSaved) SyncState.operatingProcedureToWire(OperatingProcedureStore.progress) else null
            SyncState.STORY_BOOKMARKS -> Settings.storyBookmarksRaw().takeIf { it.isNotEmpty() }?.let { SyncState.storyBookmarksToWire(it) }
            else -> null
        }

        override fun applyState(key: String, value: JSONObject) {
            when (key) {
                SyncState.JOURNEY -> JourneyStore.save(SyncState.journeyFromWire(value))
                SyncState.CHARACTERS -> EngineStore.applySynced(SyncState.applyCharacters(EngineStore.snapshot(), value))
                SyncState.FIRST_FOUR -> FirstFourStore.save(SyncState.firstFourFromWire(value))
                SyncState.OPERATING_PROCEDURE -> OperatingProcedureStore.save(SyncState.operatingProcedureFromWire(value))
                SyncState.STORY_BOOKMARKS -> Settings.replaceStoryBookmarks(SyncState.storyBookmarksFromWire(value))
            }
        }

        override fun today(): LocalDate = LocalDate.now()
        override fun now(): Long = System.currentTimeMillis()
    }
}
