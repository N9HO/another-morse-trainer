package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.HuntConfig
import app.anothermorsetrainer.morsekit.HuntKind
import app.anothermorsetrainer.morsekit.HuntOutcome
import app.anothermorsetrainer.morsekit.HuntRecord
import app.anothermorsetrainer.morsekit.HuntSession
import app.anothermorsetrainer.morsekit.HuntYouSend
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.OpLesson
import app.anothermorsetrainer.morsekit.OperatingProcedure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The hunt simulator (#313, docs/operating-procedure-design.md): a hunter's
 * side of a POTA pileup against a simulated activator. Setup (difficulty), the
 * run (the activator sends, the pileup calls, you key — on screen, on the
 * keyboard or on a Vail / MIDI key — with an XIT control for your offset), and
 * a debrief of what you heard against what the activator heard, each graded
 * point linked to its lesson. The rules are MorseKit's [HuntSession]; this
 * screen only sounds, shows and records them. Twin of the desktop tree's own
 * HuntSimScreen and of the iOS HuntSimView.swift.
 *
 * Not ranked, no leaderboard entry and no session record (maintainer,
 * 2026-10-03). A graded window counts as practice for the day.
 */
@Composable
internal fun HuntSimPage(call: String, state: String, onOpenLesson: (OpLesson) -> Unit) {
    var config by remember { mutableStateOf(HuntConfig.MEDIUM) }
    var run by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf<HuntSession?>(null) }

    val done = finished
    when {
        done != null -> HuntDebrief(
            session = done,
            onAgain = { finished = null; run++; started = true },
            onSettings = { finished = null; started = false },
            onOpenLesson = onOpenLesson
        )
        started -> androidx.compose.runtime.key(run) {
            HuntRun(config = config, call = call, state = state, onDone = { finished = it })
        }
        else -> HuntSetup(config = config, onConfig = { config = it }, onStart = { run++; started = true })
    }
}

// ---- Setup ----------------------------------------------------------------------

private val NOISE_LEVELS = listOf(0.0, 0.08, 0.15, 0.25)
private val BUST_LEVELS = listOf(0.0, 0.1, 0.25, 0.4)

private fun nearestIndex(levels: List<Double>, v: Double): Int =
    levels.indices.minByOrNull { kotlin.math.abs(levels[it] - v) } ?: 0

@Composable
private fun HuntCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun HuntEyebrow(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Brand.textSecondary)
}

