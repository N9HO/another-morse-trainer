package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.LeaderboardItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The shared leaderboard's network client (docs/high-scores-design.md §4),
 * desktop edition: **read-only, and every desktop run unranked.**
 *
 * The phone apps submit a run only with a platform attestation — Play
 * Integrity on Android, App Attest on iOS — proving the transcript came from
 * a genuine install; that is the leaderboard's anti-cheat (design §3). A
 * Windows or Linux app has no such attestation to offer, so the desktop port
 * never registers or submits a run: [beginRun] always returns null, the
 * screens show [DesktopCopy.UNRANKED_SHORT] where the phone shows its
 * `Leaderboard: #rank` line, and Settings shows [DesktopCopy.UNRANKED_NOTE]
 * instead of the opt-in switch. Personal bests (`Stats.bestScores`) are kept
 * locally as on the phones. `PlayIntegrityAttester` is not ported.
 *
 * What still works is [board]: `GET /v1/board/{mode}` needs no attestation,
 * so the Leaderboard screen shows the same public boards the phones rank to.
 *
 * The public types ([RunHandle], [BoardRow], [BoardResult], [Reply]) keep
 * the Android port's shapes so the ported screens compile unchanged.
 */
object LeaderboardClient {

    /** The Worker (leaderboard repo `wrangler.toml`). */
    const val BASE_URL = "https://amt-leaderboard.n9ho.workers.dev"

    private const val TIMEOUT_SECONDS = 10L
    private const val BOARD_LIMIT = 50

    /**
     * Desktop: false. Nothing here can attest a run, so none is ranked. A
     * screen that offers the opt-in or a ranked-run line checks this.
     */
    val rankedAvailable: Boolean = false

    /**
     * Whether attested writes (run submission, "Delete my scores", the buddy
     * routes) can be made from this build. Desktop: never — there is no
     * attester — so Settings shows its "not enabled in this build" note and
     * keeps the buddy buttons disabled, as an Android build without a Cloud
     * project number did.
     */
    val isConfigured: Boolean get() = false

    private var initialized = false

    /** True once [init] has run. */
    internal val isInitialized: Boolean get() = initialized

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Outlives any one screen. Shared with [BuddyClient], whose calls have the
     * same shape on Android.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Desktop: nothing to set up — no attester, and no install id (the
     * install identity exists only to be attested). Kept so `main()` reads
     * like Android's `MainActivity.onCreate`.
     */
    fun init() {
        initialized = true
    }

    /**
     * One registered run on Android: the token `/run/start` issued, or the
     * reason there is none. Kept so screens compile; desktop never makes one.
     */
    class RunHandle internal constructor(val modeId: String) {
        internal val token = CompletableDeferred<String?>()
        /** Why there is no token, in the words the summary line shows. */
        internal var refusal: String? = null
    }

    /**
     * Desktop: always null — desktop runs are unranked (see the class note),
     * so there is never a run to register or submit.
     */
    @Suppress("UNUSED_PARAMETER")
    fun beginRun(statsMode: String, characterWpm: Int, effectiveWpm: Int): RunHandle? = null

    /**
     * Desktop: submits nothing. [beginRun] never returns a handle, so this is
     * unreachable in practice; it reports "nothing to say" (null) on the UI
     * thread's terms, immediately.
     */
    @Suppress("UNUSED_PARAMETER")
    fun submit(handle: RunHandle, transcript: List<LeaderboardItem>, onResult: (String?) -> Unit) {
        onResult(null)
    }

    /** One row of a board, as `GET /v1/board/{mode}` returns it. */
    data class BoardRow(val rank: Int, val displayName: String, val metric: Long, val platform: String, val date: String)

    sealed class BoardResult {
        data class Rows(val rows: List<BoardRow>) : BoardResult()
        data class Failed(val message: String) : BoardResult()
    }

    /** The top [BOARD_LIMIT] for a server mode id. Read-only; needs no attestation, so it works on desktop. */
    suspend fun board(modeId: String): BoardResult {
        return try {
            val reply = call(Request.Builder().url("$BASE_URL/v1/board/$modeId?limit=$BOARD_LIMIT").get().build())
            val arr = reply.body?.optJSONArray("rows")
            if (reply.code != 200 || arr == null) {
                BoardResult.Failed(reply.body?.optString("reason")?.takeIf { it.isNotEmpty() } ?: "HTTP ${reply.code}")
            } else {
                BoardResult.Rows(parseRows(arr))
            }
        } catch (e: Exception) {
            BoardResult.Failed(describe(e))
        }
    }

    private fun parseRows(arr: JSONArray): List<BoardRow> {
        val out = ArrayList<BoardRow>(arr.length())
        for (i in 0 until arr.length()) {
            // One bad row is dropped, the rest kept (the Stats parsers' rule).
            runCatching {
                val o = arr.getJSONObject(i)
                out.add(
                    BoardRow(
                        rank = o.getInt("rank"),
                        displayName = o.getString("displayName"),
                        metric = o.getDouble("metric").toLong(),
                        platform = o.optString("platform", ""),
                        date = o.optString("date", "")
                    )
                )
            }
        }
        return out
    }

    /**
     * "Delete my scores". Desktop: never submits a score and has no install
     * identity on the server, so there is nothing to delete; returns the
     * reason (shown after "Couldn't delete: "). Local personal bests are
     * cleared by Settings' own "Reset all progress".
     */
    suspend fun deleteMyScores(): String? = DELETE_UNAVAILABLE

    /** Why "Delete my scores" does nothing on desktop, in the failure line's words. */
    const val DELETE_UNAVAILABLE =
        "desktop scores are never sent to the leaderboard, so there is nothing on the server to delete"

    // ---- HTTP ----

    /** A server answer: status and the body parsed as JSON where it is JSON. */
    internal class Reply(val code: Int, val body: JSONObject?) {
        /** The server's `reason`, or the bare status when it gave none. */
        val reason: String get() = body?.optString("reason")?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
    }

    /** OkHttp enqueue → coroutine; the body parsed as JSON where it is JSON. */
    private suspend fun call(request: Request): Reply = suspendCancellableCoroutine { cont ->
        val c = http.newCall(request)
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = response.use { r ->
                    val text = runCatching { r.body?.string() }.getOrNull()
                    Reply(r.code, text?.let { t -> runCatching { JSONObject(t) }.getOrNull() })
                }
                if (cont.isActive) cont.resume(parsed)
            }
        })
    }

    /** A failure in the summary line's words: the network, or whatever else. */
    internal fun describe(e: Exception): String = when (e) {
        is IOException -> AppStrings.get(R.string.leaderboard_reason_network)
        else -> AppStrings.get(R.string.leaderboard_reason_attestation)
    }
}
