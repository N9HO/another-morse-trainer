package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * The pure half of account sync (Phase 2 of the accounts work): the wire form
 * of a session record, the mode-id table, the practice-day batch, the five
 * progress-state codecs and PKCE. No network and no storage, so the JUnit
 * suite can hold every rule here to `fixtures/sync-wire.json`, the same file
 * the Swift harness and the Android tree read. The contract is the accounts
 * Worker's README (sections 7 and 9). The merge rules are in
 * AccountSyncMerge.kt; the OkHttp client is `AccountClient` in the app
 * package.
 */
object SyncWire {
    /** `schemaVersion` on every record this build sends. */
    const val SCHEMA_VERSION = 1

    /** `client` on `verify/start` for this port. */
    const val CLIENT = "amt-desktop"

    /**
     * The canonical mode id (the iOS `TrainingMode` raw value) for every mode
     * string this port passes to `Stats.record`. All 25, pinned both ways by
     * the fixture's `modeIds.kotlin`.
     */
    val modeIds: Map<String, String> = linkedMapOf(
        "Journey" to "journey",
        "Characters" to "characters",
        "Common Words" to "words",
        "CW 77" to "cw77",
        "CW 77 Listen" to "cw77Listen",
        "Abbreviations" to "abbreviations",
        "Q-Codes" to "qCodes",
        "Prosigns" to "prosigns",
        "Head Copy" to "headCopy",
        "Type It" to "typed",
        "Sending" to "sending",
        "Confusion Drill" to "confusion",
        "Listen" to "listen",
        "Pileup" to "qso",
        "Contest" to "contest",
        "Stories" to "story",
        "Code Exam" to "exam",
        "QRQ Speed" to "qrq",
        "Rapid Fire" to "rapidFire",
        "Morse Invaders" to "invaders",
        "CW Galaga" to "galaga",
        "Morse Defender" to "defender",
        "CW Dungeon" to "dungeon",
        "CW Frogger" to "frogger",
        "CW Asteroids" to "asteroids"
    )

    private val localModes: Map<String, String> = modeIds.entries.associate { (title, id) -> id to title }

    /** The wire id for a local mode string; one not in the table goes as it is. */
    fun wireMode(local: String): String = modeIds[local] ?: local

    /** The local mode string for a wire id; an id this build does not know stays the wire id. */
    fun localMode(wire: String): String = localModes[wire] ?: wire

    /** Seconds → whole milliseconds, round(s × 1000); null stays null. */
    fun toMs(seconds: Double?): Long? = seconds?.let { (it * 1000).roundToLong() }

    /** Whole milliseconds → seconds. */
    fun toSeconds(ms: Long?): Double? = ms?.let { it / 1000.0 }

    // ---- SessionRecord ----

    /** A record as `POST /v1/sync/sessions` takes it. Absent values are JSON null, never -1. */
    fun encodeSession(r: SessionRecord): JSONObject {
        val chars = JSONArray()
        for (c in r.characters) {
            chars.put(
                JSONObject()
                    .put("character", c.character)
                    .put("attempts", c.attempts)
                    .put("correct", c.correct)
                    .put("medianTtrMs", nullable(toMs(c.medianTTR)))
            )
        }
        return JSONObject()
            .put("id", r.id.toString().lowercase(Locale.ROOT))
            .put("date", r.date.toEpochMilli())
            .put("mode", wireMode(r.mode))
            .put("characterWpm", r.characterWPM)
            .put("effectiveWpm", r.effectiveWPM)
            .put("attempts", r.attempts)
            .put("correct", r.correct)
            .put("fastestTtrMs", nullable(toMs(r.fastestTTR)))
            .put("medianTtrMs", nullable(toMs(r.medianTTR)))
            .put("durationSeconds", nullable(r.durationSeconds))
            .put("score", nullable(r.score))
            .put("characters", chars)
            .put("activeCharacters", JSONArray(r.activeCharacters))
            .put("schemaVersion", SCHEMA_VERSION)
    }

    /**
     * A record as the server sends it (pull, snapshot). Throws on a record
     * missing a required field; callers drop that one row and keep the rest,
     * as `Stats.parseHistory` does. `characters` and `activeCharacters` are
     * absent on a summary row and read as empty.
     */
    fun decodeSession(o: JSONObject): SessionRecord {
        val charsArr = o.optJSONArray("characters") ?: JSONArray()
        val chars = (0 until charsArr.length()).map { i ->
            val c = charsArr.getJSONObject(i)
            SessionRecord.CharResult(
                character = c.getString("character"),
                attempts = c.getInt("attempts"),
                correct = c.getInt("correct"),
                medianTTR = toSeconds(optLong(c, "medianTtrMs"))
            )
        }
        val active = o.optJSONArray("activeCharacters") ?: JSONArray()
        return SessionRecord(
            id = UUID.fromString(o.getString("id").lowercase(Locale.ROOT)),
            date = Instant.ofEpochMilli(o.getLong("date")),
            mode = localMode(o.getString("mode")),
            characterWPM = o.optInt("characterWpm", 0),
            effectiveWPM = o.optInt("effectiveWpm", 0),
            attempts = o.getInt("attempts"),
            correct = o.getInt("correct"),
            fastestTTR = toSeconds(optLong(o, "fastestTtrMs")),
            medianTTR = toSeconds(optLong(o, "medianTtrMs")),
            durationSeconds = if (o.isNull("durationSeconds")) null else o.getDouble("durationSeconds"),
            characters = chars,
            activeCharacters = (0 until active.length()).map { active.getString(it) },
            score = if (o.isNull("score")) null else o.getInt("score")
        )
    }

