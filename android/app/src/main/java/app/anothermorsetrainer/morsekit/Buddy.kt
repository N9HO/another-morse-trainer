package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * The pure half of the buddy-streak client (docs/buddy-streak-design.md):
 * the day label the server takes, the invite-code rule and the status the
 * server returns. No network, no Android types, so the JUnit suite can hold
 * every rule here to the server's contract (the leaderboard repo's README and
 * `src/buddy.ts`). The OkHttp and Play Integrity halves live in the app
 * package (`BuddyClient`, reusing `LeaderboardClient`). The iOS twin has the
 * same names.
 */

/**
 * What `POST /v1/buddy/status`, `/join` and `/day` return, plus when this
 * device fetched it. The cache the app keeps between fetches is exactly this,
 * so the home line and the reminder can read it without a network round trip.
 *
 * @property today the LOCAL calendar day (yyyy-mm-dd) the status is about:
 *   the one the app sent. [buddyPractisedToday] and [practisedToday] mean
 *   nothing on any other day; see [isFor].
 * @property fetchedAt epoch milliseconds when the reply arrived, for the
 *   reminder's "as of 6:10 pm".
 */
data class BuddyStatus(
    val paired: Boolean,
    /** The buddy's leaderboard display name; "" when not paired. */
    val buddyName: String,
    val buddyPractisedToday: Boolean,
    /** Consecutive days, ending today or yesterday, on which both practised. */
    val streak: Int,
    /** This install's own streak by the same rule (the server's view, not [PracticeStreak]). */
    val myStreak: Int,
    val practisedToday: Boolean,
    val today: String,
    val fetchedAt: Long
) {
    /**
     * True when the status describes [day]: its today-flags are only good for
     * that day. A status with no day (a reply that omitted it) is for no day.
     */
    fun isFor(day: String): Boolean = today.isNotEmpty() && today == day

    /** The buddy has practised on [day] as far as this status knows; false for any other day. */
    fun buddyPractisedOn(day: String): Boolean = paired && isFor(day) && buddyPractisedToday
}

object Buddy {
    /** How long a cached status is trusted before the app asks the server again. */
    const val REFRESH_INTERVAL_MS = 15L * 60 * 1000

    /** Six characters from an alphabet without look-alikes (no 0/O, 1/I/L); the server's `INVITE_ALPHABET`. */
    const val INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val INVITE_LENGTH = 6

    private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /**
     * The day label the server takes: the LOCAL calendar day as yyyy-mm-dd.
     * Local, not UTC, on purpose — it is the same day boundary [PracticeStreak]
     * uses, so "your day is your day" across time zones and midnight. Formatted
     * by hand rather than through a locale-aware formatter: the server's regex
     * wants ASCII digits.
     */
    fun dayLabel(date: LocalDate): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)

    /** Today's [dayLabel] on the device clock. */
    fun today(): String = dayLabel(LocalDate.now())

    /** True for a well-formed yyyy-mm-dd label (shape only, like the server's `DAY_RE`). */
    fun isDay(s: String?): Boolean = s != null && DAY.matches(s)

    /**
     * The invite-code rule, mirroring the server's `normalizeInviteCode` so the
     * Join field can say no before the server does: trimmed, upper-cased,
     * spaces and dashes dropped, then exactly six characters from
     * [INVITE_ALPHABET]. Returns the normalised code or null.
     */
    fun normalizeInviteCode(raw: String?): String? {
        if (raw == null) return null
        val code = raw.trim().uppercase(Locale.ROOT).replace(Regex("[\\s-]"), "")
        if (code.length != INVITE_LENGTH) return null
        for (ch in code) if (INVITE_ALPHABET.indexOf(ch) < 0) return null
        return code
    }

    /**
     * A status reply, read tolerantly: a missing or mistyped field takes its
     * zero value rather than throwing, and a missing `buddy` object means not
     * paired whatever `paired` says, so a half-formed reply can never show a
     * nameless buddy. Returns null only when the object is not a status at
     * all (no `paired` field), which callers treat as "keep the old cache".
     */
    fun parseStatus(json: JSONObject, fetchedAt: Long): BuddyStatus? {
        if (!json.has("paired")) return null
        val buddy = json.optJSONObject("buddy")
        val name = buddy?.optString("displayName", "")?.trim().orEmpty()
        val paired = json.optBoolean("paired", false) && buddy != null && name.isNotEmpty()
        val today = json.optString("today", "").takeIf { isDay(it) } ?: ""
        return BuddyStatus(
            paired = paired,
            buddyName = if (paired) name else "",
            buddyPractisedToday = paired && (buddy?.optBoolean("practisedToday", false) ?: false),
            streak = if (paired) json.optInt("streak", 0).coerceAtLeast(0) else 0,
            myStreak = json.optInt("myStreak", 0).coerceAtLeast(0),
            practisedToday = json.optBoolean("practisedToday", false),
            today = today,
            fetchedAt = fetchedAt
        )
    }
}
