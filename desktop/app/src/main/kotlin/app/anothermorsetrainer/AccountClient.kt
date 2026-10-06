package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.Pkce
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncWire
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The accounts Worker's client (Phase 2 of the accounts work; the contract is
 * the accounts repo's README, sections 5, 7 and 8), desktop edition.
 *
 * Sign-in is the e-mailed link with PKCE: [startSignIn] sends the challenge
 * and keeps the verifier in memory, then [poll] every 2 s until the link is
 * confirmed. Every authenticated call that answers 401 refreshes the token
 * once and retries once; a refused refresh clears the tokens and reports
 * [AccountResult.SignedOut]. Refreshes are serialized, and the rotated refresh
 * token is stored before the new access token is used, because presenting a
 * spent refresh token signs the device out.
 *
 * The wire codec and the merge rules are pure (`morsekit/AccountSync.kt`,
 * `AccountSyncMerge.kt`); this file is only HTTP and the token file. The
 * [AccountTransport] and [AccountTokenStore] are interfaces so the JUnit suite
 * drives this class with canned replies and no network.
 */
class AccountClient(
    private val transport: AccountTransport,
    private val tokens: AccountTokenStore
) {
    /** A sign-in waiting for its link: the poll token and the verifier that proves this device asked. */
    private class PendingSignIn(val pollToken: String, val verifier: String)

    @Volatile private var pending: PendingSignIn? = null

    /** One refresh at a time (see the class note). */
    private val refreshLock = Mutex()

    /** True while tokens are stored. */
    val isSignedIn: Boolean get() = tokens.load() != null

    // ---- Sign-in ----

    /**
     * `POST /v1/auth/verify/start`. Omits `scopes`, so a first-party sign-in
     * gets all three. Returns the poll token; [poll] already holds it with
     * the verifier, which never leaves memory.
     */
    suspend fun startSignIn(email: String, deviceName: String?, platform: String?): AccountResult<String> {
        val verifier = Pkce.verifier()
        val body = JSONObject()
            .put("email", email.trim())
            .put("pkceChallenge", Pkce.challenge(verifier))
            .put("client", SyncWire.CLIENT)
        deviceName?.let { body.put("deviceName", it) }
        platform?.let { body.put("platform", it) }
        val reply = exchange(AccountRequest("POST", "/v1/auth/verify/start", body.toString(), null))
            ?: return networkFailure()
        val pollToken = reply.json?.optString("pollToken").orEmpty()
        if (reply.code != 202 || pollToken.isEmpty()) return failure(reply)
        pending = PendingSignIn(pollToken, verifier)
        return AccountResult.Ok(pollToken)
    }

    /** `POST /v1/auth/verify/poll`. On [PollResult.SignedIn] the tokens are already stored. */
    suspend fun poll(): PollResult {
        val p = pending ?: return PollResult.Expired
        val body = JSONObject().put("pollToken", p.pollToken).put("pkceVerifier", p.verifier)
        val reply = exchange(AccountRequest("POST", "/v1/auth/verify/poll", body.toString(), null))
            ?: return PollResult.Failed(null, "no reply", null)
        return when (reply.code) {
            202 -> PollResult.Pending
            201 -> {
                val t = reply.json?.let { parseTokens(it) }
                val account = reply.json?.optJSONObject("account")?.let { parseAccount(it) }
                if (t == null || account == null) return PollResult.Failed(201, "malformed sign-in reply", null)
                tokens.save(t)
                pending = null
                PollResult.SignedIn(t, account)
            }
            410 -> { pending = null; PollResult.Expired }
            else -> PollResult.Failed(reply.code, reply.errorMessage, reply.retryAfterSeconds)
        }
    }

    /** Forget a sign-in in progress (the user closed the sheet). */
    fun cancelSignIn() { pending = null }

    // ---- Tokens ----

    /**
     * `POST /v1/auth/token/refresh`, serialized. Returns Ok when there is a
     * fresh access token to use, SignedOut when the server refused the refresh
     * token (tokens cleared), or a failure to back off on when no answer came.
     */
    suspend fun refresh(): AccountResult<Unit> = refreshAfter(tokens.load()?.access)

    /**
     * Refresh unless another caller already did while this one waited: if
     * the stored access token is no longer [staleAccess], that newer one is
     * used and no second refresh is sent.
     */
    private suspend fun refreshAfter(staleAccess: String?): AccountResult<Unit> = refreshLock.withLock {
        val current = tokens.load() ?: return@withLock AccountResult.SignedOut
        if (staleAccess != null && current.access != staleAccess) return@withLock AccountResult.Ok(Unit)
        val body = JSONObject().put("refresh", current.refresh).toString()
        val reply = exchange(AccountRequest("POST", "/v1/auth/token/refresh", body, null))
            ?: return@withLock networkFailure()
        val fresh = if (reply.code == 200) reply.json?.let { parseTokens(it) } else null
        when {
            // Stored before anything uses the new access token.
            fresh != null -> { tokens.save(fresh); AccountResult.Ok(Unit) }
            // A lost connection, a rate limit or a server fault is not a refusal:
            // the refresh token was not spent, so keep it and back off.
            SyncMerge.retryAction(reply.code) == SyncMerge.Action.BACKOFF -> failure(reply)
            else -> { tokens.clear(); AccountResult.SignedOut }
        }
    }

    /** `POST /v1/auth/logout`. The tokens are cleared whatever the reply. */
    suspend fun logout(): AccountResult<Unit> {
        val r = authed("POST", "/v1/auth/logout", "{}") { }
        tokens.clear()
        pending = null
        return r
    }

    // ---- Account ----

    /** `GET /v1/auth/devices`: signed-in devices, oldest first. */
    suspend fun devices(): AccountResult<List<AccountDevice>> = authed("GET", "/v1/auth/devices", null) { json ->
        val arr = json?.optJSONArray("devices") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> runCatching { parseDevice(arr.getJSONObject(i)) }.getOrNull() }
    }

    /** `DELETE /v1/auth/devices/{id}`. */
    suspend fun revokeDevice(id: String): AccountResult<Unit> = authed("DELETE", "/v1/auth/devices/$id", null) { }

    /**
     * `PATCH /v1/me`. Both fields are sent: a value sets it, null clears it.
     * Returns the account as the server now has it.
     */
    suspend fun updateProfile(callsign: String?, displayName: String?): AccountResult<AccountInfo> {
        val body = JSONObject()
            .put("callsign", callsign ?: JSONObject.NULL)
            .put("displayName", displayName ?: JSONObject.NULL)
        return authed("PATCH", "/v1/me", body.toString()) { json ->
            json?.let { parseAccount(it) } ?: throw IOException("malformed /v1/me reply")
        }
    }

    /** `DELETE /v1/me`: the account and everything stored for it. Tokens are cleared on success. */
    suspend fun deleteAccount(): AccountResult<Unit> {
        val r = authed("DELETE", "/v1/me", null) { }
        if (r is AccountResult.Ok) tokens.clear()
        return r
    }

    // ---- Sync ----

    /** `POST /v1/sync/sessions`, at most [SyncMerge.Outbox.BATCH_SIZE] records. */
    suspend fun pushSessions(batch: List<SessionRecord>): AccountResult<SyncMerge.PushReply> {
        val arr = JSONArray()
        for (r in batch) arr.put(SyncWire.encodeSession(r))
        val body = JSONObject().put("sessions", arr).toString()
        return authed("POST", "/v1/sync/sessions", body) { json ->
            SyncMerge.PushReply.parse(json ?: throw IOException("malformed push reply"))
        }
    }

    /** One page of `GET /v1/sync/sessions`. Advance the cursor only after the rows are saved. */
    suspend fun pullSessions(since: Long, limit: Int = 200): AccountResult<PullPage> =
        authed("GET", "/v1/sync/sessions?since=$since&limit=$limit", null) { json ->
            val o = json ?: throw IOException("malformed pull reply")
            PullPage(
                sessions = SyncWire.decodeSessions(o.optJSONArray("sessions")),
                nextSince = o.optLong("nextSince", since),
                hasMore = o.optBoolean("hasMore", false)
            )
        }

    /** `POST /v1/sync/days`: this device's ledger. Returns the summed figure for each day sent. */
    suspend fun pushDays(days: Map<LocalDate, Int>): AccountResult<Map<LocalDate, Int>> =
        authed("POST", "/v1/sync/days", SyncWire.encodeDays(days).toString()) { json ->
            SyncWire.decodeDayMap(json?.optJSONObject("days"))
        }

    /** `PUT /v1/sync/state`. Returns the winning entry for every key sent. */
    suspend fun putState(entries: Map<String, SyncWire.StateEntry>): AccountResult<Map<String, SyncWire.StateEntry>> =
        authed("PUT", "/v1/sync/state", SyncWire.encodeState(entries).toString()) { json ->
            SyncWire.decodeState(json?.optJSONObject("entries"))
        }

    /** `GET /v1/sync/snapshot?today=…`: everything a fresh install needs. */
    suspend fun snapshot(today: LocalDate): AccountResult<SyncSnapshot> =
        authed("GET", "/v1/sync/snapshot?today=$today", null) { json ->
            val o = json ?: throw IOException("malformed snapshot")
            SyncSnapshot(
                stats = o.optJSONObject("stats"),
                sessions = SyncWire.decodeSessions(o.optJSONArray("sessions")),
                days = SyncWire.decodeDayMap(o.optJSONObject("days")),
                state = SyncWire.decodeState(o.optJSONObject("state")),
                seq = o.optLong("seq", 0)
            )
        }

    // ---- HTTP ----

    /**
     * One authenticated call: on 401, refresh once and retry once. A second
     * 401 straight after a successful refresh means the device itself is
     * revoked, so that signs out too. [parse] turns a 2xx body into the value;
     * if it throws, the reply counts as a failure to back off on.
     */
    private suspend fun <T> authed(
        method: String,
        path: String,
        body: String?,
        parse: (JSONObject?) -> T
    ): AccountResult<T> {
        val first = tokens.load() ?: return AccountResult.SignedOut
        var reply = exchange(AccountRequest(method, path, body, first.access)) ?: return networkFailure()
        if (reply.code == 401) {
            when (val r = refreshAfter(first.access)) {
                is AccountResult.Ok -> {}
                is AccountResult.Failed -> return r
                AccountResult.SignedOut -> return AccountResult.SignedOut
            }
            val again = tokens.load() ?: return AccountResult.SignedOut
            reply = exchange(AccountRequest(method, path, body, again.access)) ?: return networkFailure()
            if (reply.code == 401) {
                tokens.clear()
                return AccountResult.SignedOut
            }
        }
        if (reply.code !in 200..299) return failure(reply)
        return try {
            AccountResult.Ok(parse(reply.json))
        } catch (e: Exception) {
            AccountResult.Failed(reply.code, SyncMerge.Action.BACKOFF, e.message ?: "malformed reply")
        }
    }

    /** The transport's reply, or null when no HTTP reply arrived at all. */
    private suspend fun exchange(request: AccountRequest): ParsedReply? = try {
        val r = transport.send(request)
        ParsedReply(r.code, r.body?.let { t -> runCatching { JSONObject(t) }.getOrNull() }, r.retryAfterSeconds)
    } catch (e: IOException) {
        null
    }

    private class ParsedReply(val code: Int, val json: JSONObject?, val retryAfterSeconds: Long?) {
        /** The server's `message` (or `error`), or the bare status. */
        val errorMessage: String
            get() = json?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: json?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: "HTTP $code"
    }

    private fun failure(reply: ParsedReply): AccountResult.Failed =
        AccountResult.Failed(reply.code, SyncMerge.retryAction(reply.code), reply.errorMessage, reply.retryAfterSeconds)

    private fun networkFailure(): AccountResult.Failed =
        AccountResult.Failed(null, SyncMerge.retryAction(null), "no reply")

    private fun parseTokens(o: JSONObject): AccountTokens? {
        val access = o.optString("access")
        val refresh = o.optString("refresh")
        if (access.isEmpty() || refresh.isEmpty()) return null
        return AccountTokens(access, refresh)
    }

    private fun parseAccount(o: JSONObject): AccountInfo = AccountInfo(
        id = o.getString("id"),
        email = o.optStringOrNull("email"),
        callsign = o.optStringOrNull("callsign"),
        displayName = o.optStringOrNull("displayName")
    )

    private fun parseDevice(o: JSONObject): AccountDevice {
        val scopes = o.optJSONArray("scopes") ?: JSONArray()
        return AccountDevice(
            id = o.getString("id"),
            client = o.optString("client", ""),
            deviceName = o.optStringOrNull("deviceName"),
            platform = o.optStringOrNull("platform"),
            scopes = (0 until scopes.length()).map { scopes.getString(it) },
            createdAt = o.optLong("createdAt", 0),
            lastSeenAt = o.optLong("lastSeenAt", 0),
            current = o.optBoolean("current", false)
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    companion object {
        /** The accounts Worker. */
        const val BASE_URL = "https://amt-accounts.n9ho-amt.workers.dev"

        /** The app's client: OkHttp and the owner-only token file in [AppDirs.config]. */
        val shared: AccountClient by lazy {
            AccountClient(OkHttpAccountTransport, FileTokenStore(File(AppDirs.config, "account-tokens.json")))
        }

        /** `platform` on `verify/start` for the machine this runs on. */
        fun platformName(): String = when {
            AppDirs.isWindows -> "windows"
            AppDirs.isLinux -> "linux"
            else -> System.getProperty("os.name").orEmpty().lowercase().substringBefore(' ').ifEmpty { "desktop" }
        }
    }
}

// ---- Results ----

/** What an account call came to. */
sealed class AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>()

    /**
     * The server answered [status] (null: no reply at all); [action] is what
     * the sync engine does next, per [SyncMerge.retryAction].
     */
    data class Failed(
        val status: Int?,
        val action: SyncMerge.Action,
        val message: String,
        val retryAfterSeconds: Long? = null
    ) : AccountResult<Nothing>()

    /** There are no tokens, or the server refused them; any stored tokens are gone. */
    data object SignedOut : AccountResult<Nothing>()
}