    /** A list of wire records, one bad row dropped and the rest kept. */
    fun decodeSessions(arr: JSONArray?): List<SessionRecord> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> runCatching { decodeSession(arr.getJSONObject(i)) }.getOrNull() }
    }

    // ---- Practice days ----

    /** The `POST /v1/sync/days` body: this device's ledger, earliest day first. */
    fun encodeDays(days: Map<LocalDate, Int>): JSONObject {
        val arr = JSONArray()
        for ((day, seconds) in days.toSortedMap()) {
            arr.put(JSONObject().put("day", day.toString()).put("seconds", seconds))
        }
        return JSONObject().put("days", arr)
    }

    /** A `{ "yyyy-mm-dd": seconds }` object (days reply, snapshot); one bad day is dropped. */
    fun decodeDayMap(o: JSONObject?): Map<LocalDate, Int> {
        if (o == null) return emptyMap()
        val out = sortedMapOf<LocalDate, Int>()
        for (key in o.keys()) runCatching { out[LocalDate.parse(key)] = o.getInt(key) }
        return out
    }

    // ---- Progress state ----

    /** One progress-state key's value and when it last changed (epoch ms). */
    data class StateEntry(val value: Any, val updatedAt: Long)

    /** The `PUT /v1/sync/state` body. */
    fun encodeState(entries: Map<String, StateEntry>): JSONObject {
        val o = JSONObject()
        for ((key, e) in entries) o.put(key, JSONObject().put("value", e.value).put("updatedAt", e.updatedAt))
        return JSONObject().put("entries", o)
    }

    /** An `entries` or `state` object of a reply; an entry without a value or time is dropped. */
    fun decodeState(o: JSONObject?): Map<String, StateEntry> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, StateEntry>()
        for (key in o.keys()) {
            runCatching {
                val e = o.getJSONObject(key)
                if (!e.isNull("value")) out[key] = StateEntry(e.get("value"), e.getLong("updatedAt"))
            }
        }
        return out
    }

    /** JSON null for an absent value, so the key is present on the wire. */
    private fun nullable(v: Any?): Any = v ?: JSONObject.NULL

    private fun optLong(o: JSONObject, key: String): Long? = if (o.isNull(key)) null else o.getLong(key)
}

/**
 * The five progress-state keys (accounts README §9, "Progress-state keys"):
 * this port's stored form ↔ the one wire shape every app sends. Pure over
 * values and stored strings; reading and writing the live stores is the
 * caller's job.
 */
object SyncStateCodec {
    const val JOURNEY = "journey"
    const val CHARACTERS = "characters"
    const val FIRST_FOUR = "firstFour"
    const val OPERATING_PROCEDURE = "operatingProcedure"
    const val STORY_BOOKMARKS = "storyBookmarks"

    val keys: List<String> = listOf(JOURNEY, CHARACTERS, FIRST_FOUR, OPERATING_PROCEDURE, STORY_BOOKMARKS)

    // ---- journey (amt_journey) ----

    /** `completed` goes ascending; the store keeps it as an unordered set of strings. */
    fun journeyToWire(p: JourneyProgress): JSONObject = JSONObject()
        .put("unlockedThrough", p.unlockedThrough)
        .put("currentLevel", p.currentLevel)
        .put("completed", JSONArray(p.completed.sorted()))

    fun journeyFromWire(o: JSONObject): JourneyProgress {
        val arr = o.optJSONArray("completed") ?: JSONArray()
        return JourneyProgress(
            unlockedThrough = o.optInt("unlockedThrough", 1).coerceAtLeast(1),
            currentLevel = o.optInt("currentLevel", 1).coerceAtLeast(1),
            completed = (0 until arr.length()).mapNotNull { arr.optInt(it, -1).takeIf { n -> n >= 1 } }.toMutableSet()
        )
    }

    // ---- characters (amt_engine "engine") ----

