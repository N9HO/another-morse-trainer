package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Daily Comprehension (#353), pinned against `fixtures/comprehension-bank.json`
 * and `fixtures/comprehension-selection.json` at the repo root, the same files
 * the iOS `MorseKitCheck` harness and the desktop tests read.
 *
 * Like Daily Dit this is a *shared* daily puzzle, so the fixture pins real item
 * ids and option orders: reordering the bank or changing the shuffle fails here
 * first. The expected values were derived from the documented algorithm by a
 * throwaway script, not captured from this port.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class DailyComprehensionTest {

    private fun fixture(name: String): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
        assertNotNull("fixtures/$name is not on the test classpath", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    private fun ints(a: JSONArray): List<Int> = (0 until a.length()).map { a.getInt(it) }

    @Test
    fun bundledBankEqualsTheFixture() {
        val items = fixture("comprehension-bank.json").getJSONArray("items")
        val mine = ComprehensionBank.items
        assertEquals("item count", items.length(), mine.size)
        for (i in 0 until items.length()) {
            val f = items.getJSONObject(i)
            val m = mine[i]
            assertEquals(f.getString("id"), m.id)
            assertEquals(f.getString("sentence"), m.sentence)
            assertEquals(f.getString("question"), m.question)
            assertEquals(f.getString("answer"), m.answer)
            assertEquals(strings(f.getJSONArray("distractors")), m.distractors)
            assertEquals(f.getInt("kochLevel"), m.kochLevel)
        }
    }

    @Test
    fun bankShapeRules() {
        val bank = ComprehensionBank.items
        assertTrue("at least 120 items", bank.size >= 120)
        assertEquals("ids are unique", bank.size, bank.map { it.id }.toSet().size)
        for (item in bank) {
            assertEquals(
                "${item.id}: four distinct options",
                DailyComprehension.OPTION_COUNT,
                (listOf(item.answer) + item.distractors).toSet().size
            )
            assertTrue("${item.id}: sendable A-Z and single spaces only", item.sentence.all { it in 'A'..'Z' || it == ' ' } && !item.sentence.contains("  "))
            assertTrue("${item.id}: about five words", item.sentence.split(' ').size in 4..7)
            val highest = item.sentence.filter { it != ' ' }.maxOf { MorseCode.kochOrder.indexOf(it) }
            assertEquals("${item.id}: kochLevel", highest + 1, item.kochLevel)
        }
    }

    @Test
    fun ruleConstantsMatchTheFixture() {
        val rules = fixture("comprehension-selection.json").getJSONObject("rules")
        assertEquals(rules.getInt("selectionStride"), DailyComprehension.SELECTION_STRIDE)
        assertEquals(rules.getInt("optionCount"), DailyComprehension.OPTION_COUNT)
        assertEquals(rules.getInt("itemCount"), ComprehensionBank.items.size)
    }

    @Test
    fun oneFullCycleVisitsEveryItemOnce() {
        val n = ComprehensionBank.items.size
        val cycle = ints(fixture("comprehension-selection.json").getJSONArray("cycle"))
        assertEquals(n, cycle.toSet().size)
        assertEquals(cycle, (1..n).map { DailyComprehension.itemIndex(it, n) })
    }

    @Test
    fun datesSelectTheFixtureItemsAndOptionOrders() {
        val cases = fixture("comprehension-selection.json").getJSONArray("cases")
        assertTrue(cases.length() > 0)
        val n = ComprehensionBank.items.size
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val d = c.getJSONObject("date")
            val label = "${d.getInt("year")}-${d.getInt("month")}-${d.getInt("day")}"
            val puzzle = DailyDit.puzzleNumber(d.getInt("year"), d.getInt("month"), d.getInt("day"))
            assertEquals("$label puzzle", c.getInt("puzzleNumber"), puzzle)
            assertEquals("$label index", c.getInt("itemIndex"), DailyComprehension.itemIndex(puzzle, n))
            assertEquals("$label item", c.getString("itemId"), DailyComprehension.item(puzzle).id)
            assertEquals("$label order", ints(c.getJSONArray("optionOrder")), DailyComprehension.optionOrder(puzzle))
            assertEquals("$label correct", c.getInt("correctPosition"), DailyComprehension.options(puzzle).correctPosition)
        }
    }

    @Test
    fun shufflesMatchTheFixture() {
        val shuffles = fixture("comprehension-selection.json").getJSONArray("shuffles")
        assertTrue(shuffles.length() > 0)
        for (i in 0 until shuffles.length()) {
            val s = shuffles.getJSONObject(i)
            assertEquals(ints(s.getJSONArray("optionOrder")), DailyComprehension.optionOrder(s.getInt("puzzleNumber")))
        }
    }

    @Test
    fun optionsPutTheAnswerWhereTheOrderSaysAndShowEverythingOnce() {
        for (puzzle in 1..300) {
            val item = DailyComprehension.item(puzzle)
            val options = DailyComprehension.options(puzzle)
            assertEquals(item.answer, options.texts[options.correctPosition])
            assertEquals((listOf(item.answer) + item.distractors).toSet(), options.texts.toSet())
        }
    }

    @Test
    fun aGameCountsListensTakesOneAnswerAndThenClosesTheDay() {
        var game = DailyComprehensionGame(2).withListen().withListen()
        assertEquals(2, game.listens)
        val wrong = (game.correctPosition + 1) % DailyComprehension.OPTION_COUNT
        assertNull("out of range is refused", game.choose(DailyComprehension.OPTION_COUNT))
        game = game.choose(wrong)!!
        assertTrue(game.isFinished)
        assertTrue(!game.isCorrect)
        assertNull("one answer a day", game.choose(game.correctPosition))
        assertEquals("free replays after the answer", 2, game.withListen().listens)
    }
}