@Composable
private fun HuntStepper(label: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = Brand.textPrimary, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onLess) { Text("−", color = Brand.teal, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = onMore) { Text("+", color = Brand.teal, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun HuntSetup(config: HuntConfig, onConfig: (HuntConfig) -> Unit, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.hunt_lead), fontSize = 14.sp, color = Brand.textSecondary)
        HuntCard {
            HuntEyebrow(stringResource(R.string.hunt_difficulty))
            val presets = listOf(
                R.string.hunt_easy to HuntConfig.EASY,
                R.string.hunt_medium to HuntConfig.MEDIUM,
                R.string.hunt_hard to HuntConfig.HARD
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                presets.forEach { (name, preset) ->
                    val on = config == preset
                    Button(
                        onClick = { onConfig(preset) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (on) Brand.teal else Brand.navyRaised,
                            contentColor = if (on) Brand.navy else Brand.textPrimary
                        )
                    ) { Text(stringResource(name), fontWeight = FontWeight.SemiBold) }
                }
            }
            if (presets.none { config == it.second }) {
                Text(stringResource(R.string.hunt_custom), fontSize = 12.sp, color = Brand.textSecondary)
            }
            HuntStepper(
                stringResource(R.string.hunt_pileup_size, config.pileupSize),
                onLess = { onConfig(config.copy(pileupSize = maxOf(HuntConfig.MIN_PILEUP, config.pileupSize - 1))) },
                onMore = { onConfig(config.copy(pileupSize = minOf(HuntConfig.MAX_PILEUP, config.pileupSize + 1))) }
            )
            fun speed(d: Double): HuntConfig {
                val lo = (config.wpmMin + d).coerceIn(10.0, 36.0)
                val hi = lo + (config.wpmMax - config.wpmMin)
                return config.copy(wpmMin = lo, wpmMax = hi, activatorWpm = ((lo + hi) / 2).roundToInt().toDouble())
            }
            HuntStepper(
                stringResource(R.string.hunt_speed, config.wpmMin.roundToInt(), config.wpmMax.roundToInt()),
                onLess = { onConfig(speed(-2.0)) },
                onMore = { onConfig(speed(2.0)) }
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.hunt_qsb), fontSize = 14.sp, color = Brand.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = config.qsb, onCheckedChange = { onConfig(config.copy(qsb = it)) })
            }
            val noiseIdx = nearestIndex(NOISE_LEVELS, config.qrn)
            val noiseNames = listOf(R.string.hunt_noise_off, R.string.hunt_noise_light, R.string.hunt_noise_medium, R.string.hunt_noise_heavy)
            HuntStepper(
                stringResource(R.string.hunt_noise, stringResource(noiseNames[noiseIdx])),
                onLess = { onConfig(config.copy(qrn = NOISE_LEVELS[maxOf(0, noiseIdx - 1)])) },
                onMore = { onConfig(config.copy(qrn = NOISE_LEVELS[minOf(NOISE_LEVELS.size - 1, noiseIdx + 1)])) }
            )
            HuntStepper(
                stringResource(R.string.hunt_inaudible, config.inaudiblePercent.roundToInt()),
                onLess = { onConfig(config.copy(inaudiblePercent = maxOf(0.0, config.inaudiblePercent - 10.0))) },
                onMore = { onConfig(config.copy(inaudiblePercent = minOf(80.0, config.inaudiblePercent + 10.0))) }
            )
            val bustIdx = nearestIndex(BUST_LEVELS, config.bustRate)
            val bustNames = listOf(R.string.hunt_bust_never, R.string.hunt_bust_rarely, R.string.hunt_bust_sometimes, R.string.hunt_bust_often)
            HuntStepper(
                stringResource(R.string.hunt_bust, stringResource(bustNames[bustIdx])),
                onLess = { onConfig(config.copy(bustRate = BUST_LEVELS[maxOf(0, bustIdx - 1)])) },
                onMore = { onConfig(config.copy(bustRate = BUST_LEVELS[minOf(BUST_LEVELS.size - 1, bustIdx + 1)])) }
            )
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
        ) { Text(stringResource(R.string.hunt_start), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
    }
}

// ---- Audio ----------------------------------------------------------------------

/** The run's sounds: the activator at your tone, and the pileup through the pileup mixer, as you hear it. */
private class HuntAudio {
    private val player = MorsePlayer()

    /** Sound the activator's transmission; returns its length in seconds. */
    fun activator(text: String, wpm: Double): Double =
        player.replaySound(MorseItem.Playable.Text(text), Settings.sidetoneHz, MorseTiming(wpm))

    /** The hunters you can hear, at their offsets, levels and delays, with the band noise. */
    fun hunters(turn: app.anothermorsetrainer.morsekit.HuntTurn, qrn: Double) {
        val tone = Settings.sidetoneHz
        val voices = turn.voices.filter { it.levelYou > 0.0 }.map { v ->
            MorsePlayer.PileupVoice(
                text = v.text,
                frequency = OperatingProcedure.audible(tone + v.offsetHz),
                timing = MorseTiming(v.wpm),
                gain = v.levelYou.toFloat(),
                startDelay = v.delay,
                qsbRate = if (v.qsb) 0.25 else null
            )
        }
        player.playPileup(voices, (qrn * 0.5).toFloat()) {}
    }

    fun stop() = player.stop()

    fun release() {
        player.stop()
        player.release()
    }
}

// ---- The run --------------------------------------------------------------------

private enum class HuntPhase { ACTIVATOR, LISTEN, WAIT }

private fun signedHz(v: Double): String {
    val i = v.roundToInt()
    return if (i >= 0) "+$i" else "$i"
}

