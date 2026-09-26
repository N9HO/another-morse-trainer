package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The pure half of the buddy-streak client, held to the server contract in
 * the leaderboard repo's README and `src/buddy.ts`. Expected values come from
 * the contract (the day regex, the invite alphabet, the status shape), not
 * from running this code. The iOS twin holds the same rules.
 */
class BuddyTest {

    @Test
    fun `day labels are yyyy-mm-dd, zero padded, ASCII`() {
        assertEquals("2026-09-11", Buddy.dayLabel(LocalDate.of(2026, 9, 11)))
        assertEquals("2026-01-05", Buddy.dayLabel(LocalDate.of(2026, 1, 5)))
        assertEquals("2026-12-31", Buddy.dayLabel(LocalDate.of(2026, 12, 31)))
        assertEquals("2000-02-29", Buddy.dayLabel(LocalDate.of(2000, 2, 29)))
        assertTrue(Buddy.isDay(Buddy.today()))
    }

    @Test
    fun `isDay is the server's shape check`() {
        assertTrue(Buddy.isDay("2026-09-11"))
        assertFalse(Buddy.isDay("2026-9-11"))
        assertFalse(Buddy.isDay("11/09/2026"))
        assertFalse(Buddy.isDay("2026-09-11T00:00:00Z"))
        assertFalse(Buddy.isDay(""))
        assertFalse(Buddy.isDay(null))
    }

    @Test
    fun `invite codes normalise like the server`() {
        assertEquals("ABC234", Buddy.normalizeInviteCode("abc234"))
        assertEquals("ABC234", Buddy.normalizeInviteCode(" abc-234 "))
        assertEquals("ABC234", Buddy.normalizeInviteCode("ABC 234"))
        assertEquals("XYZ789", Buddy.normalizeInviteCode("x y z 7 8 9"))
        assertNull("too short", Buddy.normalizeInviteCode("ABC23"))
        assertNull("too long", Buddy.normalizeInviteCode("ABC2345"))
        assertNull("zero is not in the alphabet", Buddy.normalizeInviteCode("ABC230"))
        assertNull("one is not in the alphabet", Buddy.normalizeInviteCode("ABC231"))
        assertNull("I is not in the alphabet", Buddy.normalizeInviteCode("ABCI23"))
        assertNull("L is not in the alphabet", Buddy.normalizeInviteCode("ABCL23"))
        assertNull("O is not in the alphabet", Buddy.normalizeInviteCode("ABCO23"))
        assertNull("punctuation", Buddy.normalizeInviteCode("ABC.23"))
        assertNull("empty", Buddy.normalizeInviteCode(""))
        assertNull("null", Buddy.normalizeInviteCode(null))
        assertEquals(6, Buddy.INVITE_LENGTH)
        assertEquals("ABCDEFGHJKMNPQRSTUVWXYZ23456789", Buddy.INVITE_ALPHABET)
        // Every alphabet character is accepted; nothing outside it is.
        for (ch in Buddy.INVITE_ALPHABET) assertNotNull("$ch", Buddy.normalizeInviteCode("$ch$ch$ch$ch$ch$ch"))
        for (ch in "01ILO") assertNull("$ch", Buddy.normalizeInviteCode("AAAAA$ch"))
    }

    @Test
    fun `a v1 paired status parses as a list of one`() {
        val json = JSONObject(
            """{"paired":true,"buddy":{"displayName":"W1AW","practisedToday":true},
               "streak":12,"myStreak":30,"practisedToday":false,"today":"2026-09-11"}"""
        )
        val s = Buddy.parseStatus(json, fetchedAt = 1_000L)
        assertNotNull(s)
        s!!
        assertTrue(s.paired)
        assertEquals(listOf(BuddyEntry("", "W1AW", true, 12)), s.buddies)
        assertEquals(1, s.maxBuddies)
        assertTrue("a one-buddy Worker's list is full once paired", s.isFull)
        assertEquals(30, s.myStreak)
        assertFalse(s.practisedToday)
        assertEquals("2026-09-11", s.today)
        assertEquals(1_000L, s.fetchedAt)
        assertTrue(s.isFor("2026-09-11"))
        assertFalse(s.isFor("2026-09-12"))
        assertTrue(s.practisedOn(s.buddies[0], "2026-09-11"))
        assertFalse("yesterday's flag says nothing about today", s.practisedOn(s.buddies[0], "2026-09-12"))
    }

    @Test
    fun `a v2 list parses whole, with the join's pairing id`() {
        val s = Buddy.parseStatus(
            JSONObject(
                """{"buddies":[{"id":"p-1","displayName":"W1AW","practisedToday":true,"streak":3},
                   {"id":"p-2","displayName":"K1ABC","practisedToday":false,"streak":0}],
                   "maxBuddies":10,"myStreak":4,"practisedToday":true,"today":"2026-09-11","joined":"p-2"}"""
            ),
            fetchedAt = 5L
        )!!
        assertEquals(2, s.buddies.size)
        assertEquals("p-2", s.joined)
        assertEquals(10, s.maxBuddies)
        assertFalse(s.isFull)
    }

    @Test
    fun `an unpaired status has no buddies`() {
        val s = Buddy.parseStatus(
            JSONObject("""{"paired":false,"streak":0,"myStreak":3,"practisedToday":true,"today":"2026-09-11"}"""),
            fetchedAt = 5L
        )
        assertNotNull(s)
        s!!
        assertFalse(s.paired)
        assertTrue(s.buddies.isEmpty())
        assertEquals(3, s.myStreak)
        assertTrue(s.practisedToday)
    }

    @Test
    fun `the parse is tolerant of missing and mistyped fields`() {
        // paired without a buddy object: not paired, whatever the flag says.
        val noBuddy = Buddy.parseStatus(JSONObject("""{"paired":true,"streak":4}"""), 0L)
        assertNotNull(noBuddy)
        assertFalse(noBuddy!!.paired)
        // A nameless buddy is the same.
        val blankName = Buddy.parseStatus(JSONObject("""{"paired":true,"buddy":{"displayName":"  "}}"""), 0L)
        assertFalse(blankName!!.paired)
        // Mistyped numbers and flags take their zero value.
        val junk = Buddy.parseStatus(
            JSONObject("""{"paired":"yes","buddy":{"displayName":"W1AW","practisedToday":"maybe"},"streak":"lots","myStreak":-2,"today":"soon"}"""),
            0L
        )
        assertNotNull(junk)
        junk!!
        assertFalse(junk.paired)
        assertEquals(0, junk.myStreak)
        assertEquals("", junk.today)
        assertFalse(junk.isFor(""))   // an unknown day is nobody's day
        // A negative streak is clamped.
        val negative = Buddy.parseStatus(
            JSONObject("""{"paired":true,"buddy":{"displayName":"W1AW"},"streak":-5,"today":"2026-09-11"}"""),
            0L
        )
        assertEquals(0, negative!!.buddies[0].streak)
        assertFalse(negative.buddies[0].practisedToday)
        // Not a status at all.
        assertNull(Buddy.parseStatus(JSONObject("""{"reason":"attestation rejected"}"""), 0L))
        assertNull(Buddy.parseStatus(JSONObject(), 0L))
    }

    @Test
    fun `the cache's list round-trips through its JSON`() {
        val list = listOf(BuddyEntry("p-1", "W1AW", true, 12), BuddyEntry("", "K1ABC", false, 0))
        assertEquals(list, Buddy.parseEntries(Buddy.entriesJson(list)))
        assertTrue(Buddy.parseEntries(null).isEmpty())
    }
}
