package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The pure half of account sync (Phase 2 of the accounts work): the wire
 * codecs, the mode-id table, the progress-state codecs, the merge rules, the
 * outbox, the retry rules and PKCE. No network, no Android types, so the JUnit
 * suite can hold every rule here to `fixtures/sync-wire.json`, the file the
 * Swift harness and the desktop suite read too. The API contract is the
 * accounts Worker's README (sections 5, 7 and 9). The OkHttp half and the
 * token store live in the app package (`AccountApi`, `AccountClient`).
 *
 * The local record is [SessionRecord], TTRs in seconds; the wire carries
 * whole milliseconds, epoch-millisecond dates, lowercase ids and JSON null
 * where `Stats` stores its -1 sentinel.
 */
object SyncWire {
    const val SCHEMA_VERSION = 1

    /** A [SessionRecord] as `POST /v1/sync/sessions` takes it. */
    fun encodeSession(r: SessionRecord): JSONObject {
        val chars = JSONArray()
        for (c in r.characters) {
            chars.put(
                JSONObject()
                    .put("character", c.character)
                    .put("attempts", c.attempts)
                    .put("correct", c.correct)
                    .put("medianTtrMs", ms(c.medianTTR))
            )
        }
        return JSONObject()
            .put("id", r.id.toString().lowercase())
            .put("date", r.date.toEpochMilli())
            .put("mode", SyncModes.wireId(r.mode))
            .put("characterWpm", r.characterWPM)
            .put("effectiveWpm", r.effectiveWPM)
            .put("attempts", r.attempts)
            .put("correct", r.correct)
            .put("fastestTtrMs", ms(r.fastestTTR))
            .put("medianTtrMs", ms(r.medianTTR))
            .put("durationSeconds", r.durationSeconds ?: JSONObject.NULL)
            .put("score", r.score ?: JSONObject.NULL)
            .put("characters", chars)
            .put("activeCharacters", JSONArray(r.activeCharacters))
            .put("schemaVersion", SCHEMA_VERSION)
    }

    /**
     * A wire record back to a [SessionRecord]. Throws on a record missing a
     * required field; a caller decoding a list skips that one and keeps the
     * rest (the Stats parsers' rule).
     */
    fun decodeSession(o: JSONObject): SessionRecord {
        val charsArr = o.optJSONArray("characters") ?: JSONArray()
        val chars = (0 until charsArr.length()).map { i ->
            val c = charsArr.getJSONObject(i)
            SessionRecord.CharResult(
                character = c.getString("character"),
                attempts = c.getInt("attempts"),
                correct = c.getInt("correct"),
                medianTTR = seconds(c, "medianTtrMs")
            )
        }
        val active = o.optJSONArray("activeCharacters") ?: JSONArray()
        return SessionRecord(
            id = UUID.fromString(o.getString("id")),
            date = Instant.ofEpochMilli(o.getLong("date")),
            mode = SyncModes.localMode(o.getString("mode")),
            characterWPM = o.optInt("characterWpm", 0),
            effectiveWPM = o.optInt("effectiveWpm", 0),
            attempts = o.getInt("attempts"),
            correct = o.getInt("correct"),
            fastestTTR = seconds(o, "fastestTtrMs"),
            medianTTR = seconds(o, "medianTtrMs"),
            durationSeconds = if (o.isNull("durationSeconds")) null else o.getDouble("durationSeconds"),
            characters = chars,
            activeCharacters = (0 until active.length()).map { active.getString(it) },
            score = if (o.isNull("score")) null else o.getInt("score")
        )
    }

