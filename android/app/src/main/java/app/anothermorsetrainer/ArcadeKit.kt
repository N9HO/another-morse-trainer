package app.anothermorsetrainer

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.math.ceil

// What the arcade games share in the app layer: a sprite drawn once for
// stamping.

/**
 * [pattern] (`#` lit) drawn once in [tint] at [px] pixels a cell, for stamping
 * on a game field: painting every square of every sprite on every frame made
 * the fields slow to draw.
 */
fun pixelSprite(pattern: List<String>, px: Float, tint: Color): ImageBitmap {
    val image = ImageBitmap(ceil(pattern[0].length * px).toInt(), ceil(pattern.size * px).toInt())
    val canvas = Canvas(image)
    val paint = Paint().apply { color = tint }
    pattern.forEachIndexed { r, row ->
        row.forEachIndexed { c, cell ->
            if (cell == '#') canvas.drawRect(c * px, r * px, (c + 1) * px, (r + 1) * px, paint)
        }
    }
    return image
}
