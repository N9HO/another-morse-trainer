package app.anothermorsetrainer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.Drill
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.QuizSource
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.VoiceMatcher
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/** Mutable per-session tally, accumulated as the learner answers. */
internal class Tally {
    var attempts = 0
    var correct = 0
    var bestMs: Int? = null
    /** Correct recognition times this session — the median feeds speed-band stats. */
    private val ttrsMs = mutableListOf<Int>()
    /** Wall-clock when this session began — Tally is remembered at screen entry. */
    val startedAtMs: Long = System.currentTimeMillis()
    /** Whole seconds of practice elapsed since the session began. */
    fun elapsedSeconds(): Int = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()

    /** Note one correct answer's recognition time (and keep the fastest). */
    fun noteCorrectMs(ms: Int) {
        if (ms <= 0) return
        ttrsMs.add(ms)
        if (bestMs == null || ms < bestMs!!) bestMs = ms
    }

    /** Median correct recognition time this session, or null if none recorded. */
    fun medianMs(): Int? {
        if (ttrsMs.isEmpty()) return null
        val s = ttrsMs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Per-character outcomes this session (single-character drills only). */
    private val charOutcomes = LinkedHashMap<String, MutableList<Pair<Boolean, Int>>>()

    /** Note one single-character answer for the session's recognition chart. */
    fun noteChar(character: String, correct: Boolean, ms: Int) {
        charOutcomes.getOrPut(character) { mutableListOf() }.add(correct to ms)
    }

    /** This session's per-character results, for the detail record. */
    fun charResults(): List<SessionRecord.CharResult> = charOutcomes.map { (ch, results) ->
        val times = results.filter { it.first && it.second > 0 }.map { it.second }.sorted()
        val medianMs = when {
            times.isEmpty() -> null
            times.size % 2 == 1 -> times[times.size / 2]
            else -> (times[times.size / 2 - 1] + times[times.size / 2]) / 2
        }
        SessionRecord.CharResult(
            character = ch,
            attempts = results.size,
            correct = results.count { it.first },
            medianTTR = medianMs?.let { it / 1000.0 }
        )
    }
}

/** The quiz loop is either running drills or showing the end-of-session summary. */
private enum class QuizPhase { RUNNING, SUMMARY }

/**
 * A drill can be answered by keying when the text you heard IS the answer —
 * characters, groups, and words, but not meaning-answers (abbreviations,
 * Q-codes) or prosign glyphs the decoder can't produce.
 */
private val Drill.isKeyable: Boolean
    get() = correct == revealPrimary && correct.none { it == '<' }

/** Streak-milestone badge tiers (mirrors the iOS emoji map). */
internal fun milestoneEmoji(day: Int): String = when {
    day >= 365 -> "👑"
    day >= 100 -> "🏆"
    day >= 60 -> "💎"
    day >= 30 -> "🏅"
    day >= 14 -> "⚡️"
    day >= 7 -> "⭐️"
    else -> "🔥"
}

/**
 * One reusable quiz loop that drives ANY [QuizSource] — character practice
 * (ProgressiveCharacters), word/abbreviation/Q-code drills (PhraseQuiz), the
 * confusion-pair drill, or the code exam (ExamSession) all flow through here.
 * Plays the drill in Morse via [MorsePlayer], scores the tap, gives haptic +
 * colour feedback, then advances.
 *
 * Sessions run against the configured [PracticeDuration]: a countdown (or the
 * End button) closes the session with a summary — answered, accuracy, fastest
 * and median recognition — plus a streak-milestone celebration when one lands.
 * The Characters track also gets a "Track stage" pin row (issue #51 parity).
 */
@Composable
fun QuizScreen(
    title: String,
    onBack: () -> Unit,
    makeSource: () -> QuizSource,
    settingsMode: SettingsMode = SettingsMode.CHARACTERS
) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember { makeSource() }
    // The Characters track exposes its learner-pinnable stage; other sources don't.
    val progressive = source as? ProgressiveCharacters

