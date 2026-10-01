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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.AnswerEntryMode
import app.anothermorsetrainer.morsekit.AnswerEntryTier
import app.anothermorsetrainer.morsekit.AnswerKeys
import app.anothermorsetrainer.morsekit.CharacterIntroduction
import app.anothermorsetrainer.morsekit.ConfusionQuiz
import app.anothermorsetrainer.morsekit.Drill
import app.anothermorsetrainer.morsekit.PhraseQuiz
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.QuizSource
import app.anothermorsetrainer.morsekit.TypedAnswer
import app.anothermorsetrainer.morsekit.VoiceMatcher
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/** The quiz loop is either running drills or showing the end-of-session summary. */
private enum class QuizPhase { RUNNING, SUMMARY }

/**
 * A drill can be answered by keying when the text you heard IS the answer —
 * characters, groups, and words, but not meaning-answers (abbreviations,
 * Q-codes) or prosign glyphs the decoder can't produce.
 */
internal val Drill.isKeyable: Boolean
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
    settingsMode: SettingsMode = SettingsMode.CHARACTERS,
    /**
     * Leaving from the end-of-session recap, as opposed to the Back arrow
     * mid-run. Defaults to [onBack]; the caller overrides it to reopen this
     * mode's setup sheet on the way home (iOS issue #67).
     */
    onFinish: () -> Unit = onBack,
    /** Mid-session mode switcher (iOS #42); the run is recorded before this fires. */
    onSwitchMode: (TrainingMode) -> Unit = {}
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember { makeSource() }
    // The Characters track exposes its learner-pinnable stage; other sources don't.
    val progressive = source as? ProgressiveCharacters

    /**
     * A character or prosign the track is about to drill for the first time
     * (issue #162): shown on its own with its sound before the drill plays.
     * Only the Characters track, only with the setting on, and only an item
     * the learner has neither drilled nor been shown before.
     */
    fun introductionFor(d: Drill): CharacterIntroduction? {
        val engine = progressive?.engine ?: return null
        if (!Settings.introduceNewCharacters) return null
        return CharacterIntroduction.forDrill(d) { id ->
            id in Settings.introducedItems || (id.length == 1 && id[0] in engine.exposedCharacters)
        }
    }

    // Keyboard-entry answers (#232): this quiz's own four → six → typed
    // ladder, the rung the drill on screen was dealt on (so a promotion by
    // its own answer changes the next drill, not this one's feedback), and
    // the rung the last answer promoted to, held until Next like an unlock.
    val answerEntryApplies = settingsMode in ANSWER_ENTRY_MODES
    val ladder = remember { AnswerEntryStore.load(settingsMode) }
    var drillTier by remember { mutableStateOf(ladder.tier) }
    var promotedTo by remember { mutableStateOf<AnswerEntryTier?>(null) }
    var typedInput by remember { mutableStateOf("") }
    val typedFocus = remember { FocusRequester() }

    /**
     * The number of buttons the next drill offers: the ladder's rung under
     * Progressive (six on the typed rung, for drills that cannot be typed),
     * otherwise the Answer choices setting.
     */
    fun applyChoiceCount() {
        if (!answerEntryApplies) return
        val n = if (Settings.answerEntry == AnswerEntryMode.PROGRESSIVE) {
            ladder.tier.choiceCount ?: AnswerEntryTier.SIX_CHOICES.choiceCount!!
        } else {
            Settings.answerChoices
        }
        when (source) {
            is ProgressiveCharacters -> source.engine.config.optionCount = n
            is ConfusionQuiz -> source.engine.config.optionCount = n
            is PhraseQuiz -> source.config.optionCount = n
        }
    }

    var drill by remember {
        applyChoiceCount()
        mutableStateOf(source.nextDrill())
    }
    /** The introduction the current drill is waiting behind, or null while a drill is under way. */
    var intro by remember { mutableStateOf(introductionFor(drill)) }
    // Monotonic round counter drives the play/reset effect. We must NOT key that
    // effect on `drill` itself: `Drill` is a data class, so when nextDrill()
    // happens to return a value-equal round (common with small option sets like a
    // 2-character drill), assigning it is a no-op to Compose and the effect never
    // relaunches — leaving the screen frozen on the answered state. (issue #43)
    var round by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var lastTtr by remember { mutableDoubleStateOf(0.0) }
    var summary by remember { mutableStateOf(source.summary) }
    var toneFinishedAt by remember { mutableLongStateOf(0L) }
    /** A newly unlocked character/stage from the last answer (shown with a ★). */
    var unlockedNote by remember { mutableStateOf<String?>(null) }
    var stageRev by remember { mutableIntStateOf(0) }

    // Mid-session Settings, drawn over the session so its state lives on.
    var showSettings by remember { mutableStateOf(false) }

    // Session phase: drills, then a summary once the timer runs out or End is
    // tapped. Back mid-session still records — it just skips the summary.
    // These are the session itself, so they ride the saved-instance-state
    // bundle: a process Android reclaims in the background comes back with the
    // tally, the clock and the phase intact and a fresh drill, instead of
    // silently dropping the session. (Rotation never recreates the activity —
    // see android:configChanges in the manifest — so this is only for that.)
    var phase by rememberSaveable { mutableStateOf(QuizPhase.RUNNING) }
    var tally by rememberSaveable(stateSaver = TallySaver) { mutableStateOf(Tally()) }
    var remaining by rememberSaveable(stateSaver = OptionalIntSaver) {
        mutableStateOf<Int?>(Settings.practiceDuration.seconds)
    }
    var recorded by rememberSaveable { mutableStateOf(false) }
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
    var listenTick by remember { mutableIntStateOf(0) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenTick++ else voiceNote = resources.getString(R.string.quiz_mic_permission_needed)
    }

    fun clearVoicePrompts() {
        voiceGuess = null
        voiceChoices = emptyList()
        voiceNote = null
    }

    // Optional keyed answers: the straight key + decoder, live while the toggle
    // is on (the iOS "answer by keying" panel).
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    // Push a keyer mode or speed picked in the Settings sheet to the adapter
    // now, rather than at the next wake — i.e. after leaving the module (#46).
    AdapterConfigSync(midi)
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
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt(),
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
        applyChoiceCount()
        drillTier = ladder.tier
        promotedTo = null
        drill = source.nextDrill()
        // A first meeting is shown, not sprung: the drill waits until the
        // learner has heard the new item on its own and started it (#162).
        intro = introductionFor(drill)
        revealed = false
        chosen = null
        unlockedNote = null
        round++
    }

    /** The learner has heard enough: remember the item as met and play the drill that was waiting. */
    fun startIntroducedDrill() {
        val shown = intro ?: return
        Settings.markIntroduced(shown.id)
        intro = null
        round++   // re-runs the round effect, which now plays the drill
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
        typedInput = ""
        toneFinishedAt = 0L
        unlockedNote = null
        listening = false
        voiceNote = null
        voiceGuess = null
        voiceChoices = emptyList()
        voiceHeard = emptyList()
        recognizer.cancel()
        keyer.clear()
        val introducing = intro
        if (introducing != null) {
            // The new item on its own; the drill plays once it is started.
            player.replaySound(introducing.playable, Settings.sidetoneHz, Settings.timing())
        } else {
            player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
        }
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
            // A promotion up the answer-entry ladder is held until Next,
            // like a miss, so the note is read rather than flashed.
            if (promotedTo != null) return@LaunchedEffect
            delay(1100)
            if (phase == QuizPhase.RUNNING) advance()
        }
    }

    // Session countdown: ticks only while running and only when a length is set.
    // Also keyed on whether a limit exists, so the timer menu starting a
    // countdown on an open-ended run (or dropping one) restarts the loop.
    LaunchedEffect(phase, remaining == null) {
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

    /**
     * Whether this drill is answered by typing (#232): always under Type,
     * from the ladder's top rung under Progressive. Per drill like keying — a
     * meaning or a prosign glyph keeps its choices — and keyed or spoken
     * answers, which the learner switched on explicitly, win over it.
     */
    fun typedAnswering(): Boolean {
        if (!answerEntryApplies || !drill.isKeyable) return false
        if (Settings.answerByKeying || Settings.voiceAnswersEnabled) return false
        return when (Settings.answerEntry) {
            AnswerEntryMode.CHOICES -> false
            AnswerEntryMode.TYPED -> true
            AnswerEntryMode.PROGRESSIVE -> drillTier == AnswerEntryTier.TYPED
        }
    }

    fun answer(choice: String) {
        if (revealed || intro != null || phase != QuizPhase.RUNNING) return
        val ttr = if (toneFinishedAt == 0L) 0.0
                  else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        lastTtr = ttr
        chosen = choice
        val outcome = source.record(choice = choice, ttr = ttr)
        summary = source.summary
        unlockedNote = outcome.unlocked
        // Feed the answer-entry ladder: a new level (a character, a stage)
        // starts it again at four choices; keyed and spoken answers do not
        // climb it.
        promotedTo = null
        if (answerEntryApplies && Settings.answerEntry == AnswerEntryMode.PROGRESSIVE) {
            if (outcome.unlocked != null) {
                ladder.restartLevel()
            } else if (!(Settings.answerByKeying && drill.isKeyable) && !Settings.voiceAnswersEnabled) {
                promotedTo = ladder.record(outcome.correct)
            }
            AnswerEntryStore.save(settingsMode, ladder)
        }
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

    /** Grade a typed answer, normalized the shared way; a blank waits. */
    fun submitTyped() {
        val normalized = TypedAnswer.normalize(typedInput)
        if (normalized.isNotEmpty()) answer(normalized)
    }

    // Keyed answers auto-submit once the decoded copy reaches the answer's
    // length and the key has gone idle (the Sending Practice rhythm).
    LaunchedEffect(keyer.decodedText, keyer.isKeying, revealed, round) {
        if (!Settings.answerByKeying || revealed || intro != null || phase != QuizPhase.RUNNING || keyer.isKeying) return@LaunchedEffect
        if (!drill.isKeyable) return@LaunchedEffect
        val sent = keyer.decodedText.trim()
        if (sent.isNotEmpty() && sent.length >= drill.correct.length) answer(sent.uppercase())
    }

    // Listen for a spoken answer when the mic is tapped (listenTick bumps).
    LaunchedEffect(listenTick) {
        if (listenTick == 0 || revealed || intro != null || phase != QuizPhase.RUNNING) return@LaunchedEffect
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
                    else -> voiceNote = resources.getString(R.string.quiz_voice_not_caught)
                }
            },
            onError = {
                listening = false
                voiceNote = resources.getString(R.string.quiz_voice_not_caught)
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
        if (intro != null) {
            // Enter starts the introduced drill; nothing else answers during an introduction.
            if (event.key == Key.Enter) {
                startIntroducedDrill()
                return true
            }
            return false
        }
        if (revealed) {
            // Enter leaves the held correction (issue #77).
            if (event.key == Key.Enter && chosen != drill.correct) {
                advance()
                return true
            }
            return false
        }
        if (Settings.answerByKeying && drill.isKeyable) return false
        if (typedAnswering()) return false   // the answer field has the keys
        val ch = event.utf16CodePoint.takeIf { it > 0 }?.toChar() ?: return false
        // Value beats position: a single-character option is answered by its own
        // key, and only the longer options fall back to a 1–9 position. Numbering
        // the whole grid whenever one option was multi-character made a digit you
        // heard select the Nth option instead (issue #30).
        val index = AnswerKeys.optionFor(ch, drill.options) ?: return false
        answer(drill.options[index])
        return true
    }
    LaunchedEffect(phase, round, showSettings, revealed, drillTier, Settings.answerEntry) {
        if (phase != QuizPhase.RUNNING || showSettings) return@LaunchedEffect
        // Typing an answer (#232) puts the caret in its field; otherwise, and
        // once it is graded (the field is disabled), the root takes the keys.
        // runCatching: the field is not composed during an introduction.
        if (typedAnswering() && !revealed && intro == null) {
            runCatching { typedFocus.requestFocus() }.onFailure { hwFocus.requestFocus() }
        } else {
            hwFocus.requestFocus()
        }
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
            TextButton(onClick = { if (phase == QuizPhase.SUMMARY) onBack() else finish() }) { Text(stringResource(R.string.common_back)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (phase == QuizPhase.RUNNING) {
                    SessionTimerMenu(
                        remaining = remaining,
                        onAddSeconds = { remaining = (remaining ?: 0) + it },
                        onRemoveLimit = { remaining = null }
                    )
                    SessionSettingsButton { showSettings = true }
                }
                // Switching records the run the way Back does, then lands on
                // the picked mode's setup (iOS #42).
                SwitchModeButton(trainingModeFor(settingsMode)) { mode ->
                    player.stop()
                    recordSession()
                    onSwitchMode(mode)
                }
                if (phase == QuizPhase.RUNNING) {
                    TextButton(onClick = { endSession() }) { Text(stringResource(R.string.common_end)) }
                }
            }
        }

        if (phase == QuizPhase.SUMMARY) {
            SessionSummaryContent(
                title = title,
                tally = tally,
                milestone = milestone,
                onPracticeAgain = { practiceAgain() },
                onDone = onFinish
            )
        } else {
        Column(
            modifier = Modifier.fillMaxSize().widthIn(max = CONTENT_MAX_WIDTH).padding(24.dp),
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

            val introducing = intro
            if (introducing != null) {
                Spacer(Modifier.height(28.dp))
                IntroductionContent(
                    intro = introducing,
                    onReplay = { player.replaySound(introducing.playable, Settings.sidetoneHz, Settings.timing()) },
                    onStart = { startIntroducedDrill() }
                )
            } else {

            // Answer by keying, where the heard text is the answer (iOS parity).
            if (drill.isKeyable) {
                TextButton(onClick = { Settings.updateAnswerByKeying(!Settings.answerByKeying) }) {
                    Text(
                        if (Settings.answerByKeying) stringResource(R.string.quiz_key_answers_on) else stringResource(R.string.quiz_key_answers_off),
                        fontSize = 13.sp,
                        color = if (Settings.answerByKeying) Brand.teal else Brand.textSecondary
                    )
                }
            }

            // How the choice quizzes take an answer (#232): tap, climb to
            // typing, or type — switchable mid-drill.
            if (answerEntryApplies && drill.isKeyable && !Settings.answerByKeying) {
                AnswerEntryMenu(drillTier)
            }

            // Attaching a BLE-MIDI key has to happen from the screen you are
            // keying on, since nothing else opens one (see BluetoothKeyButton).
            //
            // Gated on the setting alone, never on drill.isKeyable or on
            // whether a key is already attached: this button owns the BLE link
            // and closes it when it leaves the composition, so hanging it off
            // either would drop the key the moment an unkeyable drill came up
            // or the moment one connected. The setting is what the hardware
            // key's own lifecycle is keyed to, so they come and go together.
            if (Settings.answerByKeying) {
                Spacer(Modifier.height(6.dp))
                BluetoothKeyButton()
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
                // Right/wrong is its own setting (iOS showCorrectness): off,
                // only the reveal above says anything about the answer.
                if (Settings.showCorrectness) {
                    Text(
                        text = when {
                            ok -> stringResource(R.string.common_recalled_in, lastTtr)
                            showAnswer -> stringResource(R.string.common_it_was, drill.correct)
                            else -> stringResource(R.string.common_not_quite)
                        },
                        color = if (ok) OK_GREEN else ERR_RED,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (!ok && typedAnswering()) {
                    val typed = chosen.orEmpty()
                    Text(
                        if (typed.isEmpty()) stringResource(R.string.quiz_skipped) else stringResource(R.string.quiz_you_typed, typed),
                        color = Brand.textSecondary
                    )
                }
                promotedTo?.let { tier ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tier.choiceCount?.let { stringResource(R.string.quiz_next_step_choices, it) }
                            ?: stringResource(R.string.quiz_next_step_typing),
                        color = Brand.teal,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                unlockedNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.quiz_new_character, it), color = Brand.teal, fontWeight = FontWeight.SemiBold)
                }
                if (ok && promotedTo != null) {
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { advance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.common_next), fontWeight = FontWeight.SemiBold) }
                }
                if (!ok) {
                    // The held correction (issue #77): re-hear it as often as
                    // needed, move on when ready.
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(onClick = {
                        player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
                    }) { Text(stringResource(R.string.common_replay)) }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { advance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.common_next), fontWeight = FontWeight.SemiBold) }
                }
            } else {
                Text(text = "?", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Brand.teal)
                Spacer(Modifier.height(4.dp))
                // Replay before answering is the opt-in feedback setting (iOS
                // allowReplay); a miss offers it above regardless (issue #77).
                if (Settings.allowReplay) {
                    OutlinedButton(onClick = { player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing()) }) {
                        Text(stringResource(R.string.common_replay))
                    }
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
                    onPaddleKey = { down, ms -> keyer.touchKey(down, ms) },
                    onKey = { down ->
                        keyPressed = down
                        keyer.touchKey(down)
                    },
                    onClear = { keyer.clear() },
                    onSubmit = { answer(keyer.submit().uppercase()) }
                )
            } else if (typedAnswering()) {
                val single = drill.correct.length == 1
                TypedAnswerPanel(
                    value = typedInput,
                    onValueChange = { text ->
                        if (!revealed) {
                            typedInput = text
                            // A single-character drill submits on the keystroke
                            // once the tone is done, so the recognition clock is
                            // not charged for a second tap — the one touch a
                            // choice button takes.
                            if (single && toneFinishedAt != 0L && TypedAnswer.normalize(text).length == 1) submitTyped()
                        }
                    },
                    placeholder = stringResource(if (single) R.string.quiz_type_the_character else R.string.quiz_type_what_you_heard),
                    enabled = !revealed,
                    focus = typedFocus,
                    onSubmit = { submitTyped() },
                    onSkip = { answer("") }
                )
            } else {
                OptionsGrid(drill = drill, revealed = revealed, chosen = chosen, showCorrectness = Settings.showCorrectness, onPick = ::answer)
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
                    Text(if (listening) stringResource(R.string.quiz_listening) else stringResource(R.string.quiz_speak_answer))
                }

                // Not sure what was said: confirm the best guess…
                voiceGuess?.let { guess ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(R.string.quiz_voice_confirm, guess),
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.textPrimary
                        )
                        TextButton(onClick = { acceptVoice(guess) }) { Text(stringResource(R.string.quiz_yes)) }
                        TextButton(onClick = {
                            // …or fall back to picking the closest-sounding answers.
                            voiceGuess = null
                            voiceChoices = voiceMatcher
                                .rankedCandidates(voiceHeard, drill.options)
                                .filter { it != guess }
                                .take(3)
                        }) { Text(stringResource(R.string.quiz_no)) }
                    }
                }
                if (voiceChoices.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.quiz_i_said), style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        voiceChoices.forEach { choice ->
                            OutlinedButton(onClick = { acceptVoice(choice) }) { Text(choice, maxLines = 1) }
                        }
                        TextButton(onClick = { clearVoicePrompts() }) { Text(stringResource(R.string.quiz_none)) }
                    }
                }

                voiceNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary, textAlign = TextAlign.Center)
                }
            }
            }   // introduction / drill
        }
        }

        if (showSettings) {
            SessionSettingsOverlay(
                scope = settingsMode,
                onClose = { showSettings = false },
                // The developer Preview Stage row jumped the shared track
                // underneath: start drilling the new stage, as iOS does.
                onPreviewStage = if (progressive != null) {
                    {
                        showSettings = false
                        summary = source.summary
                        stageRev++
                        advance()
                    }
                } else null
            )
        }
    }
}

