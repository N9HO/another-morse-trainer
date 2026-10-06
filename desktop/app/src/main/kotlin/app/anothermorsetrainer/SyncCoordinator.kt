package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.AccountProfile
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncAccountState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.time.LocalDate

/**
 * Account sync for the app (Phase 2 of the accounts work): the one
 * [SyncEngine], the scope its network work runs in, the sign-in flow the
 * Account section drives, and the hooks the stores call. Like
 * [LeaderboardClient] it is a process-wide object with its own long-lived
 * scope on the UI dispatcher; every network call suspends on OkHttp's own
 * threads, so nothing here blocks a screen.
 *
 * Signing in is optional. Until someone does, the hooks only grow the
 * device's own per-day record and nothing touches the network.
 *
 * Pull: at launch ([init]), when the window regains focus ([onForeground]),
 * after sign-in and on Sync now. A session finished offline sits in the
 * outbox and goes with the next sync; a failure retries on the fixture's
 * backoff, reset on success and on focus.
 */
object SyncCoordinator {

    /** The sign-in flow on the Account section. */
    sealed class SignInPhase {
        data object Idle : SignInPhase()
        data object Sending : SignInPhase()
        /** The link is out; polling every 2 s until it is confirmed. */
        data class Waiting(val email: String) : SignInPhase()
        /** The link ran out (410): "Link expired. Send another." */
        data object Expired : SignInPhase()
        /** One plain line: a bad address, a 4xx's message, or no connection. */
        data class Error(val message: String) : SignInPhase()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: SyncEngine? = null

    private val emptyState = MutableStateFlow(SyncAccountState())

    /** The persisted account state (signed in or not, email, last synced, the banner). */
    val state: StateFlow<SyncAccountState> get() = engine?.state ?: emptyState

    private val _signIn = MutableStateFlow<SignInPhase>(SignInPhase.Idle)
    val signIn: StateFlow<SignInPhase> = _signIn.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    /** True while a sync is running (the Sync now row shows it). */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var syncJob: Job? = null
    private var retryJob: Job? = null
    private var pollJob: Job? = null
    @Volatile private var again = false

    /** In `main()`, after the stores. Pulls at once when signed in. */
    fun init() {
        if (engine != null) return
        engine = SyncEngine(AccountClient.shared, PrefsSyncStore(Prefs.open("amt_account")), AppSyncLocal)
        requestSync()
    }

    /** The window regained focus: the backoff starts over and a sync runs now. */
    fun onForeground() {
        val e = engine ?: return
        e.resetBackoff()
        retryJob?.cancel()
        requestSync()
    }

    // ---- Hooks (called by Stats and the stores after their own write) ----

    fun sessionRecorded(record: SessionRecord, day: LocalDate, seconds: Int) {
        val e = engine ?: return
        e.sessionRecorded(record, day, seconds)
        requestSync()
    }

    fun practiceDayMarked(day: LocalDate) {
        val e = engine ?: return
        e.practiceDayMarked(day)
        requestSync()
    }

    /**
     * A store saved a synced key. [before] and [after] are its wire values
     * (null: nothing saved); only a real change is stamped, so a save that
     * rewrites the same position (the Characters track saves after every
     * answer) sends nothing.
     */
    fun stateSaved(key: String, before: JSONObject?, after: JSONObject?) {
        val e = engine ?: return
        if (after == null || before?.toString() == after.toString()) return
        e.stateChanged(key)
        requestSync()
    }

    // ---- Sync ----

    /** Run a sync now, or once more after the one in flight. */
    fun requestSync() {
        val e = engine ?: return
        if (!e.state.value.isSignedIn) return
        if (syncJob?.isActive == true) { again = true; return }
        retryJob?.cancel()
        syncJob = scope.launch {
            _syncing.value = true
            var outcome: SyncEngine.Outcome
            do {
                again = false
                outcome = e.sync()
            } while (again && outcome == SyncEngine.Outcome.DONE)
            _syncing.value = false
            if (outcome == SyncEngine.Outcome.BACKOFF) scheduleRetry(e)
        }
    }