    var drill by remember { mutableStateOf(source.nextDrill()) }
    // Monotonic round counter drives the play/reset effect. We must NOT key that
    // effect on `drill` itself: `Drill` is a data class, so when nextDrill()
    // happens to return a value-equal round (common with small option sets like a
    // 2-character drill), assigning it is a no-op to Compose and the effect never
    // relaunches — leaving the screen frozen on the answered state. (issue #43)
    var round by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var lastTtr by remember { mutableStateOf(0.0) }
    var summary by remember { mutableStateOf(source.summary) }
    var toneFinishedAt by remember { mutableStateOf(0L) }
    /** A newly unlocked character/stage from the last answer (shown with a ★). */
    var unlockedNote by remember { mutableStateOf<String?>(null) }
    var stageRev by remember { mutableStateOf(0) }

    // Mid-session Settings, drawn over the session so its state lives on.
    var showSettings by remember { mutableStateOf(false) }

    // Session phase: drills, then a summary once the timer runs out or End is
    // tapped. Back mid-session still records — it just skips the summary.
    var phase by remember { mutableStateOf(QuizPhase.RUNNING) }
    var tally by remember { mutableStateOf(Tally()) }
    var remaining by remember { mutableStateOf(Settings.practiceDuration.seconds) }
    var recorded by remember { mutableStateOf(false) }
    var milestone by remember { mutableStateOf<Int?>(null) }

    // Optional spoken answers (microphone). The full iOS flow: fuzzy-match the
    // transcripts against every spoken form of the options, auto-grade when
    // confident, otherwise confirm ("Did you say X?") with a pick-the-closest
    // fallback — confirmations teach the persisted VoiceProfile.
    val recognizer = remember { VoiceRecognizer(context) }
    val voiceMatcher = remember { VoiceMatcher(VoiceProfileStore.load()) }
    var listening by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    var voiceHeard by remember { mutableStateOf<List<String>>(emptyList()) }
    var voiceGuess by remember { mutableStateOf<String?>(null) }
    var voiceChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var listenTick by remember { mutableStateOf(0) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenTick++ else voiceNote = "Microphone permission is needed for voice answers."
    }

    fun clearVoicePrompts() {
        voiceGuess = null
        voiceChoices = emptyList()
        voiceNote = null
    }

