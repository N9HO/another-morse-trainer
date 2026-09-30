package app.anothermorsetrainer

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Output gain for the route a track is playing through (#259, the Android side
 * of #228).
 *
 * The synth hands over a clean tone at 0.9 of full scale, and on headphones
 * that is what should be heard. A phone's built-in speaker runs it through its
 * own limiter and speaker protection, which work in blocks of roughly one
 * output buffer (~20 ms). Above ~50 WPM whole elements begin and end inside one
 * block, and near full scale that processing turns them into pops and clicks
 * (reported on an iPhone's speaker at 75 WPM, clearing at about 50 WPM, and
 * assumed of Android's speaker path too). −6 dB keeps the tone below where it
 * engages. The iOS app does the same in `MorsePlayer.routeGain()`.
 *
 * Applied as the track's volume, never to the samples, so `MorseSynth` and
 * `fixtures/render.json` are untouched. Headphones, Bluetooth, USB and HDMI
 * keep full level.
 */
object SpeakerHeadroom {

    /** −6 dB: 10^(−6/20). */
    const val BUILT_IN_SPEAKER_GAIN = 0.5012f

    /**
     * The volume for a track routed to a device of [routedDeviceType] (an
     * `AudioDeviceInfo.TYPE_*`), or `null` when the route is not known yet.
     * An unknown route plays at full level: the routing callback corrects it
     * as soon as the route is established.
     */
    fun gainFor(routedDeviceType: Int?): Float =
        if (routedDeviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) BUILT_IN_SPEAKER_GAIN else 1f

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Keep [track]'s volume matched to its route for as long as it lives: set
     * it now from the current route, and again on every route change
     * (headphones plugged in or pulled out, Bluetooth connecting mid-drill).
     * Call after `play()`, when the route has usually been established; before
     * then [AudioTrack.getRoutedDevice] can be null, and the listener catches
     * up when it is.
     */
    fun follow(track: AudioTrack) {
        apply(track)
        runCatching {
            track.addOnRoutingChangedListener(
                AudioRouting.OnRoutingChangedListener { router -> apply(router as AudioTrack) },
                main
            )
        }
    }

    private fun apply(track: AudioTrack) {
        runCatching { track.setVolume(gainFor(track.routedDevice?.type)) }
    }
}