/** One [AccountClient.poll]. */
sealed class PollResult {
    /** Not confirmed yet: poll again in 2 s. */
    data object Pending : PollResult()
    data class SignedIn(val tokens: AccountTokens, val account: AccountInfo) : PollResult()
    /** The link ran out or was already used (or no sign-in was started): start again. */
    data object Expired : PollResult()
    data class Failed(val status: Int?, val message: String, val retryAfterSeconds: Long?) : PollResult()
}

/** `account` of the sign-in reply and the `/v1/me` body. [email] only with the `account` scope. */
data class AccountInfo(val id: String, val email: String?, val callsign: String?, val displayName: String?)

/** One row of `GET /v1/auth/devices`. */
data class AccountDevice(
    val id: String,
    val client: String,
    val deviceName: String?,
    val platform: String?,
    val scopes: List<String>,
    val createdAt: Long,
    val lastSeenAt: Long,
    val current: Boolean
)

/** One page of `GET /v1/sync/sessions`. */
data class PullPage(val sessions: List<SessionRecord>, val nextSince: Long, val hasMore: Boolean)

/** `GET /v1/sync/snapshot`. [seq] is the pull cursor to store. */
data class SyncSnapshot(
    val stats: JSONObject?,
    val sessions: List<SessionRecord>,
    val days: Map<LocalDate, Int>,
    val state: Map<String, SyncWire.StateEntry>,
    val seq: Long
)