    /**
     * The ladder's position from EngineStore's saved JSON: the active set,
     * the exposed set (a save from before exposure tracking has none; the
     * engine backfills it with the active set on restore, so that is what is
     * sent), the stage and the pinned stage, lowercase.
     */
    fun charactersToWire(engineJson: String): JSONObject {
        val o = JSONObject(engineJson)
        // The opt-in punctuation never travels: each device's own setting
        // decides it (fixtures/sync-wire.json `charactersPunctuation`).
        val punctuation = MorseCode.pickablePunctuation.toSet()
        val savedActive = o.optString("active", "")
        val active = savedActive.filter { it !in punctuation }
        val exposed = (if (o.has("exposed")) o.getString("exposed") else savedActive).filter { it !in punctuation }
        val pin = o.optString("pin", "").takeIf { it.isNotEmpty() }
        return JSONObject()
            .put("activeCharacters", JSONArray(active.map { it.toString() }))
            .put("exposedCharacters", JSONArray(exposed.map { it.toString() }))
            .put("stage", o.optString("stage", ProgressiveCharacters.Stage.Singles.name).lowercase(Locale.ROOT))
            .put("pinnedStage", pin?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
    }

    /**
     * EngineStore's saved JSON with a received position applied: active,
     * exposed, stage and pin replaced; the per-character stats and the
     * confusion matrix kept exactly as they were (they are not synced).
     * [engineJson] null (nothing saved yet) starts from empty stats. An
     * unknown stage keeps the local one. Only single characters fit this
     * port's ladder, so a longer entry is skipped.
     */
    fun applyCharacters(engineJson: String?, wire: JSONObject): String {
        val o = engineJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject().put("stats", JSONArray()).put("conf", JSONObject()).put("stage", ProgressiveCharacters.Stage.Singles.name)
        fun chars(key: String): String {
            val arr = wire.optJSONArray(key) ?: return ""
            return (0 until arr.length()).map { arr.optString(it) }.filter { it.length == 1 }.joinToString("")
        }
        // The received Koch characters, then this device's own opt-in
        // punctuation, which never travels.
        val punctuation = MorseCode.pickablePunctuation.toSet()
        val localActive = o.optString("active", "")
        val localExposed = if (o.has("exposed")) o.getString("exposed") else localActive
        o.put("active", chars("activeCharacters").filter { it !in punctuation } + localActive.filter { it in punctuation })
        o.put("exposed", chars("exposedCharacters").filter { it !in punctuation } + localExposed.filter { it in punctuation })
        stageName(wire.optString("stage", ""))?.let { o.put("stage", it) }
        val pin = if (wire.isNull("pinnedStage")) null else stageName(wire.getString("pinnedStage"))
        if (pin != null) o.put("pin", pin) else o.remove("pin")
        return o.toString()
    }

    /** "pairs" → "Pairs"; null for a stage this build does not have. */
    private fun stageName(wire: String): String? =
        ProgressiveCharacters.Stage.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == wire }?.name

    // ---- firstFour (amt_first_four "progress") ----

    /** Same shape FirstFourStore saves: names sorted, clean runs by name. */
    fun firstFourToWire(p: FirstFourProgress): JSONObject = JSONObject()
        .put("passed", JSONArray(p.passed.map { it.raw }.sorted()))
        .put("copyPassed", JSONArray(p.copyPassed.map { it.raw }.sorted()))
        .put("cleanRuns", JSONObject().apply { p.cleanRunsByStage.forEach { (k, v) -> put(k.raw, v) } })

    /** Unknown stage names (from a newer build) are skipped. */
    fun firstFourFromWire(o: JSONObject): FirstFourProgress {
        fun stages(key: String): List<FirstFourStage> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { FirstFourStage.fromRaw(a.optString(it)) }
        }
        val runs = mutableMapOf<FirstFourStage, Int>()
        o.optJSONObject("cleanRuns")?.let { r ->
            r.keys().forEach { k -> FirstFourStage.fromRaw(k)?.let { runs[it] = r.optInt(k) } }
        }
        return FirstFourProgress.restore(stages("passed"), stages("copyPassed"), runs)
    }

    // ---- operatingProcedure (amt_operating_procedure "progress") ----

    fun operatingProcedureToWire(p: OperatingProcedureProgress): JSONObject = JSONObject()
        .put("passed", JSONArray(p.passedNames))
        .put("cleanRuns", JSONArray(p.cleanRunNames))
        .put("drillPassed", p.drillPassed)

    fun operatingProcedureFromWire(o: JSONObject): OperatingProcedureProgress {
        fun names(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }
        return OperatingProcedureProgress.restore(names("passed"), names("cleanRuns"), o.optBoolean("drillPassed", false))
    }

    // ---- storyBookmarks (amt_settings "storyBookmarks") ----

    /** Settings' `key=index|key=index` string → `{ key: index }`. An unreadable entry is skipped. */
    fun storyBookmarksToWire(stored: String): JSONObject {
        val o = JSONObject()
        for (entry in stored.split('|')) {
            val eq = entry.lastIndexOf('=')
            if (eq <= 0) continue
            val idx = entry.substring(eq + 1).toIntOrNull() ?: continue
            o.put(entry.substring(0, eq), idx)
        }
        return o
    }

    /** `{ key: index }` → Settings' stored string, keys sorted so the result is stable. */
    fun storyBookmarksFromWire(o: JSONObject): String =
        o.keys().asSequence().toList().sorted()
            .mapNotNull { k -> o.optInt(k, -1).takeIf { it >= 0 }?.let { "$k=$it" } }
            .joinToString("|")
}

/**
 * PKCE for the sign-in (RFC 7636, S256): the verifier is 32 random bytes as
 * base64url without padding (43 characters), the challenge base64url of the
 * verifier's SHA-256. Pinned by the fixture's `pkce` (RFC 7636 Appendix B).
 */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun verifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
