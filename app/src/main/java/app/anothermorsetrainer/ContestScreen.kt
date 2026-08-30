package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.ContestLength
import app.anothermorsetrainer.morsekit.ContestType
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.PileupConfig
import app.anothermorsetrainer.morsekit.PileupEngine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class CtPhase { SETUP, RUNNING, SUMMARY }

/**
 * Map an engine [PileupEngine.Voice] to a renderable [MorsePlayer.PileupVoice].
 * The contest band sits around your sidetone pitch, like the Pileup Runner.
 */
private fun PileupEngine.Voice.toMix() = MorsePlayer.PileupVoice(
    text = text,
    frequency = Settings.sidetoneHz + toneOffset,
    timing = MorseTiming(wpm),
    gain = volume,
    startDelay = delay,
    qsbRate = if (qsb) 0.3 else null
)

/**
 * Contest mode: run a simulated CW contest against the clock. Pick from the
 * weekly sprints — K1USN SST, ICWC MST, CWops CWT, the NCCC Sprint — or ARRL
 * Field Day, then call CQ and work the pileup that answers, with authentic
 * speeds, a live score and rate, and an end-of-run scorecard.
 *
 * Ported from MorseKit/Contest.swift and the iOS ContentView contest flow. The
 * pileup machinery is the fully-ported [PileupEngine]; the contest pins its
 * exchange, speed band, run length, and scoring rule.
 */
@Composable
fun ContestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }

    var phase by remember { mutableStateOf(CtPhase.SETUP) }
    var contest by remember { mutableStateOf(ContestType.Sst) }
    var length by remember { mutableStateOf(ContestLength.TenMin) }

    // Run state.
    var engine by remember { mutableStateOf<PileupEngine?>(null) }
    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var startedAtMs by remember { mutableStateOf(0L) }
    var endedAtMs by remember { mutableStateOf(0L) }
    // Engine state isn't Compose-observable; the clock also rides this tick.
    var rev by remember { mutableStateOf(0) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun elapsedSeconds(): Int {
        if (startedAtMs == 0L) return 0
        val end = if (endedAtMs > 0L) endedAtMs else System.currentTimeMillis()
        return ((end - startedAtMs) / 1000L).toInt()
    }

    fun endRun() {
        val e = engine ?: return
        player.stop()
        endedAtMs = System.currentTimeMillis()
        // A contest answer is a whole copied exchange; clean copies count as
        // correct, busts as misses — mirroring the engine's clean-copy accuracy.
        Stats.record(
            mode = "Contest",
            attempts = e.qsoCount + e.bustCount,
            correct = e.qsoCount,
            bestTtrMs = null,
            durationSeconds = elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt()
        )
        phase = CtPhase.SUMMARY
    }

    fun startRun() {
        engine = PileupEngine(
            PileupConfig(
                mode = contest.qsoMode,
                minWPM = contest.minWPM,
                maxWPM = contest.maxWPM
            )
        )
        input = ""
        reveal = false
        startedAtMs = System.currentTimeMillis()
        endedAtMs = 0L
        rev++
        phase = CtPhase.RUNNING
    }

    fun perform(action: PileupEngine.Action) {
        when (action) {
            is PileupEngine.Action.Play -> player.playPileup(action.voices.map { it.toMix() }) {}
            PileupEngine.Action.Silence -> player.stop()
            is PileupEngine.Action.Logged -> if (Settings.hapticsEnabled) haptics.success()
        }
        rev++
    }

    fun submit() {
        val e = engine ?: return
        if (input.isBlank()) return
        val raw = input.trim()
        val action = e.send(raw)
        // Keep a typed repeat request's partial call in the box (iOS #49).
        val frag = PileupEngine.fragment(raw)
        input = if (raw.endsWith("?") && frag.isNotEmpty() && e.phase is PileupEngine.Phase.Pileup) frag else ""
        perform(action)
    }

    // The contest clock: tick once a second while running; a timed run ends
    // itself when the length is up.
    LaunchedEffect(phase) {
        if (phase != CtPhase.RUNNING) return@LaunchedEffect
        while (true) {
            delay(1000)
            rev++
            val limit = length.seconds ?: continue
            if (elapsedSeconds() >= limit) {
                endRun()
                break
            }
        }
    }

    BackHandler {
        when (phase) {
            CtPhase.SETUP -> { player.stop(); onBack() }
            CtPhase.RUNNING -> endRun()
            CtPhase.SUMMARY -> onBack()
        }
    }

    // Mid-session Settings, drawn over the run so the contest lives on.
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    when (phase) {
        CtPhase.SETUP -> ContestSetup(
            contest = contest, onContest = { contest = it },
            length = length, onLength = { length = it },
            onStart = ::startRun,
            onBack = { player.stop(); onBack() }
        )
        CtPhase.RUNNING -> engine?.let { e ->
            // rev rides in as a plain parameter, NOT a key(): keying the subtree
            // on it rebuilt the run UI every clock tick, which yanked focus from
            // the Send box and closed the keyboard as soon as it opened (#24).
            ContestRun(
                engine = e,
                tick = rev,
                contest = contest,
                clockText = contestClock(length, elapsedSeconds()),
                elapsedSeconds = elapsedSeconds(),
                input = input,
                onInput = { input = it.uppercase() },
                onSend = ::submit,
                reveal = reveal,
                onToggleReveal = { reveal = !reveal },
                onCQ = { perform(e.callCQ()) },
                onRepeat = { perform(e.repeatRequest()) },
                onLog = { perform(e.logCurrent()) },
                onSettings = { showSettings = true },
                onEnd = ::endRun
            )
        }
        CtPhase.SUMMARY -> engine?.let { e ->
            ContestSummary(
                engine = e,
                contest = contest,
                length = length,
                elapsedSeconds = elapsedSeconds(),
                onAgain = { phase = CtPhase.SETUP },
                onBack = onBack
            )
        }
    }

    if (showSettings) {
        SessionSettingsOverlay(scope = SettingsMode.CONTEST, onClose = { showSettings = false })
    }
    }
}

