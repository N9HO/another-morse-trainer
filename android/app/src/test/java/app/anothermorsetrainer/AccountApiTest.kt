package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.Pkce
import app.anothermorsetrainer.morsekit.SyncOutbox
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * [AccountApi] against a fake transport: canned replies, no network. Pins the
 * token rules the accounts README sets (§5): a 401 refreshes once and retries
 * once, a refresh that fails signs the device out, the new refresh token is
 * stored before the new access token is used, and two callers never refresh
 * at once. Also drains an outbox against a canned push reply.
 */
class AccountApiTest {

    /** Replies queued per path (query string dropped), and every call it saw. */
    private class FakeTransport : AccountTransport {
        val replies = HashMap<String, ArrayDeque<() -> AccountReply>>()
        val calls = ArrayList<Triple<String, String, String?>>()   // method, path, bearer
        val bodies = ArrayList<JSONObject?>()

        fun on(path: String, code: Int, body: JSONObject? = null) {
            replies.getOrPut(path) { ArrayDeque() }.addLast { AccountReply(code, body) }
        }

        fun offline(path: String) {
            replies.getOrPut(path) { ArrayDeque() }.addLast { throw IOException("offline") }
        }

        override suspend fun send(method: String, path: String, body: JSONObject?, bearer: String?): AccountReply {
            calls.add(Triple(method, path, bearer))
            bodies.add(body)
            yield()   // a real call suspends; lets concurrent callers interleave
            val key = path.substringBefore('?')
            val next = replies[key]?.removeFirstOrNull() ?: error("no reply queued for $method $key")
            return next()
        }

        fun count(path: String) = calls.count { it.second.substringBefore('?') == path }
    }

    /** Records the order tokens were written in. */
    private class MemoryTokens(override var access: String? = null, override var refresh: String? = null) : AccountTokenStore {
        val writes = ArrayList<String>()
        override fun saveRefresh(token: String) { writes.add("refresh=$token"); refresh = token }
        override fun saveAccess(token: String) { writes.add("access=$token"); access = token }
        override fun clear() { writes.add("clear"); access = null; refresh = null }
    }

    private fun tokens(access: String, refresh: String) = JSONObject().put("access", access).put("refresh", refresh).put("expiresIn", 900)

    @Test
    fun `a 401 refreshes once and retries once with the new token`() = runBlocking {
        val t = FakeTransport()
        val store = MemoryTokens("a1", "r1")
        t.on("/v1/auth/devices", 401)
        t.on("/v1/auth/token/refresh", 200, tokens("a2", "r2"))
        t.on("/v1/auth/devices", 200, JSONObject().put("devices", JSONArray()))
        val api = AccountApi(t, store, "amt-android")

        val result = api.devices()

        assertEquals(200, (result as AccountResult.Reply).code)
        assertEquals(listOf("a1", null, "a2"), t.calls.map { it.third })
        assertEquals("r1", t.bodies[1]!!.getString("refresh"))
        // The rotated refresh token is on disk before the new access token is used.
        assertEquals(listOf("refresh=r2", "access=a2"), store.writes)
    }

    @Test
    fun `a refused refresh signs out and clears the tokens`() = runBlocking {
        val t = FakeTransport()
        val store = MemoryTokens("a1", "r1")
        t.on("/v1/sync/snapshot", 401)
        t.on("/v1/auth/token/refresh", 401)
        val api = AccountApi(t, store, "amt-android")

        assertEquals(AccountResult.SignedOut, api.snapshot(LocalDate.of(2026, 10, 5)))
        assertNull(store.access)
        assertNull(store.refresh)
        assertEquals(1, t.count("/v1/sync/snapshot"))
    }

    @Test
    fun `a refresh with no reply keeps the tokens and reports offline`() = runBlocking {
        val t = FakeTransport()
        val store = MemoryTokens("a1", "r1")
        t.on("/v1/auth/devices", 401)
        t.offline("/v1/auth/token/refresh")
        val api = AccountApi(t, store, "amt-android")

        assertTrue(api.devices() is AccountResult.Offline)
        assertEquals("r1", store.refresh)
    }

