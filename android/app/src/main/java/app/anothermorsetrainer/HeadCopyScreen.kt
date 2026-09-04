package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.PhraseQuiz
import kotlinx.coroutines.delay

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/** A guaranteed non-match, so a self-graded miss never scores as correct. */
private const val MISS_SENTINEL = "miss"

/** Pause before each auto-repeat (the iOS model's `headCopyRepeatGap`, 1.5 s). */
private const val HC_REPEAT_GAP_MS = 1500L

private enum class HcPhase { RUNNING, SUMMARY }

/**
 * **Head Copy**: hear a word or call sign, copy it in your head with no choices
 * on screen, then reveal and self-grade. Builds true head-copy. Mirrors the iOS
 * Head Copy flow: replay on demand, reveal when ready (or on a timer), optional
 * auto-repeats until you reveal, then "Got it" / "Missed". Runs against the
 * configured session length and ends with the standard summary.
 */
@Composable
fun HeadCopyScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember { PhraseQuiz("Head Copy", MorseData.wordAndCallSignItems, summaryNoun = "words & calls") }

    var drill by remember { mutableStateOf(source.nextDrill()) }
    // Monotonic counter drives play/reset — never key on the Drill value. See #43.
    var round by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(source.summary) }
    // Mid-session Settings, drawn over the session so its state lives on.
    var showSettings by remember { mutableStateOf(false) }
    // When the tone finished, so recall time (tone end → Reveal) feeds stats
    // like every other mode instead of a flat zero.
    var toneFinishedAt by remember { mutableLongStateOf(0L) }
    var lastRecallSec by remember { mutableDoubleStateOf(0.0) }

    // Session phase: drills, then a summary once the timer runs out or End is
    // tapped. Back mid-session still records — it just skips the summary.
    // These are the session itself, so they ride the saved-instance-state
    // bundle: a process Android reclaims in the background comes back with the
    // tally, the clock and the phase intact and a fresh drill, instead of
    // silently dropping the session. (Rotation never recreates the activity —
    // see android:configChanges in the manifest — so this is only for that.)
    var phase by rememberSaveable { mutableStateOf(HcPhase.RUNNING) }
    var tally by rememberSaveable(stateSaver = TallySaver) { mutableStateOf(Tally()) }
    var remaining by rememberSaveable(stateSaver = OptionalIntSaver) {
        mutableStateOf<Int?>(Settings.practiceDuration.seconds)
    }
    var recorded by rememberSaveable { mutableStateOf(false) }
    var milestone by remember { mutableStateOf<Int?>(null) }

    // Head Copy's structured re-hearing, as the iOS model runs it: once the
    // first play finishes, replay the item `headCopyRepeats` times with a
    // short gap, then count down to the reveal — "Revealing in N…" — when a
    // reveal delay is set. A manual Repeat drops the remaining auto-repeats
    // and restarts the countdown once its sound is over; `autoGen` is what
    // re-launches the chain for it.
    var autoGen by remember { mutableIntStateOf(0) }
    var autoRepeatsLeft by remember { mutableIntStateOf(0) }
    var manualWaitMs by remember { mutableLongStateOf(0L) }
    var countdown by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(round) {
        revealed = false
        toneFinishedAt = 0L
        lastRecallSec = 0.0
        autoRepeatsLeft = Settings.headCopyRepeats
        manualWaitMs = 0L
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    fun doReveal() {
        if (revealed) return
        // Recall time runs from the end of the tone to the reveal.
        lastRecallSec = if (toneFinishedAt == 0L) 0.0
                        else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        revealed = true
    }

    // Keyed on `revealed` too, so a manual reveal cancels a pending repeat or
    // countdown the moment it happens rather than a tick later.
    LaunchedEffect(round, toneFinishedAt, autoGen, revealed) {
        countdown = null
        if (revealed || phase != HcPhase.RUNNING || toneFinishedAt == 0L) return@LaunchedEffect
        if (manualWaitMs > 0L) {
            delay(manualWaitMs)
            manualWaitMs = 0L
        }
        while (autoRepeatsLeft > 0) {
            delay(HC_REPEAT_GAP_MS)
            autoRepeatsLeft -= 1
            val dur = player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
            delay((dur * 1000).toLong())
        }
        var n = Settings.headCopyRevealSec
        if (n <= 0) return@LaunchedEffect   // manual reveal only
        while (n > 0) {
            countdown = n
            delay(1000)
            n -= 1
        }
        countdown = null
        doReveal()
    }

    /** Replay now: skip the remaining auto-repeats, then restart the reveal countdown after the sound. */
    fun repeatNow() {
        if (revealed || phase != HcPhase.RUNNING) return
        autoRepeatsLeft = 0
        val dur = player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
        manualWaitMs = (dur * 1000).toLong()
        // A repeat before the first play finished: its callback never fires
        // once the player is restarted, so mark the tone finished here.
        if (toneFinishedAt == 0L) toneFinishedAt = System.nanoTime()
        autoGen++
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    /** Persist the session once (summary entry or an early Back, whichever first). */
    fun recordSession(): Int? {
        if (recorded) return null
        recorded = true
        return Stats.record(
            mode = "Head Copy", attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = tally.bestMs, durationSeconds = tally.elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt(), medianTtrMs = tally.medianMs()
        )
    }

    fun endSession() {
        if (phase == HcPhase.SUMMARY) return
        player.stop()
        milestone = recordSession()
        phase = HcPhase.SUMMARY
    }

    fun finish() {
        recordSession()
        onBack()
    }

    fun practiceAgain() {
        tally = Tally()
        recorded = false
        milestone = null
        remaining = Settings.practiceDuration.seconds
        phase = HcPhase.RUNNING
        drill = source.nextDrill()
        revealed = false
        round++
    }

    // Session countdown: ticks only while running and only when a length is set.
    // Also keyed on whether a limit exists, so the timer menu starting a
    // countdown on an open-ended run (or dropping one) restarts the loop.
    LaunchedEffect(phase, remaining == null) {
        if (phase != HcPhase.RUNNING) return@LaunchedEffect
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

    BackHandler { if (phase == HcPhase.SUMMARY) onBack() else finish() }

    fun grade(gotIt: Boolean) {
        if (phase != HcPhase.RUNNING) return
        val outcome = source.record(choice = if (gotIt) drill.correct else MISS_SENTINEL, ttr = lastRecallSec)
        tally.attempts += 1
        if (outcome.correct) {
            tally.correct += 1
            tally.noteCorrectMs((lastRecallSec * 1000).roundToInt())
        }
        if (Settings.hapticsEnabled) {
            if (outcome.correct) haptics.success() else haptics.error()
        }
        summary = source.summary
        // Hide the reveal in the same recomposition as the new drill —
        // LaunchedEffect(round) resets it a frame later, and that stale frame
        // flashed the next item's answer (issue #63).
        drill = source.nextDrill()
        revealed = false
        round++
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (phase == HcPhase.SUMMARY) onBack() else finish() }) { Text(stringResource(R.string.common_back)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (phase == HcPhase.RUNNING) {
                    SessionTimerMenu(
                        remaining = remaining,
                        onAddSeconds = { remaining = (remaining ?: 0) + it },
                        onRemoveLimit = { remaining = null }
                    )
                    SessionSettingsButton { showSettings = true }
                }
                // Switching records the run the way Back does, then lands on
                // the picked mode's setup (iOS #42).
                SwitchModeButton(TrainingMode.HEAD_COPY) { mode ->
                    player.stop()
                    recordSession()
                    onSwitchMode(mode)
                }
                if (phase == HcPhase.RUNNING) {
                    TextButton(onClick = { endSession() }) { Text(stringResource(R.string.common_end)) }
                }
            }
        }

        if (phase == HcPhase.SUMMARY) {
            SessionSummaryContent(
                title = stringResource(R.string.mode_head_copy),
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
            Text(text = stringResource(R.string.mode_head_copy), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(text = summary, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

            // How each item plays out: how many auto-repeats, and how long
            // after them the answer reveals itself. The same two controls as
            // the Head Copy section of Settings (iOS parity), kept on the
            // drill so they can be tuned without leaving it.
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.headcopy_repeat), style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
                (0..Settings.MAX_HEAD_COPY_REPEATS).forEach { n ->
                    val label = if (n == 0) stringResource(R.string.common_off) else stringResource(R.string.settings_repeat_times, n)
                    HcPill(label, selected = Settings.headCopyRepeats == n) { Settings.updateHeadCopyRepeats(n) }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.headcopy_reveal), style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
                Slider(
                    value = Settings.headCopyRevealSec.toFloat(),
                    onValueChange = { Settings.updateHeadCopyRevealSec(it.roundToInt()) },
                    valueRange = 0f..Settings.MAX_HEAD_COPY_REVEAL_SEC.toFloat(),
                    steps = Settings.MAX_HEAD_COPY_REVEAL_SEC - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = Brand.teal,
                        activeTrackColor = Brand.teal,
                        inactiveTrackColor = Brand.navyRaised,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f).height(28.dp)
                )
                Text(
                    if (Settings.headCopyRevealSec < 1) stringResource(R.string.headcopy_manual)
                    else stringResource(R.string.settings_seconds_whole, Settings.headCopyRevealSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.teal,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(28.dp))

            // The live reveal countdown, as on iOS ("Revealing in N…").
            countdown?.let { n ->
                Text(
                    stringResource(R.string.headcopy_revealing_in, n),
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.textSecondary
                )
            }
            Spacer(Modifier.height(8.dp))

            if (revealed) {
                SlashableText(
                    text = drill.revealPrimary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.headcopy_did_you_copy_it), color = Brand.textSecondary)
            } else {
                Text(text = "🧠", fontSize = 52.sp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.common_copy_it_in_your_head), fontSize = 18.sp, color = Brand.teal)
            }

            Spacer(Modifier.height(36.dp))

            if (revealed) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = { grade(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = ERR_RED, contentColor = Color.White),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                    ) { Text(stringResource(R.string.headcopy_missed), fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { grade(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = OK_GREEN, contentColor = Color.White),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                    ) { Text(stringResource(R.string.headcopy_got_it), fontWeight = FontWeight.SemiBold) }
                }
            } else {
                Button(
                    onClick = { doReveal() },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.headcopy_reveal), fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { repeatNow() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.common_replay)) }
            }
        }
        }

        if (showSettings) {
            SessionSettingsOverlay(scope = SettingsMode.HEAD_COPY, onClose = { showSettings = false })
        }
    }
}

@Composable
private fun HcPill(label: String, selected: Boolean, onClick: () -> Unit) {
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