    // Optional keyed answers: the straight key + decoder, live while the toggle
    // is on (the iOS "answer by keying" panel).
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { MidiKeyInput(context) }
    val scope = rememberCoroutineScope()
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Settings.answerByKeying) {
        if (Settings.answerByKeying) {
            keyer.scope = scope
            keyer.start()
            // A hardware key (Vail Adapter / BLE-MIDI) drives the same decoder.
            midi.start(
                onKey = { down -> keyer.touchKey(down) },
                onConnected = { name -> midiDevice = name }
            )
        }
        onDispose { midi.stop(); keyer.stop() }
    }

    /** Persist the session once (summary entry or an early Back, whichever first). */
    fun recordSession(): Int? {
        if (recorded) return null
        recorded = true
        EngineStore.save()
        return Stats.record(
            mode = title, attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = tally.bestMs, durationSeconds = tally.elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt(), medianTtrMs = tally.medianMs(),
            effectiveWpm = Settings.effectiveWpm.roundToInt(),
            charResults = tally.charResults(),
            // The Characters track supplies its active set so the session chart
            // can show a row per learned character, drilled or not.
            activeCharacters = progressive?.engine?.activeCharacters?.map { it.toString() } ?: emptyList()
        )
    }

    /** Close the running session and show the summary (timer expiry or End). */
    fun endSession() {
        if (phase == QuizPhase.SUMMARY) return
        player.stop()
        recognizer.cancel()
        listening = false
        milestone = recordSession()
        phase = QuizPhase.SUMMARY
    }

    /** Leave the screen, recording the session if the summary never showed. */
    fun finish() {
        recordSession()
        onBack()
    }

    /**
     * Move to the next drill. The reveal state MUST be cleared in the same
     * recomposition as the new drill: LaunchedEffect(round) also resets it,
     * but that runs a frame later, and the stale frame in between rendered
     * the *revealed* branch with the *new* drill — a flash of red "✗ it was …"
     * where the replay button sits, leaking the upcoming answer (issue #63).
     */
    fun advance() {
        drill = source.nextDrill()
        revealed = false
        chosen = null
        unlockedNote = null
        round++
    }

    /** Start a fresh session from the summary screen. */
    fun practiceAgain() {
        tally = Tally()
        recorded = false
        milestone = null
        remaining = Settings.practiceDuration.seconds
        phase = QuizPhase.RUNNING
        advance()
    }

    LaunchedEffect(round) {
        revealed = false
        chosen = null
        toneFinishedAt = 0L
        unlockedNote = null
        listening = false
        voiceNote = null
        voiceGuess = null
        voiceChoices = emptyList()
        voiceHeard = emptyList()
        recognizer.cancel()
        keyer.clear()
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    LaunchedEffect(revealed) {
        if (revealed) {
            if (chosen != drill.correct) {
                // A miss holds the correction until Next is pressed (issue
                // #77) — and after a beat repeats what was actually sent, so
                // you re-hear the sound you got wrong while the answer shows.
                delay(450)
                player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
                return@LaunchedEffect
            }
            delay(1100)
            if (phase == QuizPhase.RUNNING) advance()
        }
    }

    // Session countdown: ticks only while running and only when a length is set.
    LaunchedEffect(phase) {
        if (phase != QuizPhase.RUNNING) return@LaunchedEffect
        while (true) {
            val r = remaining ?: return@LaunchedEffect
            if (r <= 0) {
                endSession()
                return@LaunchedEffect
            }
            delay(1000)
            remaining = remaining?.minus(1)
        }
    }

    DisposableEffect(Unit) { onDispose { player.release(); recognizer.release() } }

    // Hardware/gesture back records the session too, then leaves.
    BackHandler { if (phase == QuizPhase.SUMMARY) onBack() else finish() }

    fun answer(choice: String) {
        if (revealed || phase != QuizPhase.RUNNING) return
        val ttr = if (toneFinishedAt == 0L) 0.0
                  else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        lastTtr = ttr
        chosen = choice
        val outcome = source.record(choice = choice, ttr = ttr)
        summary = source.summary
        unlockedNote = outcome.unlocked
        tally.attempts += 1
        val ms = (ttr * 1000).roundToInt()
        if (outcome.correct) {
            tally.correct += 1
            tally.noteCorrectMs(ms)
        }
        // Single-character drills feed the per-character recognition charts —
        // the lifetime one and this session's own.
        if (drill.correct.length == 1) {
            Stats.recordChar(drill.correct, outcome.correct, if (outcome.correct && ms > 0) ms else null)
            tally.noteChar(drill.correct, outcome.correct, ms)
        }
        // Keep the Characters track durable — the ladder resumes next time.
        EngineStore.save()
        if (Settings.hapticsEnabled) {
            if (outcome.correct) haptics.success() else haptics.error()
        }
        revealed = true
    }

    /** A confirmed/corrected voice answer: teach the profile, then grade. */
    fun acceptVoice(token: String) {
        voiceHeard.firstOrNull()?.let { heard ->
            voiceMatcher.profile.record(heard, token)
            VoiceProfileStore.save(voiceMatcher.profile)
        }
        clearVoicePrompts()
        answer(token)
    }

    // Keyed answers auto-submit once the decoded copy reaches the answer's
    // length and the key has gone idle (the Sending Practice rhythm).
    LaunchedEffect(keyer.decodedText, keyer.isKeying, revealed, round) {
        if (!Settings.answerByKeying || revealed || phase != QuizPhase.RUNNING || keyer.isKeying) return@LaunchedEffect
        if (!drill.isKeyable) return@LaunchedEffect
        val sent = keyer.decodedText.trim()
        if (sent.isNotEmpty() && sent.length >= drill.correct.length) answer(sent.uppercase())
    }

    // Listen for a spoken answer when the mic is tapped (listenTick bumps).
    LaunchedEffect(listenTick) {
        if (listenTick == 0 || revealed || phase != QuizPhase.RUNNING) return@LaunchedEffect
        listening = true
        clearVoicePrompts()
        recognizer.start(
            // Bias the recognizer toward every spoken form of every option
            // (NATO words, letter names, digit words, spelled variants).
            hints = voiceMatcher.contextualStrings(drill.options),
            onResult = { candidates ->
                listening = false
                voiceHeard = candidates
                val res = voiceMatcher.interpret(candidates, drill.options)
                val token = res.token
                when {
                    token != null && res.isConfident -> answer(token)
                    token != null -> voiceGuess = token
                    else -> voiceNote = "Didn't catch that — tap an option or try again."
                }
            },
            onError = {
                listening = false
                voiceNote = "Didn't catch that — tap an option or try again."
            }
        )
    }

    // Hardware-keyboard answering (issue #69): with a Bluetooth/USB keyboard
    // attached, type the character you heard in single-character drills, or
    // press 1–9 for the Nth option in meaning drills. The root stays focused
    // so key events land here — no text field competes on this screen.
    val hwFocus = remember { FocusRequester() }
    fun handleHardwareKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (showSettings) return false
        if (phase != QuizPhase.RUNNING) return false
        if (revealed) {
            // Enter leaves the held correction (issue #77).
            if (event.key == Key.Enter && chosen != drill.correct) {
                advance()
                return true
            }
            return false
        }
        if (Settings.answerByKeying && drill.isKeyable) return false
        val ch = event.utf16CodePoint.takeIf { it > 0 }?.toChar()?.uppercaseChar() ?: return false
        val options = drill.options
        if (options.isNotEmpty() && options.all { it.length == 1 }) {
            // Every option is one character: its own key answers directly.
            val match = options.firstOrNull { it.uppercase() == ch.toString() } ?: return false
            answer(match)
            return true
        }
        // Mixed/meaning drills: 1–9 picks the Nth option, so a digit option
        // and an index can never collide.
        val index = ch - '1'
        if (index in 0..8 && index in options.indices) {
            answer(options[index])
            return true
        }
        return false
    }
    LaunchedEffect(phase, round, showSettings) {
        if (phase == QuizPhase.RUNNING && !showSettings) hwFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hwFocus)
            .onKeyEvent(::handleHardwareKey)
            .focusable()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (phase == QuizPhase.SUMMARY) onBack() else finish() }) { Text("‹ Back") }
            if (phase == QuizPhase.RUNNING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    remaining?.let {
                        Text(
                            "%d:%02d".format(it / 60, it % 60),
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.textSecondary
                        )
                    }
                    SessionSettingsButton { showSettings = true }
                    TextButton(onClick = { endSession() }) { Text("End") }
                }
            }
        }

        if (phase == QuizPhase.SUMMARY) {
            SessionSummaryContent(
                title = title,
                tally = tally,
                milestone = milestone,
                onPracticeAgain = { practiceAgain() },
                onDone = onBack
            )
        } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(text = summary, style = MaterialTheme.typography.labelMedium)

            // Characters only: hold the track at a learner-chosen stage (#51).
            if (progressive != null) {
                Spacer(Modifier.height(10.dp))
                key(stageRev) {
                    StagePinRow(pinned = progressive.pinnedStage) { pick ->
                        if (pick == null) progressive.unpin() else progressive.pin(pick)
                        EngineStore.save()
                        summary = source.summary
                        stageRev++
                        advance()
                    }
                }
            }

            // Answer by keying, where the heard text is the answer (iOS parity).
            if (drill.isKeyable) {
                TextButton(onClick = { Settings.updateAnswerByKeying(!Settings.answerByKeying) }) {
                    Text(
                        if (Settings.answerByKeying) "⠿ Key answers · on" else "⠿ Key answers · off",
                        fontSize = 13.sp,
                        color = if (Settings.answerByKeying) Brand.teal else Brand.textSecondary
                    )
                }
            }

            // Exam-style comprehension prompt (empty for plain recognition drills).
            if (drill.question.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = drill.question,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(28.dp))

            if (revealed) {
                val ok = chosen == drill.correct
                // Show the answer per the user's reveal preference; the ✓/✗ line
                // always shows so they still know if they were right.
                val showAnswer = when (Settings.revealMode) {
                    RevealMode.ALWAYS -> true
                    RevealMode.ON_WRONG -> !ok
                    RevealMode.NEVER -> false
                }
                if (showAnswer) {
                    SlashableText(
                        text = drill.revealPrimary,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    if (drill.revealSecondary.isNotEmpty()) {
                        Text(text = drill.revealSecondary, fontSize = 20.sp, color = Brand.textSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = when {
                        ok -> "✓ recalled in %.1f s".format(lastTtr)
                        showAnswer -> "✗ it was “${drill.correct}”"
                        else -> "✗ not quite"
                    },
                    color = if (ok) OK_GREEN else ERR_RED,
                    fontWeight = FontWeight.Medium
                )
                unlockedNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("★ New: $it", color = Brand.teal, fontWeight = FontWeight.SemiBold)
                }
                if (!ok) {
                    // The held correction (issue #77): re-hear it as often as
                    // needed, move on when ready.
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(onClick = {
                        player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
                    }) { Text("▶ Replay") }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { advance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Next", fontWeight = FontWeight.SemiBold) }
                }
            } else {
                Text(text = "?", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Brand.teal)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing()) }) {
                    Text("▶ Replay")
                }
            }

            Spacer(Modifier.height(28.dp))
            val keyedMode = Settings.answerByKeying && drill.isKeyable
            if (keyedMode) {
                KeyedAnswerPanel(
                    decoded = keyer.decodedText,
                    keyPressed = keyPressed,
                    enabled = !revealed,
                    midiDevice = midiDevice,
                    onKey = { down ->
                        keyPressed = down
                        keyer.touchKey(down)
                    },
                    onClear = { keyer.clear() },
                    onSubmit = { answer(keyer.submit().uppercase()) }
                )
            } else {
                OptionsGrid(drill = drill, revealed = revealed, chosen = chosen, onPick = ::answer)
            }

            // Voice answers: a mic to speak instead of tap (options stay as fallback).
            if (Settings.voiceAnswersEnabled && recognizer.isAvailable && !revealed && !keyedMode) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            listenTick++
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !listening
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (listening) "  Listening…" else "  Speak answer")
                }

                // Not sure what was said: confirm the best guess…
                voiceGuess?.let { guess ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Did you say “$guess”?",
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.textPrimary
                        )
                        TextButton(onClick = { acceptVoice(guess) }) { Text("Yes") }
                        TextButton(onClick = {
                            // …or fall back to picking the closest-sounding answers.
                            voiceGuess = null
                            voiceChoices = voiceMatcher
                                .rankedCandidates(voiceHeard, drill.options)
                                .filter { it != guess }
                                .take(3)
                        }) { Text("No") }
                    }
                }
                if (voiceChoices.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("I said:", style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        voiceChoices.forEach { choice ->
                            OutlinedButton(onClick = { acceptVoice(choice) }) { Text(choice, maxLines = 1) }
                        }
                        TextButton(onClick = { clearVoicePrompts() }) { Text("None") }
                    }
                }

                voiceNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
        }

        if (showSettings) {
            SessionSettingsOverlay(scope = settingsMode, onClose = { showSettings = false })
        }
    }
}

