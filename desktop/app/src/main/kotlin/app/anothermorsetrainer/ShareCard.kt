package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the Brag Sheet highlights to a shareable PNG. Drawn with a plain
 * [Graphics2D] (not an off-screen composition) so it's robust across Compose
 * versions, and mirrors the iOS `BragShareCard` and the Android card, which
 * draws the same layout on an `android.graphics.Canvas`.
 *
 * Desktop: there is no system share sheet, so [share] copies the image (with
 * the one-line text as a plain-text alternative) to the clipboard and [save]
 * asks where to write the PNG — both through [DesktopShare].
 */
object ShareCard {
    private val NAVY_TOP = Color(0x05, 0x12, 0x1C)
    private val NAVY = Color(0x0B, 0x1A, 0x2D)
    private val TEAL = Color(0x2C, 0xC0, 0xD1)
    private val WHITE = Color(0xF2, 0xF6, 0xFA)
    private val SUB = Color(0x9D, 0xB2, 0xC6)
    private val ORANGE = Color(0xEF, 0x9F, 0x27)

    /** Copies the card and its text line to the clipboard. False if the clipboard refused. */
    fun share(): Boolean = DesktopShare.copyImage(render(), shareText())

    /** Asks where to save the card as a PNG; the file written, or null if cancelled or failed. */
    fun save(): File? = DesktopShare.saveImage(render(), "brag-sheet.png")

    /** The text that travels with the image, as the Android share intent's EXTRA_TEXT. */
    private fun shareText(): String =
        "${Stats.currentStreak}-day Morse streak — ${Stats.totalAttempts} copied at " +
            "${(Stats.overallAccuracy * 100).roundToInt()}%. anothermorsetrainer.app"

    /** The 1080×600 card itself. */
    fun render(): BufferedImage {
        val w = 1080
        val h = 600
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            paintBackground(g, w, h)

            val margin = 64f
            text(g, "Another Morse Trainer", margin, 110f, WHITE, 46f, bold = true)

            val snapshot = EngineStore.snapshot()

            val streak = "${Stats.currentStreak}"
            text(g, streak, margin, 290f, WHITE, 150f, bold = true)
            val streakW = width(g, streak, 150f, bold = true)
            text(g, "day streak", margin + streakW + 28f, 250f, SUB, 44f)
            text(g, "longest ${Stats.longestStreak}", margin + streakW + 28f, 300f, TEAL, 36f)
            // The Characters stage, trailing the streak row (iOS BragShareCard), right-aligned.
            val stage = stageDisplayName(snapshot.stage)
            text(g, stage, w - margin - width(g, stage, 36f), 250f, TEAL, 36f)

            val total = MorseCode.kochOrder.size
            val mastered = masteredCount(snapshot)
            val stats = listOf(
                Triple("${Stats.totalAttempts}", "answered", WHITE),
                Triple("${(Stats.overallAccuracy * 100).roundToInt()}%", "accuracy", WHITE),
                Triple("$mastered/$total", "mastered", ORANGE)
            )
            var x = margin
            for ((value, label, color) in stats) {
                text(g, value, x, 410f, color, 64f, bold = true)
                text(g, label, x, 452f, SUB, 34f)
                x += 320f
            }

            Stats.bestTtrMs?.let {
                // Always seconds to two places, as iOS formats it ("0.84 s").
                val secs = "%.2f s".format(it / 1000.0)
                text(g, "Fastest copy $secs · ${Stats.totalSessions} sessions", margin, 520f, SUB, 34f)
            }
            text(g, "anothermorsetrainer.app", margin, 565f, TEAL, 32f)
        } finally {
            g.dispose()
        }
        return img
    }

    /**
     * Koch-order characters the engine counts as mastered — `CharacterStats.isMastered`
     * over its 5-attempt window, which also needs all five attempts to exist, so a
     * character copied once, quickly, is not yet "mastered". The same gate the
     * ladder uses to introduce the next character, and what iOS `bragStats` counts.
     */
    fun masteredCount(snapshot: ProgressiveCharacters.Snapshot = EngineStore.snapshot()): Int {
        val threshold = Settings.recognitionTargetSec
        val byChar = snapshot.engine.stats.associateBy { it.character }
        return MorseCode.kochOrder.count { byChar[it]?.isMastered(threshold) ?: false }
    }

    // ---- shared drawing helpers (also used by DailyDitShareCard) ----

    /** Vertical navy gradient, then a soft teal glow from the top right. */
    internal fun paintBackground(g: Graphics2D, w: Int, h: Int) {
        g.paint = GradientPaint(0f, 0f, NAVY_TOP, 0f, h.toFloat(), NAVY)
        g.fillRect(0, 0, w, h)
        g.paint = RadialGradientPaint(
            Point2D.Float(w * 0.82f, 0f),
            760f,
            floatArrayOf(0f, 1f),
            arrayOf(Color(TEAL.red, TEAL.green, TEAL.blue, 0x44), Color(TEAL.red, TEAL.green, TEAL.blue, 0)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE
        )
        g.fillRect(0, 0, w, h)
    }

    internal fun font(size: Float, bold: Boolean = false, mono: Boolean = false): Font =
        Font(if (mono) Font.MONOSPACED else Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 1)
            .deriveFont(size)

    /** Draws [s] with its baseline at [y], as Android's `Canvas.drawText` does. */
    internal fun text(
        g: Graphics2D, s: String, x: Float, y: Float, color: Color, size: Float,
        bold: Boolean = false, mono: Boolean = false
    ) {
        g.font = font(size, bold, mono)
        g.color = color
        g.drawString(s, x, y)
    }

    internal fun width(g: Graphics2D, s: String, size: Float, bold: Boolean = false, mono: Boolean = false): Float =
        font(size, bold, mono).getStringBounds(s, g.fontRenderContext).width.toFloat()
}
