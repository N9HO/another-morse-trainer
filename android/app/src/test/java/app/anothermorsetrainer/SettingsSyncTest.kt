package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.SyncSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsSync]'s local-to-wire tables held to `fixtures/sync-wire.json`
 * `settings.keys[].kotlin`: every enum key maps each of this port's
 * constants to the wire value at the same place, both ways.
 */
class SettingsSyncTest {

    private val keys: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("sync-wire.json")
        assertNotNull("fixtures/sync-wire.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText()).getJSONObject("settings").getJSONObject("keys")
    }

    @Test
    fun `every enum key maps this port's constants to the fixture's wire values`() {
        val enumKeys = keys.keys().asSequence().filter { keys.getJSONObject(it).getString("kind") == "enum" && it != "setting.wordPool" }.toSet()
        assertEquals(enumKeys, SettingsSync.enums.keys)
        for (key in enumKeys) {
            val spec = keys.getJSONObject(key)
            val kotlin = spec.getJSONArray("kotlin").let { a -> (0 until a.length()).map { a.getString(it) } }
            val wire = spec.getJSONArray("values").let { a -> (0 until a.length()).map { a.getString(it) } }
            val local = SettingsSync.enums.getValue(key)
            assertEquals(key, kotlin, local.map { it.name })
            for ((i, constant) in local.withIndex()) {
                assertEquals(key, wire[i], SettingsSync.wireOf(key, constant))
                assertEquals(key, constant, SettingsSync.localConstant(key, wire[i]))
            }
        }
    }

    @Test
    fun `the word pool maps the stored counts to the fixture's wire values`() {
        val spec = keys.getJSONObject("setting.wordPool")
        val kotlin = spec.getJSONArray("kotlin").let { a -> (0 until a.length()).map { a.getInt(it) } }
        assertEquals(kotlin, SettingsSync.wordCounts)
    }

    @Test
    fun `the call-sign shapes' codes are the fixture's Kotlin names' wire values`() {
        for (key in listOf("setting.rapidFireFormats", "setting.pileupFormats")) {
            val spec = keys.getJSONObject(key)
            val kotlin = spec.getJSONArray("kotlin").let { a -> (0 until a.length()).map { a.getString(it) } }
            val wire = spec.getJSONArray("values").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(kotlin, app.anothermorsetrainer.morsekit.CallsignFormat.entries.map { it.name })
            assertEquals(wire, app.anothermorsetrainer.morsekit.CallsignFormat.entries.map { it.code })
        }
    }

    @Test
    fun `only settings all apps share are keys, and none of the device or consent settings`() {
        val deviceOnly = listOf("bluetoothKeepAlive", "haptics", "onScreenKey", "paddleMode", "paddleSwap", "voiceAnswers",
            "answerByKeying", "reminders", "leaderboardEnabled", "leaderboardName", "buddyOnHome")
        assertTrue(SyncSettings.KEYS.none { key -> deviceOnly.any { key.equals(SyncSettings.PREFIX + it, ignoreCase = true) } })
    }
}
