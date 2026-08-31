package app.anothermorsetrainer

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Lightweight wrapper around Android's [Vibrator] so the UI can add tactile
 * confirmation without boilerplate. No-ops on devices without a vibrator.
 *
 * Ported from the iOS MorseTrainerApp/Haptics.swift. iOS's semantic feedback
 * generators (success/error/selection/tap) become short vibration waveforms.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** A correct answer — a light double tick. */
    fun success() = play(longArrayOf(0, 28, 55, 28))

    /** A wrong answer — a firmer double buzz. */
    fun error() = play(longArrayOf(0, 90, 60, 90))

    /** A light tick for selections (mode tiles, choices). */
    fun selection() = play(longArrayOf(0, 15))

    /** A soft tap for primary taps like Start / Reveal. */
    fun tap() = play(longArrayOf(0, 22))

    private fun play(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }
}