    /** A list of wire records, one bad record dropped and the rest kept. */
    fun decodeSessions(arr: JSONArray?): List<SessionRecord> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { decodeSession(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    /** The `POST /v1/sync/sessions` body for a batch. */
    fun sessionsBody(records: List<SessionRecord>): JSONObject =
        JSONObject().put("sessions", JSONArray().apply { records.forEach { put(encodeSession(it)) } })

    /** The `POST /v1/sync/days` body: the ledger's days, earliest first, whole seconds. */
    fun encodeDays(days: Map<LocalDate, Int>): JSONObject {
        val arr = JSONArray()
        for ((day, secs) in TreeMap(days)) arr.put(JSONObject().put("day", day.toString()).put("seconds", secs))
        return JSONObject().put("days", arr)
    }

    /**
     * Per-day figures from a reply, in either shape a day list can take:
     * `{ "yyyy-mm-dd": seconds }` or `[{ day, seconds }]`. A bad day is
     * dropped, the rest kept.
     */
    fun decodeDays(value: Any?): Map<LocalDate, Int> {
        val out = TreeMap<LocalDate, Int>()
        when (value) {
            is JSONObject -> for (key in value.keys()) {
                runCatching { out[LocalDate.parse(key)] = value.getInt(key) }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                runCatching {
                    val o = value.getJSONObject(i)
                    out[LocalDate.parse(o.getString("day"))] = o.getInt("seconds")
                }
            }
        }
        return out
    }

    /** Seconds to whole milliseconds on the wire, or JSON null. */
    private fun ms(seconds: Double?): Any = seconds?.let { (it * 1000).roundToLong() } ?: JSONObject.NULL

    private fun seconds(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.getLong(key) / 1000.0
}

/**
 * Canonical mode ids (the iOS `TrainingMode` raw values) for the mode strings
 * this port passes to `Stats.record`. All 25, both ways; pinned by the
 * fixture's `modeIds.kotlin`. Not [Leaderboard.modeIds], which names the
 * leaderboard Worker's own ids (Pileup is `pileup` there, `qso` here).
 */
object SyncModes {
    val wireIds: Map<String, String> = linkedMapOf(
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

    private val localModes: Map<String, String> = wireIds.entries.associate { (k, v) -> v to k }

    /** The wire id for a local mode string; one not in the table goes as it is. */
    fun wireId(localMode: String): String = wireIds[localMode] ?: localMode

    /** The local mode string for a wire id; an id this build does not know is kept as the mode. */
    fun localMode(wireId: String): String = localModes[wireId] ?: wireId
}

/**
 * The five progress-state keys `PUT /v1/sync/state` carries, each from this
 * port's stored value to its one wire shape and back. Applying a received
 * value never touches what does not travel (the Characters track's
 * per-character stats and confusions).
 */
object SyncState {
    const val JOURNEY = "journey"
    const val CHARACTERS = "characters"
    const val FIRST_FOUR = "firstFour"
    const val OPERATING_PROCEDURE = "operatingProcedure"
    const val STORY_BOOKMARKS = "storyBookmarks"
    val KEYS = listOf(JOURNEY, CHARACTERS, FIRST_FOUR, OPERATING_PROCEDURE, STORY_BOOKMARKS)

    // ---- journey ----

    fun journeyToWire(p: JourneyProgress): JSONObject = JSONObject()
        .put("unlockedThrough", p.unlockedThrough)
        .put("currentLevel", p.currentLevel)
        .put("completed", JSONArray(p.completed.sorted()))

    fun journeyFromWire(o: JSONObject): JourneyProgress {
        val arr = o.optJSONArray("completed") ?: JSONArray()
        val completed = (0 until arr.length()).mapNotNull { i -> runCatching { arr.getInt(i) }.getOrNull() }
        return JourneyProgress(
            unlockedThrough = o.optInt("unlockedThrough", 1),
            currentLevel = o.optInt("currentLevel", 1),
            completed = completed.toMutableSet()
        )
    }

    // ---- characters ----

    /**
     * The Characters track's ladder: active and exposed characters, stage and
     * pinned stage. A snapshot from before exposure tracking sends its active
     * set as exposed, which is what restoring it would make of it.
     */
    fun charactersToWire(s: ProgressiveCharacters.Snapshot): JSONObject {
        // The opt-in punctuation never travels: each device's own setting
        // decides it (fixtures/sync-wire.json `charactersPunctuation`).
        val punctuation = MorseCode.pickablePunctuation.toSet()
        val active = s.engine.activeCharacters.filter { it !in punctuation }
        val exposedSet = (s.engine.exposedCharacters ?: s.engine.activeCharacters.toSet()) - punctuation
        // Active order first, then anything exposed but no longer active.
        val exposed = active.filter { it in exposedSet } + exposedSet.filter { it !in active }.sorted()
        return JSONObject()
            .put("activeCharacters", JSONArray(active.map { it.toString() }))
            .put("exposedCharacters", JSONArray(exposed.map { it.toString() }))
            .put("stage", stageWire(s.stage))
            .put("pinnedStage", s.pinnedStage?.let { stageWire(it) } ?: JSONObject.NULL)
    }

    /**
     * [local] with the received ladder applied. Per-character stats and
     * confusions are this device's own and are kept; an unknown stage keeps
     * the local one.
     */
    fun applyCharacters(local: ProgressiveCharacters.Snapshot?, o: JSONObject): ProgressiveCharacters.Snapshot {
        fun chars(key: String): List<Char>? {
            val arr = o.optJSONArray(key) ?: return null
            return (0 until arr.length()).mapNotNull { arr.optString(it).firstOrNull() }
        }
        val engine = local?.engine ?: TrainerEngine.Snapshot(activeCharacters = emptyList(), stats = emptyList())
        // The received Koch characters, then this device's own opt-in
        // punctuation, which never travels.
        val punctuation = MorseCode.pickablePunctuation.toSet()
        val active = (chars("activeCharacters") ?: engine.activeCharacters).filter { it !in punctuation } +
            engine.activeCharacters.filter { it in punctuation }
        val exposed = chars("exposedCharacters")?.let { wire ->
            (wire.toSet() - punctuation) + (engine.exposedCharacters ?: emptySet()).filter { it in punctuation }
        } ?: engine.exposedCharacters
        val stage = stageLocal(o.optString("stage", "")) ?: local?.stage ?: ProgressiveCharacters.Stage.Singles
        val pin = if (o.isNull("pinnedStage")) null else stageLocal(o.optString("pinnedStage", ""))
        return ProgressiveCharacters.Snapshot(
            engine = engine.copy(activeCharacters = active, exposedCharacters = exposed),
            stage = stage,
            pinnedStage = pin
        )
    }

    /** `Pairs` ↔ `pairs`: the wire uses the iOS raw values. */
    fun stageWire(stage: ProgressiveCharacters.Stage): String = stage.name.lowercase()

    fun stageLocal(wire: String): ProgressiveCharacters.Stage? =
        ProgressiveCharacters.Stage.entries.firstOrNull { it.name.lowercase() == wire }

    // ---- firstFour ----

    /** The wire shape is the store's saved form: names sorted, clean runs by name. */
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

    // ---- operatingProcedure ----

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

    // ---- storyBookmarks ----

    /**
     * `Settings`' stored form, `key=index` pairs joined with `|`, to a JSON
     * object of key → index. Same parse as `Settings.decodeBookmarks`.
     */
    fun storyBookmarksToWire(raw: String): JSONObject {
        val o = JSONObject()
        for (entry in raw.split('|')) {
            val eq = entry.lastIndexOf('=')
            if (eq <= 0) continue
            val idx = entry.substring(eq + 1).toIntOrNull() ?: continue
            o.put(entry.substring(0, eq), idx)
        }
        return o
    }

    /** The wire object back to the stored `key=index|…` form; a non-integer entry is dropped. */
    fun storyBookmarksFromWire(o: JSONObject): String =
        o.keys().asSequence().sorted()
            .mapNotNull { k -> runCatching { "$k=${o.getInt(k)}" }.getOrNull() }
            .joinToString("|")
}

/** One progress-state entry: the wire value and when it last changed (epoch ms). */
data class StateEntry(val value: Any, val updatedAt: Long)

/**
 * The client merge rules (README §7, "Client merge rules"; fixture `merge`).
 * Each returns the new value and changes nothing; the stores apply it.
 */
object SyncMerge {
    /**
     * Pulled or snapshot records join the local list when their id (compared
     * lowercase) is not already there; a local record is never replaced. Then
     * newest first (equal dates: id ascending) and the newest [limit] kept.
     */
    fun mergeSessions(local: List<SessionRecord>, pulled: List<SessionRecord>, limit: Int = SessionHistory.limit): List<SessionRecord> {
        val seen = local.map { key(it) }.toMutableSet()
        val merged = local.toMutableList()
        for (r in pulled) if (seen.add(key(r))) merged.add(r)
        return merged
            .sortedWith(compareByDescending<SessionRecord> { it.date }.thenBy { key(it) })
            .take(limit)
    }

    private fun key(r: SessionRecord) = r.id.toString().lowercase()

    /**
     * The lifetime counters `Stats` keeps, in the fixture's units: practice
     * time and best TTR in seconds. [bestScores] is keyed by this port's mode
     * strings.
     */
    data class LifetimeTotals(
        val totalSessions: Int,
        val totalAnswered: Int,
        val totalCorrect: Int,
        val totalPracticeSeconds: Double,
        val bestTtrSeconds: Double?,
        val bestScores: Map<String, Int>
    )

    /**
     * The server's aggregates (the `stats` object a push, pull or snapshot
     * reply carries) replacing [local]'s. A field the reply leaves out keeps
     * the local value; `bestTtrMs: null` is a real "none".
     */
    fun adoptAggregates(local: LifetimeTotals, stats: JSONObject): LifetimeTotals {
        val totals = stats.optJSONObject("totals")
        var out = local
        if (totals != null) {
            out = out.copy(
                totalSessions = totals.optInt("sessions", out.totalSessions),
                totalAnswered = totals.optInt("answered", out.totalAnswered),
                totalCorrect = totals.optInt("correct", out.totalCorrect),
                totalPracticeSeconds = totals.optDouble("practiceSeconds", out.totalPracticeSeconds)
            )
        }
        if (stats.has("bestTtrMs")) {
            out = out.copy(bestTtrSeconds = if (stats.isNull("bestTtrMs")) null else stats.getLong("bestTtrMs") / 1000.0)
        }
        stats.optJSONObject("personalBests")?.let { pb ->
            val bests = LinkedHashMap<String, Int>()
            for (k in pb.keys()) runCatching { bests[SyncModes.localMode(k)] = pb.getInt(k) }
            out = out.copy(bestScores = bests)
        }
        return out
    }

    /**
     * The server's per-day figures adopted day by day; local days it does not
     * mention are kept; then the ledger's cap drops the earliest.
     */
    fun mergeLedger(local: Map<LocalDate, Int>, server: Map<LocalDate, Int>): Map<LocalDate, Int> {
        val out = TreeMap(local)
        out.putAll(server)
        while (out.size > ActivityLedger.CAP_DAYS) out.pollFirstEntry()
        return out
    }

    /**
     * The `POST /v1/sync/days` figures for the [queued] days: this device's
     * OWN seconds for each (fixture `merge.deviceDays`), never the displayed
     * ledger, which after adoption holds every device's sum. A queued day the
     * own record no longer has (past its cap) is left out.
     */
    fun ownDaysToPush(own: Map<LocalDate, Int>, queued: Collection<LocalDate>): Map<LocalDate, Int> {
        val out = TreeMap<LocalDate, Int>()
        for (day in queued) own[day]?.let { out[day] = it }
        return out
    }

    /**
     * The local practice streak with the server's `streak` object (from
     * `GET /v1/me/stats` or the snapshot's `stats`) adopted, so a restored
     * install shows the account's streak. The local longest is never
     * lowered. A local last day newer than the server's (practice not yet
     * pushed) keeps the local current run; no server streak keeps [local].
     */
    fun adoptStreak(local: PracticeStreak, stats: JSONObject): PracticeStreak {
        val s = stats.optJSONObject("streak") ?: return local
        val serverLast = if (s.isNull("lastPractisedDay")) null
            else runCatching { LocalDate.parse(s.getString("lastPractisedDay")) }.getOrNull()
        val longest = maxOf(local.longest, s.optInt("longest", 0))
        val localLast = local.lastPracticeDay
        if (serverLast == null || (localLast != null && localLast > serverLast)) {
            return PracticeStreak(local.current, maxOf(longest, local.current), localLast)
        }
        val current = s.optInt("current", 0)
        return PracticeStreak(current, maxOf(longest, current), serverLast)
    }

    /** Per key, a reply entry wins only when strictly newer; keys it omits keep local. */
    fun mergeState(local: Map<String, StateEntry>, reply: Map<String, StateEntry>): Map<String, StateEntry> {
        val out = LinkedHashMap(local)
        for ((key, entry) in reply) {
            val mine = out[key]
            if (mine == null || entry.updatedAt > mine.updatedAt) out[key] = entry
        }
        return out
    }

    /** `{ key: { value, updatedAt } }` as the state routes carry it; a bad entry is dropped. */
    fun decodeStateEntries(o: JSONObject?): Map<String, StateEntry> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, StateEntry>()
        for (k in o.keys()) runCatching {
            val e = o.getJSONObject(k)
            out[k] = StateEntry(e.get("value"), e.getLong("updatedAt"))
        }
        return out
    }

    /** The `PUT /v1/sync/state` body. */
    fun stateBody(entries: Map<String, StateEntry>): JSONObject {
        val o = JSONObject()
        for ((k, e) in entries) o.put(k, JSONObject().put("value", e.value).put("updatedAt", e.updatedAt))
        return JSONObject().put("entries", o)
    }
}

/**
 * Work waiting to be pushed, oldest first: one entry per session record, its
 * wire JSON kept here so a record that ages out of the 100-row history still
 * reaches the server. At most [max]; enqueuing past it drops the oldest.
 */
data class SyncOutbox(val entries: List<Entry> = emptyList(), val max: Int = APP_MAX) {
    data class Entry(val id: String, val payload: String)

    val ids: List<String> get() = entries.map { it.id }
    val isEmpty: Boolean get() = entries.isEmpty()

    /** [entry] added at the end (replacing one with the same id), the oldest dropped past [max]. */
    fun enqueue(entry: Entry): SyncOutbox {
        val next = entries.filter { it.id != entry.id } + entry
        return copy(entries = next.takeLast(max))
    }

    /** The oldest [size] entries: the next push. */
    fun nextBatch(size: Int = BATCH_SIZE): List<Entry> = entries.take(size)

    /**
     * After a 2xx push reply: every id it lists as accepted, skipped or
     * rejected leaves (a rejected record stays in local history); the rest
     * stay, in order.
     */
    fun applyPushReply(reply: JSONObject): SyncOutbox {
        val done = HashSet<String>()
        fun ids(key: String) {
            val a = reply.optJSONArray(key) ?: return
            for (i in 0 until a.length()) {
                val v = a.opt(i)
                val id = if (v is JSONObject) v.optString("id", "") else v?.toString().orEmpty()
                if (id.isNotEmpty()) done.add(id.lowercase())
            }
        }
        ids("accepted"); ids("skipped"); ids("rejected")
        return copy(entries = entries.filter { it.id.lowercase() !in done })
    }

    /** The persisted form: a JSON array of `{ id, payload }`. */
    fun encode(): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("id", it.id).put("payload", it.payload)) }
    }.toString()

