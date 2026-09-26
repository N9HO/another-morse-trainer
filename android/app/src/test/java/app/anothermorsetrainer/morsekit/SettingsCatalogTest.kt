package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings map (#236), pinned against `fixtures/settings-catalog.json` —
 * the same file the iOS `MorseKitCheck` harness reads. It holds both apps to
 * one list of category names in one order, one category for every setting
 * they share, and one set of search answers, so a support reply that says
 * "Settings › Keys & Sending" is right on either phone.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class SettingsCatalogTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("settings-catalog.json")
        assertNotNull("fixtures/settings-catalog.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    @Test
    fun categoriesMatchTheFixtureInOrder() {
        val cats = fixture.getJSONArray("categories")
        val expected = (0 until cats.length()).map { cats.getJSONObject(it).let { c -> c.getString("id") to c.getString("title") } }
        assertEquals(expected, SettingsCategory.entries.map { it.id to it.title })
    }

    @Test
    fun everySharedSettingIsInTheFixturesCategory() {
        val byId = SettingsCatalog.entries.associateBy { it.id }
        val shared = fixture.getJSONArray("entries")
        for (i in 0 until shared.length()) {
            val row = shared.getJSONObject(i)
            val id = row.getString("id")
            val entry = byId[id]
            assertNotNull("shared setting $id is missing from the Android catalog", entry)
            assertEquals("category of $id", row.getString("category"), entry!!.category.id)
        }
    }

    @Test
    fun idsAreUniqueAndEveryCategoryHasASetting() {
        val ids = SettingsCatalog.entries.map { it.id }
        assertEquals("duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size, ids.toSet().size)
        for (category in SettingsCategory.entries) {
            assertTrue("${category.title} has no searchable setting", SettingsCatalog.entries.any { it.category == category })
            assertTrue("${category.title} has no section", category.sections.isNotEmpty())
        }
    }

    @Test
    fun catalogIsInCategoryThenSectionOrder() {
        val order = SettingsCatalog.entries.map { it.section.ordinal }
        assertEquals("entries must follow section order", order.sorted(), order)
    }

    @Test
    fun searchAnswersMatchTheFixture() {
        val queries = fixture.getJSONArray("queries")
        for (i in 0 until queries.length()) {
            val q = queries.getJSONObject(i)
            val name = q.getString("name")
            val got = SettingsCatalog.search(q.getString("query")).map { it.id }
            if (q.optBoolean("empty", false)) {
                assertTrue("$name: expected no results, got $got", got.isEmpty())
            }
            q.optJSONArray("includes")?.strings()?.forEach { id ->
                assertTrue("$name: \"${q.getString("query")}\" should find $id, got $got", id in got)
            }
            q.optJSONArray("excludes")?.strings()?.forEach { id ->
                assertFalse("$name: \"${q.getString("query")}\" should not find $id, got $got", id in got)
            }
            if (q.has("first")) {
                assertEquals("$name: top result", q.getString("first"), got.firstOrNull())
            }
        }
    }
}