@Composable
private fun HuntRun(config: HuntConfig, call: String, state: String, onDone: (HuntSession) -> Unit) {
    val session = remember { HuntSession(config, Random.nextLong(), call, state) }
    val audio = remember { HuntAudio() }
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val context = LocalContext.current
    val midi = remember { HardwareKey(context) }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    val responses = remember { Channel<HuntYouSend?>(Channel.CONFLATED) }

    var phase by remember { mutableStateOf(HuntPhase.ACTIVATOR) }
    var window by remember { mutableIntStateOf(1) }
    var windowToken by remember { mutableIntStateOf(0) }
    var openedAtMs by remember { mutableStateOf(0L) }
    var firstKeyMs by remember { mutableStateOf<Long?>(null) }
    var xit by remember { mutableDoubleStateOf(0.0) }
    var xitAtKey by remember { mutableDoubleStateOf(0.0) }
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }

    DisposableEffect(Unit) {
        keyer.scope = scope
        keyer.start()
        midi.start(onKey = { down -> keyer.touchKey(down) }, onConnected = { midiDevice = it })
        onDispose {
            midi.stop()
            keyer.stop()
            audio.release()
            responses.close()
        }
    }

    // The activator's turn, the pileup's window, your answer; round after round.
    LaunchedEffect(Unit) {
        while (!session.ended) {
            val turn = session.turn
            audio.stop()
            phase = HuntPhase.ACTIVATOR
            window = session.round + 1
            keyer.clear()
            firstKeyMs = null
            log.add(turn.text)
            val seconds = audio.activator(turn.text, turn.wpm)
            delay(((seconds + 0.25) * 1000).toLong())
            if (!keyer.isKeying) keyer.clear()
            while (responses.tryReceive().isSuccess) { /* drop anything left over */ }
            openedAtMs = System.currentTimeMillis()
            firstKeyMs = null
            phase = HuntPhase.LISTEN
            windowToken++
            audio.hunters(turn, config.qrn)
            val you = responses.receive()
            phase = HuntPhase.WAIT
            keyer.clear()
            session.respond(you)
            Stats.recordPracticeDay()
            delay(350)
        }
        audio.stop()
        onDone(session)
    }

    // Your first key-down in the window is your start time, and its XIT your offset.
    LaunchedEffect(keyer.edgeCount) {
        if (phase == HuntPhase.LISTEN && firstKeyMs == null && keyer.edgeCount > 0) {
            firstKeyMs = System.currentTimeMillis()
            xitAtKey = xit
        }
    }
    // Nobody keyed by the time the activator decides: you stayed silent.
    LaunchedEffect(windowToken) {
        if (windowToken == 0) return@LaunchedEffect
        delay((app.anothermorsetrainer.morsekit.HuntModel.LISTEN_SECONDS * 1000).toLong())
        if (phase == HuntPhase.LISTEN && firstKeyMs == null) responses.trySend(null)
    }
    // Keyed, then idle: that is your send.
    LaunchedEffect(keyer.edgeCount, keyer.isKeying, keyer.decodedText, phase) {
        if (phase != HuntPhase.LISTEN || keyer.isKeying || keyer.decodedText.isBlank()) return@LaunchedEffect
        delay(maxOf(1500.0, keyer.wordGapMs * 2.5).toLong())
        sendNow(keyer, firstKeyMs, openedAtMs, xitAtKey, responses)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HuntCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { HuntEyebrow(stringResource(R.string.hunt_window, window)) }
                midiDevice?.let { Text("🎹 $it", fontSize = 12.sp, color = Brand.teal, maxLines = 1) }
            }
            Text(
                stringResource(
                    when (phase) {
                        HuntPhase.ACTIVATOR -> R.string.hunt_phase_activator
                        HuntPhase.LISTEN -> R.string.hunt_phase_listen
                        HuntPhase.WAIT -> R.string.hunt_phase_wait
                    }
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (phase == HuntPhase.LISTEN) Brand.tealBright else Brand.textPrimary
            )
            HuntEyebrow(stringResource(R.string.hunt_activator_heading))
            log.takeLast(5).forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Brand.textPrimary)
            }
        }

        HuntCard {
            HuntEyebrow(stringResource(R.string.hunt_xit_heading))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OperatingProcedure.knobSteps.forEach { step ->
                    OutlinedButton(
                        onClick = {
                            xit = (xit + step).coerceIn(-OperatingProcedure.RIT_RANGE_HZ, OperatingProcedure.RIT_RANGE_HZ)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(signedHz(step), color = Brand.teal, maxLines = 1) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.hunt_xit_value, signedHz(xit)),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = Brand.textPrimary, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { xit = 0.0 }) { Text(stringResource(R.string.hunt_xit_zero), color = Brand.teal) }
            }
            Text(stringResource(R.string.hunt_xit_hint), fontSize = 12.sp, color = Brand.textSecondary)
        }

        HuntCard {
            HuntEyebrow(stringResource(R.string.hunt_you_sent))
            Text(
                keyer.decodedText.ifEmpty { "—" },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
                color = if (keyer.decodedText.isEmpty()) Brand.textSecondary else Brand.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)
            )
            OnScreenKeySwitch(
                onPaddleKey = { down, ms -> keyer.touchKey(down, ms) },
                modifier = Modifier.fillMaxWidth().height(100.dp)
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
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                keyPressed = true
                                keyer.touchKey(true)
                                try { tryAwaitRelease() } finally {
                                    keyPressed = false
                                    keyer.touchKey(false)
                                }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.hunt_hold_to_key),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (keyPressed) Brand.navy else Brand.textSecondary
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { keyer.clear() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.hunt_clear), color = Brand.textSecondary)
                }
                Button(
                    onClick = { sendNow(keyer, firstKeyMs, openedAtMs, xitAtKey, responses) },
                    enabled = phase == HuntPhase.LISTEN && keyer.decodedText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.hunt_submit), fontWeight = FontWeight.Bold) }
            }
            OutlinedButton(
                onClick = { if (phase == HuntPhase.LISTEN) responses.trySend(null) },
                enabled = phase == HuntPhase.LISTEN,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.hunt_silent), color = Brand.teal) }
        }

        TextButton(
            onClick = {
                session.quit()
                responses.trySend(null)
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text(stringResource(R.string.hunt_stop), color = Brand.textSecondary) }
    }
}

