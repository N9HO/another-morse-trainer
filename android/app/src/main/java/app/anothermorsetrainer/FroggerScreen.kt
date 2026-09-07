package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.FroggerDirection
import app.anothermorsetrainer.morsekit.FroggerEvent
import app.anothermorsetrainer.morsekit.FroggerFrog
import app.anothermorsetrainer.morsekit.FroggerGame
import app.anothermorsetrainer.morsekit.FroggerLabelStage
import app.anothermorsetrainer.morsekit.FroggerLaneKind
import app.anothermorsetrainer.morsekit.FroggerObject
import app.anothermorsetrainer.morsekit.InvadersCharacterSet
import app.anothermorsetrainer.morsekit.InvadersDifficulty
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.SessionRecord
import kotlin.math.min
import kotlin.math.roundToInt

private enum class FrogPhase { SETUP, RUNNING, OVER }

private val FROG_VEHICLE = Color(0xFFF59E3D)
private val FROG_LOG = Color(0xFF8C6138)
private val FROG_FROG = Color(0xFF6BD966)
private val FROG_RIVER = Color(0xFF21528C)
private val FROG_ROAD = Color(0xFF29303D)
private val FROG_BANK = Color(0xFF2B664D)

/**
 * CW Frogger (#190): the frog crosses three lanes of traffic and three of
 * river a hop at a time, and Morse says what is safe — each lane is cued by
 * sending one of the characters its vehicles or logs carry; the cued vehicle
 * is harmless and every other one fatal, the cued log floats and every other
 * one sinks. The rules live in [FroggerGame]; this screen is the frame clock
 * ([withFrameNanos]), the sound, the direction pad and the drawing.
 *
 * Twin of the iOS `FroggerView.swift`. Session state survives process death
 * as a score, not a game: the tally rides `rememberSaveable` and a reclaimed
 * run is closed out to [Stats] on restore, like Invaders.
 */
