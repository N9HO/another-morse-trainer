package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The device's own day record (`fixtures/sync-wire.json`, `merge.deviceDays`)
 * and the pure pieces of the sync engine's persisted state: the stored form,
 * sign-out, the outbox payloads, and the profile rules shown in Settings.
 */
class SyncAccountStateTest {

    private val deviceDays: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("sync-wire.json")
        assertNotNull("fixtures/sync-wire.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText()).getJSONObject("merge").getJSONObject("deviceDays")
    }

    private fun days(key: String) = SyncWire.decodeDayMap(deviceDays.getJSONObject(key))

    /** Seed, then the fixture's local sessions, as the hooks apply them. */
    private fun ownAfterSessions(): Map<LocalDate, Int> {
        var own = SyncOwnDays.seed(days("seedFromLedgerOnFirstSignIn"))
        val sessions = deviceDays.getJSONArray("localSessions")
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            own = SyncOwnDays.record(own, LocalDate.parse(s.getString("day")), s.getInt("seconds"))
        }
        return own
    }

    @Test
    fun `the own record is seeded from the ledger`() {
        assertEquals(days("ownBefore"), SyncOwnDays.seed(days("seedFromLedgerOnFirstSignIn")))
    }

    @Test
    fun `the own record grows with every local session`() {
        assertEquals(days("ownAfter"), ownAfterSessions())
    }

    @Test
    fun `the days push carries the own figures for the changed days only`() {
        val sessions = deviceDays.getJSONArray("localSessions")
        val changed = (0 until sessions.length()).map { LocalDate.parse(sessions.getJSONObject(it).getString("day")) }
        val body = SyncWire.encodeDays(SyncOwnDays.pushBody(ownAfterSessions(), changed))
        val expected = deviceDays.getJSONObject("expectedPushBody")
        assertTrue("expected: $expected\nactual:   $body", expected.similar(body))
    }

    @Test
    fun `adoption touches only the displayed ledger`() {
        val own = ownAfterSessions()
        // The displayed ledger held the same local figures before the reply.
        val displayed = SyncMerge.mergeLedger(own, days("serverReply"))
        assertEquals(days("expectedDisplayedAfterAdoption"), displayed.days)
        assertEquals(days("expectedOwnAfterAdoption"), own)
    }

    @Test
    fun `a practice-day mark adds the day at zero`() {
        val day = LocalDate.of(2026, 10, 5)
        assertEquals(mapOf(day to 0), SyncOwnDays.record(emptyMap(), day, 0))
        assertEquals(mapOf(day to 30), SyncOwnDays.record(mapOf(day to 30), day, 0))
    }

    @Test
    fun `the stored state round-trips`() {
        val s = SyncAccountState(
            accountId = "acct", email = "e@example.org", callsign = "W1AW", displayName = null,
            cursor = 42, lastSyncedAt = 1_791_240_000_000,
            dayOutbox = setOf(LocalDate.of(2026, 10, 4)), stateOutbox = setOf("journey"),
            ownDays = mapOf(LocalDate.of(2026, 10, 4) to 120), ownDaysSeeded = true,
            stateStamps = mapOf("journey" to 1_791_230_000_000), needsSnapshot = true, signedOutBanner = false
        ).enqueueSession("ABC", """{"id":"abc"}""")
        assertEquals(s, SyncAccountState.decode(s.encode().toString()))
        assertEquals(listOf("abc"), s.sessionOutbox.ids)
    }

    @Test
    fun `an unreadable stored state is a fresh one`() {
        assertEquals(SyncAccountState(), SyncAccountState.decode("not json"))
        assertEquals(SyncAccountState(), SyncAccountState.decode(null))
    }

    @Test
    fun `sign-out keeps the own record, its seed flag and the stamps`() {
        val day = LocalDate.of(2026, 10, 4)
        val s = SyncAccountState(
            accountId = "acct", email = "e@example.org", cursor = 9, lastSyncedAt = 1L,
            dayOutbox = setOf(day), stateOutbox = setOf("journey"), ownDays = mapOf(day to 60), ownDaysSeeded = true,
            stateStamps = mapOf("journey" to 5L), needsSnapshot = true
        ).enqueueSession("a", "{}")
        val out = s.signedOut(banner = true)
        assertFalse(out.isSignedIn)
        assertEquals(0L, out.cursor)
        assertEquals(null, out.lastSyncedAt)
        assertTrue(out.sessionOutbox.isEmpty && out.sessionPayloads.isEmpty() && out.dayOutbox.isEmpty() && out.stateOutbox.isEmpty())
        assertEquals(mapOf(day to 60), out.ownDays)
        assertTrue(out.ownDaysSeeded)
        assertEquals(mapOf("journey" to 5L), out.stateStamps)
        assertTrue(out.signedOutBanner)
    }

    @Test
    fun `the session outbox cap drops the oldest payload with its id`() {
        var s = SyncAccountState(sessionOutbox = SyncMerge.Outbox(max = 3))
        for (id in listOf("a", "b", "c", "d")) s = s.enqueueSession(id, "{\"id\":\"$id\"}")
        assertEquals(listOf("b", "c", "d"), s.sessionOutbox.ids)
        assertEquals(setOf("b", "c", "d"), s.sessionPayloads.keys)
        // Queuing an id already waiting keeps its place.
        s = s.enqueueSession("B", "{}")
        assertEquals(listOf("b", "c", "d"), s.sessionOutbox.ids)
    }

    @Test
    fun `profile rules match the accounts Worker`() {
        assertTrue(AccountProfile.isValidCallsign(" w1aw/p "))
        assertEquals("W1AW/P", AccountProfile.normalizeCallsign(" w1aw/p "))
        assertTrue("empty clears", AccountProfile.isValidCallsign(""))
        assertFalse(AccountProfile.isValidCallsign("W1"))
        assertFalse(AccountProfile.isValidCallsign("W1AW-P"))
        assertFalse(AccountProfile.isValidCallsign("A".repeat(17)))
        assertTrue(AccountProfile.isValidDisplayName("Hiram"))
        assertTrue(AccountProfile.isValidDisplayName("Jo"))
        assertFalse(AccountProfile.isValidDisplayName("J"))
        assertFalse(AccountProfile.isValidDisplayName("x".repeat(25)))
        assertFalse(AccountProfile.isValidDisplayName("tab\there"))
        assertTrue(AccountProfile.isPlausibleEmail(" learner@example.org "))
        assertFalse(AccountProfile.isPlausibleEmail("learner"))
        assertFalse(AccountProfile.isPlausibleEmail("a@b"))
        assertFalse(AccountProfile.isPlausibleEmail("a b@example.org"))
    }

    @Test
    fun `the server streak is adopted without lowering the local longest`() {
        val d4 = LocalDate.of(2026, 10, 4)
        val d5 = LocalDate.of(2026, 10, 5)
        val server = SyncStreak.parse(JSONObject().put("streak", JSONObject().put("current", 12).put("longest", 20).put("lastPractisedDay", "2026-10-05")))!!
        assertEquals(SyncStreak.Server(12, 20, d5), server)
        // A fresh install (0) takes the account's run.
        assertEquals(PracticeStreak(12, 20, d5), SyncStreak.adopt(PracticeStreak(), server))
        // A longer local longest is kept.
        assertEquals(PracticeStreak(12, 50, d5), SyncStreak.adopt(PracticeStreak(2, 50, d4), server))
        // A local day the server has not heard of yet keeps the local run.
        val ahead = SyncStreak.adopt(PracticeStreak(13, 13, LocalDate.of(2026, 10, 6)), server)
        assertEquals(PracticeStreak(13, 20, LocalDate.of(2026, 10, 6)), ahead)
        assertEquals(null, SyncStreak.parse(JSONObject()))
    }

    @Test
    fun `relative times floor to the unit`() {
        assertEquals(SyncTime.Span.JUST_NOW to 0L, SyncTime.ago(60_000, 1_000))
        assertEquals(SyncTime.Span.MINUTES to 1L, SyncTime.ago(61_000, 0))
        assertEquals(SyncTime.Span.HOURS to 2L, SyncTime.ago(2 * 3_600_000L + 5, 0))
        assertEquals(SyncTime.Span.DAYS to 3L, SyncTime.ago(3 * 86_400_000L, 0))
    }
}
