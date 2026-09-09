package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.Asteroid
import app.anothermorsetrainer.morsekit.AsteroidsEvent
import app.anothermorsetrainer.morsekit.AsteroidsGame
import app.anothermorsetrainer.morsekit.AsteroidsInput
import app.anothermorsetrainer.morsekit.AsteroidsOutcome
import app.anothermorsetrainer.morsekit.AsteroidsShot
import app.anothermorsetrainer.morsekit.InvadersCharacterSet
import app.anothermorsetrainer.morsekit.InvadersDifficulty
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.Leaderboard
import app.anothermorsetrainer.morsekit.LeaderboardItem
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.SessionRecord
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class AstPhase { SETUP, RUNNING, OVER }

/**
 * Radius multipliers around an asteroid's outline, so each one is a lumpy
 * rock rather than a circle; offset by id so they differ. The same table is
 * in `AsteroidsView.swift`.
 */
private val ASTEROID_LUMPS = listOf(1.0f, 0.82f, 0.95f, 0.72f, 1.0f, 0.88f, 0.76f, 1.0f, 0.9f, 0.8f)

/** Drawn radius in dp for a label: single characters small, fragments smaller, words wider. */
private fun asteroidRadiusDp(a: Asteroid): Float = when {
    a.isFragment -> 15f
    a.label.length == 1 -> 19f
    else -> 16f + 6f * a.label.length
}

/**
 * CW Asteroids (#189, part of #170): labelled asteroids drift in toward the
 * ship at the centre and the learner destroys each one by sending its label
 * (see it, send it) or by tapping the one whose label was just sent (hear it,
 * tap it). The rules live in [AsteroidsGame]; this screen is the frame clock
 * ([withFrameNanos]), the sound, the input and the drawing.
 *
 * Twin of the iOS `AsteroidsView.swift`. Session state survives process death
 * as a score, not a game: the tally rides `rememberSaveable` and a reclaimed
 * run is closed out to [Stats] on restore, like Invaders.
 */
