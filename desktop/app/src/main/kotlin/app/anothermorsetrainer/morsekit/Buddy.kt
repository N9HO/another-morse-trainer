package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * The pure half of the buddy-streak client (docs/buddy-streak-design.md,
 * #219, #237): the day label the server takes, the invite-code rule, the
 * status the server returns and the digest the one-line surfaces say. No
 * network, no Android types, so the JUnit suite can hold every rule here to
 * the server's contract (the leaderboard repo's README and `src/buddy.ts`)
 * and to `fixtures/buddy-list.json`, which the iOS harness reads too. The
 * OkHttp and Play Integrity halves live in the app package (`BuddyClient`,
 * reusing `LeaderboardClient`). The iOS twin has the same rules.
 */

/**
 * One buddy: a pairing as the server lists it. [id] is the pairing's opaque
 * id (the same on both sides, never an identity), which `/v2/buddy/leave`
 * takes; "" for a buddy learned from a one-buddy (v1) answer or a cache saved
 * before #237, which is left through the v1 route.
 */
data class BuddyEntry(
    val id: String,
    /** The buddy's leaderboard display name, never blank. */
    val name: String,
    /** Whether the buddy had practised on the status's `today`. */
    val practisedToday: Boolean,
    /** Consecutive days, ending today or yesterday, on which you BOTH practised. */
    val streak: Int
)

/**
 * What every buddy route that answers with a status returns, plus when this
 * device fetched it. The cache the app keeps between fetches is exactly
 * this, so the home line and the reminder can read it without a network
 * round trip.
 *
 * @property buddies every buddy, oldest pairing first.
 * @property maxBuddies how many the server allows (10 today); 1 from a v1 answer.
 * @property today the LOCAL calendar day (yyyy-mm-dd) the status is about:
 *   the one the app sent. The practised flags mean nothing on any other day;
 *   see [isFor].
 * @property fetchedAt epoch milliseconds when the reply arrived, for the
 *   reminder's "as of 6:10 pm".
 * @property joined on a v2 join, the id of the pairing just made; "" otherwise.
 */
data class BuddyStatus(
    val buddies: List<BuddyEntry>,
    val maxBuddies: Int,
    /** This install's own streak by the same rule (the server's view, not [PracticeStreak]). */
    val myStreak: Int,
    val practisedToday: Boolean,
    val today: String,
    val fetchedAt: Long,
    val joined: String = ""
) {
    /** At least one buddy. */
    val paired: Boolean get() = buddies.isNotEmpty()

    /** At the server's cap: no more invites or joins. */
    val isFull: Boolean get() = buddies.size >= maxBuddies

    /**
     * True when the status describes [day]: its today-flags are only good for
     * that day. A status with no day (a reply that omitted it) is for no day.
     */
    fun isFor(day: String): Boolean = today.isNotEmpty() && today == day

    /** [buddy] has practised on [day] as far as this status knows; false for any other day. */
    fun practisedOn(buddy: BuddyEntry, day: String): Boolean = isFor(day) && buddy.practisedToday

    /** The list boiled down for the home line and the reminder, as of [day]. */
    fun digest(day: String): BuddyDigest = Buddy.digest(buddies, today, day)
}

/**
 * The buddy list boiled down to what the one-line surfaces say: who has not
 * practised yet today, and the best streak (fixtures/buddy-list.json,
 * "digestCases"). The words are in strings.xml; this is which ones and with
 * what.
 */
data class BuddyDigest(
    val count: Int,
    val allPractised: Boolean,
    /** The first [Buddy.NAMES_SHOWN] buddies who have not practised today, in list order. */
    val waitingNames: List<String>,
    /** How many more are waiting beyond [waitingNames]. */
    val waitingMore: Int,
    val bestStreak: Int
)

object Buddy {
    /** How long a cached status is trusted before the app asks the server again. */
    const val REFRESH_INTERVAL_MS = 15L * 60 * 1000

    /** Six characters from an alphabet without look-alikes (no 0/O, 1/I/L); the server's `INVITE_ALPHABET`. */
    const val INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val INVITE_LENGTH = 6

