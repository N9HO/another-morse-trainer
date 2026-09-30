package app.anothermorsetrainer.vail

import org.json.JSONArray
import org.json.JSONObject

/**
 * The single JSON envelope used in both directions on the Vail WebSocket.
 * Different "message types" are distinguished by which fields are populated.
 *
 * Timestamps are **always in server clock** on the wire; the client translates
 * to/from local clock using [VailClient]'s clock offset.
 *
 * Translated from MorseTrainerApp/VailMessage.swift. Swift `Codable` with
 * case-tolerant `CodingKeys` becomes explicit `org.json` encode/decode that
 * accepts both the capitalized wire keys (`Timestamp`, `Callsign`, …) and the
 * lowercase forms the server emits on some paths.
 */
data class VailMessage(
    /** Milliseconds since Unix epoch, in server clock. */
    val timestamp: Long,
    /** Alternating tone/silence durations in ms (single tone outbound). */
    val duration: List<Int> = emptyList(),
    val callsign: String? = null,
    /** Sender's TX tone as a MIDI note number (default 72 = C5). */
    val txTone: Int? = null,
    val private: Boolean? = null,
    val decoder: Boolean? = null,
    val text: String? = null,
    // Server-added (received only):
    val clients: Int? = null,
    val users: List<String>? = null,
    val usersInfo: List<UserInfo>? = null,
    val rooms: List<Room>? = null
) {
    data class UserInfo(val callsign: String, val txTone: Int?)
    data class Room(val name: String, val users: Int)

    /** Match against a previously-sent message for echo detection. */
    fun isEchoOf(sent: VailMessage): Boolean =
        timestamp == sent.timestamp && duration == sent.duration

    /** Encode using the capitalized wire keys, omitting null/empty fields. */
    fun toJson(): String {
        val o = JSONObject()
        o.put("Timestamp", timestamp)
        o.put("Duration", JSONArray(duration))
        callsign?.let { o.put("Callsign", it) }
        txTone?.let { o.put("TxTone", it) }
        private?.let { o.put("Private", it) }
        decoder?.let { o.put("Decoder", it) }
        text?.let { o.put("Text", it) }
        return o.toString()
    }

    companion object {
        fun fromJson(json: String): VailMessage? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return VailMessage(
                timestamp = o.optLongCI("Timestamp", "timestamp", 0L),
                duration = o.optIntArrayCI("Duration", "duration"),
                callsign = o.optStringCI("Callsign", "callsign"),
                txTone = o.optIntOrNullCI("TxTone", "txTone"),
                private = o.optBoolOrNullCI("Private", "private"),
                decoder = o.optBoolOrNullCI("Decoder", "decoder"),
                text = o.optStringCI("Text", "text"),
                clients = o.optIntOrNullCI("Clients", "clients"),
                users = o.optStringArrayCI("Users", "users"),
                usersInfo = o.optUsersInfo("UsersInfo", "usersInfo"),
                rooms = o.optRooms("Rooms", "rooms")
            )
        }
    }
}

// ---- Case-tolerant JSONObject helpers ----

private fun JSONObject.firstKey(vararg keys: String): String? =
    keys.firstOrNull { has(it) && !isNull(it) }

private fun JSONObject.optLongCI(a: String, b: String, def: Long): Long =
    firstKey(a, b)?.let { optLong(it, def) } ?: def

private fun JSONObject.optStringCI(a: String, b: String): String? =
    firstKey(a, b)?.let { getString(it) }   // firstKey guarantees present + non-null

private fun JSONObject.optIntOrNullCI(a: String, b: String): Int? =
    firstKey(a, b)?.let { if (isNull(it)) null else optInt(it) }

private fun JSONObject.optBoolOrNullCI(a: String, b: String): Boolean? =
    firstKey(a, b)?.let { optBoolean(it) }

private fun JSONObject.optIntArrayCI(a: String, b: String): List<Int> {
    val arr = firstKey(a, b)?.let { optJSONArray(it) } ?: return emptyList()
    return (0 until arr.length()).map { arr.optInt(it) }
}

private fun JSONObject.optStringArrayCI(a: String, b: String): List<String>? {
    val arr = firstKey(a, b)?.let { optJSONArray(it) } ?: return null
    return (0 until arr.length()).map { arr.optString(it) }
}

private fun JSONObject.optUsersInfo(a: String, b: String): List<VailMessage.UserInfo>? {
    val arr = firstKey(a, b)?.let { optJSONArray(it) } ?: return null
    return (0 until arr.length()).mapNotNull { i ->
        val u = arr.optJSONObject(i) ?: return@mapNotNull null
        val cs = u.optStringCI("Callsign", "callsign") ?: return@mapNotNull null
        VailMessage.UserInfo(cs, u.optIntOrNullCI("TxTone", "txTone"))
    }
}

private fun JSONObject.optRooms(a: String, b: String): List<VailMessage.Room>? {
    val arr = firstKey(a, b)?.let { optJSONArray(it) } ?: return null
    return (0 until arr.length()).mapNotNull { i ->
        val r = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = r.optStringCI("Name", "name") ?: return@mapNotNull null
        VailMessage.Room(name, r.optIntOrNullCI("Users", "users") ?: 0)
    }
}
