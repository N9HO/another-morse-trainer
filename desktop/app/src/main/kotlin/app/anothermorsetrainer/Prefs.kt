package app.anothermorsetrainer

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the app keeps its files, per platform convention.
 *
 * - Windows: `%APPDATA%\AnotherMorseTrainer`. Under the MSIX (Store) install
 *   Windows redirects that path into the package's own private AppData, which
 *   is what the Store expects and what an uninstall removes.
 * - Linux: `$XDG_CONFIG_HOME/another-morse-trainer`, default
 *   `~/.config/another-morse-trainer`. Inside the Flatpak sandbox
 *   `XDG_CONFIG_HOME` is `~/.var/app/<app-id>/config`, so the same code lands
 *   in the sandbox's own directory.
 * - Anything else (a developer running `./gradlew run` on a Mac): the user's
 *   home, `~/.another-morse-trainer`.
 *
 * `AMT_CONFIG_DIR` overrides all of them; CI uses it to launch the app with a
 * seeded settings file.
 */
object AppDirs {
    val config: File by lazy {
        val override = System.getenv("AMT_CONFIG_DIR")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        val dir = when {
            !override.isNullOrBlank() -> File(override)
            os.startsWith("windows") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "AnotherMorseTrainer")
            os.contains("linux") || os.contains("nix") || os.contains("bsd") -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
                File(xdg, "another-morse-trainer")
            }
            else -> File(home, ".another-morse-trainer")
        }
        dir.mkdirs()
        dir
    }

    val isWindows: Boolean by lazy { System.getProperty("os.name").orEmpty().lowercase().startsWith("windows") }
    val isLinux: Boolean by lazy { System.getProperty("os.name").orEmpty().lowercase().contains("linux") }
}

/**
 * A small key-value store, one JSON file per name in [AppDirs.config].
 *
 * The desktop stand-in for the `SharedPreferences` files the Android port
 * keeps (`amt_settings`, `amt_stats`, …). The getters, [contains] and the
 * `edit { putX(...) }` block have the same shapes, so the stores ported from
 * android/ keep their key names, defaults and migration logic line for line,
 * and a settings file from one release reads in the next the same way.
 *
 * Values are held in memory and written through on every [edit] — atomically,
 * via a temporary file and a rename, so a crash mid-write leaves the previous
 * file rather than half of a new one. The files are small (the largest is the
 * session history, capped at 100 records), so the write stays on the calling
 * thread, as `SharedPreferences.commit()` would.
 */
class Prefs private constructor(private val file: File) {

    private val values = LinkedHashMap<String, Any>()

    init {
        runCatching {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                for (k in json.keys()) {
                    val v = json.get(k)
                    if (v != JSONObject.NULL) values[k] = v
                }
            }
        }
    }

    @Synchronized fun contains(key: String): Boolean = values.containsKey(key)

    @Synchronized fun getString(key: String, default: String?): String? =
        values[key]?.let { it as? String ?: it.toString() } ?: default

    @Synchronized fun getInt(key: String, default: Int): Int = (values[key] as? Number)?.toInt() ?: default

    @Synchronized fun getLong(key: String, default: Long): Long = (values[key] as? Number)?.toLong() ?: default

    @Synchronized fun getFloat(key: String, default: Float): Float = (values[key] as? Number)?.toFloat() ?: default

    @Synchronized fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    @Synchronized fun getStringSet(key: String, default: Set<String>?): Set<String>? {
        val v = values[key] ?: return default
        return when (v) {
            is org.json.JSONArray -> (0 until v.length()).map { v.getString(it) }.toSet()
            is Collection<*> -> v.map { it.toString() }.toSet()
            else -> default
        }
    }

    /** Every stored pair, as `SharedPreferences.getAll()` returns them. */
    @Synchronized fun getAll(): Map<String, Any?> = LinkedHashMap(values)

    /** A batch of changes, applied and written together when the block returns. */
    fun edit(block: Editor.() -> Unit) {
        val e = Editor()
        e.block()
        e.apply()
    }

    /** The unbatched form, for code that holds an editor across statements. */
    fun edit(): Editor = Editor()

    inner class Editor internal constructor() {
        private val puts = LinkedHashMap<String, Any?>()
        private var clearAll = false

        fun putString(key: String, value: String?): Editor = also { puts[key] = value }
        fun putInt(key: String, value: Int): Editor = also { puts[key] = value }
        fun putLong(key: String, value: Long): Editor = also { puts[key] = value }
        fun putFloat(key: String, value: Float): Editor = also { puts[key] = value.toDouble() }
        fun putBoolean(key: String, value: Boolean): Editor = also { puts[key] = value }
        fun putStringSet(key: String, value: Set<String>?): Editor =
            also { puts[key] = value?.let { org.json.JSONArray(it.toList()) } }
        fun remove(key: String): Editor = also { puts[key] = null }
        fun clear(): Editor = also { clearAll = true }

        fun apply() { commit() }

        fun commit(): Boolean {
            synchronized(this@Prefs) {
                if (clearAll) values.clear()
                for ((k, v) in puts) if (v == null) values.remove(k) else values[k] = v
                return write()
            }
        }
    }

    private fun write(): Boolean = runCatching {
        val json = JSONObject()
        for ((k, v) in values) json.put(k, v)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrElse {
        // ATOMIC_MOVE is refused on some filesystems; a plain replace is the fallback.
        runCatching {
            val json = JSONObject()
            for ((k, v) in values) json.put(k, v)
            file.writeText(json.toString())
            true
        }.getOrDefault(false)
    }

    companion object {
        private val open = HashMap<String, Prefs>()

        /** The store called [name] (`amt_settings`, …), shared by every caller. */
        @Synchronized
        fun open(name: String): Prefs = open.getOrPut(name) { Prefs(File(AppDirs.config, "$name.json")) }
    }
}
