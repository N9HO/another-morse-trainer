package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncMerge
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

/**
 * [AccountClient] against canned replies: no test here touches the network.
 * Pins the token rules from the accounts README §5 (refresh once on 401 and
 * retry once; a refused refresh signs out; refreshes never overlap) and an
 * outbox drained by a real push reply.
 */
class AccountClientTest {

    /** Answers each request with [reply] and records what was sent. */
    private class FakeTransport(val reply: suspend (AccountRequest) -> AccountReply) : AccountTransport {
        val sent = ArrayList<AccountRequest>()
        override suspend fun send(request: AccountRequest): AccountReply {
            sent.add(request)
            return reply(request)
        }
    }

    private class MemoryTokens(var tokens: AccountTokens?) : AccountTokenStore {
        override fun load() = tokens
        override fun save(tokens: AccountTokens) { this.tokens = tokens }
        override fun clear() { tokens = null }
    }

    private fun tokensReply(access: String, refresh: String) =
        AccountReply(200, JSONObject().put("access", access).put("refresh", refresh).put("expiresIn", 900).toString())

    private val noDevices = AccountReply(200, JSONObject().put("devices", JSONArray()).toString())
    private val unauthorized = AccountReply(401, """{"error":"unauthorized","message":"token expired"}""")

    @Test
    fun `a 401 refreshes once, stores the rotated tokens and retries once`() = runBlocking<Unit> {
        val store = MemoryTokens(AccountTokens("A1", "R1"))
        val transport = FakeTransport { r ->
            when {
                r.path == "/v1/auth/token/refresh" -> tokensReply("A2", "R2")
                r.bearer == "A1" -> unauthorized
                else -> noDevices
            }
        }
        val result = AccountClient(transport, store).devices()
        assertEquals(AccountResult.Ok(emptyList<AccountDevice>()), result)
        assertEquals(AccountTokens("A2", "R9"), store.tokens)
        assertEquals(listOf("/v1/auth/devices", "/v1/auth/token/refresh", "/v1/auth/devices"), transport.sent.map { it.path })
        assertEquals(listOf("A1", null, "A2"), transport.sent.map { it.bearer })
        assertEquals("R1", JSONObject(transport.sent[1].body!!).getString("refresh"))
    }

    @Test
    fun `a refused refresh clears the tokens and reports signed out`() = runBlocking<Unit> {
        val store = MemoryTokens(AccountTokens("A1", "R1"))
        val transport = FakeTransport { unauthorized }
        val result = AccountClient(transport, store).devices()
        assertEquals(AccountResult.SignedOut, result)
        assertNull(store.tokens)
        assertEquals("no retry after a refused refresh", 2, transport.sent.size)
    }

    @Test
    fun `a refresh with no reply keeps the tokens and backs off`() = runBlocking<Unit> {
        val store = MemoryTokens(AccountTokens("A1", "R1"))
        val transport = FakeTransport { r ->
            if (r.path == "/v1/auth/token/refresh") throw IOException("offline") else unauthorized
        }
        val result = AccountClient(transport, store).devices()
        assertTrue(result is AccountResult.Failed && result.action == SyncMerge.Action.BACKOFF)
        assertEquals(AccountTokens("A1", "R1"), store.tokens)
    }

    @Test
    fun `two calls that both hit a 401 share one refresh`() = runBlocking<Unit> {
        val store = MemoryTokens(AccountTokens("A1", "R1"))
        var refreshes = 0
        val transport = FakeTransport { r ->
            yield()   // let the other call interleave
            when {
                r.path == "/v1/auth/token/refresh" -> {
                    refreshes++
                    // A second refresh would present R1 again: a reuse, which signs the device out.
                    if (JSONObject(r.body!!).getString("refresh") == "R1" && refreshes == 1) tokensReply("A2", "R2") else unauthorized
                }
                r.bearer == "A1" -> unauthorized
                else -> noDevices
            }
        }
        val client = AccountClient(transport, store)
        val a = async { client.devices() }
        val b = async { client.devices() }
        assertTrue(a.await() is AccountResult.Ok)
        assertTrue(b.await() is AccountResult.Ok)
        assertEquals(1, refreshes)
        assertEquals(AccountTokens("A2", "R2"), store.tokens)
    }

