package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.Pkce
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.StateEntry
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncWire
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate

/** One HTTP reply: the status and the body, parsed as JSON where it is JSON. */
class AccountReply(val code: Int, val body: JSONObject?)

/**
 * How [AccountApi] reaches the Worker. The app's is OkHttp ([AccountClient]);
 * the tests inject canned replies, so no test touches the network. Throws
 * [IOException] when there is no HTTP reply at all.
 */
interface AccountTransport {
    suspend fun send(method: String, path: String, body: JSONObject?, bearer: String?): AccountReply
}

/**
 * Where the two tokens live: EncryptedSharedPreferences in the app, a map in
 * the tests. [saveRefresh] must have persisted the token when it returns,
 * because the server spends the old one the moment it rotates.
 */
interface AccountTokenStore {
    val access: String?
    val refresh: String?
    fun saveRefresh(token: String)
    fun saveAccess(token: String)
    fun clear()
}

/** The signed-in account, as the sign-in reply gives it. `email` is there only with the `account` scope. */
data class AccountInfo(val id: String, val email: String?, val callsign: String?, val displayName: String?) {
    companion object {
        fun parse(o: JSONObject?): AccountInfo? {
            if (o == null) return null
            val id = o.optString("id", "").takeIf { it.isNotEmpty() } ?: return null
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k, "").takeIf { it.isNotEmpty() }
            return AccountInfo(id, str("email"), str("callsign"), str("displayName"))
        }
    }
}

/** The outcome of an authenticated call. */
sealed class AccountResult {
    /** The server answered; [SyncRetry.action][app.anothermorsetrainer.morsekit.SyncRetry.action] says what [code] means. */
    data class Reply(val code: Int, val body: JSONObject?) : AccountResult()
    /** No usable tokens, or the refresh after a 401 failed: the tokens are cleared. */
    data object SignedOut : AccountResult()
    /** No HTTP reply at all. */
    data class Offline(val message: String?) : AccountResult()
}

/** What a sign-in poll found. */
sealed class PollResult {
    data object Pending : PollResult()
    data class SignedIn(val account: AccountInfo?) : PollResult()
    /** The 15 minutes ran out, or this sign-in already completed: start again. */
    data object Expired : PollResult()
    /** Anything else: no sign-in in progress, a bad verifier, rate limited, offline. */
    data class Error(val code: Int?, val reason: String?) : PollResult()
}

/**
 * The accounts Worker's API (its README §5, §7, §8), over an injectable
 * [AccountTransport] and [AccountTokenStore], with no Android types so the
 * JUnit suite can drive it. [AccountClient] is the app's instance.
 *
 * Every authenticated call sends the access token; on a 401 it refreshes once
 * and retries once, and when the refresh fails the tokens are cleared and the
 * call reports [AccountResult.SignedOut]. Refreshes are serialized: a refresh
 * token is single use, so two at once would sign the device out. The new
 * refresh token is stored before the new access token is used.
 *
 * The pending sign-in (poll token and PKCE verifier) is held in memory only.
 */
