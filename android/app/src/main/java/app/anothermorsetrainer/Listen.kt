package app.anothermorsetrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.ShuffledDeck
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/** What the hands-free "Listen & Learn" mode announces. */
enum class ListenContent(val label: String) {
    CHARACTERS("Characters"),
    WORDS("Words"),
    ABBREVIATIONS("Abbreviations")
}

/** Gap between the code and the spoken answer (matches the iOS AnswerGap choices). */
enum class ListenGap(val label: String, val ms: Long) {
    STANDARD("Standard", 1300),
    FAST("Fast", 700),
    ICR("ICR", 300)
}

/** One loop item: what to key, what to show, and what to say. */
data class ListenItem(val playable: MorseItem.Playable, val display: String, val spoken: String)

/**
 * Process-wide state for the Listen & Learn loop, shared between [ListenService]
 * (which drives the loop, even with the screen locked) and the Compose UI
 * (which reads it for display and sends control commands). Both run in the same
 * process, so a singleton of Compose state is the simplest reliable bridge.
 */
object ListenState {
    var contentSel by mutableStateOf(ListenContent.CHARACTERS)
    var gapSel by mutableStateOf(ListenGap.STANDARD)
    var running by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var playing by mutableStateOf(false)   // true while the code sounds, false while the answer shows
    var display by mutableStateOf("")

    // Session accounting (the practice-length framework): items completed this
    // session, seconds actually listened (pauses excluded), the configured
    // limit, and a note shown once a timed session finishes itself.
    var itemsHeard by mutableIntStateOf(0)
    var activeSeconds by mutableIntStateOf(0)
    var limitSeconds by mutableStateOf<Int?>(null)
    var finishedNote by mutableStateOf<String?>(null)
}

/**
 * Hands out Listen & Learn items so that every item in the chosen pool is
 * heard once before any is heard again (mirrors the iOS nextListenItem).
 * Picking each item at random repeated words within a dozen or so items and
 * was reported as a small word list (issue #158). The deck is re-dealt when
 * the content changes and otherwise kept for the picker's life.
 */
class ListenPicker(private val rng: Random = Random.Default) {
    private var dealtFor: ListenContent? = null
    private var deck: ShuffledDeck<ListenItem>? = null

    fun next(content: ListenContent): ListenItem {
        if (content != dealtFor) {
            dealtFor = content
            deck = ShuffledDeck(listenPool(content), rng)
        }
        return deck?.draw()
            ?: ListenItem(MorseItem.Playable.Text("E"), "E", spokenName('E'))
    }
}

/** Everything Listen & Learn can announce for [content]. */
fun listenPool(content: ListenContent): List<ListenItem> = when (content) {
    ListenContent.CHARACTERS -> MorseCode.kochOrder.map { ch ->
        ListenItem(MorseItem.Playable.Text(ch.toString()), ch.toString(), spokenName(ch))
    }
    ListenContent.WORDS -> MorseData.wordItems.map { item ->
        ListenItem(item.playable, item.display, item.answer)
    }
    ListenContent.ABBREVIATIONS -> (MorseData.abbreviationItems + MorseData.qCodeItems).map { item ->
        val spelled = item.display.lowercase().map { it.toString() }.joinToString(" ")
        ListenItem(item.playable, "${item.display} — ${item.answer}", "$spelled. ${item.answer}")
    }
}

private fun spokenName(ch: Char): String = when (ch) {
    '?' -> "question mark"
    ',' -> "comma"
    '.' -> "period"
    '/' -> "slash"
    '=' -> "equals"
    '+' -> "plus"
    else -> ch.toString().lowercase()
}

/** Suspend until [player] finishes keying [playable] (cancellation stops it). */
suspend fun awaitPlay(player: MorsePlayer, playable: MorseItem.Playable): Unit =
    suspendCancellableCoroutine { cont ->
        player.play(playable, Settings.sidetoneHz, Settings.timing()) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { player.stop() }
    }

/** Suspend until [speech] finishes speaking [text] (cancellation stops it). */
suspend fun awaitSpeak(speech: SpeechPlayer, text: String): Unit =
    suspendCancellableCoroutine { cont ->
        speech.speak(text) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { speech.stop() }
    }
