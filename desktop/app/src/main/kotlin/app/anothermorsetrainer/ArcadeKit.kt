package app.anothermorsetrainer

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import app.anothermorsetrainer.morsekit.GameAnnouncer
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import kotlin.math.ceil

// What the arcade games share in the app layer: a sprite drawn once for
// stamping, and the step that plays what the sound channel has waiting.

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

/**
 * Plays what the channel has waiting, if it is free, at [timing] in the
 * sidetone pitch: a new enemy's character or callsign, or a tapped replay.
 * An arrival's end goes to [toneEnd], so a hit's reaction time runs from the
 * end of its sound.
 */
fun GameAnnouncer.playNext(player: MorsePlayer, timing: MorseTiming, toneEnd: MutableMap<Int, Long>) {
    val now = System.nanoTime() / 1e9
    val sound = next(now) ?: return
    val secs = player.replaySound(MorseItem.Playable.Text(sound.text), Settings.sidetoneHz, timing)
    if (sound is GameAnnouncer.Sound.Arrival) {
        toneEnd[sound.id] = System.currentTimeMillis() + (secs * 1000).toLong()
    }
    started(now, secs, timing.wordGap)
}
