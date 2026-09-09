package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.Leaderboard
import app.anothermorsetrainer.morsekit.LeaderboardItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The shared leaderboard's network client (docs/high-scores-design.md §4 and
 * step 2 of §5): OkHttp against the Worker, Play Integrity for every write,
 * coroutines on the main dispatcher so nothing blocks a screen. Modelled on
 * `vail/VailClient.kt` (one OkHttp client, main-thread-confined state) and
 * `NewsFetcher.kt` (enqueue, never execute). The iOS twin is the
 * `LeaderboardClient` actor.
 *
 * The flow, per the server README:
 *
 *  1. [beginRun] at run start, when the user has opted in and the mode is
 *     ranked: `POST /v1/attest/challenge`, attest the challenge, then
 *     `POST /v1/run/start` for a run token. Runs in the background; the run
 *     never waits for it. A failure means "no submission", logged quietly.
 *  2. [submit] at run end, where the screen records the finished run to
 *     [Stats]: attest the run token, `POST /v1/run/submit` with the
 *     transcript, and hand the summary a one-line result.
 *  3. [board] reads `GET /v1/board/{mode}`; no attestation, edge-cached.
 *  4. [deleteMyScores] is the Settings button both stores require.
 *
 * Nothing the client computed about its score is sent: the server grades the
 * transcript (`sent` vs `answered` per item) and ranks on the speed summed
 * over correct items. A recovered run (process death) has no transcript and
 * is never submitted.
 */
object LeaderboardClient {

    /** The Worker (leaderboard repo `wrangler.toml`). */
    const val BASE_URL = "https://amt-leaderboard.n9ho.workers.dev"

    /**
     * The Google Cloud project NUMBER Play Integrity decodes tokens for.
     *
     * TODO(maintainer): fill this from the Google Cloud console (project
     * `amt-leaderboard`, the numeric "Project number" on the dashboard, not
     * the project id) once the project is linked in Play Console, before the
     * release that ships the leaderboard. 0 means "not configured": the
     * client attests nothing, submits nothing and shows nothing, so a build
     * with this unset behaves as though the feature were off.
     */
    const val LEADERBOARD_CLOUD_PROJECT_NUMBER = 0L