    @Test
    fun `no tokens is signed out without a request`() = runBlocking<Unit> {
        val transport = FakeTransport { noDevices }
        assertEquals(AccountResult.SignedOut, AccountClient(transport, MemoryTokens(null)).devices())
        assertTrue(transport.sent.isEmpty())
    }

    private fun record() = SessionRecord(
        id = UUID.randomUUID(), date = Instant.ofEpochMilli(1_791_230_000_000), mode = "Rapid Fire",
        characterWPM = 20, effectiveWPM = 15, attempts = 10, correct = 9,
        fastestTTR = 0.3, medianTTR = 0.5, durationSeconds = 60.0,
        characters = emptyList(), activeCharacters = emptyList(), score = 9
    )

    @Test
    fun `an outbox drains what a push reply accepts, skips or rejects`() = runBlocking<Unit> {
        val records = List(4) { record() }
        val ids = records.map { it.id.toString() }
        val canned = JSONObject()
            .put("accepted", JSONArray().put(ids[0]))
            .put("skipped", JSONArray().put(ids[1]))
            .put("rejected", JSONArray().put(JSONObject().put("id", ids[2]).put("reason", "need attempts >= correct >= 0")))
            .put("stats", JSONObject().put("totals", JSONObject().put("sessions", 2).put("answered", 20).put("correct", 18).put("practiceSeconds", 120.0)).put("bestTtrMs", 300).put("personalBests", JSONObject().put("rapidFire", 9)))
        val transport = FakeTransport { AccountReply(200, canned.toString()) }
        var outbox = SyncMerge.Outbox()
        for (id in ids) outbox = outbox.enqueue(id)

        val result = AccountClient(transport, MemoryTokens(AccountTokens("A1", "R1"))).pushSessions(records)
        val reply = (result as AccountResult.Ok).value
        assertEquals(listOf(ids[3]), outbox.afterPush(reply).ids)

        val sent = JSONObject(transport.sent.single().body!!).getJSONArray("sessions")
        assertEquals(4, sent.length())
        assertEquals("rapidFire", sent.getJSONObject(0).getString("mode"))
        assertEquals("POST", transport.sent.single().method)
        assertEquals("/v1/sync/sessions", transport.sent.single().path)
        assertEquals(mapOf("Rapid Fire" to 9), SyncMerge.adoptAggregates(reply.stats)!!.bestScores)
    }

    @Test
    fun `sign-in sends the challenge and polls with the verifier`() = runBlocking<Unit> {
        val store = MemoryTokens(null)
        val transport = FakeTransport { r ->
            when (r.path) {
                "/v1/auth/verify/start" -> AccountReply(202, """{"pollToken":"P1"}""")
                else -> AccountReply(
                    201,
                    JSONObject().put("access", "A1").put("refresh", "R1").put("expiresIn", 900)
                        .put("account", JSONObject().put("id", "acct").put("email", "e@example.org").put("callsign", JSONObject.NULL).put("displayName", JSONObject.NULL))
                        .toString()
                )
            }
        }
        val client = AccountClient(transport, store)
        assertEquals(AccountResult.Ok("P1"), client.startSignIn("e@example.org", "Test PC", "linux"))
        val start = JSONObject(transport.sent[0].body!!)
        assertEquals("amt-desktop", start.getString("client"))
        assertTrue("scopes omitted", !start.has("scopes"))
        val polled = client.poll()
        assertTrue(polled is PollResult.SignedIn && polled.account == AccountInfo("acct", "e@example.org", null, null))
        val poll = JSONObject(transport.sent[1].body!!)
        assertEquals("P1", poll.getString("pollToken"))
        assertEquals(start.getString("pkceChallenge"), app.anothermorsetrainer.morsekit.Pkce.challenge(poll.getString("pkceVerifier")))
        assertEquals(AccountTokens("A1", "R1"), store.tokens)
    }

    @Test
    fun `the token file is owner-only and round-trips`() {
        val dir = Files.createTempDirectory("amt-tokens")
        val file = dir.resolve("account-tokens.json").toFile()
        val store = FileTokenStore(file)
        assertNull(store.load())
        store.save(AccountTokens("A1", "R1"))
        store.save(AccountTokens("A2", "R2"))
        assertEquals(AccountTokens("A2", "R2"), store.load())
        if ("posix" in file.toPath().fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        }
        store.clear()
        assertNull(store.load())
        dir.toFile().deleteRecursively()
    }
}
