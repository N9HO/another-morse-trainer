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

/**
 * What the hands-free "Listen & Learn" mode announces. Entry order is the
 * chip row's order. The two QSO tiers are the curated on-air vocabulary of
 * [MorseData.qsoElements] (#182): the first 20, or all 100.
 */
enum class ListenContent(val label: String) {
    CHARACTERS("Characters"),
    QSO_TOP_20("QSO elements · Top 20"),
    QSO_TOP_100("QSO elements · Top 100"),
    WORDS("Words"),
    ABBREVIATIONS("Abbreviations")
}

/**
 * Gap between the code and the spoken answer. The four tiers and their labels
 * are the iOS `AnswerGap` set (Morse Code Ninja's Standard → ICR-Territory),
 * which is the canonical one for both platforms. The old three-tier set
 * (Standard / Fast 0.7 s / ICR 0.3 s) is migrated in [Settings.init].
 */
enum class ListenGap(val label: String, val ms: Long) {
    STANDARD("Standard (1.3 s)", 1300),
    RAPID_FIRE("Rapid Fire (1.0 s)", 1000),
    WARP("Warp (0.5 s)", 500),
    ICR("ICR-Territory (0.2 s)", 200)
}

/**
 * What Listen & Learn says after the code, for the token-plus-meaning sets
 * (QSO elements, Abbreviations & Q-codes). [SPELLED] reads the token letter
 * by letter and then the full meaning — the teaching form; [MEANING_ONLY]
 * says the brief meaning alone ([MorseData.briefMeaning]), the drilling form
 * a listener asked for once the glosses got in the way (#210). Characters
 * and Words are one word either way. Mirrors iOS `ListenReadback`.
 */
enum class ListenReadback(val label: String) {
    SPELLED("Spell it out"),
    MEANING_ONLY("Meaning only")
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
    // The content and gap choices persist (iOS keeps them in its settings),
    // so they live in [Settings]; these are the loop's view of them. Both are
    // Compose state there, so readers here recompose the same way.
    var contentSel: ListenContent
        get() = Settings.listenContent
        set(value) = Settings.updateListenContent(value)
    var gapSel: ListenGap
        get() = Settings.listenGap
        set(value) = Settings.updateListenGap(value)
    var readbackSel: ListenReadback
        get() = Settings.listenReadback
        set(value) = Settings.updateListenReadback(value)
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
    private var dealtFor: Triple<ListenContent, ListenReadback, List<Char>>? = null
    private var deck: ShuffledDeck<ListenItem>? = null

    /**
     * [activeCharacters] is the Koch ladder's active set, which the Characters
     * pool follows (as on iOS, #213); a change re-deals the deck.
     */
    fun next(
        content: ListenContent,
        readback: ListenReadback = ListenReadback.SPELLED,
        activeCharacters: List<Char> = MorseCode.kochOrder
    ): ListenItem {
        val key = Triple(content, readback, activeCharacters)
        if (key != dealtFor) {
            dealtFor = key
            deck = ShuffledDeck(listenPool(content, readback, activeCharacters), rng)
        }
        return deck?.draw()
            ?: ListenItem(MorseItem.Playable.Text("E"), "E", spokenName('E'))
    }
}

/**
 * Everything Listen & Learn can announce for [content], spoken per [readback].
 * The Characters pool is [activeCharacters], the ladder's active set, as on
 * iOS — it used to be the whole Koch order regardless of progress (#213), so
 * a beginner heard all 37 and an opted-in punctuation mark never arrived.
 */
fun listenPool(
    content: ListenContent,
    readback: ListenReadback = ListenReadback.SPELLED,
    activeCharacters: List<Char> = MorseCode.kochOrder
): List<ListenItem> = when (content) {
    ListenContent.CHARACTERS -> activeCharacters.ifEmpty { listOf('E') }.map { ch ->
        ListenItem(MorseItem.Playable.Text(ch.toString()), ch.toString(), spokenName(ch))
    }
    ListenContent.WORDS -> MorseData.wordItems.map { item ->
        ListenItem(item.playable, item.display, item.answer)
    }
    ListenContent.ABBREVIATIONS -> (MorseData.abbreviationItems + MorseData.qCodeItems).map { item ->
        ListenItem(item.playable, "${item.display} — ${item.answer}", spokenReadback(item.display, item.answer, readback))
    }
    // The curated on-air vocabulary (#182), revealed and spoken exactly like
    // the abbreviations. A prosign's angle brackets are shown but not spelled.
    ListenContent.QSO_TOP_20, ListenContent.QSO_TOP_100 -> {
        val limit = if (content == ListenContent.QSO_TOP_20) MorseData.QSO_TOP_20_COUNT else MorseData.QSO_TOP_100_COUNT
        MorseData.qsoElementItems(limit).map { item ->
            ListenItem(item.playable, "${item.display} — ${item.answer}", spokenReadback(item.display, item.answer, readback))
        }
    }
}

/**
 * What TTS says for a token-plus-meaning item (#210). Spelled: the token in
 * lowercase letters so TTS says "q t h", not "capital Q…", then the full
 * meaning. Meaning only: the brief meaning, nothing else — the screen still
 * shows the token and the whole gloss. Twin of iOS AppModel.spokenReadback.
 */
internal fun spokenReadback(token: String, meaning: String, readback: ListenReadback): String = when (readback) {
    ListenReadback.SPELLED -> {
        val spelled = token.filter { it != '<' && it != '>' }.lowercase().map { it.toString() }.joinToString(" ")
        "$spelled. $meaning"
    }
    ListenReadback.MEANING_ONLY -> MorseData.briefMeaning(meaning)
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
