package app.anothermorsetrainer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import app.anothermorsetrainer.morsekit.MorseCode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Renders the Brag Sheet highlights to a shareable PNG and fires a share sheet.
 * Drawn with a plain [Canvas] (not an off-screen composition) so it's robust
 * across devices and Compose versions, and mirrors the iOS `BragShareCard`.
 */
object ShareCard {
    private val NAVY_TOP = 0xFF05121C.toInt()
    private val NAVY = 0xFF0B1A2D.toInt()
    private val TEAL = 0xFF2CC0D1.toInt()
    private val WHITE = 0xFFF2F6FA.toInt()
    private val SUB = 0xFF9DB2C6.toInt()
    private val ORANGE = 0xFFEF9F27.toInt()

    fun share(context: Context) {
        val w = 1080
        val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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

        val margin = 64f
        canvas.drawText("Another Morse Trainer", margin, 110f, paint(WHITE, 46f, true))

        val streak = "${Stats.currentStreak}"
        val streakPaint = paint(WHITE, 150f, true)
        canvas.drawText(streak, margin, 290f, streakPaint)
        val streakW = streakPaint.measureText(streak)
        canvas.drawText("day streak", margin + streakW + 28f, 250f, paint(SUB, 44f))
        canvas.drawText("longest ${Stats.longestStreak}", margin + streakW + 28f, 300f, paint(TEAL, 36f))

        val total = MorseCode.kochOrder.size
        val mastered = masteredCount()
        val stats = listOf(
            Triple("${Stats.totalAttempts}", "answered", WHITE),
            Triple("${(Stats.overallAccuracy * 100).roundToInt()}%", "accuracy", WHITE),
            Triple("$mastered/$total", "mastered", ORANGE)
        )
        var x = margin
        for ((value, label, color) in stats) {
            canvas.drawText(value, x, 410f, paint(color, 64f, true))
            canvas.drawText(label, x, 452f, paint(SUB, 34f))
            x += 320f
        }

        Stats.bestTtrMs?.let {
            val secs = if (it >= 1000) "%.2fs".format(it / 1000.0) else "${it}ms"
            canvas.drawText("Fastest copy $secs · ${Stats.totalSessions} sessions",
                margin, 520f, paint(SUB, 34f))
        }
        canvas.drawText("anothermorsetrainer.app", margin, 565f, paint(TEAL, 32f))

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "brag-sheet.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT,
                "${Stats.currentStreak}-day Morse streak — ${Stats.totalAttempts} copied at " +
                    "${(Stats.overallAccuracy * 100).roundToInt()}%. anothermorsetrainer.app")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share your progress"))
    }

    /** Characters copied accurately and within the recognition target — "mastered". */
    fun masteredCount(): Int {
        val targetMs = Settings.recognitionTargetSec * 1000.0
        return Stats.charStats.count { (_, agg) ->
            val median = agg.medianMs
            agg.accuracy >= 0.9 && median != null && median <= targetMs
        }
    }
}