    companion object {
        const val APP_MAX = 1000
        const val BATCH_SIZE = 200

        /** A saved outbox; anything unreadable starts empty, a bad entry is skipped. */
        fun decode(json: String?, max: Int = APP_MAX): SyncOutbox {
            val arr = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return SyncOutbox(max = max)
            val list = (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).let { Entry(it.getString("id"), it.getString("payload")) } }.getOrNull()
            }
            return SyncOutbox(list.takeLast(max), max)
        }
    }
}

/** What the sync engine does with an HTTP outcome (fixture `merge.retry`). */
enum class SyncAction {
    /** Succeeded. */
    DONE,
    /** The server refused this request for good; do not send it again. */
    DROP,
    /** Refresh the token once and retry once; a failed refresh signs out. */
    REFRESH,
    /** Try again after [SyncRetry.backoffSeconds]. */
    BACKOFF
}

object SyncRetry {
    const val CAP_SECONDS = 600L

    /** Seconds before retry [n] (1-based): 2^n, capped at 10 minutes. */
    fun backoffSeconds(n: Int): Long {
        val k = n.coerceAtLeast(1)
        return if (k >= 10) CAP_SECONDS else min(CAP_SECONDS, 1L shl k)
    }

    /** The action for an HTTP [status], or for no reply at all when it is null. */
    fun action(status: Int?): SyncAction = when {
        status == null -> SyncAction.BACKOFF
        status in 200..299 -> SyncAction.DONE
        status == 401 -> SyncAction.REFRESH
        status == 429 || status >= 500 -> SyncAction.BACKOFF
        else -> SyncAction.DROP
    }
}

