package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The pure half of the shared-leaderboard client (docs/high-scores-design.md,
 * step 2): the transcript item the server grades, the mode-id mapping, the
 * display-name rule and the attestation request hash. No network, no Android
 * types, so the JUnit suite can hold every rule here to the server's contract
 * (the leaderboard repo's README and `src/names.ts` / `src/grade.ts`). The
 * OkHttp and Play Integrity halves live in the app package
 * (`LeaderboardClient`, `PlayIntegrityAttester`).
 */

/**
 * One graded item of a run, as `POST /v1/run/submit` takes it. The server
 * decides what was correct (`answered == sent`, case- and space-insensitive)
 * and sums the speed over the correct items; nothing the client computed
 * about its own score is sent.
 *
 * @property sent the text the player had to produce, exactly as the mode
 *   would log it (a call sign, a word, a character, `CALL 599 BOB OH`).
 * @property answered what the player produced; "" for a miss or a timeout.
 * @property reactionMs time to respond from the end of the item's audio, >= 0;
 *   0 where the mode does not measure it.
 * @property wpm the character speed this item was sent at. Required for the
 *   six ramping arcade games (each item carries its own), forbidden for
 *   Rapid Fire, Contest and Pileup Runner, which rank on the run's speed.
 */
data class LeaderboardItem(
    val sent: String,
    val answered: String,
    val reactionMs: Int,
    val wpm: Int? = null
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("sent", sent)
            .put("answered", answered)
            .put("reactionMs", reactionMs)
        if (wpm != null) o.put("wpm", wpm)
        return o
    }

    companion object {
        /** The whole transcript as the JSON array the submit body carries. */
        fun transcriptJson(items: List<LeaderboardItem>): JSONArray {
            val arr = JSONArray()
            for (it in items) arr.put(it.toJson())
            return arr
        }
    }
}

object Leaderboard {
    /** Longest the server accepts per item; the client never generates more. */
    const val MAX_REACTION_MS = 60_000

    /**
     * Server mode ids, keyed by the mode string each screen passes to
     * `Stats.record`. A mode not in this map is not ranked and is never
     * submitted (Code Exam, the quizzes, the passive modes).
     */
    val modeIds: Map<String, String> = linkedMapOf(
        "Rapid Fire" to "rapidFire",
        "Contest" to "contest",
        "Pileup" to "pileup",
        "Morse Invaders" to "invaders",
        "CW Galaga" to "galaga",
        "Morse Defender" to "defender",
        "CW Dungeon" to "dungeon",
        "CW Frogger" to "frogger",
        "CW Asteroids" to "asteroids"
    )

    /** The nine server mode ids, in the order the board screen lists them. */
    val rankedModes: List<String> get() = modeIds.values.toList()

    /** The server mode id for a `Stats.record` mode string, or null when the mode is not ranked. */
    fun modeId(statsMode: String): String? = modeIds[statsMode]

    /** The modes whose items carry their own `wpm` (the server's RAMPING set). */
    val rampingModes: Set<String> = setOf("invaders", "galaga", "defender", "dungeon", "frogger", "asteroids")

    fun isRamping(modeId: String): Boolean = modeId in rampingModes

    /**
     * The display-name rule, mirroring `src/names.ts` so the Settings field
     * can say no before the server does: trimmed, upper-cased, runs of
     * whitespace collapsed, 2 to 12 characters, a letter or digit first, then
     * letters, digits, space, `/` and `-`. Returns the normalised name, or
     * null when it is not allowed. The server's short deny list is its own
     * business; the client does not duplicate it.
     */
    fun normalizeDisplayName(raw: String): String? {
        val name = raw.trim().uppercase().replace(Regex("\\s+"), " ")
        if (name.length < 2 || name.length > 12) return null
        if (!DISPLAY_NAME.matches(name)) return null
        return name
    }

    fun isValidDisplayName(raw: String): Boolean = normalizeDisplayName(raw) != null

    private val DISPLAY_NAME = Regex("^[A-Z0-9][A-Z0-9 /-]{1,11}$")

    /**
     * The Play Integrity request hash: base64url, no padding, of the SHA-256
     * of the challenge (the server-issued challenge on `/run/start` and
     * `/me/delete`, the run token on `/run/submit`). The server recomputes it
     * from the same challenge and compares.
     */
    fun requestHash(challenge: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8)))

    /**
     * RFC 4648 §5 base64url without padding. Hand-rolled: `java.util.Base64`
     * is API 26 and `android.util.Base64` is a stub on the JVM, and this must
     * run under both minSdk 24 and the unit tests.
     */
    fun base64Url(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** A reaction time as the server accepts it: never negative, capped at [MAX_REACTION_MS]. */
    fun clampReaction(ms: Long): Int = ms.coerceIn(0L, MAX_REACTION_MS.toLong()).toInt()
}