@Composable
fun FroggerScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val prefs = remember { context.getSharedPreferences("amt_frogger", android.content.Context.MODE_PRIVATE) }

    var phase by rememberSaveable { mutableStateOf(FrogPhase.SETUP) }
    // Setup choices persist across launches, like every other mode's.
    var difficulty by rememberSaveable {
        mutableStateOf(runCatching { InvadersDifficulty.valueOf(prefs.getString("difficulty", "") ?: "") }.getOrDefault(InvadersDifficulty.NORMAL))
    }
    var characterSet by rememberSaveable {
        mutableStateOf(runCatching { InvadersCharacterSet.valueOf(prefs.getString("characters", "") ?: "") }.getOrDefault(InvadersCharacterSet.ACTIVE))
    }
    LaunchedEffect(difficulty, characterSet) {
        prefs.edit().putString("difficulty", difficulty.name).putString("characters", characterSet.name).apply()
    }

    // The shared Koch ladder: its active set is the pool, and its stats and
    // confusion matrix take every decision.
    val track = remember { EngineStore.characters() }
    val engine = track.engine

    /** The pool, letters first then digits (the recognition chart's order). */
    fun characterPool(): List<Char> = when (characterSet) {
        InvadersCharacterSet.ACTIVE -> engine.activeCharacters
        InvadersCharacterSet.FULL -> MorseCode.kochOrder.filter { it.isLetterOrDigit() }
    }.map { it.toString() }.sortedWith(SessionRecord.characterOrder).map { it[0] }

    // Run state. The game dies with the process; the tally below does not.
    var game by remember { mutableStateOf<FroggerGame?>(null) }
    var objects by remember { mutableStateOf<List<FroggerObject>>(emptyList()) }
    var frog by remember { mutableStateOf(FroggerFrog(FroggerGame.startRow, 0.5)) }
    var visibleRows by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var nextCueRow by remember { mutableStateOf<Int?>(null) }
    var stage by remember { mutableStateOf(FroggerLabelStage.VISIBLE) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var wave by rememberSaveable { mutableIntStateOf(1) }
    var lives by rememberSaveable { mutableIntStateOf(3) }
    var multiplier by remember { mutableIntStateOf(1) }
    var bestCombo by rememberSaveable { mutableIntStateOf(0) }
    var wpm by remember { mutableIntStateOf(0) }
    var bestWpm by rememberSaveable { mutableIntStateOf(0) }
    var runAttempts by rememberSaveable { mutableIntStateOf(0) }
    var runCorrect by rememberSaveable { mutableIntStateOf(0) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // When each lane's cue finished sounding, by row, so a decision's
    // time-to-recognize runs from the end of the tone.
    val cueToneEnd = remember { HashMap<Int, Long>() }
    // Characters waiting to be sent (the traffic announcing itself in the
    // hidden stage), played one after another behind whatever is sounding.
    val soundQueue = remember { ArrayList<Char>() }
    var soundBusyUntil by remember { mutableLongStateOf(0L) }
    val charResults = remember { HashMap<Char, IntArray>() }   // attempts, correct

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun syncBoard(g: FroggerGame) {
        objects = g.objects
        frog = g.frog
        visibleRows = FroggerGame.lanes.map { it.row }.filter { g.isLabelVisible(it) }.toSet()
        nextCueRow = g.nextCueRow
        stage = g.labelStage
    }

    fun syncHud(g: FroggerGame) {
        score = g.score
        wave = g.wave
        lives = g.lives
        multiplier = g.multiplier
        bestCombo = g.bestCombo
        wpm = g.currentWpm.roundToInt()
        bestWpm = g.bestWpm.roundToInt()
        runAttempts = g.decisions + g.misses
        runCorrect = g.decisions
        lastSeenMs = System.currentTimeMillis()
    }

    fun recordRun(attempts: Int, correct: Int, seconds: Int) {
        if (attempts <= 0) return
        val results = charResults.map { (ch, a) -> SessionRecord.CharResult(ch.toString(), a[0], a[1], null) }
        Stats.record(
            mode = "CW Frogger", attempts = attempts, correct = correct,
            bestTtrMs = null, durationSeconds = seconds,
            characterWpm = Settings.characterWpm.roundToInt(),
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt(),
            charResults = results,
            activeCharacters = if (results.isEmpty()) emptyList() else engine.activeCharacters.map { it.toString() }
        )
    }

    fun tally(ch: Char, correct: Boolean) {
        val a = charResults.getOrPut(ch) { IntArray(2) }
        a[0] += 1
        if (correct) a[1] += 1
        Stats.recordChar(ch.toString(), correct, null)
    }

    /** Send a lane's cue now, ahead of anything queued. */
    fun playCue(character: Char, row: Int, wpmNow: Double) {
        soundQueue.clear()
        val secs = player.replaySound(MorseItem.Playable.Text(character.toString()), Settings.sidetoneHz, MorseTiming(wpmNow))
        val now = System.currentTimeMillis()
        soundBusyUntil = now + (secs * 1000).toLong() + 350
        cueToneEnd[row] = now + (secs * 1000).toLong()
    }

    fun startGame() {
        val g = FroggerGame(FroggerGame.Config(
            characters = characterPool(), difficulty = difficulty, characterWpm = Settings.characterWpm
        ))
        game = g
        cueToneEnd.clear()
        soundQueue.clear()
        soundBusyUntil = 0L
        charResults.clear()
        flash = null
        startedAtMs = System.currentTimeMillis()
        syncBoard(g)
        syncHud(g)
        phase = FrogPhase.RUNNING
        // The first lane is cued at birth; send it.
        val cue = g.nextCue
        val row = g.nextCueRow
        if (cue != null && row != null) playCue(cue, row, g.currentWpm)
    }

    fun finishGame() {
        val g = game ?: return
        player.stop()
        syncHud(g)
        recordRun(g.decisions + g.misses, g.decisions, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
        EngineStore.save()
        phase = FrogPhase.OVER
    }

    /** End a run early (Back, the mode switcher): record what was played. */
    fun abandonRun() {
        val g = game
        player.stop()
        if (g != null && phase == FrogPhase.RUNNING) {
            recordRun(g.decisions + g.misses, g.decisions, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
            EngineStore.save()
        }
        game = null
        phase = FrogPhase.SETUP
    }

    fun switchTo(mode: TrainingMode) {
        if (phase == FrogPhase.RUNNING) abandonRun() else player.stop()
        onSwitchMode(mode)
    }

    // A game the system reclaimed mid-way cannot resume — the engine died with
    // the process — but its score need not die with it. Close it out from the
    // saved tally, as finishGame would have, and land on setup.
    LaunchedEffect(Unit) {
        if (game != null) return@LaunchedEffect
        if (phase == FrogPhase.RUNNING) {
            recordRun(runAttempts, runCorrect, ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0))
        }
        if (phase != FrogPhase.SETUP) phase = FrogPhase.SETUP
    }

    /** A correct decision: a recognition of the cue, timed from the end of its tone. */
    fun decided(obj: FroggerObject, points: Int, now: Long) {
        val end = cueToneEnd.remove(obj.row)
        val ttr = if (end != null) ((now - end).coerceAtLeast(0)) / 1000.0 else 0.0
        engine.noteAttempt(obj.character, obj.character, ttr)
        tally(obj.character, true)
        if (Settings.hapticsEnabled) haptics.success()
        flash = "+$points" to now + 800
    }

    /** Apply what one advance or hop did: sound, stats, haptics, the flash. Returns whether the game ended. */
    fun handle(events: List<FroggerEvent>, g: FroggerGame): Boolean {
        val now = System.currentTimeMillis()
        var over = false
        for (event in events) {
            when (event) {
                FroggerEvent.Hopped -> Unit
                is FroggerEvent.Cue -> playCue(event.character, event.row, g.currentWpm)
                is FroggerEvent.Passed -> decided(event.obj, event.points, now)
                is FroggerEvent.Landed -> decided(event.obj, event.points, now)
                is FroggerEvent.Squashed -> {
                    engine.noteAttempt(event.obj.character, event.cue, 0.0)
                    tally(event.cue, false)
                    if (Settings.hapticsEnabled) haptics.error()
                    flash = context.getString(R.string.frogger_flash_hit, event.obj.character.toString(), event.cue.toString()) to now + 1200
                    cueToneEnd.clear()
                }
                is FroggerEvent.Sank -> {
                    engine.noteAttempt(event.obj.character, event.cue, 0.0)
                    tally(event.cue, false)
                    if (Settings.hapticsEnabled) haptics.error()
                    flash = context.getString(R.string.frogger_flash_sank, event.obj.character.toString(), event.cue.toString()) to now + 1200
                    cueToneEnd.clear()
                }
                is FroggerEvent.Drowned -> {
                    engine.noteMiss(event.cue)
                    tally(event.cue, false)
                    if (Settings.hapticsEnabled) haptics.error()
                    flash = context.getString(R.string.frogger_flash_splash, event.cue.toString()) to now + 1200
                    cueToneEnd.clear()
                }
                is FroggerEvent.Crossed -> {
                    if (Settings.hapticsEnabled) haptics.success()
                    flash = context.getString(R.string.frogger_flash_across, event.points, g.wave) to now + 1200
                    cueToneEnd.clear()
                }
                is FroggerEvent.Entered -> {
                    // From wave 5 the traffic in the lane ahead announces itself.
                    if (g.labelStage == FroggerLabelStage.HIDDEN && event.obj.row == g.frog.row + 1 && soundQueue.size < 3) {
                        soundQueue.add(event.obj.character)
                    }
                }
                FroggerEvent.GameOver -> over = true
            }
        }
        return over
    }

    fun hop(direction: FroggerDirection) {
        val g = game ?: return
        if (phase != FrogPhase.RUNNING) return
        val events = g.move(direction)
        if (events.isEmpty()) return
        val over = handle(events, g)
        syncBoard(g)
        syncHud(g)
        if (over) finishGame()
    }

    // The frame clock: each frame moves the game on by the real time elapsed.
    LaunchedEffect(phase, game) {
        val g = game ?: return@LaunchedEffect
        if (phase != FrogPhase.RUNNING) return@LaunchedEffect
        var last = -1L
        while (true) {
            val nanos = withFrameNanos { it }
            if (last < 0) { last = nanos; continue }
            // Cap a long gap (the app was backgrounded) so the traffic does
            // not sweep the board in one step.
            val dt = ((nanos - last) / 1e9).coerceAtMost(0.1)
            last = nanos
            val over = handle(g.advance(dt), g)
            val now = System.currentTimeMillis()
            if (now >= soundBusyUntil && soundQueue.isNotEmpty()) {
                val next = soundQueue.removeAt(0)
                val secs = player.replaySound(MorseItem.Playable.Text(next.toString()), Settings.sidetoneHz, MorseTiming(g.currentWpm))
                soundBusyUntil = now + (secs * 1000).toLong() + 350
            }
            syncBoard(g)
            syncHud(g)
            if (over) { finishGame(); return@LaunchedEffect }
        }
    }

    // Hardware keyboard: the arrow keys hop.
    val hwFocus = remember { FocusRequester() }
    LaunchedEffect(phase) { if (phase == FrogPhase.RUNNING) hwFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hwFocus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || phase != FrogPhase.RUNNING) return@onKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionUp -> FroggerDirection.UP
                    Key.DirectionDown -> FroggerDirection.DOWN
                    Key.DirectionLeft -> FroggerDirection.LEFT
                    Key.DirectionRight -> FroggerDirection.RIGHT
                    else -> return@onKeyEvent false
                }
                hop(direction)
                true
            }
            .focusable()
    ) {
        when (phase) {
            FrogPhase.SETUP -> {
                BackHandler { onBack() }
                FroggerSetup(
                    characterSet = characterSet, onCharacterSet = { characterSet = it },
                    pool = characterPool(),
                    difficulty = difficulty, onDifficulty = { difficulty = it },
                    onStart = { startGame() },
                    onBack = onBack,
                    onSwitchMode = ::switchTo
                )
            }
            FrogPhase.RUNNING -> {
                BackHandler { abandonRun() }
                FroggerRun(
                    objects = objects, frog = frog, visibleRows = visibleRows, nextCueRow = nextCueRow, stage = stage,
                    score = score, wave = wave, lives = lives, multiplier = multiplier, wpm = wpm,
                    flash = flash,
                    onHop = { hop(it) },
                    onReplay = {
                        val g = game ?: return@FroggerRun
                        val cue = g.nextCue ?: return@FroggerRun
                        val row = g.nextCueRow ?: return@FroggerRun
                        playCue(cue, row, g.currentWpm)
                    },
                    onEnd = { abandonRun() },
                    onSwitchMode = ::switchTo
                )
            }
            FrogPhase.OVER -> {
                BackHandler { onBack() }
                FroggerOver(
                    score = score, wave = wave, bestCombo = bestCombo,
                    accuracy = if (runAttempts == 0) 0.0 else runCorrect.toDouble() / runAttempts,
                    bestWpm = bestWpm,
                    onAgain = { startGame() },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun FroggerSetup(
    characterSet: InvadersCharacterSet, onCharacterSet: (InvadersCharacterSet) -> Unit,
    pool: List<Char>,
    difficulty: InvadersDifficulty, onDifficulty: (InvadersDifficulty) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_frogger), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.FROGGER, onSwitchMode)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.frogger_blurb), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            FrogLabel(stringResource(R.string.frogger_characters))
            FrogPills(InvadersCharacterSet.entries.map { it to it.label }, characterSet, onCharacterSet)
            Text(pool.joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            FrogLabel(stringResource(R.string.frogger_difficulty))
            FrogPills(InvadersDifficulty.entries.map { it to it.label }, difficulty, onDifficulty)
            // The ramp in words: where a game opens and where it climbs to.
            val target = Settings.characterWpm.roundToInt()
            val start = FroggerGame.rampStart(Settings.characterWpm).roundToInt()
            val speedNote = if (start >= target) {
                stringResource(R.string.frogger_speed_flat_note, target)
            } else {
                stringResource(R.string.frogger_speed_ramp_note, start, FroggerGame.rampStep.roundToInt(), FroggerGame.decisionsPerRampStep, target)
            }
            Text(speedNote, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Text(stringResource(R.string.frogger_labels_note), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.frogger_start), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FroggerRun(
    objects: List<FroggerObject>,
    frog: FroggerFrog,
    visibleRows: Set<Int>,
    nextCueRow: Int?,
    stage: FroggerLabelStage,
    score: Int, wave: Int, lives: Int, multiplier: Int, wpm: Int,
    flash: Pair<String, Long>?,
    onHop: (FroggerDirection) -> Unit,
    onReplay: () -> Unit,
    onEnd: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    val measurer = rememberTextMeasurer()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.frogger_end_game), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.FROGGER, onSwitchMode)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FrogStat(stringResource(R.string.frogger_score), score.toString())
            FrogStat(stringResource(R.string.frogger_wave), wave.toString())
            FrogStat(stringResource(R.string.frogger_lives), "♥".repeat(lives) + "♡".repeat((3 - lives).coerceAtLeast(0)))
            FrogStat(stringResource(R.string.frogger_combo_label), "×$multiplier")
            FrogStat(stringResource(R.string.frogger_wpm), wpm.toString())
        }
        Spacer(Modifier.height(10.dp))
        val flashStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        val boardDescription = stringResource(R.string.frogger_board_description, frog.row, FroggerGame.rows - 1)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Brand.cornerRadius))
                .background(Brand.navyElevated)
                .border(1.dp, Brand.hairline, RoundedCornerShape(Brand.cornerRadius))
                .semantics { contentDescription = boardDescription }
                .pointerInput(Unit) { detectTapGestures { onReplay() } }
        ) {
            val rowHeight = size.height / FroggerGame.rows
            fun top(row: Int): Float = size.height - (row + 1) * rowHeight
            val labelStyle = TextStyle(
                fontSize = min(20f, rowHeight * 0.5f / density).sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = Brand.navy
            )

            // The ground: banks, asphalt with lane lines, the median, water.
            for (row in 0 until FroggerGame.rows) {
                val kind = FroggerGame.lane(row)?.kind
                val fill = when (kind) {
                    FroggerLaneKind.ROAD -> FROG_ROAD
                    FroggerLaneKind.RIVER -> FROG_RIVER
                    null -> FROG_BANK
                }
                drawRect(fill, Offset(0f, top(row)), Size(size.width, rowHeight))
                if (kind == FroggerLaneKind.ROAD && row < FroggerGame.medianRow - 1) {
                    drawLine(
                        Color.White.copy(alpha = 0.35f), Offset(0f, top(row)), Offset(size.width, top(row)),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 14.dp.toPx()))
                    )
                }
                if (nextCueRow == row) {
                    // The lane the cue is for, so the eye knows where the ear is aimed.
                    drawRect(
                        Brand.tealBright.copy(alpha = 0.7f), Offset(1f, top(row) + 1f), Size(size.width - 2f, rowHeight - 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Vehicles and logs, drawn twice where they straddle an edge.
            for (obj in objects) {
                val w = obj.width.toFloat() * size.width
                val h = rowHeight * (if (obj.kind == FroggerLaneKind.ROAD) 0.58f else 0.5f)
                val y = top(obj.row) + (rowHeight - h) / 2
                val colour = if (obj.kind == FroggerLaneKind.ROAD) FROG_VEHICLE else FROG_LOG
                val radius = if (obj.kind == FroggerLaneKind.ROAD) 5.dp.toPx() else h / 2
                for (offset in listOf(-1.0, 0.0, 1.0)) {
                    val cx = (obj.x + offset).toFloat() * size.width
                    if (cx + w / 2 < 0 || cx - w / 2 > size.width) continue
                    drawRoundRect(colour, Offset(cx - w / 2, y), Size(w, h), CornerRadius(radius, radius))
                    if (obj.row in visibleRows) {
                        val measured = measurer.measure(obj.character.toString(), labelStyle)
                        drawText(measured, topLeft = Offset(cx - measured.size.width / 2, y + h / 2 - measured.size.height / 2))
                    }
                }
            }

            // The frog.
            val frogSize = min(rowHeight * 0.62f, size.width * FroggerGame.hop.toFloat() * 0.8f)
            val fx = frog.x.toFloat() * size.width
            val fy = top(frog.row) + rowHeight / 2
            drawRoundRect(FROG_FROG, Offset(fx - frogSize / 2, fy - frogSize / 2), Size(frogSize, frogSize), CornerRadius(frogSize * 0.35f, frogSize * 0.35f))
            val eye = frogSize * 0.16f
            for (dx in listOf(-0.22f, 0.22f)) {
                drawCircle(Brand.navy, eye / 2, Offset(fx + dx * frogSize, fy - frogSize * 0.28f))
            }

            if (flash != null && flash.second > System.currentTimeMillis()) {
                val measured = measurer.measure(flash.first, flashStyle)
                drawText(measured, topLeft = Offset(size.width / 2 - measured.size.width / 2, rowHeight / 2 - measured.size.height / 2))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (stage == FroggerLabelStage.HIDDEN) R.string.frogger_tap_to_replay_hidden else R.string.frogger_tap_to_replay),
            style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        // Up on top, then left, down and right — the arrow keys press them too.
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FrogPadButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.frogger_hop_up)) { onHop(FroggerDirection.UP) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FrogPadButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.frogger_hop_left)) { onHop(FroggerDirection.LEFT) }
                FrogPadButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.frogger_hop_down)) { onHop(FroggerDirection.DOWN) }
                FrogPadButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.frogger_hop_right)) { onHop(FroggerDirection.RIGHT) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FrogPadButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(84.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brand.navyRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Brand.textPrimary)
    }
}

@Composable
private fun FroggerOver(
    score: Int, wave: Int, bestCombo: Int, accuracy: Double,
    bestWpm: Int,
    onAgain: () -> Unit, onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.frogger_game_over), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FrogStat(stringResource(R.string.frogger_score), score.toString())
                FrogStat(stringResource(R.string.frogger_wave), wave.toString())
                FrogStat(stringResource(R.string.frogger_accuracy), "${(accuracy * 100).roundToInt()}%")
                FrogStat(stringResource(R.string.frogger_best_combo), bestCombo.toString())
            }
            Text(stringResource(R.string.frogger_speed_reached, bestWpm), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Button(
                onClick = onAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.frogger_play_again), fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

@Composable
private fun FrogStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FrogLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
}

@Composable
private fun <T> FrogPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Brand.navy else Brand.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) Brand.teal else Brand.navyRaised)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}
