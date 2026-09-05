package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Morse Invaders rules (#170), held to the same expectations as the Swift
 * MorseKitCheck "Morse Invaders" section: the two engines are twins.
 */
class InvadersTest {

    private val pool = listOf('K', 'M', 'R', 'S')

    private fun game(
        difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        hitsPerWave: Int = 10,
        seed: Int = 1
    ) = InvadersGame(
        InvadersGame.Config(characters = pool, difficulty = difficulty, hitsPerWave = hitsPerWave),
        rng = Random(seed)
    )

    @Test
    fun spawnAppearsAtTheConfiguredInterval() {
        val g = game()
        val interval = g.spawnInterval
        assertEquals(InvadersGame.baseSpawnInterval, interval, 1e-9)
        assertTrue(g.advance(interval - 0.01).isEmpty())
        assertTrue(g.invaders.isEmpty())
        val events = g.advance(0.02)
        assertEquals(1, events.size)
        assertTrue(events[0] is InvadersEvent.Spawned)
        assertEquals(1, g.invaders.size)
        assertTrue(g.invaders[0].character in pool)
        // Born a hair past the top: the step overshot the spawn time by 0.01 s.
        assertEquals(0.01 / g.fallTime, g.invaders[0].progress, 1e-9)
        // The second one arrives one interval later, not before.
        assertTrue(g.advance(interval - 0.02).none { it is InvadersEvent.Spawned })
        assertEquals(1, g.advance(0.02).count { it is InvadersEvent.Spawned })
        assertEquals(2, g.invaders.size)
    }

    @Test
    fun correctAnswerRemovesTheLowestMatchingInvaderAndScores() {
        // A one-character pool so every invader carries the same letter.
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K')), rng = Random(3))
        g.advance(g.spawnInterval)          // first invader
        g.advance(g.spawnInterval)          // second, one interval higher
        assertEquals(2, g.invaders.size)
        val lowest = g.invaders.maxByOrNull { it.progress }!!
        val shot = g.shoot('k')             // case-insensitive
        assertTrue(shot.isHit)
        assertEquals(lowest.id, shot.invader!!.id)
        assertEquals(InvadersGame.pointsPerHit, shot.points)
        assertEquals(InvadersGame.pointsPerHit, g.score)
        assertEquals(1, g.hits)
        assertEquals(1, g.combo)
        assertEquals(1, g.invaders.size)
        assertTrue(g.invaders[0].progress < lowest.progress)
    }

    @Test
    fun comboMultiplierGrows() {
        assertEquals(1, InvadersGame.multiplier(1))
        assertEquals(1, InvadersGame.multiplier(3))
        assertEquals(2, InvadersGame.multiplier(4))
        assertEquals(3, InvadersGame.multiplier(7))
        assertEquals(4, InvadersGame.multiplier(10))
        assertEquals(4, InvadersGame.multiplier(40))
    }

    @Test
    fun wrongAnswerBreaksTheComboAndCountsAMiss() {
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K')), rng = Random(3))
        g.advance(g.spawnInterval)
        assertTrue(g.shoot('K').isHit)
        g.advance(g.spawnInterval)
        assertTrue(g.shoot('K').isHit)
        assertEquals(2, g.combo)
        g.advance(g.spawnInterval)
        val shot = g.shoot('Z')             // nothing on the field carries Z
        assertFalse(shot.isHit)
        assertNull(shot.invader)
        assertEquals(0, shot.points)
        assertEquals(0, g.combo)
        assertEquals(2, g.bestCombo)
        assertEquals(1, g.misses)
        assertEquals(2, g.hits)
        assertEquals(1, g.invaders.size)    // the miss shoots nothing down
        assertEquals(2.0 / 3.0, g.accuracy, 1e-9)
    }

    @Test
    fun invaderReachingTheGroundCostsALife() {
        val g = game()
        g.advance(g.spawnInterval)
        assertEquals(3, g.lives)
        val fall = g.invaders[0].fallTime
        assertEquals(InvadersGame.baseFallTime, fall, 1e-9)
        // Shoot down everything that spawns meanwhile except the first one, so
        // only it can reach the ground.
        val first = g.invaders[0].id
        var escaped: InvadersEvent.Escaped? = null
        var t = 0.0
        while (t + 0.25 < fall - 1e-9) {
            val events = g.advance(0.25)
            t += 0.25
            escaped = events.filterIsInstance<InvadersEvent.Escaped>().firstOrNull() ?: escaped
            g.invaders.filter { it.id != first }.forEach { g.shoot(it.character) }
        }
        assertNull(escaped)
        assertEquals(3, g.lives)
        val events = g.advance(0.25)
        val e = events.filterIsInstance<InvadersEvent.Escaped>().single()
        assertEquals(first, e.invader.id)
        assertEquals(1.0, e.invader.progress, 1e-9)
        assertEquals(2, g.lives)
        assertEquals(0, g.combo)
        assertEquals(1, g.misses)
        assertFalse(g.isOver)
    }

    @Test
    fun thirdLostLifeEndsTheGame() {
        // Never shoot: every invader lands. Three landings end it.
        val g = game()
        var over = false
        var escapes = 0
        var guardSteps = 0
        while (!over && guardSteps < 100_000) {
            val events = g.advance(0.1)
            escapes += events.count { it is InvadersEvent.Escaped }
            over = events.any { it is InvadersEvent.GameOver }
            guardSteps += 1
        }
        assertTrue(over)
        assertTrue(g.isOver)
        assertEquals(0, g.lives)
        assertEquals(3, escapes)
        assertTrue(g.invaders.isEmpty())
        // A finished game ignores time and shots.
        assertTrue(g.advance(10.0).isEmpty())
        assertFalse(g.shoot('K').isHit)
        assertEquals(3, g.misses)
    }

    @Test
    fun waveAdvanceTightensTheInterval() {
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K'), hitsPerWave = 3), rng = Random(5))
        val wave1 = g.spawnInterval
        val fall1 = g.fallTime
        repeat(2) {
            g.advance(g.spawnInterval)
            assertFalse(g.shoot('K').waveCleared)
        }
        assertEquals(1, g.wave)
        g.advance(g.spawnInterval)
        assertTrue(g.shoot('K').waveCleared)
        assertEquals(2, g.wave)
        assertTrue(g.spawnInterval < wave1)
        assertTrue(g.fallTime < fall1)
        assertEquals(wave1 * 0.88, g.spawnInterval, 1e-9)
        assertEquals(fall1 * 0.9, g.fallTime, 1e-9)
        // Floors: a very high wave never goes below the minimums.
        assertEquals(InvadersGame.minSpawnInterval, InvadersGame.spawnInterval(60, InvadersDifficulty.NORMAL), 1e-9)
        assertEquals(InvadersGame.minFallTime, InvadersGame.fallTime(60, InvadersDifficulty.NORMAL), 1e-9)
    }

    @Test
    fun difficultyScalesTheTimings() {
        val normal = InvadersGame.spawnInterval(1, InvadersDifficulty.NORMAL)
        assertEquals(normal * 1.35, InvadersGame.spawnInterval(1, InvadersDifficulty.RELAXED), 1e-9)
        assertEquals(normal * 0.75, InvadersGame.spawnInterval(1, InvadersDifficulty.FAST), 1e-9)
        assertEquals(InvadersGame.baseFallTime * 0.75, InvadersGame.fallTime(1, InvadersDifficulty.FAST), 1e-9)
    }

    @Test
    fun sameSeedGivesTheSameSpawnSequence() {
        fun sequence(seed: Int): List<Pair<Char, Int>> {
            val g = game(seed = seed)
            val out = ArrayList<Pair<Char, Int>>()
            repeat(12) {
                g.advance(g.spawnInterval)
                out.addAll(g.invaders.takeLast(1).map { it.character to it.column })
                // Keep the field clear so nothing lands and the sequence stays pure spawn order.
                g.invaders.toList().forEach { g.shoot(it.character) }
            }
            return out
        }
        assertEquals(sequence(42), sequence(42))
        assertTrue(sequence(42) != sequence(43))
        // Consecutive spawns never share a column, and every character is from the pool.
        val s = sequence(42)
        assertNotNull(s)
        s.zipWithNext().forEach { (a, b) -> assertTrue(a.second != b.second) }
        s.forEach { assertTrue(it.first in pool) }
    }
}
