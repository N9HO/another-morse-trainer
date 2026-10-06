package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The accounts Worker's client on this port: [AccountApi] over OkHttp, the
 * two tokens in EncryptedSharedPreferences, and the plain `amt_account`
 * preferences file that [SyncCoordinator]'s engine keeps its non-secret state
 * in (account, pull cursor, outbox, own-day record, state stamps). Modelled
 * on [LeaderboardClient]: one OkHttp client, `enqueue` never `execute`, 10 s
 * timeouts. The iOS twin is the `AccountClient` actor.
 *
 * [SyncCoordinator.init] calls [init] before any call.
 */
object AccountClient {

    /** The accounts Worker. */
    const val BASE_URL = "https://amt-accounts.n9ho-amt.workers.dev"

    /** The first-party `client` this port signs in as. */
    const val CLIENT = "amt-android"
    const val PLATFORM = "android"

    private const val TAG = "Account"
    private const val TIMEOUT_SECONDS = 10L

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Non-secret account state, read and written by [SyncEngine] only. */
    lateinit var prefs: SharedPreferences
        private set

    lateinit var api: AccountApi
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences("amt_account", Context.MODE_PRIVATE)
        api = AccountApi(OkHttpTransport, LazyTokenStore(app), CLIENT)
    }

    /**
     * What the sign-in shows the user on the confirm page and the device
     * list: the name the user gave the device (Settings › About phone), else
     * its model.
     */
    fun deviceName(context: Context): String {
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching {
                android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            }.getOrNull()
        } else {
            null
        }
        return (named?.takeIf { it.isNotBlank() } ?: Build.MODEL).trim().take(64)
    }

    /** [SyncEngine]'s key-value store over [prefs]. */
    object PrefsKeyValues : SyncKeyValues {
        override fun get(key: String): String? = AccountClient.prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            AccountClient.prefs.edit { if (value == null) remove(key) else putString(key, value) }
        }
    }

    // ---- Token store ----

    /**
     * Opens the Keystore-backed store on first use, not at launch: the sync
     * engine reads no token while signed out, so most launches never pay for it.
     */
    private class LazyTokenStore(context: Context) : AccountTokenStore {
        private val inner by lazy { EncryptedTokenStore(context) }
        override val access: String? get() = inner.access
        override val refresh: String? get() = inner.refresh
        override fun saveRefresh(token: String) = inner.saveRefresh(token)
        override fun saveAccess(token: String) = inner.saveAccess(token)
        override fun clear() = inner.clear()
    }

    /**
     * The access and refresh tokens, encrypted at rest with a key in the
     * Android Keystore. The refresh token is written with `commit`, not
     * `apply`: the server has already spent the old one, so it must be on
     * disk before the new access token is used.
     */
    private class EncryptedTokenStore(context: Context) : AccountTokenStore {
        private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
            context,
            "amt_account_tokens",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        override val access: String? get() = prefs.getString("access", null)
        override val refresh: String? get() = prefs.getString("refresh", null)

        override fun saveRefresh(token: String) {
            prefs.edit(commit = true) { putString("refresh", token) }
        }

        override fun saveAccess(token: String) {
            prefs.edit { putString("access", token) }
        }

        override fun clear() {
            prefs.edit(commit = true) { clear() }
        }
    }

    // ---- HTTP ----

    private object OkHttpTransport : AccountTransport {
        override suspend fun send(method: String, path: String, body: JSONObject?, bearer: String?): AccountReply {
            val builder = Request.Builder().url(BASE_URL + path)
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            val requestBody = body?.toString()?.toRequestBody(json)
            // OkHttp refuses a body on GET and needs one on POST/PUT/PATCH.
            builder.method(
                method,
                if (method == "GET") null else requestBody ?: if (method == "DELETE") null else "{}".toRequestBody(json)
            )
            return call(builder.build())
        }
    }

    /** OkHttp enqueue → coroutine; the body parsed as JSON where it is JSON. */
    private suspend fun call(request: Request): AccountReply = suspendCancellableCoroutine { cont ->
        val c = http.newCall(request)
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "${request.method} ${request.url.encodedPath} failed: ${e.message}")
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = response.use { r ->
                    val text = runCatching { r.body?.string() }.getOrNull()
                    AccountReply(r.code, text?.takeIf { it.isNotBlank() }?.let { t -> runCatching { JSONObject(t) }.getOrNull() })
                }
                if (cont.isActive) cont.resume(parsed)
            }
        })
    }
}