    private fun scheduleRetry(e: SyncEngine) {
        val seconds = e.retryDelaySeconds() ?: return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(seconds * 1000)
            requestSync()
        }
    }

    // ---- Sign-in ----

    /** Send the link to [rawEmail] and start polling. */
    fun startSignIn(rawEmail: String) {
        val e = engine ?: return
        val email = rawEmail.trim()
        if (!AccountProfile.isPlausibleEmail(email)) {
            _signIn.value = SignInPhase.Error(AppStrings.get(R.string.account_email_invalid))
            return
        }
        pollJob?.cancel()
        _signIn.value = SignInPhase.Sending
        pollJob = scope.launch {
            // The hostname lookup can wait on DNS, so not on the UI thread.
            val name = withContext(Dispatchers.IO) { deviceName() }
            when (val r = AccountClient.shared.startSignIn(email, name, AccountClient.platformName())) {
                is AccountResult.Ok -> {
                    _signIn.value = SignInPhase.Waiting(email)
                    poll(e)
                }
                is AccountResult.Failed -> _signIn.value = SignInPhase.Error(failureLine(r.status, r.message))
                AccountResult.SignedOut -> _signIn.value = SignInPhase.Error(failureLine(null, ""))
            }
        }
    }

    /** Every 2 s until confirmed, expired, refused, or 15 minutes have passed. */
    private suspend fun poll(e: SyncEngine) {
        val deadline = System.currentTimeMillis() + POLL_LIMIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            when (val p = AccountClient.shared.poll()) {
                PollResult.Pending -> {}
                is PollResult.SignedIn -> {
                    e.signedIn(p.account)
                    _signIn.value = SignInPhase.Idle
                    requestSync()
                    return
                }
                PollResult.Expired -> { _signIn.value = SignInPhase.Expired; return }
                is PollResult.Failed -> when {
                    p.status == null || p.status >= 500 -> {}           // keep polling through a blip
                    p.status == 429 -> delay((p.retryAfterSeconds ?: 2) * 1000)
                    else -> { _signIn.value = SignInPhase.Error(failureLine(p.status, p.message)); return }
                }
            }
        }
        AccountClient.shared.cancelSignIn()
        _signIn.value = SignInPhase.Expired
    }

    /** Cancel: polling stops and the sign-in in progress is forgotten. */
    fun cancelSignIn() {
        pollJob?.cancel()
        pollJob = null
        AccountClient.shared.cancelSignIn()
        _signIn.value = SignInPhase.Idle
    }

    fun dismissBanner() { engine?.dismissBanner() }

    // ---- Account actions (the Settings section awaits these) ----

    suspend fun signOut() {
        val e = engine ?: return
        cancelPending()
        e.signOut()
    }

    suspend fun deleteAccount(): AccountResult<Unit> {
        val e = engine ?: return AccountResult.SignedOut
        val r = e.deleteAccount()
        if (r !is AccountResult.Failed) cancelPending()
        return r
    }

    suspend fun updateProfile(callsign: String?, displayName: String?): AccountResult<AccountInfo> =
        engine?.updateProfile(callsign, displayName) ?: AccountResult.SignedOut

    suspend fun devices(): AccountResult<List<AccountDevice>> = engine?.devices() ?: AccountResult.SignedOut

    suspend fun revokeDevice(id: String): AccountResult<Unit> = engine?.revokeDevice(id) ?: AccountResult.SignedOut

    private fun cancelPending() {
        retryJob?.cancel()
        again = false
    }

    /** The one plain line for a failed call: the server's message for a 4xx, else a connection line. */
    fun failureLine(status: Int?, message: String): String =
        if (status != null && status in 400..499 && message.isNotBlank() && !message.startsWith("HTTP ")) message
        else if (status != null && status in 400..499) AppStrings.get(R.string.account_request_refused)
        else AppStrings.get(R.string.account_network_error)

    /** `deviceName` on `verify/start`: the machine's hostname, at most 64 printable characters. */
    private fun deviceName(): String? {
        val raw = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        return raw?.filter { !it.isISOControl() }?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
    }

    private const val POLL_INTERVAL_MS = 2_000L
    private const val POLL_LIMIT_MS = 15 * 60 * 1000L
}
