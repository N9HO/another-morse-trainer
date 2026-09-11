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
    fun `a paired status parses whole`() {
        val json = JSONObject(
            """{"paired":true,"buddy":{"displayName":"W1AW","practisedToday":true},
               "streak":12,"myStreak":30,"practisedToday":false,"today":"2026-09-11"}"""
        )
        val s = Buddy.parseStatus(json, fetchedAt = 1_000L)
        assertNotNull(s)
        s!!
        assertTrue(s.paired)
        assertEquals("W1AW", s.buddyName)
        assertTrue(s.buddyPractisedToday)
        assertEquals(12, s.streak)
        assertEquals(30, s.myStreak)
        assertFalse(s.practisedToday)
        assertEquals("2026-09-11", s.today)
        assertEquals(1_000L, s.fetchedAt)
        assertTrue(s.isFor("2026-09-11"))
        assertFalse(s.isFor("2026-09-12"))
        assertTrue(s.buddyPractisedOn("2026-09-11"))
        assertFalse("yesterday's flag says nothing about today", s.buddyPractisedOn("2026-09-12"))
    }

    @Test
    fun `an unpaired status has no buddy`() {
        val s = Buddy.parseStatus(
            JSONObject("""{"paired":false,"streak":0,"myStreak":3,"practisedToday":true,"today":"2026-09-11"}"""),
            fetchedAt = 5L
        )
        assertNotNull(s)
        s!!
        assertFalse(s.paired)
        assertEquals("", s.buddyName)
        assertFalse(s.buddyPractisedToday)
        assertEquals(0, s.streak)
        assertEquals(3, s.myStreak)
        assertTrue(s.practisedToday)
        assertFalse(s.buddyPractisedOn("2026-09-11"))
    }

    @Test
    fun `the parse is tolerant of missing and mistyped fields`() {
        // paired without a buddy object: not paired, whatever the flag says.
        val noBuddy = Buddy.parseStatus(JSONObject("""{"paired":true,"streak":4}"""), 0L)
        assertNotNull(noBuddy)
        assertFalse(noBuddy!!.paired)
        assertEquals("", noBuddy.buddyName)
        assertEquals(0, noBuddy.streak)
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
        assertEquals(0, junk.streak)
        assertEquals(0, junk.myStreak)
        assertEquals("", junk.today)
        assertFalse(junk.isFor(""))   // an unknown day is nobody's day
        // A negative streak is clamped.
        val negative = Buddy.parseStatus(
            JSONObject("""{"paired":true,"buddy":{"displayName":"W1AW"},"streak":-5,"today":"2026-09-11"}"""),
            0L
        )
        assertEquals(0, negative!!.streak)
        assertTrue(negative.paired)
        assertFalse(negative.buddyPractisedToday)
        // Not a status at all.
        assertNull(Buddy.parseStatus(JSONObject("""{"reason":"attestation rejected"}"""), 0L))
        assertNull(Buddy.parseStatus(JSONObject(), 0L))
    }
}