/** "12:34 left" for timed runs, "12:34" elapsed for open-ended ones. */
private fun contestClock(length: ContestLength, elapsed: Int): String {
    val secs = length.seconds
    val shown = if (secs != null) maxOf(0, secs - elapsed) else elapsed
    val text = "%d:%02d".format(shown / 60, shown % 60)
    return if (secs != null) "$text left" else text
}

// MARK: - Setup

@Composable
private fun ContestSetup(
    contest: ContestType, onContest: (ContestType) -> Unit,
    length: ContestLength, onLength: (ContestLength) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Brand.teal) }
            Text("Contest", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
      CenteredContent {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CtSectionLabel("WHICH CONTEST?")
            CtPills(ContestType.allCases.map { it to it.shortName }, contest, onContest)
            Text(
                contest.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary
            )
            Text(
                "Stations run ${contest.minWPM.roundToInt()}–${contest.maxWPM.roundToInt()} WPM.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )

            CtSectionLabel("HOW LONG DO YOU WANT TO RUN?")
            CtPills(ContestLength.allCases.map { it to it.label }, length, onLength)

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Brand.cornerRadius)
            ) { Text("Start ${contest.shortName}", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            Spacer(Modifier.height(16.dp))
        }
      }
    }
}

// MARK: - Run

