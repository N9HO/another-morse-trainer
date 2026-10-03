package app.anothermorsetrainer

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * A built-in news feed the Short Stories mode can pull headlines from, so the
 * learner decodes real, unpredictable text — the modern version of W1AW
 * sending text from QST. Feeds are plain public RSS; each source lists a
 * couple of candidate URLs tried in order so a path change on the publisher's
 * side degrades gracefully instead of breaking the mode.
 *
 * Port of the iOS NewsFetcher (URLSession/XMLParser → OkHttp/XmlPullParser on
 * Android, OkHttp/StAX here).
 */
enum class NewsSource(val label: String, val attribution: String, val feedUrls: List<String>) {
    HAM_RADIO(
        "Ham radio news", "Amateur Radio Daily",
        listOf(
            "https://daily.hamweekly.com/rss/",
            "https://daily.hamweekly.com/feed/",
            "https://daily.hamweekly.com/index.xml",
            "https://daily.hamweekly.com/rss.xml"
        )
    ),
    WORLD(
        "World news (BBC)", "BBC World News",
        listOf("https://feeds.bbci.co.uk/news/world/rss.xml")
    ),
    TOP_STORIES(
        "Top stories (NPR)", "NPR Top Stories",
        listOf("https://feeds.npr.org/1001/rss.xml")
    ),
    TECHNOLOGY(
        "Technology (NPR)", "NPR Technology",
        listOf("https://feeds.npr.org/1019/rss.xml")
    )
}

/**
 * Downloads a news source's feed and hands back its headlines. Results are
 * cached (most recent successful fetch per source) so a dead radio — er,
 * network — still leaves something to decode.
 *
 * Desktop: the cache is the `amt_news_cache` [Prefs] file in [AppDirs.config]
 * (Android keeps it in SharedPreferences of the same name).
 */
class NewsFetcher {

    /** One feed entry, still in plain English (sanitizing to sendable Morse
     *  text is the caller's job so display and keying stay in one place). */
    data class Item(val title: String, val summary: String)

    companion object {
        /** How many headlines a session keeps from a feed. */
        const val MAX_ITEMS = 12
        const val NETWORK_ERROR = "Could not reach the news feed."
        const val EMPTY_FEED_ERROR = "The feed came back empty."
    }

    private val prefs: Prefs = Prefs.open("amt_news_cache")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mainHandler = MainHandler()

    /**
     * Fetch [source], trying its candidate URLs in order. Calls [onResult] on
     * the UI thread with the parsed items, or [onError] with a message.
     */
    fun fetch(source: NewsSource, onResult: (List<Item>) -> Unit, onError: (String) -> Unit) {
        attempt(source.feedUrls, source, onResult, onError)
    }

    private fun attempt(
        urls: List<String>,
        source: NewsSource,
        onResult: (List<Item>) -> Unit,
        onError: (String) -> Unit
    ) {
        val url = urls.firstOrNull()
        if (url == null) {
            mainHandler.post { onError(NETWORK_ERROR) }
            return
        }

        fun nextOrFail(message: String) {
            if (urls.size > 1) {
                attempt(urls.drop(1), source, onResult, onError)
            } else {
                mainHandler.post { onError(message) }
            }
        }

        val request = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            nextOrFail(NETWORK_ERROR)
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                nextOrFail(NETWORK_ERROR)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string() else null
                    if (body == null) {
                        nextOrFail(NETWORK_ERROR)
                        return
                    }
                    val items = parseFeed(body, MAX_ITEMS)
                    if (items.isEmpty()) {
                        nextOrFail(EMPTY_FEED_ERROR)
                        return
                    }
                    store(items, source)
                    mainHandler.post { onResult(items) }
                }
            }
        })
    }

    // ---- Last-good cache ----

    /** The most recent successful fetch for [source], if any. */
    fun cached(source: NewsSource): Pair<List<Item>, Long>? {
        val raw = prefs.getString("cache-${source.name}", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val fetchedAt = obj.getLong("fetchedAt")
            val array = obj.getJSONArray("items")
            val items = ArrayList<Item>(array.length())
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                items.add(Item(entry.getString("title"), entry.optString("summary")))
            }
            if (items.isEmpty()) null else items to fetchedAt
        } catch (_: Exception) {
            null
        }
    }

    private fun store(items: List<Item>, source: NewsSource) {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().put("title", item.title).put("summary", item.summary))
        }
        val obj = JSONObject()
            .put("fetchedAt", System.currentTimeMillis())
            .put("items", array)
        prefs.edit { putString("cache-${source.name}", obj.toString()) }
    }

    // ---- Minimal RSS 2.0 / Atom parsing ----

    /**
     * Collects each item's title and description/summary and ignores
     * everything else. Tolerant of CDATA and of feeds that put HTML in the
     * description (stripping happens downstream). Items collected before a
     * mid-stream error still count.
     *
     * Desktop: the JDK's StAX reader stands in for Android's XmlPullParser,
     * with DTDs and external entities off (a feed is untrusted input). A
     * prefixed element keeps its prefix ("media:title"), so it is not taken
     * for the item's own title — as the non-namespace-aware pull parser on
     * Android saw it.
     */
    private fun parseFeed(xml: String, limit: Int): List<Item> {
        val items = ArrayList<Item>()
        var reader: XMLStreamReader? = null
        try {
            val factory = XMLInputFactory.newInstance()
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            val parser = factory.createXMLStreamReader(StringReader(xml))
            reader = parser
            var inItem = false
            var element = ""
            var title = StringBuilder()
            var summary = StringBuilder()
            while (parser.hasNext() && items.size < limit) {
                when (parser.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val name = qualifiedName(parser)
                        if (name == "item" || name == "entry") {
                            inItem = true
                            title = StringBuilder()
                            summary = StringBuilder()
                        }
                        element = name
                    }
                    // CDATA and resolved entity text ("&amp;") arrive as their
                    // own events; all carry text to keep.
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                    XMLStreamConstants.SPACE, XMLStreamConstants.ENTITY_REFERENCE ->
                        if (inItem) {
                            val text: String? = parser.text
                            if (text != null) {
                                when (element) {
                                    "title" -> title.append(text)
                                    "description", "summary" -> summary.append(text)
                                }
                            }
                        }
                    XMLStreamConstants.END_ELEMENT -> {
                        val name = qualifiedName(parser)
                        if (name == "item" || name == "entry") {
                            inItem = false
                            val t = title.toString().trim()
                            if (t.isNotEmpty()) items.add(Item(t, summary.toString().trim()))
                        }
                        element = ""
                    }
                }
            }
        } catch (_: Exception) {
            // Malformed tail — keep whatever parsed cleanly.
        } finally {
            runCatching { reader?.close() }
        }
        return items
    }

    /** The element's name as written, lowercased: "title", or "media:title" when prefixed. */
    private fun qualifiedName(parser: XMLStreamReader): String {
        val local: String = parser.localName ?: ""
        val prefix: String = parser.prefix ?: ""
        return (if (prefix.isEmpty()) local else "$prefix:$local").lowercase()
    }
}
