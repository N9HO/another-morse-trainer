package app.anothermorsetrainer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import app.anothermorsetrainer.morsekit.DailyDit
import app.anothermorsetrainer.morsekit.DailyDitGame
import app.anothermorsetrainer.morsekit.DailyDitTile
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the finished Daily Dit to a shareable PNG — brand navy/teal, the
 * puzzle headline, and the guess grid as coloured tiles with each row's
 * sending speed beside it — then fires a share sheet with
 * [DailyDitGame.shareText] attached as [Intent.EXTRA_TEXT], so the pasteable
 * text still travels with the image. Drawn with a plain [Canvas] like
 * [ShareCard], and mirrors the iOS `DailyDitShareCard`. No letters appear:
 * the image spoils nothing the emoji grid didn't.
 */
object DailyDitShareCard {
    private val NAVY_TOP = 0xFF05121C.toInt()
    private val NAVY = 0xFF0B1A2D.toInt()
    private val TEAL = 0xFF2CC0D1.toInt()
    private val WHITE = 0xFFF2F6FA.toInt()
    private val SUB = 0xFF9DB2C6.toInt()
    private val HAIRLINE = 0x14FFFFFF

    // The same fills the on-screen grid uses (DailyDitScreen's Tile).
    private val TILE_CORRECT = 0xFF3D9E5C.toInt()
    private val TILE_PRESENT = 0xFFCAA033.toInt()
    private val TILE_ABSENT = 0xFF1C324C.toInt()

    fun share(context: Context, game: DailyDitGame, chooserTitle: String) {
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

        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)

        Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), NAVY_TOP, NAVY, Shader.TileMode.CLAMP)
        }.also { canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), it) }
        Paint().apply {
            shader = RadialGradient(w * 0.82f, 0f, 760f,
                (0x44 shl 24) or (TEAL and 0xFFFFFF), TEAL and 0xFFFFFF, Shader.TileMode.CLAMP)
        }.also { canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), it) }

        fun paint(color: Int, size: Float, bold: Boolean = false) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }

        canvas.drawText("Another Morse Trainer", margin, 104f, paint(WHITE, 46f, true))
        canvas.drawText("Daily Dit #${game.puzzleNumber}", margin, 208f, paint(WHITE, 84f, true))
        canvas.drawText(scoreLine(game), margin, 268f, paint(SUB, 40f))

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = HAIRLINE
        }
        val wpmPaint = paint(SUB, 36f).apply { typeface = Typeface.MONOSPACE }
        var y = gridTop
        for (round in game.rounds) {
            var x = margin
            for (t in round.tiles) {
                fill.color = when (t) {
                    DailyDitTile.CORRECT -> TILE_CORRECT
                    DailyDitTile.PRESENT -> TILE_PRESENT
                    DailyDitTile.ABSENT -> TILE_ABSENT
                }
                val rect = RectF(x, y, x + tile, y + tile)
                canvas.drawRoundRect(rect, corner, corner, fill)
                // An absent tile is near the field colour; a hairline keeps it
                // legible, as on-screen empty tiles get.
                if (t == DailyDitTile.ABSENT) canvas.drawRoundRect(rect, corner, corner, stroke)
                x += tile + gap
            }
            canvas.drawText(DailyDit.formatWpm(round.wpm), x + 6f, y + tile / 2f + 13f, wpmPaint)
            y += tile + gap
        }

        canvas.drawText(DailyDit.SHARE_LINK, margin, h - 52f, paint(TEAL, 36f))

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "daily-dit.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, game.shareText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
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