/**
 * End-of-session summary: the tallies, a milestone celebration when this
 * session's first-practice-of-the-day landed on one, and the way onward.
 * Internal so Head Copy and the typed quizzes end their sessions the same way.
 */
@Composable
internal fun SessionSummaryContent(
    title: String,
    tally: Tally,
    milestone: Int?,
    onPracticeAgain: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Session complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.fillMaxWidth().brandCard()) {
            SummaryRow("Answered", "${tally.attempts}")
            val pct = if (tally.attempts == 0) 0 else (tally.correct * 100.0 / tally.attempts).roundToInt()
            SummaryRow("Accuracy", "$pct%")
            SummaryRow("Fastest", tally.bestMs?.let { "%.2f s".format(it / 1000.0) } ?: "—")
            SummaryRow("Median", tally.medianMs()?.let { "%.2f s".format(it / 1000.0) } ?: "—")
        }

        milestone?.let { day ->
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().brandCard().padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(milestoneEmoji(day), fontSize = 40.sp)
                Text("$day-day streak!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("New milestone reached — keep it going.", color = Brand.textSecondary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPracticeAgain,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            shape = RoundedCornerShape(Brand.cornerRadius),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text("Practice again", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Done")
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Brand.textSecondary)
        Text(value, color = Brand.textPrimary, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * "Track stage" pills for the Characters track: Auto (the default ladder) or a
 * held stage that never auto-advances. Mirrors the iOS setup-card picker (#51).
 */
@Composable
private fun StagePinRow(
    pinned: ProgressiveCharacters.Stage?,
    onPick: (ProgressiveCharacters.Stage?) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Stage", style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
        StagePill("Auto", selected = pinned == null) { onPick(null) }
        ProgressiveCharacters.Stage.entries.forEach { stage ->
            val label = when (stage) {
                ProgressiveCharacters.Stage.Singles -> "Chars"
                ProgressiveCharacters.Stage.Phrases -> "Words"
                else -> stage.displayName
            }
            StagePill(label, selected = pinned == stage) { onPick(stage) }
        }
    }
}

@Composable
private fun StagePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) Brand.teal else Brand.navyRaised,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (selected) Brand.navy else Brand.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}

