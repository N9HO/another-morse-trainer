package app.anothermorsetrainer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's copy, read from `values/strings.xml` on the classpath.
 *
 * The desktop UI was forked from android/, whose screens say
 * `stringResource(R.string.foo)`. Compose Desktop has no Android resource
 * system, so this file supplies the same call shapes over the same XML: the
 * build generates [R] (one Int per entry, plus the entry names) and this object
 * resolves an id through its name. Android's string-value rules are applied on
 * load — surrounding whitespace collapsed, `\'` `\"` `\n` `\t` `\\` `\@` `\?`
 * unescaped — and format arguments go through [String.format], exactly as
 * `Resources.getString(id, args)` does. With no arguments nothing is
 * formatted, so a literal `%` survives, as it does on Android.
 *
 * English only, as the other two apps are. Quantity strings use English
 * plural rules: "one" for 1, "other" otherwise.
 */
object AppStrings {

    private class Table(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, List<String>>
    )

    private val table: Table by lazy { load() }

    fun get(id: Int, vararg args: Any?): String {
        val name = R.string.names.getOrNull(id) ?: return ""
        val raw = table.strings[name] ?: return name
        return format(raw, args)
    }

    fun quantity(id: Int, count: Int, vararg args: Any?): String {
        val name = R.plurals.names.getOrNull(id) ?: return ""
        val forms = table.plurals[name] ?: return name
        val raw = (if (count == 1) forms["one"] else null) ?: forms["other"] ?: forms.values.firstOrNull() ?: name
        return format(raw, args)
    }

    fun array(id: Int): Array<String> {
        val name = R.array.names.getOrNull(id) ?: return emptyArray()
        return table.arrays[name]?.toTypedArray() ?: emptyArray()
    }

    private fun format(raw: String, args: Array<out Any?>): String =
        if (args.isEmpty()) raw else runCatching { String.format(raw, *args) }.getOrDefault(raw)

    private fun load(): Table {
        val stream = AppStrings::class.java.classLoader.getResourceAsStream("values/strings.xml")
            ?: return Table(emptyMap(), emptyMap(), emptyMap())
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val arrays = HashMap<String, List<String>>()

        val s = doc.getElementsByTagName("string")
        for (i in 0 until s.length) {
            val e = s.item(i) as Element
            strings[e.getAttribute("name")] = unescape(e.textContent)
        }
        val p = doc.getElementsByTagName("plurals")
        for (i in 0 until p.length) {
            val e = p.item(i) as Element
            val items = e.getElementsByTagName("item")
            val forms = HashMap<String, String>()
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                forms[item.getAttribute("quantity")] = unescape(item.textContent)
            }
            plurals[e.getAttribute("name")] = forms
        }
        val a = doc.getElementsByTagName("string-array")
        for (i in 0 until a.length) {
            val e = a.item(i) as Element
            val items = e.getElementsByTagName("item")
            arrays[e.getAttribute("name")] = (0 until items.length).map { unescape(items.item(it).textContent) }
        }
        return Table(strings, plurals, arrays)
    }

    /** Android's string-value rules: collapse whitespace, then resolve escapes. */
    internal fun unescape(value: String): String {
        var v = value.replace(Regex("\\s+"), " ").trim()
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
        val out = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(n)   // \' \" \\ \@ \?
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }
}

/** Same call shape as Android's `stringResource`. */
@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String = AppStrings.get(id, *formatArgs)

/** Same call shape as Android's `pluralStringResource`. */
@Composable
fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String =
    AppStrings.quantity(id, count, *formatArgs)

/** Same call shape as Android's `stringArrayResource`. */
@Composable
fun stringArrayResource(id: Int): Array<String> = AppStrings.array(id)

/**
 * The non-composable half, for code that resolved copy through a `Resources`
 * or `Context` on Android (`LocalResources.current.getString(...)`).
 */
object AppResources {
    fun getString(id: Int, vararg formatArgs: Any?): String = AppStrings.get(id, *formatArgs)
    fun getQuantityString(id: Int, count: Int, vararg formatArgs: Any?): String =
        AppStrings.quantity(id, count, *formatArgs)
    fun getStringArray(id: Int): Array<String> = AppStrings.array(id)
}

val LocalResources = staticCompositionLocalOf { AppResources }