@Composable
fun AsteroidsScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val prefs = remember { context.getSharedPreferences("amt_asteroids", android.content.Context.MODE_PRIVATE) }

    var phase by rememberSaveable { mutableStateOf(AstPhase.SETUP) }
    // Setup choices persist across launches, like every other mode's.
    var input by rememberSaveable {
        mutableStateOf(runCatching { AsteroidsInput.valueOf(prefs.getString("input", "") ?: "") }.getOrDefault(AsteroidsInput.SEND))
    }
    var difficulty by rememberSaveable {
        mutableStateOf(runCatching { InvadersDifficulty.valueOf(prefs.getString("difficulty", "") ?: "") }.getOrDefault(InvadersDifficulty.NORMAL))
    }
    var characterSet by rememberSaveable {
        mutableStateOf(runCatching { InvadersCharacterSet.valueOf(prefs.getString("characters", "") ?: "") }.getOrDefault(InvadersCharacterSet.ACTIVE))
    }
    LaunchedEffect(input, difficulty, characterSet) {
        prefs.edit().putString("input", input.name).putString("difficulty", difficulty.name)
            .putString("characters", characterSet.name).apply()
    }

    // The shared Koch ladder: its active set is the pool, and its stats and
    // confusion matrix take every hit and miss.
    val track = remember { EngineStore.characters() }
    val engine = track.engine

    /** The pool, letters first then digits (the recognition chart's order). */
    fun characterPool(): List<Char> = when (characterSet) {
        InvadersCharacterSet.ACTIVE -> engine.activeCharacters
        InvadersCharacterSet.FULL -> MorseCode.kochOrder.filter { it.isLetterOrDigit() }
    }.map { it.toString() }.sortedWith(SessionRecord.characterOrder).map { it[0] }

    // Run state. The game dies with the process; the tally below does not.
    var game by remember { mutableStateOf<AsteroidsGame?>(null) }
    var field by remember { mutableStateOf<List<Asteroid>>(emptyList()) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var sendBuffer by remember { mutableStateOf("") }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var wave by rememberSaveable { mutableIntStateOf(1) }
    var lives by rememberSaveable { mutableIntStateOf(3) }
    var multiplier by remember { mutableIntStateOf(1) }
    var bestCombo by rememberSaveable { mutableIntStateOf(0) }
    var wpm by remember { mutableIntStateOf(0) }
    var bestWpm by rememberSaveable { mutableIntStateOf(0) }
    // Per character, the way the iOS session tally counts: every character
    // of a destroyed or struck label, and one per wrong send or tap.
    var runAttempts by rememberSaveable { mutableIntStateOf(0) }
    var runCorrect by rememberSaveable { mutableIntStateOf(0) }
    // The game's own count, one per asteroid, for the end card (the iOS
    // card shows the same AsteroidsGame.accuracy).
    var gameHits by rememberSaveable { mutableIntStateOf(0) }
    var gameMisses by rememberSaveable { mutableIntStateOf(0) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // Copy mode: when the armed label finished sounding, so a hit's
    // time-to-recognize runs from the end of the tone.
    var cueEndMs by remember { mutableStateOf<Long?>(null) }
    val charResults = remember { HashMap<Char, IntArray>() }   // attempts, correct
    // The shared leaderboard (docs/high-scores-design.md, step 2): the run's
    // registration and one item per shot or decision, each carrying the
    // ramp speed it was sent at (the HUD's `wpm`, synced before every item
    // resolves). Plain state, not saveable: a game the process lost has no
    // transcript and is never submitted.
    var lbRun by remember { mutableStateOf<LeaderboardClient.RunHandle?>(null) }
    val lbItems = remember { ArrayList<LeaderboardItem>() }
    var lbLine by remember { mutableStateOf<String?>(null) }

    // Send mode: the same decoder Sending Practice uses, plus a hardware key.
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    DisposableEffect(phase, input) {
        if (phase == AstPhase.RUNNING && input == AsteroidsInput.SEND) {
            keyer.scope = scope
            keyer.start()
            midi.start(onKey = { down -> keyer.touchKey(down) }, onConnected = { midiDevice = it })
        }
        onDispose { midi.stop(); keyer.stop() }
    }

    fun syncHud(g: AsteroidsGame) {
        field = g.asteroids
        elapsed = g.elapsed
        sendBuffer = g.sendBuffer
        score = g.score
        wave = g.wave
        lives = g.lives
        multiplier = g.multiplier
        bestCombo = g.bestCombo
        wpm = g.currentWpm.roundToInt()
        bestWpm = g.bestWpm.roundToInt()
        gameHits = g.hits
        gameMisses = g.misses
        lastSeenMs = System.currentTimeMillis()
    }

    /** Hand the finished game's transcript to the leaderboard; the end card shows the reply when it lands. */
    fun submitLeaderboard() {
        val h = lbRun ?: return
        lbRun = null
        val items = lbItems.toList()
        if (items.isEmpty()) return
        lbLine = context.getString(R.string.leaderboard_submitting)
        LeaderboardClient.submit(h, items) { lbLine = it }
    }

    fun recordRun(attempts: Int, correct: Int, seconds: Int) {
        if (attempts <= 0) return
        val results = charResults.map { (ch, a) -> SessionRecord.CharResult(ch.toString(), a[0], a[1], null) }
        Stats.record(
            mode = "CW Asteroids", attempts = attempts, correct = correct,
            score = score,   // the saveable mirror: valid on every path, including process-death recovery
            bestTtrMs = null, durationSeconds = seconds,
            characterWpm = Settings.characterWpm.roundToInt(),
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt(),
            charResults = results,
            activeCharacters = if (results.isEmpty()) emptyList() else engine.activeCharacters.map { it.toString() }
        )
        submitLeaderboard()
    }

    fun tally(ch: Char, correct: Boolean) {
        val a = charResults.getOrPut(ch) { IntArray(2) }
        a[0] += 1
        if (correct) a[1] += 1
        runAttempts += 1
        if (correct) runCorrect += 1
        Stats.recordChar(ch.toString(), correct, null)
    }

    fun cue(a: Asteroid, g: AsteroidsGame) {
        // At the ramp's current speed, not the session timing: the label is
        // sent as one unit at the game's own speed.
        val secs = player.replaySound(MorseItem.Playable.Text(a.label), Settings.sidetoneHz, MorseTiming(g.currentWpm))
        cueEndMs = System.currentTimeMillis() + (secs * 1000).toLong()
    }

    fun startGame() {
        val g = AsteroidsGame(AsteroidsGame.Config(
            characters = characterPool(), words = MorseData.rankedWords, input = input,
            difficulty = difficulty, characterWpm = Settings.characterWpm
        ))
        game = g
        charResults.clear()
        runAttempts = 0
        runCorrect = 0
        cueEndMs = null
        flash = null
        startedAtMs = System.currentTimeMillis()
        lbItems.clear()
        lbLine = null
        // Send mode plays no Morse (see the label, key it), so there is no
        // audio for the server's timing bound to measure; only hear-it runs
        // are ranked.
        lbRun = if (input == AsteroidsInput.SEND) null else LeaderboardClient.beginRun(
            statsMode = "CW Asteroids",
            characterWpm = Settings.characterWpm.roundToInt(),
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt()
        )
        syncHud(g)
        phase = AstPhase.RUNNING
    }

    fun finishGame() {
        val g = game ?: return
        player.stop()
        syncHud(g)
        recordRun(runAttempts, runCorrect, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
        EngineStore.save()
        phase = AstPhase.OVER
    }

    /** End a run early (Back, the mode switcher): record what was played. */
    fun abandonRun() {
        val g = game
        player.stop()
        if (g != null && phase == AstPhase.RUNNING) {
            recordRun(runAttempts, runCorrect, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
            EngineStore.save()
        }
        game = null
        phase = AstPhase.SETUP
    }

    fun switchTo(mode: TrainingMode) {
        if (phase == AstPhase.RUNNING) abandonRun() else player.stop()
        onSwitchMode(mode)
    }

    // A game the system reclaimed mid-way cannot resume — the engine died with
    // the process — but its score need not die with it. Close it out from the
    // saved tally, as finishGame would have, and land on setup.
    LaunchedEffect(Unit) {
        if (game != null) return@LaunchedEffect
        if (phase == AstPhase.RUNNING) {
            recordRun(runAttempts, runCorrect, ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0))
        }
        if (phase != AstPhase.SETUP) phase = AstPhase.SETUP
    }

    fun resolve(shot: AsteroidsShot, g: AsteroidsGame) {
        val now = System.currentTimeMillis()
        when (shot.outcome) {
            AsteroidsOutcome.HIT -> {
                val hit = shot.asteroid
                if (hit != null) {
                    val end = cueEndMs
                    val ttr = if (end != null) ((now - end).coerceAtLeast(0)) / 1000.0 else 0.0
                    for (ch in hit.label) {
                        engine.noteAttempt(ch, ch, ttr)
                        tally(ch, true)
                    }
                    // One item per asteroid: its whole label, however long.
                    lbItems.add(LeaderboardItem(hit.label, hit.label, Leaderboard.clampReaction((ttr * 1000).toLong()), wpm))
                }
                cueEndMs = null
                shot.cued?.let { cue(it, g) }
                if (Settings.hapticsEnabled) haptics.success()
                flash = (if (shot.waveCleared) "Wave ${g.wave}!" else "+${shot.points}") to now + 800
            }
            AsteroidsOutcome.MISS -> {
                // A wrong character or the wrong asteroid: confused with what
                // was expected, so the pair feeds the Confusion Drill.
                val expected = shot.expected
                val chosen = shot.chosen
                if (expected != null) {
                    if (chosen != null) engine.noteAttempt(chosen, expected, 0.0) else engine.noteMiss(expected)
                    tally(expected, false)
                    // Not a leaderboard item: the asteroid is still in play and
                    // resolves as a hit or a strike. Same rule on iOS.
                } else {
                    runAttempts += 1
                }
                if (Settings.hapticsEnabled) haptics.error()
                flash = "miss" to now + 600
            }
            AsteroidsOutcome.PARTIAL, AsteroidsOutcome.IGNORED -> Unit
        }
        syncHud(g)
    }

    fun send(character: Char) {
        val g = game ?: return
        if (phase != AstPhase.RUNNING) return
        resolve(g.send(character), g)
    }

    fun tapAsteroid(id: Int) {
        val g = game ?: return
        if (phase != AstPhase.RUNNING) return
        resolve(g.tap(id), g)
    }

    fun replayCue() {
        val g = game ?: return
        val armed = g.armed ?: return
        cue(armed, g)
    }

    // The frame clock: each frame moves the game on by the real time elapsed.
    LaunchedEffect(phase, game) {
        val g = game ?: return@LaunchedEffect
        if (phase != AstPhase.RUNNING) return@LaunchedEffect
        var last = -1L
        while (true) {
            val nanos = withFrameNanos { it }
            if (last < 0) { last = nanos; continue }
            // Cap a long gap (the app was backgrounded) so the field does not
            // empty onto the ship in one step.
            val dt = ((nanos - last) / 1e9).coerceAtMost(0.1)
            last = nanos
            var over = false
            for (event in g.advance(dt)) {
                when (event) {
                    is AsteroidsEvent.Spawned -> Unit
                    is AsteroidsEvent.Cued -> cue(event.asteroid, g)
                    is AsteroidsEvent.Struck -> {
                        for (ch in event.asteroid.label) {
                            engine.noteMiss(ch)
                            tally(ch, false)
                        }
                        lbItems.add(LeaderboardItem(event.asteroid.label, "", 0, wpm))
                        if (Settings.hapticsEnabled) haptics.error()
                        flash = "${event.asteroid.label} hit the ship" to System.currentTimeMillis() + 1000
                    }
                    AsteroidsEvent.GameOver -> over = true
                }
            }
            syncHud(g)
            if (over) { finishGame(); return@LaunchedEffect }
        }
    }

    // Send mode: each finalised character is sent toward a label.
    LaunchedEffect(keyer.decodedText, keyer.isKeying, phase) {
        if (phase != AstPhase.RUNNING || input != AsteroidsInput.SEND || keyer.isKeying) return@LaunchedEffect
        val ch = keyer.decodedText.trim().firstOrNull() ?: return@LaunchedEffect
        keyer.clear()
        send(ch)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (phase) {
            AstPhase.SETUP -> {
                BackHandler { onBack() }
                AsteroidsSetup(
                    input = input, onInput = { input = it },
                    characterSet = characterSet, onCharacterSet = { characterSet = it },
                    pool = characterPool(),
                    difficulty = difficulty, onDifficulty = { difficulty = it },
                    onStart = { startGame() },
                    onBack = onBack,
                    onSwitchMode = ::switchTo
                )
            }
            AstPhase.RUNNING -> {
                BackHandler { abandonRun() }
                AsteroidsRun(
                    input = input,
                    field = field,
                    elapsed = elapsed,
                    score = score, wave = wave, lives = lives, multiplier = multiplier, wpm = wpm,
                    flash = flash,
                    buffer = sendBuffer,
                    keyPressed = keyPressed,
                    midiDevice = midiDevice,
                    onKey = { down -> keyPressed = down; keyer.touchKey(down) },
                    onTapAsteroid = { tapAsteroid(it) },
                    onTapShip = { replayCue() },
                    onEnd = { abandonRun() },
                    onSwitchMode = ::switchTo
                )
            }
            AstPhase.OVER -> {
                BackHandler { onBack() }
                AsteroidsOver(
                    score = score, wave = wave, bestCombo = bestCombo,
                    accuracy = if (gameHits + gameMisses == 0) 0.0 else gameHits.toDouble() / (gameHits + gameMisses),
                    bestWpm = if (input == AsteroidsInput.COPY) bestWpm else null,
                    leaderboard = lbLine,
                    onAgain = { startGame() },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun AsteroidsSetup(
    input: AsteroidsInput, onInput: (AsteroidsInput) -> Unit,
    characterSet: InvadersCharacterSet, onCharacterSet: (InvadersCharacterSet) -> Unit,
    pool: List<Char>,
    difficulty: InvadersDifficulty, onDifficulty: (InvadersDifficulty) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_asteroids), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.ASTEROIDS, onSwitchMode)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.asteroids_blurb), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            AstLabel(stringResource(R.string.asteroids_how_you_play))
            AstPills(AsteroidsInput.entries.map { it to it.label }, input, onInput)
            Text(input.blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            AstLabel(stringResource(R.string.asteroids_characters))
            AstPills(InvadersCharacterSet.entries.map { it to it.label }, characterSet, onCharacterSet)
            Text(pool.joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Text(stringResource(R.string.asteroids_words_note, AsteroidsGame.wordWaveStart), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            AstLabel(stringResource(R.string.asteroids_difficulty))
            AstPills(InvadersDifficulty.entries.map { it to it.label }, difficulty, onDifficulty)
            // The ramp in words: where a game opens and where it climbs to.
            // Send mode sends nothing, so it names the decoder speed.
            val target = Settings.characterWpm.roundToInt()
            val start = AsteroidsGame.rampStart(Settings.characterWpm).roundToInt()
            val speedNote = when {
                input == AsteroidsInput.SEND -> stringResource(R.string.asteroids_send_speed_note, target)
                start >= target -> stringResource(R.string.asteroids_speed_flat_note, target)
                else -> stringResource(
                    R.string.asteroids_speed_ramp_note,
                    start, AsteroidsGame.rampStep.roundToInt(), AsteroidsGame.hitsPerRampStep, target
                )
            }
            Text(speedNote, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.asteroids_start), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AsteroidsRun(
    input: AsteroidsInput,
    field: List<Asteroid>,
    elapsed: Double,
    score: Int, wave: Int, lives: Int, multiplier: Int, wpm: Int,
    flash: Pair<String, Long>?,
    buffer: String,
    keyPressed: Boolean,
    midiDevice: String?,
    onKey: (Boolean) -> Unit,
    onTapAsteroid: (Int) -> Unit,
    onTapShip: () -> Unit,
    onEnd: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.asteroids_end_game), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.ASTEROIDS, onSwitchMode)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AstStat(stringResource(R.string.asteroids_score), score.toString())
            AstStat(stringResource(R.string.asteroids_wave), wave.toString())
            AstStat(stringResource(R.string.asteroids_lives), "♥".repeat(lives) + "♡".repeat((3 - lives).coerceAtLeast(0)))
            AstStat(stringResource(R.string.asteroids_combo_label), "×$multiplier")
            if (input == AsteroidsInput.COPY) AstStat(stringResource(R.string.asteroids_wpm), wpm.toString())
        }
        Spacer(Modifier.height(10.dp))
        val labelStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Brand.textPrimary)
        val flashStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Brand.cornerRadius))
                .background(Brand.navyElevated)
                .border(1.dp, Brand.hairline, RoundedCornerShape(Brand.cornerRadius))
                .pointerInput(input, field) {
                    if (input != AsteroidsInput.COPY) return@pointerInput
                    detectTapGestures { offset ->
                        // The ship replays the cue; otherwise the nearest
                        // asteroid within reach is the one tapped; empty
                        // space does nothing.
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        if (hypot(offset.x - cx, offset.y - cy) <= with(density) { 30.dp.toPx() }) {
                            onTapShip()
                            return@detectTapGestures
                        }
                        var best: Asteroid? = null
                        var bestD = Float.MAX_VALUE
                        for (a in field) {
                            val px = a.x.toFloat() * size.width
                            val py = a.y.toFloat() * size.height
                            val d = hypot(offset.x - px, offset.y - py)
                            val reach = with(density) { (asteroidRadiusDp(a) + 14f).dp.toPx() }
                            if (d <= reach && d < bestD) { best = a; bestD = d }
                        }
                        best?.let { onTapAsteroid(it.id) }
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // The ship: a small triangle facing the nearest asteroid, inside
            // a faint shield ring.
            val nearest = field.maxByOrNull { it.progress }
            val facing = if (nearest != null) {
                atan2(nearest.y.toFloat() * size.height - cy, nearest.x.toFloat() * size.width - cx)
            } else {
                (-PI / 2).toFloat()
            }
            drawCircle(Brand.teal.copy(alpha = 0.35f), radius = 26.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            val ship = Path().apply {
                val nose = 14.dp.toPx()
                val wing = 11.dp.toPx()
                moveTo(cx + nose * cos(facing), cy + nose * sin(facing))
                lineTo(cx + wing * cos(facing + 2.5f), cy + wing * sin(facing + 2.5f))
                lineTo(cx, cy)
                lineTo(cx + wing * cos(facing - 2.5f), cy + wing * sin(facing - 2.5f))
                close()
            }
            drawPath(ship, Brand.teal)

            for (a in field) {
                val px = a.x.toFloat() * size.width
                val py = a.y.toFloat() * size.height
                val r = asteroidRadiusDp(a).dp.toPx()
                val spin = (elapsed * 0.6 * (if (a.id % 2 == 0) 1 else -1)).toFloat()
                val n = ASTEROID_LUMPS.size
                val rock = Path().apply {
                    for (k in 0 until n) {
                        val theta = spin + k * 2f * PI.toFloat() / n
                        val rr = r * ASTEROID_LUMPS[(k + a.id) % n]
                        val x = px + rr * cos(theta)
                        val y = py + rr * sin(theta)
                        if (k == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(rock, Brand.navyRaised)
                drawPath(rock, Brand.tealBright, style = Stroke((if (a.isFragment) 1.dp else 1.5.dp).toPx()))
                val measured = measurer.measure(a.label, labelStyle)
                drawText(measured, topLeft = Offset(px - measured.size.width / 2, py - measured.size.height / 2))
            }
            if (flash != null && flash.second > System.currentTimeMillis()) {
                val measured = measurer.measure(flash.first, flashStyle)
                drawText(measured, topLeft = Offset(size.width / 2 - measured.size.width / 2, 6.dp.toPx()))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (input == AsteroidsInput.COPY) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.asteroids_tap_what_you_heard), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onTapShip) { Text(stringResource(R.string.asteroids_hear_again), color = Brand.teal) }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.asteroids_key_a_label), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(if (buffer.isEmpty()) "—" else "${buffer}_", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Brand.textPrimary)
                midiDevice?.let { Text("  🎹", color = Brand.teal) }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(Brand.cornerRadius))
                    .background(if (keyPressed) Brand.teal else Brand.navyRaised)
                    .border(
                        width = if (keyPressed) 2.dp else 1.dp,
                        color = if (keyPressed) Brand.tealBright else Brand.hairline,
                        shape = RoundedCornerShape(Brand.cornerRadius)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            onKey(true)
                            try { tryAwaitRelease() } finally { onKey(false) }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.common_hold_to_key),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (keyPressed) Brand.navy else Brand.textSecondary
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AsteroidsOver(
    score: Int, wave: Int, bestCombo: Int, accuracy: Double,
    bestWpm: Int?,
    leaderboard: String?,
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
            Text(stringResource(R.string.asteroids_game_over), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AstStat(stringResource(R.string.asteroids_score), score.toString())
                AstStat(stringResource(R.string.asteroids_wave), wave.toString())
                AstStat(stringResource(R.string.asteroids_accuracy), "${(accuracy * 100).roundToInt()}%")
                AstStat(stringResource(R.string.asteroids_best_combo), bestCombo.toString())
            }
            // The speed the ramp reached; null in send mode, which sends nothing.
            if (bestWpm != null) {
                Text(stringResource(R.string.asteroids_speed_reached, bestWpm), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            }
            // The shared leaderboard's reply, or why the game was not posted; nothing when not opted in.
            if (leaderboard != null) {
                Text(leaderboard, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            }
            Button(
                onClick = onAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.asteroids_play_again), fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

@Composable
private fun AstStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AstLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
}

@Composable
private fun <T> AstPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Brand.navy else Brand.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