// ---- Transport ----

/** One request to the Worker: [path] is relative to [AccountClient.BASE_URL]; [bearer] the access token, if any. */
data class AccountRequest(val method: String, val path: String, val body: String?, val bearer: String?)

/** A reply: status, raw body (null when empty) and `Retry-After` in seconds. */
class AccountReply(val code: Int, val body: String?, val retryAfterSeconds: Long? = null)

/** How [AccountClient] reaches the Worker. Throws [IOException] when no HTTP reply arrived. */
interface AccountTransport {
    suspend fun send(request: AccountRequest): AccountReply
}

/** The app's transport: one OkHttp client, 10 s timeouts, `enqueue` (never `execute`), like [LeaderboardClient]. */
object OkHttpAccountTransport : AccountTransport {
    private const val TIMEOUT_SECONDS = 10L
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun send(request: AccountRequest): AccountReply {
        val builder = Request.Builder()
            .url(AccountClient.BASE_URL + request.path)
            .method(request.method, request.body?.toRequestBody(JSON))
        request.bearer?.let { builder.header("Authorization", "Bearer $it") }
        return suspendCancellableCoroutine { cont ->
            val c = http.newCall(builder.build())
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = response.use { r ->
                        val text = runCatching { r.body?.string() }.getOrNull()?.takeIf { it.isNotEmpty() }
                        AccountReply(r.code, text, r.header("Retry-After")?.trim()?.toLongOrNull())
                    }
                    if (cont.isActive) cont.resume(reply)
                }
            })
        }
    }
}