/**
 * PKCE (RFC 7636, S256) for the sign-in: the verifier is 32 random bytes as
 * base64url without padding (43 characters), the challenge base64url of
 * SHA-256 of the verifier's ASCII.
 */
object Pkce {
    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        Leaderboard.base64Url(ByteArray(32).also { random.nextBytes(it) })

    fun challenge(verifier: String): String =
        Leaderboard.base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/**
 * The account fields the Settings screen checks before `PATCH /v1/me`, by the
 * server's own rules (accounts README §8): a callsign is trimmed and
 * uppercased, then 3–16 of `A–Z 0–9 /`; a display name is trimmed, then 2–24
 * printable characters. The email check is only a sanity check before
 * `verify/start`; the server trims and lowercases, nothing more.
 */
object AccountRules {
    private val CALLSIGN = Regex("^[A-Z0-9/]{3,16}$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValidEmail(raw: String): Boolean {
        val e = raw.trim()
        return e.length <= 254 && EMAIL.matches(e)
    }

    /** The callsign as the server will store it, or null when it breaks the rule. */
    fun normalizeCallsign(raw: String): String? = raw.trim().uppercase().takeIf { CALLSIGN.matches(it) }

    /** The display name as the server will store it, or null when it breaks the rule. */
    fun normalizeDisplayName(raw: String): String? {
        val n = raw.trim()
        val count = n.codePointCount(0, n.length)
        if (count !in 2..24) return null
        var i = 0
        while (i < n.length) {
            val cp = n.codePointAt(i)
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED,
                Character.PRIVATE_USE, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> return null
            }
            i += Character.charCount(cp)
        }
        return n
    }
}
