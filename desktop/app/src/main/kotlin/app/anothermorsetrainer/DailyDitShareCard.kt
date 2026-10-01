package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.DailyDit
import app.anothermorsetrainer.morsekit.DailyDitGame
import app.anothermorsetrainer.morsekit.DailyDitTile
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File

/**
 * Renders the finished Daily Dit to a shareable PNG — brand navy/teal, the
 * puzzle headline, and the guess grid as coloured tiles with each row's
 * sending speed beside it. Drawn with a plain [java.awt.Graphics2D] like
 * [ShareCard], and mirrors the iOS and Android `DailyDitShareCard`. No letters
 * appear: the image spoils nothing the emoji grid didn't.
 *
 * Desktop: there is no system share sheet. [copy] (the Copy button, #266) and
 * [share] (the Share menu's "Copy image") put the image on the clipboard with
 * [DailyDitGame.shareText] as its plain-text alternative, one Transferable
 * carrying both flavours, so a target that only takes text still gets the
 * result; [save] writes the PNG where the user picks.
 */
object DailyDitShareCard {
    private val TEAL = Color(0x2C, 0xC0, 0xD1)
    private val WHITE = Color(0xF2, 0xF6, 0xFA)
    private val SUB = Color(0x9D, 0xB2, 0xC6)
    private val HAIRLINE = Color(0xFF, 0xFF, 0xFF, 0x14)

    // The same fills the on-screen grid uses (DailyDitScreen's Tile).
    private val TILE_CORRECT = Color(0x3D, 0x9E, 0x5C)
    private val TILE_PRESENT = Color(0xCA, 0xA0, 0x33)
    private val TILE_ABSENT = Color(0x1C, 0x32, 0x4C)

    /**
     * Copies the card and the share text to the clipboard. [chooserTitle] was
     * the Android share sheet's title; desktop has no chooser and ignores it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun share(game: DailyDitGame, chooserTitle: String): Boolean = copy(game)

    /**
     * The card on the clipboard as an AWT image flavour, with the share text as
     * a string flavour in the same Transferable (#266): an app that pastes
     * images gets the card, one that only takes text gets the text. If the
     * image can't be placed at all, the text alone is copied, so Copy never
     * does less than it did before.
     */
    fun copy(game: DailyDitGame): Boolean =
        DesktopShare.copyImage(render(game), game.shareText) || DesktopShare.copyText(game.shareText)

    /** Asks where to save the card as a PNG; the file written, or null if cancelled or failed. */
    fun save(game: DailyDitGame): File? =
        DesktopShare.saveImage(render(game), "daily-dit-${game.puzzleNumber}.png")

    fun render(game: DailyDitGame): BufferedImage {
        val w = 1080
        val margin = 64f
        val tile = 96f
        val gap = 18f
        val corner = 20f
        val gridTop = 330f
        // The grid is every guess, so the card grows with the day — a long
        // card is the story of a hard day, same as the emoji grid.
        val rows = game.rounds.size
        val gridBottom = gridTop + rows * (tile + gap) - gap
        val h = (gridBottom + 130f).toInt()

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            ShareCard.paintBackground(g, w, h)

            ShareCard.text(g, "Another Morse Trainer", margin, 104f, WHITE, 46f, bold = true)
            ShareCard.text(g, "Daily Dit #${game.puzzleNumber}", margin, 208f, WHITE, 84f, bold = true)
            ShareCard.text(g, scoreLine(game), margin, 268f, SUB, 40f)

            // Android's RoundRect takes corner radii; AWT's arc sizes are diameters.
            val arc = corner * 2f
            var y = gridTop
            for (round in game.rounds) {
                var x = margin
                for (t in round.tiles) {
                    g.color = when (t) {
                        DailyDitTile.CORRECT -> TILE_CORRECT
                        DailyDitTile.PRESENT -> TILE_PRESENT
                        DailyDitTile.ABSENT -> TILE_ABSENT
                    }
                    val rect = RoundRectangle2D.Float(x, y, tile, tile, arc, arc)
                    g.fill(rect)
                    // An absent tile is near the field colour; a hairline keeps it
                    // legible, as on-screen empty tiles get.
                    if (t == DailyDitTile.ABSENT) {
                        g.color = HAIRLINE
                        g.stroke = BasicStroke(3f)
                        g.draw(rect)
                    }
                    x += tile + gap
                }
                ShareCard.text(g, DailyDit.formatWpm(round.wpm), x + 6f, y + tile / 2f + 13f, SUB, 36f, mono = true)
                y += tile + gap
            }

            ShareCard.text(g, DailyDit.SHARE_LINK, margin, h - 52f, TEAL, 36f)
        } finally {
            g.dispose()
        }
        return img
    }

    /** The headline's score, minus the "Daily Dit #N" the title already says. */
    private fun scoreLine(game: DailyDitGame): String = buildString {
        game.solvedWpm?.let { append("${DailyDit.formatWpm(it)} WPM · ") }
        append(DailyDit.count(game.guessesUsed, "guess", "guesses"))
        append(" · ")
        append(DailyDit.count(game.listens, "listen", "listens"))
        if (game.hideReference) append(" · no reference")
    }
}