/** Hand the keyed text to the session loop, with when you started and your XIT then. */
private fun sendNow(
    keyer: SendingKeyer,
    firstKeyMs: Long?,
    openedAtMs: Long,
    xitAtKey: Double,
    responses: Channel<HuntYouSend?>
) {
    val text = keyer.submit()
    if (text.isEmpty()) return
    val start = firstKeyMs ?: System.currentTimeMillis()
    val delaySeconds = maxOf(0L, start - openedAtMs).toDouble() / 1000.0
    responses.trySend(HuntYouSend(text, Settings.characterWpm, delaySeconds, xitAtKey))
}

// ---- Debrief --------------------------------------------------------------------

@Composable
private fun kindName(kind: HuntKind): String = stringResource(
    when (kind) {
        HuntKind.CALLING -> R.string.hunt_kind_calling
        HuntKind.QUERY -> R.string.hunt_kind_query
        HuntKind.EXCHANGE -> R.string.hunt_kind_exchange
        HuntKind.CLOSE -> R.string.hunt_kind_close
        HuntKind.AS -> R.string.hunt_kind_as
    }
)

@Composable
private fun pointText(code: String): String = stringResource(
    when (code) {
        "timing.late" -> R.string.hunt_pt_timing_late
        "timing.ok" -> R.string.hunt_pt_timing_ok
        "timing.over" -> R.string.hunt_pt_timing_over
        "call.once" -> R.string.hunt_pt_call_once
        "call.repeated" -> R.string.hunt_pt_call_repeated
        "call.extra" -> R.string.hunt_pt_call_extra
        "call.missing" -> R.string.hunt_pt_call_missing
        "offset.ok" -> R.string.hunt_pt_offset_ok
        "offset.zeroBeat" -> R.string.hunt_pt_offset_zerobeat
        "offset.far" -> R.string.hunt_pt_offset_far
        "partial.answered" -> R.string.hunt_pt_partial_answered
        "near.answered" -> R.string.hunt_pt_near_answered
        "partial.notMine" -> R.string.hunt_pt_partial_notmine
        "partial.silent" -> R.string.hunt_pt_partial_silent
        "near.noQuestion" -> R.string.hunt_pt_near_noquestion
        "exchange.ok" -> R.string.hunt_pt_exchange_ok
        "exchange.form" -> R.string.hunt_pt_exchange_form
        "exchange.silent" -> R.string.hunt_pt_exchange_silent
        "signoff.ok" -> R.string.hunt_pt_signoff_ok
        "signoff.form" -> R.string.hunt_pt_signoff_form
        "signoff.missing" -> R.string.hunt_pt_signoff_missing
        else -> R.string.hunt_pt_as_ignored
    }
)