/**
 * The keyed answer panel: a live "YOU SENT" decode readout, a hold-to-key
 * straight key, and Clear/Submit — the quiz-loop twin of Sending Practice's
 * pad (and of the iOS keyer answer panel). Auto-submit lives in the caller.
 */
@Composable
private fun KeyedAnswerPanel(
    decoded: String,
    keyPressed: Boolean,
    enabled: Boolean,
    midiDevice: String?,
    onKey: (Boolean) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        midiDevice?.let {
            Text(
                "🎹 $it",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.teal,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("YOU SENT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = decoded.ifEmpty { "—" },
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (decoded.isEmpty()) Brand.textSecondary else Brand.textPrimary,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(Brand.cornerRadius))
                .background(if (keyPressed) Brand.teal else Brand.navyRaised)
                .border(
                    width = if (keyPressed) 2.dp else 1.dp,
                    color = if (keyPressed) Brand.tealBright else Brand.hairline,
                    shape = RoundedCornerShape(Brand.cornerRadius)
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            onKey(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                onKey(false)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⠿", fontSize = 22.sp, color = if (keyPressed) Brand.navy else Brand.teal)
                Text(
                    "HOLD TO KEY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (keyPressed) Brand.navy else Brand.textSecondary
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onClear,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            ) { Text("Clear") }
            Button(
                onClick = onSubmit,
                enabled = enabled && decoded.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            ) { Text("Submit", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun OptionsGrid(drill: Drill, revealed: Boolean, chosen: String?, onPick: (String) -> Unit) {
    // Two-column grid of bold teal buttons (matches the iOS choice grid).
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        drill.options.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { option ->
                    val colors = when {
                        revealed && option == drill.correct -> ButtonDefaults.buttonColors(containerColor = OK_GREEN, contentColor = Color.White)
                        revealed && option == chosen -> ButtonDefaults.buttonColors(containerColor = ERR_RED, contentColor = Color.White)
                        revealed -> ButtonDefaults.buttonColors(containerColor = Brand.navyRaised, contentColor = Brand.textSecondary)
                        else -> ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
                    }
                    // Single letters/numbers get a big monospaced glyph; words stay readable.
                    val short = option.length <= 3
                    Button(
                        onClick = { onPick(option) },
                        colors = colors,
                        shape = RoundedCornerShape(Brand.cornerRadius),
                        modifier = Modifier.weight(1f).heightIn(min = 80.dp)
                    ) {
                        // SlashableText: a slashed zero on the answer buttons is
                        // exactly where 0-vs-O confusion bites (issue #62).
                        SlashableText(
                            text = option,
                            fontSize = if (short) 34.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = if (short) FontFamily.Monospace else null,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