/**
 * The first sight of a character or prosign (issue #162): on its own, large,
 * with its pattern in symbols and in "dah-di-dah", its sound on Replay, and
 * the way into the drill that is waiting behind it. Until now a new
 * character's first appearance was as a question, so the only way to learn
 * its sound was to guess wrong at it.
 */
@Composable
private fun IntroductionContent(intro: CharacterIntroduction, onReplay: () -> Unit, onStart: () -> Unit) {
    Text(
        stringResource(if (intro.isProsign) R.string.quiz_intro_new_prosign else R.string.quiz_intro_new_character),
        color = Brand.teal,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(12.dp))
    SlashableText(
        text = intro.display,
        fontSize = 88.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(intro.symbolPattern, fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
    Text(intro.spokenPattern, fontSize = 20.sp, color = Brand.textPrimary)
    intro.meaning?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.quiz_intro_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = Brand.textSecondary,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onReplay) { Text(stringResource(R.string.common_replay)) }
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onStart,
        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.quiz_intro_start), fontWeight = FontWeight.SemiBold) }
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
        modifier = Modifier.fillMaxSize().widthIn(max = CONTENT_MAX_WIDTH).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.common_session_complete), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.fillMaxWidth().brandCard()) {
            SummaryRow(stringResource(R.string.common_answered), "${tally.attempts}")
            val pct = if (tally.attempts == 0) 0 else (tally.correct * 100.0 / tally.attempts).roundToInt()
            SummaryRow(stringResource(R.string.common_accuracy), "$pct%")
            SummaryRow(stringResource(R.string.common_fastest), tally.bestMs?.let { stringResource(R.string.common_seconds_2dp, it / 1000.0) } ?: "—")
            SummaryRow(stringResource(R.string.common_median), tally.medianMs()?.let { stringResource(R.string.common_seconds_2dp, it / 1000.0) } ?: "—")
        }

        milestone?.let { day ->
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().brandCard().padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(milestoneEmoji(day), fontSize = 40.sp)
                Text(stringResource(R.string.common_streak_milestone, day), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(stringResource(R.string.common_milestone_body), color = Brand.textSecondary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPracticeAgain,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            shape = RoundedCornerShape(Brand.cornerRadius),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(stringResource(R.string.common_practice_again), fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        // Named for where it lands (issue #90): "Done" said nothing about
        // the destination, and the iOS recap now says the same thing.
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.common_return_home))
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
internal fun StagePinRow(
    pinned: ProgressiveCharacters.Stage?,
    onPick: (ProgressiveCharacters.Stage?) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.quiz_stage), style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
        StagePill(stringResource(R.string.common_auto), selected = pinned == null) { onPick(null) }
        ProgressiveCharacters.Stage.entries.forEach { stage ->
            val label = when (stage) {
                ProgressiveCharacters.Stage.Singles -> stringResource(R.string.quiz_chars)
                ProgressiveCharacters.Stage.Phrases -> stringResource(R.string.quiz_words)
                else -> stage.displayName
            }
            StagePill(label, selected = pinned == stage) { onPick(stage) }
        }
    }
}

