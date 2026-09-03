package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * The no-repeat draw behind Listen & Learn (issue #158): a pass is the whole
 * pool in some order, and the seam between passes never repeats an item.
 *
 * Behaviour rather than fixture data — it is RNG-driven — so every test seeds
 * its own [Random] and the invariants are checked over many draws.
 */
class ShuffledDeckTest {

    private val pool = (1..10).toList()

    @Test
    fun `a pass is every element exactly once`() {
        val deck = ShuffledDeck(pool, Random(3))
        val pass = List(pool.size) { deck.draw() }
        assertEquals(pool, pass.sorted())
        assertEquals(0, deck.remainingInPass)
    }

    @Test
    fun `every later pass is also the whole pool`() {
        val deck = ShuffledDeck(pool, Random(11))
        repeat(6) {
            val pass = List(pool.size) { deck.draw() }
            assertEquals("pass $it", pool, pass.sorted())
        }
    }

    @Test
    fun `no two consecutive draws are the same, across pass boundaries`() {
        // A three-item pool crosses the seam every three draws, so 600 draws
        // test it two hundred times per seed.
        for (seed in 1..25) {
            val deck = ShuffledDeck(listOf("A", "B", "C"), Random(seed))
            var previous = deck.draw()
            repeat(600) {
                val next = deck.draw()
                assertNotEquals("seed $seed repeated $next", previous, next)
                previous = next
            }
        }
    }

    @Test
    fun `remainingInPass counts down through the pass`() {
        val deck = ShuffledDeck(pool, Random(5))
        assertEquals(0, deck.remainingInPass)   // nothing dealt until the first draw
        deck.draw()
        assertEquals(pool.size - 1, deck.remainingInPass)
        repeat(pool.size - 1) { deck.draw() }
        assertEquals(0, deck.remainingInPass)
    }

    @Test
    fun `an empty pool draws nothing`() {
        assertNull(ShuffledDeck(emptyList<String>(), Random(1)).draw())
    }

    @Test
    fun `a single element keeps coming back`() {
        val deck = ShuffledDeck(listOf("E"), Random(1))
        repeat(5) { assertEquals("E", deck.draw()) }
    }
}