    @Test
    fun `a second 401 after a good refresh is not retried again`() = runBlocking {
        val t = FakeTransport()
        val store = MemoryTokens("a1", "r1")
        t.on("/v1/auth/devices", 401)
        t.on("/v1/auth/token/refresh", 200, tokens("a2", "r2"))
        t.on("/v1/auth/devices", 401)
        val api = AccountApi(t, store, "amt-android")

        assertEquals(AccountResult.SignedOut, api.devices())
        assertEquals(2, t.count("/v1/auth/devices"))
        assertEquals(1, t.count("/v1/auth/token/refresh"))
    }

    @Test
    fun `two callers hitting 401 together refresh only once`() = runBlocking {
        val t = FakeTransport()
        val store = MemoryTokens("a1", "r1")
        t.on("/v1/auth/devices", 401)
        t.on("/v1/auth/devices", 401)
        t.on("/v1/auth/token/refresh", 200, tokens("a2", "r2"))
        t.on("/v1/auth/devices", 200)
        t.on("/v1/auth/devices", 200)
        val api = AccountApi(t, store, "amt-android")

        val results = listOf(async { api.devices() }, async { api.devices() }).awaitAll()

        assertTrue(results.all { it is AccountResult.Reply && it.code == 200 })
        assertEquals(1, t.count("/v1/auth/token/refresh"))
    }

    @Test
    fun `sign-in starts with a PKCE challenge and polls with the verifier`() = runBlocking {
        val t = FakeTransport()
        val store = MemoryTokens()
        t.on("/v1/auth/verify/start", 202, JSONObject().put("pollToken", "p1"))
        t.on("/v1/auth/verify/poll", 202)
        val account = JSONObject().put("id", "acc-1").put("email", "learner@example.org")
            .put("callsign", JSONObject.NULL).put("displayName", JSONObject.NULL)
        t.on("/v1/auth/verify/poll", 201, tokens("a1", "r1").put("account", account))
        val api = AccountApi(t, store, "amt-android")

        assertNull(api.startSignIn(" learner@example.org ", "Pixel 9", "android"))
        val start = t.bodies[0]!!
        assertEquals("amt-android", start.getString("client"))
        assertTrue("scopes omitted: all three", !start.has("scopes"))
        assertEquals(43, start.getString("pkceChallenge").length)

        assertEquals(PollResult.Pending, api.poll())
        val signedIn = api.poll() as PollResult.SignedIn
        assertEquals(AccountInfo("acc-1", "learner@example.org", null, null), signedIn.account)
        val poll = t.bodies[1]!!
        assertEquals("p1", poll.getString("pollToken"))
        assertEquals(start.getString("pkceChallenge"), Pkce.challenge(poll.getString("pkceVerifier")))
        assertEquals("r1", store.refresh)
        assertEquals("a1", store.access)
    }

    @Test
    fun `an outbox drains against a canned push reply`() = runBlocking {
        val t = FakeTransport()
        val store = MemoryTokens("a1", "r1")
        val reply = JSONObject("""{"accepted":["s1"],"skipped":["s2"],"rejected":[{"id":"s3","reason":"need attempts >= correct >= 0"}]}""")
        t.on("/v1/sync/sessions", 200, reply)
        val api = AccountApi(t, store, "amt-android")
        var box = SyncOutbox()
        for (id in listOf("s1", "s2", "s3", "s4")) box = box.enqueue(SyncOutbox.Entry(id, JSONObject().put("id", id).toString()))

        val result = api.pushEncodedSessions(box.nextBatch().map { JSONObject(it.payload) }) as AccountResult.Reply
        assertEquals(200, result.code)
        assertEquals(4, t.bodies[0]!!.getJSONArray("sessions").length())
        box = box.applyPushReply(result.body!!)

        assertEquals(listOf("s4"), box.ids)
    }
}
