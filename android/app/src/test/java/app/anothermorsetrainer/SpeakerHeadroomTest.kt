package app.anothermorsetrainer

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.log10

/**
 * The route gain decision (#259), pinned without an `AudioTrack`: −6 dB on the
 * built-in speaker, full level everywhere else, including a route that is not
 * known yet.
 */
class SpeakerHeadroomTest {

    @Test
    fun builtInSpeakerGetsSixDecibelsOfHeadroom() {
        val gain = SpeakerHeadroom.gainFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertEquals(0.25f, gain, 1e-4f)
        assertEquals(-6.0, 20 * log10(gain.toDouble()), 0.01)
    }

    @Test
    fun everyOtherRouteKeepsFullLevel() {
        val others = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
        for (type in others) {
            assertEquals("device type $type", 1f, SpeakerHeadroom.gainFor(type), 0f)
        }
    }

    @Test
    fun unknownRouteKeepsFullLevel() {
        assertEquals(1f, SpeakerHeadroom.gainFor(null), 0f)
    }
}
