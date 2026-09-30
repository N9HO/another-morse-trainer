package app.anothermorsetrainer.morsekit

import app.anothermorsetrainer.BuddyWords
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Several buddies (#237), pinned against `fixtures/buddy-list.json` at the
 * repo root — the same file the iOS harness (MorseKitCheck) reads: the status
 * parse in both server shapes, the digest behind the home line and the
 * reminder, the words those surfaces say, and when an invite stays shown.
 *
 * Expected values were written from the rules in the design note and the
 * leaderboard README, independently of either implementation, so both ports
 * drifting the same way still fails. The words are checked through
 * [BuddyWords] with the app's own strings.xml templates, read from the
 * module directory (Gradle's working directory for unit tests), so a change
 * to a template that the fixture does not expect fails here too.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class BuddyListTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("buddy-list.json")
        assertNotNull("fixtures/buddy-list.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    /** The app's string templates by name, `\'` unescaped, as `getString(id)` returns them. */
    private val strings: Map<String, String> by lazy {
        val file = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml not found from ${File(".").absolutePath}", file.exists())
        val re = Regex("<string name=\"([a-z_]+)\">(.*?)</string>")
        re.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private val words: BuddyWords by lazy {
        fun s(name: String) = strings[name] ?: error("no string $name")
        BuddyWords(
            practised = s("home_buddy_practised"),
            notYet = s("home_buddy_not_yet"),
            allPractised = s("home_buddies_all"),
            several = s("home_buddies_waiting"),
            waitingOne = s("buddy_waiting_one"),
            waitingTwo = s("buddy_waiting_two"),
            waitingMany = s("buddy_waiting_many"),
            streakDays = s("buddy_streak_days"),
            streakNone = s("buddy_streak_none"),
            streakBest = s("buddy_streak_best")
        )
    }

    private fun entries(a: JSONArray?): List<BuddyEntry> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            BuddyEntry(o.optString("id", ""), o.getString("name"), o.optBoolean("practisedToday", false), o.optInt("streak", 0))
        }
    }

    private fun names(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun optString(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)

    @Test
    fun `digest, home line and reminder match the fixture`() {
        val cases = fixture.getJSONArray("digestCases")
        val asOf = fixture.getString("asOf")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val today = c.getString("today")
            val buddies = entries(c.getJSONArray("buddies"))
            val status = BuddyStatus(
                buddies = buddies,
                maxBuddies = maxOf(1, buddies.size),
                myStreak = 0,
                practisedToday = false,
                today = c.getString("statusDay"),
                fetchedAt = if (c.getBoolean("fetched")) 1_790_000_000_000L else 0L
            )
            val want = c.getJSONObject("digest")
            val d = status.digest(today)
            assertEquals("$name: count", want.getInt("count"), d.count)
            assertEquals("$name: allPractised", want.getBoolean("allPractised"), d.allPractised)
            assertEquals("$name: waitingNames", names(want.getJSONArray("waitingNames")), d.waitingNames)
            assertEquals("$name: waitingMore", want.getInt("waitingMore"), d.waitingMore)
            assertEquals("$name: bestStreak", want.getInt("bestStreak"), d.bestStreak)
            assertEquals("$name: home", optString(c, "home"), words.home(status, today))
            // The reminder as ReminderReceiver builds it: only from a fetched
            // status for today, with someone waiting; the template adds the
            // sentence's full stop, which the fixture leaves off.
            val reminder = if (status.fetchedAt > 0 && status.paired && status.isFor(today)) {
                words.waiting(d)?.let { strings.getValue("reminder_body_buddy").format(it, asOf).removeSuffix(".") }
            } else {
                null
            }
            assertEquals("$name: reminder", optString(c, "reminder"), reminder)
        }
    }

    @Test
    fun `status parse matches the fixture in both server shapes`() {
        val cases = fixture.getJSONArray("parseCases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val parsed = Buddy.parseStatus(c.getJSONObject("body"), fetchedAt = 7L)
            if (c.isNull("expect")) {
                assertNull(name, parsed)
                continue
            }
            val want = c.getJSONObject("expect")
            assertNotNull(name, parsed)
            parsed!!
            assertEquals("$name: buddies", entries(want.getJSONArray("buddies")), parsed.buddies)
            assertEquals("$name: maxBuddies", want.getInt("maxBuddies"), parsed.maxBuddies)
            assertEquals("$name: myStreak", want.getInt("myStreak"), parsed.myStreak)
            assertEquals("$name: practisedToday", want.getBoolean("practisedToday"), parsed.practisedToday)
            assertEquals("$name: today", want.getString("today"), parsed.today)
            assertEquals("$name: joined", want.getString("joined"), parsed.joined)
        }
    }

    @Test
    fun `an invite stays shown exactly when the fixture says`() {
        val cases = fixture.getJSONArray("inviteCases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val keep = Buddy.keepsInvite(
                previous = entries(c.getJSONArray("previous")),
                current = entries(c.getJSONArray("current")),
                maxBuddies = c.getInt("maxBuddies"),
                joinedByMe = c.getBoolean("joinedByMe"),
                joinedId = c.getString("joinedId")
            )
            assertEquals(c.getString("name"), c.getBoolean("keep"), keep)
        }
    }

    @Test
    fun `names shown matches the fixture's rule`() {
        assertEquals(2, Buddy.NAMES_SHOWN)
    }
}
