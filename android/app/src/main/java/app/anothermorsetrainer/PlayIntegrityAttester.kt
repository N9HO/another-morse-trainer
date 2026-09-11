package app.anothermorsetrainer

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Play Integrity, standard API: the Android half of the leaderboard's device
 * attestation (docs/high-scores-design.md §3, layer 1). A genuine,
 * Play-installed build on a device that meets integrity gets a token the
 * server decodes through Google; a sideloaded or self-built copy does not,
 * and the server rejects it (decision 2026-09-08).
 *
 * The token provider is prepared once and kept for the session — Google
 * caps preparation at five per minute and the warm-up can take seconds — and
 * each request carries the server's challenge as the `requestHash`
 * ([app.anothermorsetrainer.morsekit.Leaderboard.requestHash]), which binds
 * the token to one request so a captured token cannot be replayed. The iOS
 * twin is App Attest in `LeaderboardClient.swift`; both are "the platform's
 * own idiom" (CLAUDE.md), not a divergence.
 *
 * Nothing here can be exercised without Play: the emulator smoke test runs a
 * debug build with no Play Store, so the first Play internal-test build is
 * this class's first run.
 */
class PlayIntegrityAttester(context: Context, private val cloudProjectNumber: Long) {

    private val manager: StandardIntegrityManager =
        IntegrityManagerFactory.createStandard(context.applicationContext)
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    private val lock = Mutex()

    /**
     * An integrity token bound to [requestHash]. Throws whatever Play
     * Integrity threw when the device cannot attest; the caller turns that
     * into a quiet "not submitted".
     *
     * A provider goes stale (INTEGRITY_TOKEN_PROVIDER_INVALID) after a while;
     * rather than match error codes, a failed request drops the provider and
     * tries once more with a fresh one.
     */
    suspend fun token(requestHash: String): String {
        val first = prepared()
        return try {
            request(first, requestHash)
        } catch (e: Exception) {
            lock.withLock { if (provider === first) provider = null }
            request(prepared(), requestHash)
        }
    }

    private suspend fun prepared(): StandardIntegrityManager.StandardIntegrityTokenProvider = lock.withLock {
        provider ?: manager.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build()
        ).await().also { provider = it }
    }

    private suspend fun request(
        p: StandardIntegrityManager.StandardIntegrityTokenProvider,
        requestHash: String
    ): String = p.request(
        StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
            .setRequestHash(requestHash)
            .build()
    ).await().token()

    /**
     * Play Services `Task` → coroutine. kotlinx-coroutines-play-services is
     * not a dependency here and this is all it would add.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }
}