    /** At most this many waiting buddies are named; the rest are "N more". */
    const val NAMES_SHOWN = 2

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
     * A status reply in either of the server's shapes (fixtures/buddy-list.json,
     * "parseCases"):
     *
     * - v2 (#237): `{ buddies: [{ id, displayName, practisedToday, streak }],
     *   maxBuddies, myStreak, practisedToday, today, joined? }`;
     * - v1 (#219, a Worker that predates #237): `{ paired, buddy?, streak,
     *   myStreak, practisedToday, today }`, read as a list of at most one
     *   buddy with no id and a cap of 1.
     *
     * Read tolerantly: a mistyped field takes its zero value, a negative
     * streak reads as zero, and an entry with no name is dropped, so a
     * half-formed reply can never show a nameless buddy. Returns null only
     * when the object is not a status at all, which callers treat as "keep
     * the old cache".
     */
    fun parseStatus(json: JSONObject, fetchedAt: Long): BuddyStatus? {
        val list: List<BuddyEntry>
        val max: Int
        if (json.has("buddies")) {
            list = parseEntries(json.optJSONArray("buddies"))
            max = json.optInt("maxBuddies", 1)
        } else if (json.has("paired")) {
            val buddy = json.optJSONObject("buddy")
            val name = buddy?.optString("displayName", "")?.trim().orEmpty()
            list = if (json.optBoolean("paired", false) && buddy != null && name.isNotEmpty()) {
                listOf(BuddyEntry("", name, buddy.optBoolean("practisedToday", false), json.optInt("streak", 0).coerceAtLeast(0)))
            } else {
                emptyList()
            }
            max = 1
        } else {
            return null
        }
        return BuddyStatus(
            buddies = list,
            maxBuddies = maxOf(1, max, list.size),
            myStreak = json.optInt("myStreak", 0).coerceAtLeast(0),
            practisedToday = json.optBoolean("practisedToday", false),
            today = json.optString("today", "").takeIf { isDay(it) } ?: "",
            fetchedAt = fetchedAt,
            joined = json.optString("joined", "")
        )
    }

    /** A `buddies` array, nameless or malformed entries dropped. Also reads the cache's copy. */
    fun parseEntries(array: JSONArray?): List<BuddyEntry> {
        if (array == null) return emptyList()
        val out = ArrayList<BuddyEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("displayName", "").trim()
            if (name.isEmpty()) continue
            out += BuddyEntry(
                id = o.optString("id", ""),
                name = name,
                practisedToday = o.optBoolean("practisedToday", false),
                streak = o.optInt("streak", 0).coerceAtLeast(0)
            )
        }
        return out
    }

    /** The list as the cache writes it: the server's own entry shape, so [parseEntries] reads it back. */
    fun entriesJson(buddies: List<BuddyEntry>): JSONArray {
        val a = JSONArray()
        for (b in buddies) {
            a.put(
                JSONObject()
                    .put("id", b.id)
                    .put("displayName", b.name)
                    .put("practisedToday", b.practisedToday)
                    .put("streak", b.streak)
            )
        }
        return a
    }

    /**
     * The digest (fixtures/buddy-list.json, "digestCases"). A buddy counts as
     * practised only when the status is for [today] (`statusDay == today`) and
     * says so: an older status cannot vouch.
     */
    fun digest(buddies: List<BuddyEntry>, statusDay: String, today: String): BuddyDigest {
        val current = statusDay.isNotEmpty() && statusDay == today
        val waiting = buddies.filter { !(current && it.practisedToday) }.map { it.name }
        val shown = waiting.take(NAMES_SHOWN)
        return BuddyDigest(
            count = buddies.size,
            allPractised = buddies.isNotEmpty() && waiting.isEmpty(),
            waitingNames = shown,
            waitingMore = waiting.size - shown.size,
            bestStreak = buddies.maxOfOrNull { it.streak } ?: 0
        )
    }

    /**
     * Whether an invite this install issued should stay shown after the list
     * went from [previous] to [current] (fixtures/buddy-list.json,
     * "inviteCases"): not once the list is full, and not once a buddy has
     * appeared that this install did not join itself — that one came through
     * the invite, which is single use. A buddy "appeared" when no previous
     * entry matches it by id, or by name when either id is empty (a v1 answer
     * or a cache from before #237).
     */
    fun keepsInvite(
        previous: List<BuddyEntry>,
        current: List<BuddyEntry>,
        maxBuddies: Int,
        joinedByMe: Boolean,
        joinedId: String
    ): Boolean {
        if (current.size >= maxBuddies) return false
        val newcomers = current.filter { e ->
            previous.none { p -> (e.id.isNotEmpty() && p.id == e.id) || ((p.id.isEmpty() || e.id.isEmpty()) && p.name == e.name) }
        }.toMutableList()
        if (joinedByMe && newcomers.isNotEmpty()) {
            if (joinedId.isNotEmpty()) {
                val i = newcomers.indexOfFirst { it.id == joinedId }
                if (i >= 0) newcomers.removeAt(i)
            } else {
                newcomers.removeAt(0)
            }
        }
        return newcomers.isEmpty()
    }
}
