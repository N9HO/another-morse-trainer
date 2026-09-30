package app.anothermorsetrainer

import java.awt.EventQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Posts work to the UI thread, now or after a delay.
 *
 * Compose Desktop's UI thread is the AWT event dispatch thread, and Compose
 * state must be written there — the job `Handler(Looper.getMainLooper())` does
 * in the Android port. This keeps that shape (post, postDelayed,
 * removeCallbacks, removeCallbacksAndMessages) so the ported players and
 * keyers keep their timing logic unchanged. A delay is kept by one shared
 * scheduler thread and the runnable then hops onto the event thread.
 *
 * Each instance tracks only what it posted, so [removeCallbacksAndMessages]
 * cancels this owner's pending work and nobody else's, as on Android.
 */
class MainHandler {

    private class Pending(val runnable: Runnable, var future: ScheduledFuture<*>?)

    private val pending = mutableListOf<Pending>()

    fun post(r: Runnable): Boolean {
        val p = Pending(r, null)
        synchronized(pending) { pending.add(p) }
        EventQueue.invokeLater { if (claim(p)) r.run() }
        return true
    }

    fun post(block: () -> Unit): Boolean = post(Runnable(block))

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        val p = Pending(r, null)
        synchronized(pending) { pending.add(p) }
        p.future = scheduler.schedule({
            EventQueue.invokeLater { if (claim(p)) r.run() }
        }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return true
    }

    fun postDelayed(block: () -> Unit, delayMillis: Long): Boolean = postDelayed(Runnable(block), delayMillis)

    /** Cancel every pending post of [r] (compared by identity, as Android does). */
    fun removeCallbacks(r: Runnable) {
        synchronized(pending) {
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.runnable === r) { p.future?.cancel(false); it.remove() }
            }
        }
    }

    /** With null, cancel everything this handler has pending. */
    @Suppress("UNUSED_PARAMETER")
    fun removeCallbacksAndMessages(token: Any?) {
        synchronized(pending) {
            pending.forEach { it.future?.cancel(false) }
            pending.clear()
        }
    }

    /** True if [p] was still pending (and is now claimed to run). */
    private fun claim(p: Pending): Boolean = synchronized(pending) { pending.remove(p) }

    companion object {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "amt-main-handler").apply { isDaemon = true }
        }

        /** True on the UI (event dispatch) thread. */
        val isMainThread: Boolean get() = EventQueue.isDispatchThread()
    }
}
