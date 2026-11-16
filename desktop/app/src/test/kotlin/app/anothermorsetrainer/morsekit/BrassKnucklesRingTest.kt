package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Brass Knuckles Knock Out art and the pure-Kotlin ring renderer: the generated sprite tables
 * are well-formed, and every frame the fight screen can ask for draws without throwing. Nothing
 * here looks at the pixels (nobody can, in CI); it catches a malformed table or an out-of-range
 * index in the renderer.
 */
class BrassKnucklesRingTest {

    private val sprites: Map<String, List<BkSprite>> = mapOf(
        "TORSO" to BrassKnucklesArt.TORSO, "LEGS" to BrassKnucklesArt.LEGS, "ARM" to BrassKnucklesArt.ARM,
        "HEAD" to BrassKnucklesArt.HEAD, "GEAR" to BrassKnucklesArt.GEAR, "FACE" to BrassKnucklesArt.FACE,
        "EXTRA" to BrassKnucklesArt.EXTRA, "HERO_BODY" to BrassKnucklesArt.HERO_BODY,
        "HERO_ARM" to BrassKnucklesArt.HERO_ARM, "FX" to BrassKnucklesArt.FX,
        "STAGE_TILE" to BrassKnucklesArt.STAGE_TILE, "LOGO" to BrassKnucklesArt.LOGO,
        "GLYPH" to BrassKnucklesArt.GLYPH,
    )

    @Test
    fun everySpritePixelStringHasWidthTimesHeightHexDigits() {
        for ((name, list) in sprites) {
            assertTrue("$name is empty", list.isNotEmpty())
            list.forEachIndexed { i, s ->
                assertEquals("$name[$i] length", s.w * s.h, s.pixels.length)
                assertTrue("$name[$i] has a non-hex digit", s.pixels.all { it in '0'..'9' || it in 'a'..'f' })
            }
        }
    }

    @Test
    fun everyFontCellHasWidthTimesHeightDigits() {
        for ((name, font) in listOf("SMALL" to BrassKnucklesArt.FONT_SMALL, "TINY" to BrassKnucklesArt.FONT_TINY)) {
            assertEquals("$name cells", font.chars.length, font.cells.size)
            font.cells.forEachIndexed { i, c -> assertEquals("$name cell $i", font.w * font.h, c.length) }
        }
    }

    private val foePoses: List<BoxingFoe> =
        listOf(BoxingSide.LEFT, BoxingSide.RIGHT).flatMap {
            listOf(BoxingFoe.Windup(it), BoxingFoe.Punch(it), BoxingFoe.Whiff(it), BoxingFoe.Hurt(it))
        } + listOf(
            BoxingFoe.Idle, BoxingFoe.Open, BoxingFoe.Block, BoxingFoe.Stunned,
            BoxingFoe.Down, BoxingFoe.Out, BoxingFoe.Rise, BoxingFoe.Victory,
        )

    private val mePoses: List<BoxingMe> =
        listOf(BoxingSide.LEFT, BoxingSide.RIGHT).flatMap {
            listOf(BoxingMe.Dodge(it), BoxingMe.Jab(it), BoxingMe.Hook(it), BoxingMe.Blocked(it))
        } + (1..4).map { BoxingMe.Combo(it) } + listOf(
            BoxingMe.Idle, BoxingMe.Uppercut, BoxingMe.Miss, BoxingMe.Hurt,
            BoxingMe.Down, BoxingMe.Rise, BoxingMe.Victory,
        )

    @Test
    fun ringDrawsForEveryFighterPoseAndStage() {
        var frames = 0
        for (fighter in 0 until BoxingRules.FIGHTERS) {
            for (hurting in listOf(false, true)) {
                for ((n, foe) in foePoses.withIndex()) {
                    val me = mePoses[n % mePoses.size]
                    val stage = (fighter + n) % BkRing.STAGES.size
                    val buf = BkRing.ring(fighter, foe to 1000, me to 1000, hurting, 1000 + 37 * n, stage)
                    assertEquals(BkRing.ART_W * BkRing.ART_H, buf.size)
                    frames++
                }
            }
        }
        // Every own pose against a fixed opponent, and every stage.
        for (me in mePoses) {
            for (t in listOf(1000, 1060, 1500)) {
                assertEquals(BkRing.ART_W * BkRing.ART_H, BkRing.ring(0, BoxingFoe.Idle to 1000, me to 1000, false, t, 0).size)
                frames++
            }
        }
        for (stage in BkRing.STAGES.indices) {
            assertEquals(BkRing.ART_W * BkRing.ART_H, BkRing.ring(1, BoxingFoe.Idle to 0, BoxingMe.Idle to 0, false, 5, stage).size)
            frames++
        }
        assertTrue(frames > 0)
    }

    @Test
    fun ringDrawsABannerOverTheTop() {
        val plain = BkRing.ring(0, BoxingFoe.Idle to 0, BoxingMe.Idle to 0, false, 0, 0)
        val banner = BkRing.Banner("ROUND 1", 0xFFE8B020.toInt(), 36, 3, 0xFF3A0A0C.toInt())
        val withBanner = BkRing.ring(0, BoxingFoe.Idle to 0, BoxingMe.Idle to 0, false, 0, 0, banner)
        assertEquals(plain.size, withBanner.size)
        assertTrue("the banner changed no pixel", !plain.contentEquals(withBanner))
    }

    @Test
    fun splashAndPortraitsDraw() {
        for (t in listOf(0, 300, 1500, 2100, 7000)) {
            assertEquals(BkRing.SCREEN_W * BkRing.SCREEN_H, BkRing.splash(t, 12345).size)
        }
        for (fighter in 0 until BoxingRules.FIGHTERS) {
            for (locked in listOf(false, true)) {
                assertEquals(
                    BkRing.PORTRAIT * BkRing.PORTRAIT,
                    BkRing.portrait(fighter, 0, locked, 0xFF120E1C.toInt()).size
                )
            }
        }
    }
}