    private const val TAG = "Leaderboard"
    private const val TIMEOUT_SECONDS = 10L
    /** The most the summary will wait for a run token that was still being fetched. */
    private const val START_GRACE_MS = 45_000L
    private const val BOARD_LIMIT = 50

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var attester: PlayIntegrityAttester? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * Outlives any one screen: a submission started from a run summary must
     * finish even if the user taps Back or the mode switcher a moment later.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** True once the maintainer has filled [LEADERBOARD_CLOUD_PROJECT_NUMBER]. */
    val isConfigured: Boolean get() = LEADERBOARD_CLOUD_PROJECT_NUMBER != 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("amt_leaderboard", Context.MODE_PRIVATE)
        if (isConfigured) attester = PlayIntegrityAttester(appContext, LEADERBOARD_CLOUD_PROJECT_NUMBER)
    }

    /**
     * The install's identity on the server, hashed there: a random UUID made
     * once and kept (design §3, layer 4 — identity comes from attestation,
     * not an account). Deleting the app's data makes a new one, which is the
     * "start over" the design accepts.
     */
    val installId: String
        get() {
            prefs.getString("installId", null)?.let { return it }
            val fresh = UUID.randomUUID().toString()
            prefs.edit { putString("installId", fresh) }
            return fresh
        }

    /**
     * One registered run: the token `/run/start` issued, or the reason there
     * is none. A screen keeps it for the run and hands it back to [submit].
     */
    class RunHandle internal constructor(val modeId: String) {
        internal val token = CompletableDeferred<String?>()
        /** Why there is no token, in the words the summary line shows. */
        internal var refusal: String? = null
    }

    /**
     * Register a run, or return null when there is nothing to do: the user
     * has not opted in, the build is not configured, or the mode is not one
     * of the nine ranked ones. Never blocks — the network work runs in the
     * background and the handle resolves when it is done.
     *
     * @param statsMode the mode string the screen passes to `Stats.record`.
     * @param characterWpm the run's character speed, whole WPM.
     * @param effectiveWpm the run's effective speed, whole WPM, <= character.
     */
    fun beginRun(statsMode: String, characterWpm: Int, effectiveWpm: Int): RunHandle? {
        if (!isConfigured || !Settings.leaderboardEnabled) return null
        val modeId = Leaderboard.modeId(statsMode) ?: return null
        val handle = RunHandle(modeId)
        // A run with no usable name is refused locally; no point minting a token.
        if (Leaderboard.normalizeDisplayName(Settings.leaderboardName) == null) {
            handle.refusal = appContext.getString(R.string.leaderboard_reason_no_name)
            handle.token.complete(null)
            return handle
        }
        // effective <= character is a server rule; rounding two doubles
        // independently can break it by one, so pin it here.
        val eff = effectiveWpm.coerceAtMost(characterWpm)
        scope.launch {
            try {
                val challenge = postJson("/v1/attest/challenge", JSONObject())
                    .body?.optString("challenge")?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("no challenge")
                val body = JSONObject()
                    .put("platform", "android")
                    .put("mode", modeId)
                    .put("characterWpm", characterWpm)
                    .put("effectiveWpm", eff)
                    .put("challenge", challenge)
                    .put("attestation", attestation(challenge))
                val reply = postJson("/v1/run/start", body)
                val token = reply.body?.optString("runToken")?.takeIf { it.isNotEmpty() }
                if (reply.code == 200 && token != null) {
                    handle.token.complete(token)
                } else {
                    handle.refusal = appContext.getString(
                        R.string.leaderboard_reason_not_registered,
                        reply.body?.optString("reason")?.takeIf { it.isNotEmpty() } ?: "HTTP ${reply.code}"
                    )
                    handle.token.complete(null)
                }
            } catch (e: Exception) {
                Log.d(TAG, "run/start failed: ${e.message}")
                handle.refusal = appContext.getString(R.string.leaderboard_reason_not_registered, describe(e))
                handle.token.complete(null)
            }
        }
        return handle
    }

    /**
     * Submit a finished run's transcript and report the outcome to
     * [onResult] on the main thread: the `Leaderboard: #rank · metric` line,
     * a one-line reason it was refused, or null when there is nothing to say
     * (an empty transcript). Fire and forget: the summary shows the line when
     * it arrives, and nothing waits on it.
     */
    fun submit(handle: RunHandle, transcript: List<LeaderboardItem>, onResult: (String?) -> Unit) {
        if (transcript.isEmpty()) { onResult(null); return }
        scope.launch {
            val line = try {
                submitNow(handle, transcript)
            } catch (e: Exception) {
                Log.d(TAG, "run/submit failed: ${e.message}")
                refused(describe(e))
            }
            onResult(line)
        }
    }

    private suspend fun submitNow(handle: RunHandle, transcript: List<LeaderboardItem>): String {
        // The start may still be in flight if the run was short; give it the
        // time its own timeouts allow, then give up on the token.
        val runToken = withTimeoutOrNull(START_GRACE_MS) { handle.token.await() }
            ?: return refused(handle.refusal ?: appContext.getString(R.string.leaderboard_reason_network))
        val name = Leaderboard.normalizeDisplayName(Settings.leaderboardName)
            ?: return refused(appContext.getString(R.string.leaderboard_reason_no_name))
        val body = JSONObject()
            .put("runToken", runToken)
            .put("displayName", name)
            .put("transcript", LeaderboardItem.transcriptJson(transcript))
            .put("attestation", attestation(runToken))
        val reply = postJson("/v1/run/submit", body)
        val b = reply.body
        if (reply.code == 200 && b != null && b.optBoolean("accepted", false)) {
            return appContext.getString(
                R.string.leaderboard_result,
                b.optInt("rank", 0),
                b.optDouble("metric", 0.0).toLong()
            )
        }
        return refused(b?.optString("reason")?.takeIf { it.isNotEmpty() } ?: "HTTP ${reply.code}")
    }

    private fun refused(reason: String): String = appContext.getString(R.string.leaderboard_refused, reason)

    /** One row of a board, as `GET /v1/board/{mode}` returns it. */
    data class BoardRow(val rank: Int, val displayName: String, val metric: Long, val platform: String, val date: String)

    sealed class BoardResult {
        data class Rows(val rows: List<BoardRow>) : BoardResult()
        data class Failed(val message: String) : BoardResult()
    }

    /** The top [BOARD_LIMIT] for a server mode id. Read-only; works without Play or opt-in. */
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
            Log.d(TAG, "board failed: ${e.message}")
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
     * "Delete my scores": a fresh challenge, attested, to `POST /v1/me/delete`.
     * Returns null on success or the reason it failed.
     */
    suspend fun deleteMyScores(): String? {
        if (!isConfigured) return appContext.getString(R.string.settings_leaderboard_unconfigured)
        return try {
            val challenge = postJson("/v1/attest/challenge", JSONObject())
                .body?.optString("challenge")?.takeIf { it.isNotEmpty() }
                ?: throw IOException("no challenge")
            val reply = postJson(
                "/v1/me/delete",
                JSONObject().put("challenge", challenge).put("attestation", attestation(challenge))
            )
            if (reply.code == 200 && reply.body?.optBoolean("deleted", false) == true) null
            else reply.body?.optString("reason")?.takeIf { it.isNotEmpty() } ?: "HTTP ${reply.code}"
        } catch (e: Exception) {
            Log.d(TAG, "me/delete failed: ${e.message}")
            describe(e)
        }
    }

    // ---- Attestation ----

    /** The `attestation` object every write carries; see `src/attest/playintegrity.ts`. */
    private suspend fun attestation(challenge: String): JSONObject {
        val a = attester ?: throw IllegalStateException("not configured")
        val token = a.token(Leaderboard.requestHash(challenge))
        return JSONObject()
            .put("platform", "android")
            .put("payload", JSONObject().put("token", token).put("installId", installId))
    }

    // ---- HTTP ----

    private class Reply(val code: Int, val body: JSONObject?)

    private suspend fun postJson(path: String, body: JSONObject): Reply =
        call(Request.Builder().url(BASE_URL + path).post(body.toString().toRequestBody(json)).build())

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

    /** A failure in the summary line's words: the network, the attestation, or whatever else. */
    private fun describe(e: Exception): String = when (e) {
        is IOException -> appContext.getString(R.string.leaderboard_reason_network)
        else -> appContext.getString(R.string.leaderboard_reason_attestation)
    }
}