// ---- Tokens ----

/** The access token (15 minutes) and the refresh token (60 days, rotated on every use). */
data class AccountTokens(val access: String, val refresh: String)

/** Where [AccountClient] keeps its tokens. */
interface AccountTokenStore {
    fun load(): AccountTokens?
    fun save(tokens: AccountTokens)
    fun clear()
}

/**
 * The tokens in one JSON file that only the current user can read: created
 * `rw-------` where the file system is POSIX (Linux, the Flatpak sandbox),
 * and on Windows given an ACL whose only entry is the file's owner. Written
 * to a temporary file with those permissions first and renamed over the old
 * one, so the secret is never in a file anyone else can open and a crash
 * mid-write leaves the previous tokens. Account id, e-mail, cursor and outbox
 * are not secrets and do not live here.
 */
class FileTokenStore(private val file: File) : AccountTokenStore {

    @Synchronized override fun load(): AccountTokens? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        AccountTokens(o.getString("access"), o.getString("refresh"))
    }.getOrNull()

    @Synchronized override fun save(tokens: AccountTokens) {
        val text = JSONObject().put("access", tokens.access).put("refresh", tokens.refresh).toString()
        val target = file.toPath()
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = dir.resolve(file.name + ".tmp")
        Files.deleteIfExists(tmp)
        createOwnerOnly(tmp)
        Files.write(tmp, text.toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized override fun clear() {
        runCatching { Files.deleteIfExists(file.toPath()) }
    }

    private fun createOwnerOnly(path: Path) {
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            return
        }
        Files.createFile(path)
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        val ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(acl.owner)
            .setPermissions(
                EnumSet.of(
                    AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.WRITE_ATTRIBUTES,
                    AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.WRITE_NAMED_ATTRS,
                    AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL, AclEntryPermission.DELETE,
                    AclEntryPermission.SYNCHRONIZE
                )
            )
            .build()
        acl.acl = listOf(ownerOnly)
    }
}
