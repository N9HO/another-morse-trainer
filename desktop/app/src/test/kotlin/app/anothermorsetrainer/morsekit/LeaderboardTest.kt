package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the leaderboard client, held to the server contract in the
 * leaderboard repo's README, `src/names.ts` and `src/attest/playintegrity.ts`.
 * The expected hashes below were derived from the specification (RFC 4648 §5
 * on a SHA-256 digest), not from running this code.
 */
class LeaderboardTest {

    @Test
    fun `every ranked mode maps to the server id and nothing else does`() {
        assertEquals("rapidFire", Leaderboard.modeId("Rapid Fire"))
        assertEquals("contest", Leaderboard.modeId("Contest"))
        assertEquals("pileup", Leaderboard.modeId("Pileup"))
        assertEquals("invaders", Leaderboard.modeId("Morse Invaders"))
        assertEquals("galaga", Leaderboard.modeId("CW Galaga"))
        assertEquals("defender", Leaderboard.modeId("Morse Defender"))
        assertEquals("dungeon", Leaderboard.modeId("CW Dungeon"))
        assertEquals("frogger", Leaderboard.modeId("CW Frogger"))
        assertEquals("asteroids", Leaderboard.modeId("CW Asteroids"))
        assertNull(Leaderboard.modeId("Code Exam"))
        assertNull(Leaderboard.modeId("Characters"))
        assertNull(Leaderboard.modeId("Listen"))
        assertEquals(9, Leaderboard.rankedModes.size)
        assertEquals(
            listOf("rapidFire", "contest", "pileup", "invaders", "galaga", "defender", "dungeon", "frogger", "asteroids"),
            Leaderboard.rankedModes
        )
    }

    @Test
    fun `the six games ramp and the three graded modes do not`() {
        for (m in listOf("invaders", "galaga", "defender", "dungeon", "frogger", "asteroids")) {
            assertTrue(m, Leaderboard.isRamping(m))
        }
        for (m in listOf("rapidFire", "contest", "pileup")) {
            assertFalse(m, Leaderboard.isRamping(m))
        }
    }

    @Test
    fun `display names follow the server rule`() {
        assertEquals("N9HO", Leaderboard.normalizeDisplayName(" n9ho "))
        assertEquals("K1ABC/P", Leaderboard.normalizeDisplayName("k1abc/p"))
        assertEquals("OP JIM-2", Leaderboard.normalizeDisplayName("op   jim-2"))
        assertEquals("AB", Leaderboard.normalizeDisplayName("ab"))
        assertEquals("ABCDEFGHIJKL", Leaderboard.normalizeDisplayName("abcdefghijkl"))
        assertNull("too short", Leaderboard.normalizeDisplayName("A"))
        assertNull("too long", Leaderboard.normalizeDisplayName("ABCDEFGHIJKLM"))
        assertNull("leading space", Leaderboard.normalizeDisplayName("  /N9HO"))
        assertNull("leading dash", Leaderboard.normalizeDisplayName("-N9HO"))
        assertNull("bad character", Leaderboard.normalizeDisplayName("N9HO!"))
        assertNull("empty", Leaderboard.normalizeDisplayName(""))
        assertNull("blank", Leaderboard.normalizeDisplayName("   "))
        assertTrue(Leaderboard.isValidDisplayName("w1aw"))
        assertFalse(Leaderboard.isValidDisplayName("x"))
    }

    @Test
    fun `base64url matches RFC 4648 section 5 test vectors, unpadded`() {
        assertEquals("", Leaderboard.base64Url(ByteArray(0)))
        assertEquals("Zg", Leaderboard.base64Url("f".toByteArray()))
        assertEquals("Zm8", Leaderboard.base64Url("fo".toByteArray()))
        assertEquals("Zm9v", Leaderboard.base64Url("foo".toByteArray()))
        assertEquals("Zm9vYg", Leaderboard.base64Url("foob".toByteArray()))
        assertEquals("Zm9vYmE", Leaderboard.base64Url("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Leaderboard.base64Url("foobar".toByteArray()))
        // The url-safe alphabet: 0xFB 0xFF encodes to "-_8" in base64url ("+/8" in base64).
        assertEquals("-_8", Leaderboard.base64Url(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `request hash is base64url of SHA-256 of the challenge`() {
        // SHA-256("abc") = ba7816bf 8f01cfea 414140de 5dae2223 b00361a3 96177a9c b410ff61 f20015ad
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", Leaderboard.requestHash("abc"))
        // SHA-256("") = e3b0c442 98fc1c14 9afbf4c8 996fb924 27ae41e4 649b934c a495991b 7852b855
        assertEquals("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU", Leaderboard.requestHash(""))
        // 43 characters for 32 bytes, no '=' padding, url-safe alphabet only.
        val h = Leaderboard.requestHash("3f6c2a1e-run-token")
        assertEquals(43, h.length)
        assertTrue(h.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun `transcript JSON carries wpm only when the item has one`() {
        val items = listOf(
            LeaderboardItem(sent = "K1ABC", answered = "K1ABC", reactionMs = 420),
            LeaderboardItem(sent = "R", answered = "", reactionMs = 0, wpm = 22)
        )
        val arr: JSONArray = LeaderboardItem.transcriptJson(items)
        assertEquals(2, arr.length())
        val a = arr.getJSONObject(0)
        assertEquals("K1ABC", a.getString("sent"))
        assertEquals("K1ABC", a.getString("answered"))
        assertEquals(420, a.getInt("reactionMs"))
        assertFalse(a.has("wpm"))
        val b = arr.getJSONObject(1)
        assertEquals("R", b.getString("sent"))
        assertEquals("", b.getString("answered"))
        assertEquals(0, b.getInt("reactionMs"))
        assertEquals(22, b.getInt("wpm"))
    }

    @Test
    fun `reaction times are clamped into the server range`() {
        assertEquals(0, Leaderboard.clampReaction(-5))
        assertEquals(0, Leaderboard.clampReaction(0))
        assertEquals(1234, Leaderboard.clampReaction(1234))
        assertEquals(60_000, Leaderboard.clampReaction(60_001))
        assertEquals(60_000, Leaderboard.clampReaction(Long.MAX_VALUE))
    }
}