class AccountApi(
    private val transport: AccountTransport,
    private val tokens: AccountTokenStore,
    private val client: String
) {
    private val refreshLock = Mutex()
    private var pollToken: String? = null
    private var verifier: String? = null

    val isSignedIn: Boolean get() = tokens.refresh != null

    // ---- Sign-in ----

    /**
     * `POST /v1/auth/verify/start`: mails the link and keeps the poll token.
     * Scopes are omitted, which grants all three. Returns null on success or
     * the reason it failed.
     */
    suspend fun startSignIn(email: String, deviceName: String?, platform: String): String? {
        val v = Pkce.newVerifier()
        val body = JSONObject()
            .put("email", email.trim())
            .put("pkceChallenge", Pkce.challenge(v))
            .put("client", client)
            .put("platform", platform)
        if (!deviceName.isNullOrBlank()) body.put("deviceName", deviceName.take(64))
        val reply = try {
            transport.send("POST", "/v1/auth/verify/start", body, null)
        } catch (e: IOException) {
            return e.message ?: "network"
        }
        val token = reply.body?.optString("pollToken", "")?.takeIf { it.isNotEmpty() }
        if (reply.code != 202 || token == null) return reason(reply)
        pollToken = token
        verifier = v
        return null
    }

    /** `POST /v1/auth/verify/poll`, once. The caller waits at least 2 s between polls. */
    suspend fun poll(): PollResult {
        val token = pollToken ?: return PollResult.Error(null, "no sign-in in progress")
        val v = verifier ?: return PollResult.Error(null, "no sign-in in progress")
        val reply = try {
            transport.send("POST", "/v1/auth/verify/poll", JSONObject().put("pollToken", token).put("pkceVerifier", v), null)
        } catch (e: IOException) {
            return PollResult.Error(null, e.message)
        }
        return when (reply.code) {
            202 -> PollResult.Pending
            201 -> {
                val b = reply.body
                val access = b?.optString("access", "")?.takeIf { it.isNotEmpty() }
                val refresh = b?.optString("refresh", "")?.takeIf { it.isNotEmpty() }
                if (access == null || refresh == null) return PollResult.Error(201, "no tokens in the reply")
                tokens.saveRefresh(refresh)
                tokens.saveAccess(access)
                pollToken = null
                verifier = null
                PollResult.SignedIn(AccountInfo.parse(b.optJSONObject("account")))
            }
            410 -> {
                pollToken = null
                verifier = null
                PollResult.Expired
            }
            else -> PollResult.Error(reply.code, reason(reply))
        }
    }

    /** Forget a sign-in in progress (the user backed out). */
    fun cancelSignIn() {
        pollToken = null
        verifier = null
    }

    // ---- Tokens ----

    /**
     * `POST /v1/auth/token/refresh`. True when there is a fresh access token
     * to use. A 401 (any 4xx but 429) means the refresh token is dead: the
     * tokens are cleared. No reply, a 429 or a 5xx leaves them as they are
     * and returns false.
     *
     * [staleAccess] is the access token that just got a 401: if another
     * caller has already replaced it while this one waited for the lock,
     * there is nothing to do.
     */
    suspend fun refresh(staleAccess: String? = tokens.access): Boolean = refreshLock.withLock {
        val current = tokens.access
        if (current != null && current != staleAccess) return@withLock true
        val refresh = tokens.refresh ?: return@withLock false
        val reply = try {
            transport.send("POST", "/v1/auth/token/refresh", JSONObject().put("refresh", refresh), null)
        } catch (e: IOException) {
            return@withLock false
        }
        val b = reply.body
        val newAccess = b?.optString("access", "")?.takeIf { it.isNotEmpty() }
        val newRefresh = b?.optString("refresh", "")?.takeIf { it.isNotEmpty() }
        if (reply.code == 200 && newAccess != null && newRefresh != null) {
            tokens.saveRefresh(newRefresh)
            tokens.saveAccess(newAccess)
            true
        } else {
            // A 4xx is a dead refresh token (rotated, expired, revoked): signed
            // out. A 429 or a 5xx is the server's trouble; keep it and back off.
            if (reply.code in 400..499 && reply.code != 429) tokens.clear()
            false
        }
    }

    /**
     * An authenticated call: on a 401, one refresh and one retry. A failed
     * refresh clears the tokens (a dead refresh token) or leaves them (no
     * reply): either way the second 401 is not retried again.
     */
    suspend fun authed(method: String, path: String, body: JSONObject? = null): AccountResult {
        val access = tokens.access ?: if (tokens.refresh != null && refresh(null)) tokens.access else null
        if (access == null) {
            if (tokens.refresh == null) return AccountResult.SignedOut
            return AccountResult.Offline("refresh failed")
        }
        val first = try {
            transport.send(method, path, body, access)
        } catch (e: IOException) {
            return AccountResult.Offline(e.message)
        }
        if (first.code != 401) return AccountResult.Reply(first.code, first.body)
        if (!refresh(access)) {
            if (tokens.refresh == null) return AccountResult.SignedOut
            return AccountResult.Offline("refresh failed")
        }
        val retry = try {
            transport.send(method, path, body, tokens.access)
        } catch (e: IOException) {
            return AccountResult.Offline(e.message)
        }
        if (retry.code == 401) {
            tokens.clear()
            return AccountResult.SignedOut
        }
        return AccountResult.Reply(retry.code, retry.body)
    }

    // ---- Account (README §5, §8) ----

    /** `POST /v1/auth/logout`: this device only. The tokens go whatever the server says. */
    suspend fun logout(): AccountResult {
        val result = if (tokens.access != null) authed("POST", "/v1/auth/logout") else AccountResult.SignedOut
        tokens.clear()
        cancelSignIn()
        return result
    }

    /** `GET /v1/auth/devices`: `{ devices: [...] }`, oldest first. */
    suspend fun devices(): AccountResult = authed("GET", "/v1/auth/devices")

    /** `DELETE /v1/auth/devices/{id}`: 204, or 404 for an id that is not one of ours. */
    suspend fun revokeDevice(id: String): AccountResult = authed("DELETE", "/v1/auth/devices/${enc(id)}")

    /** `PATCH /v1/me`. A null field is sent as null, which clears it. */
    suspend fun updateProfile(callsign: String?, displayName: String?): AccountResult = authed(
        "PATCH", "/v1/me",
        JSONObject().put("callsign", callsign ?: JSONObject.NULL).put("displayName", displayName ?: JSONObject.NULL)
    )

    /** `DELETE /v1/me`. On success the tokens are gone with the account. */
    suspend fun deleteAccount(): AccountResult {
        val result = authed("DELETE", "/v1/me")
        if (result is AccountResult.Reply && result.code in 200..299) tokens.clear()
        return result
    }

    // ---- Sync (README §7) ----

    /** `POST /v1/sync/sessions` with up to 200 records. */
    suspend fun pushSessions(batch: List<SessionRecord>): AccountResult =
        authed("POST", "/v1/sync/sessions", SyncWire.sessionsBody(batch))

    /** `POST /v1/sync/sessions` with records already encoded (the outbox's payloads). */
    suspend fun pushEncodedSessions(batch: List<JSONObject>): AccountResult =
        authed("POST", "/v1/sync/sessions", JSONObject().put("sessions", JSONArray(batch)))

    /** `GET /v1/sync/sessions?since=<seq>&limit=<n>`. */
    suspend fun pullSessions(since: Long, limit: Int): AccountResult =
        authed("GET", "/v1/sync/sessions?since=$since&limit=$limit")

    /** `POST /v1/sync/days`: this device's ledger. */
    suspend fun pushDays(days: Map<LocalDate, Int>): AccountResult =
        authed("POST", "/v1/sync/days", SyncWire.encodeDays(days))

    /** `PUT /v1/sync/state`. */
    suspend fun putState(entries: Map<String, StateEntry>): AccountResult =
        authed("PUT", "/v1/sync/state", SyncMerge.stateBody(entries))

    /** `GET /v1/sync/snapshot?today=yyyy-mm-dd`: the restore after sign-in. */
    suspend fun snapshot(today: LocalDate): AccountResult = authed("GET", "/v1/sync/snapshot?today=$today")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun reason(reply: AccountReply): String {
        val b = reply.body
        val r = b?.optString("reason", "")?.takeIf { it.isNotEmpty() }
            ?: b?.optString("error", "")?.takeIf { it.isNotEmpty() }
        return r ?: "HTTP ${reply.code}"
    }
}