@Composable
internal fun StagePill(label: String, selected: Boolean, onClick: () -> Unit) {
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
    onPaddleKey: (Boolean, Long) -> Unit,
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
            Text(stringResource(R.string.common_you_sent), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
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
        // Straight key or paddles, as chosen in Settings (#233).
        OnScreenKeySwitch(
            onPaddleKey = onPaddleKey,
            modifier = Modifier.fillMaxWidth().height(100.dp),
            enabled = enabled
        ) {
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
                        stringResource(R.string.common_hold_to_key),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (keyPressed) Brand.navy else Brand.textSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onClear,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            ) { Text(stringResource(R.string.common_clear)) }
            Button(
                onClick = onSubmit,
                enabled = enabled && decoded.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
            ) { Text(stringResource(R.string.common_submit), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * The in-drill Answer entry picker (#232), labelled with where the
 * progressive ladder stands for the drill on screen.
 */
@Composable
private fun AnswerEntryMenu(drillTier: AnswerEntryTier) {
    var open by remember { mutableStateOf(false) }
    val label = when (Settings.answerEntry) {
        AnswerEntryMode.CHOICES -> stringResource(R.string.answer_entry_label_tap)
        AnswerEntryMode.TYPED -> stringResource(R.string.answer_entry_label_type)
        AnswerEntryMode.PROGRESSIVE -> drillTier.choiceCount
            ?.let { stringResource(R.string.answer_entry_label_progressive_choices, it) }
            ?: stringResource(R.string.answer_entry_label_progressive_typing)
    }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                stringResource(R.string.quiz_answers_menu, label),
                fontSize = 13.sp,
                color = if (Settings.answerEntry == AnswerEntryMode.CHOICES) Brand.textSecondary else Brand.teal
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AnswerEntryMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(mode.titleRes) + if (mode == Settings.answerEntry) "  ✓" else "",
                            fontWeight = if (mode == Settings.answerEntry) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        Settings.updateAnswerEntry(mode)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * A typed answer to a choice quiz (#232): the field, the number and
 * punctuation row the typed modes share, Submit, and "Don't know" — a miss
 * recorded with nothing as the character it was mistaken for.
 */
@Composable
private fun TypedAnswerPanel(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    focus: FocusRequester,
    onSubmit: () -> Unit,
    onSkip: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        MorseNumberRow(
            onKey = { if (enabled) onValueChange(value + it) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(placeholder, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSubmit,
            enabled = enabled && value.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.quiz_submit), fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onSkip, enabled = enabled) {
            Text(stringResource(R.string.quiz_dont_know), color = Brand.textSecondary)
        }
    }
}

@Composable
private fun OptionsGrid(drill: Drill, revealed: Boolean, chosen: String?, showCorrectness: Boolean, onPick: (String) -> Unit) {
    // Two-column grid of bold teal buttons (matches the iOS choice grid).
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        drill.options.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { option ->
                    // The iOS tint rule: the correct option goes green only with
                    // "Show right / wrong" on; a wrong pick goes red regardless.
                    val colors = when {
                        revealed && option == drill.correct && showCorrectness -> ButtonDefaults.buttonColors(containerColor = OK_GREEN, contentColor = Color.White)
                        revealed && option == chosen && option != drill.correct -> ButtonDefaults.buttonColors(containerColor = ERR_RED, contentColor = Color.White)
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