@Composable
private fun HuntDebrief(
    session: HuntSession,
    onAgain: () -> Unit,
    onSettings: () -> Unit,
    onOpenLesson: (OpLesson) -> Unit
) {
    val points = session.records.flatMap { it.grade }
    val right = points.count { it.good }
    val wrong = points.count { !it.good }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HuntCard {
            Text(
                stringResource(
                    when (session.outcome) {
                        HuntOutcome.LOGGED -> R.string.hunt_debrief_logged
                        HuntOutcome.TIMEOUT -> R.string.hunt_debrief_timeout
                        else -> R.string.hunt_debrief_quit
                    }
                ),
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (session.outcome == HuntOutcome.LOGGED) Brand.tealBright else Brand.textPrimary
            )
            Text(stringResource(R.string.hunt_debrief_score, right, wrong), fontSize = 14.sp, color = Brand.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.hunt_go_again), fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hunt_change_difficulty), color = Brand.teal)
            }
        }
        HuntEyebrow(stringResource(R.string.hunt_debrief_heading))
        session.records.forEach { HuntWindowCard(it, onOpenLesson) }
    }
}

@Composable
private fun HuntWindowCard(r: HuntRecord, onOpenLesson: (OpLesson) -> Unit) {
    val callOf = r.turn.voices.associate { it.id.toString() to it.call }
    val youLabel = stringResource(R.string.hunt_debrief_you)
    fun who(id: String) = if (id == "you") youLabel else (callOf[id] ?: id)
    HuntCard {
        HuntEyebrow(stringResource(R.string.hunt_debrief_window, r.round, kindName(r.turn.kind)))
        Text(
            stringResource(R.string.hunt_debrief_activator_sent) + ": " + r.turn.text,
            fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Brand.textPrimary
        )
        Text(stringResource(R.string.hunt_debrief_you_heard), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        val inaudible = r.turn.voices.filter { it.levelYou <= 0.0 }
        if (r.youHeard.isEmpty() && inaudible.isEmpty()) {
            Text(stringResource(R.string.hunt_debrief_nothing), fontSize = 13.sp, color = Brand.textSecondary)
        }
        r.youHeard.forEach {
            Text("${who(it.who)}: ${it.decoded}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Brand.textPrimary)
        }
        inaudible.forEach {
            Text(
                stringResource(R.string.hunt_debrief_inaudible, it.call),
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Brand.textSecondary
            )
        }
        Text(stringResource(R.string.hunt_debrief_act_heard), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        if (r.activatorHeard.isEmpty()) {
            Text(stringResource(R.string.hunt_debrief_nothing), fontSize = 13.sp, color = Brand.textSecondary)
        }
        r.activatorHeard.forEach {
            Text("${who(it.who)}: ${it.decoded}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Brand.textPrimary)
        }
        val you = r.you
        Text(
            when {
                you == null || you.text.isBlank() -> stringResource(R.string.hunt_debrief_silent)
                r.late -> stringResource(R.string.hunt_you_sent) + ": " + you.text.uppercase() + " (" + stringResource(R.string.hunt_debrief_late) + ")"
                else -> stringResource(R.string.hunt_you_sent) + ": " + you.text.uppercase() + " (XIT " + signedHz(you.offsetHz) + " Hz)"
            },
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary
        )
        r.grade.forEach { p ->
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Brand.navyRaised).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    (if (p.good) "✓ " else "✗ ") + pointText(p.code),
                    fontSize = 13.sp,
                    color = if (p.good) Brand.tealBright else Brand.warning
                )
                TextButton(onClick = { onOpenLesson(p.lesson) }) {
                    Text(stringResource(R.string.hunt_debrief_lesson, opLessonTitle(p.lesson)), fontSize = 12.sp, color = Brand.teal)
                }
            }
        }
    }
}