@Composable
private fun ContestRun(
    engine: PileupEngine,
    // Deliberately unread: the engine mutates outside Compose, so this bumped
    // counter is what makes each tick/action recompose the run (strong
    // skipping would otherwise see identical parameters and skip).
    tick: Int,
    contest: ContestType,
    clockText: String,
    elapsedSeconds: Int,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    onCQ: () -> Unit,
    onRepeat: () -> Unit,
    onLog: () -> Unit,
    onSettings: () -> Unit,
    onEnd: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onEnd) { Text("End", color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            Text(
                "${contest.eventName} · $clockText",
                style = MaterialTheme.typography.labelMedium,
                color = Brand.textSecondary
            )
            SessionSettingsButton(onOpen = onSettings)
        }

      CenteredContent {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ContestScoreboard(engine, contest, elapsedSeconds)
            Spacer(Modifier.height(28.dp))

            when (engine.phase) {
                PileupEngine.Phase.Idle -> {
                    Text("Call CQ ${contest.shortName} and work who answers.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onCQ) { Text("Call CQ") }
                }

                PileupEngine.Phase.Pileup -> {
                    Text(
                        "${engine.activeCount} stations calling.",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text("Copy a call and send it.", textAlign = TextAlign.Center)
                    if (reveal) {
                        Text(
                            "calling: ${engine.stations.joinToString(", ") { it.call }}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    ContestEntry(input = input, onChange = onInput, onSend = onSend)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRepeat) { Text("▶ Again") }
                        OutlinedButton(onClick = onCQ) { Text("CQ") }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onToggleReveal) {
                        Text(if (reveal) "Hide hint" else "Show hint")
                    }
                }

                is PileupEngine.Phase.Working, is PileupEngine.Phase.ReadyToLog -> {
                    val st = engine.workingStation
                    Text("Working ${st?.call ?: "?"}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Copy their exchange and send it.", textAlign = TextAlign.Center)
                    if (reveal) {
                        Text(
                            "expecting: ${engine.expectedCopy ?: "—"}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    ContestEntry(input = input, onChange = onInput, onSend = onSend)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRepeat) { Text("▶ Again") }
                        Button(onClick = onLog) { Text("Log (TU)") }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onToggleReveal) {
                        Text(if (reveal) "Hide hint" else "Show hint")
                    }
                }
            }
        }
      }
    }
}

/** The live scoreboard: score, QSOs/mults where they differ, rate, clean copy. */
@Composable
private fun ContestScoreboard(engine: PileupEngine, contest: ContestType, elapsedSeconds: Int) {
    val mults = contest.multiplierCount(
        calls = engine.log.map { it.call },
        exchanges = engine.log.map { it.exchange }
    )
    val score = contest.score(qsoCount = engine.qsoCount, multipliers = mults)
    val rate = if (elapsedSeconds <= 0) 0 else (engine.qsoCount * 3600.0 / elapsedSeconds).roundToInt()
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        CtStat("Score", "$score")
        // QSOs only when the score isn't simply the QSO count (a multiplier
        // applies, or each QSO is worth more than a point).
        if (contest.usesMultipliers || contest.pointsPerQSO != 1) {
            CtStat("QSOs", "${engine.qsoCount}")
        }
        contest.multiplierLabel?.let { CtStat(it, "$mults") }
        CtStat("Rate", "$rate/hr")
        // Drop accuracy when a multiplier column already fills the row.
        if (!contest.usesMultipliers) {
            CtStat("Acc", "${(engine.accuracy * 100).roundToInt()}%")
        }
    }
}

@Composable
private fun CtStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
    }
}

// MARK: - Summary

@Composable
private fun ContestSummary(
    engine: PileupEngine,
    contest: ContestType,
    length: ContestLength,
    elapsedSeconds: Int,
    onAgain: () -> Unit,
    onBack: () -> Unit
) {
    val mults = contest.multiplierCount(
        calls = engine.log.map { it.call },
        exchanges = engine.log.map { it.exchange }
    )
    val score = contest.score(qsoCount = engine.qsoCount, multipliers = mults)
    val rate = if (elapsedSeconds <= 0) 0 else (engine.qsoCount * 3600.0 / elapsedSeconds).roundToInt()
    val cleanTotal = engine.qsoCount + engine.bustCount

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Brand.teal) }
            Text("${contest.eventName} — ${length.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).brandCard().padding(14.dp)) {
            CtSummaryRow("Score", "$score")
            if (contest.usesMultipliers || contest.pointsPerQSO != 1) {
                CtSummaryRow("QSOs", "${engine.qsoCount}")
            }
            contest.multiplierLabel?.let { CtSummaryRow(it, "$mults") }
            CtSummaryRow("Rate", "$rate/hr")
            CtSummaryRow("Clean copy", if (cleanTotal == 0) "—" else "${(engine.accuracy * 100).roundToInt()}%")
            CtSummaryRow("Busts", "${engine.bustCount}")
        }

        if (engine.log.isNotEmpty()) {
            Text(
                "WORKED",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Brand.textSecondary,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(engine.log, key = { it.id }) { q ->
                Row(
                    modifier = Modifier.fillMaxWidth().brandCard().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(q.call, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Brand.textPrimary, modifier = Modifier.weight(1f))
                    Text(q.exchange, fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
                    Text("${q.wpm} wpm", style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Run again") }
            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Return home") }
        }
    }
}

@Composable
private fun CtSummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Brand.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, color = Brand.textPrimary)
    }
}

// MARK: - Small shared bits

@Composable
private fun ContestEntry(input: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onChange,
            singleLine = true,
            label = { Text("Send") },
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Button(onClick = onSend, modifier = Modifier.height(56.dp)) { Text("Send") }
    }
}

@Composable
private fun CtSectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun <T> CtPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                modifier = Modifier
                    .background(if (sel) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(label, color = if (sel) Brand.navy else Brand.textSecondary, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}
