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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.GalagaEnemy
import app.anothermorsetrainer.morsekit.GalagaEnemyState
import app.anothermorsetrainer.morsekit.GalagaEvent
import app.anothermorsetrainer.morsekit.GalagaGame
import app.anothermorsetrainer.morsekit.GalagaPath
import app.anothermorsetrainer.morsekit.InvadersCharacterSet
import app.anothermorsetrainer.morsekit.InvadersDifficulty
import app.anothermorsetrainer.morsekit.InvadersInput
import app.anothermorsetrainer.morsekit.InvadersKeyboard
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.SessionRecord
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class GalPhase { SETUP, RUNNING, OVER }

/**
 * An 11×8 "butterfly" enemy, drawn from this bitmap so there is no image
 * asset; `#` is a lit pixel. The same table is in `GalagaView.swift`.
 */
private val GALAGA_SPRITE = listOf(
    "#.........#",
    "#..#...#..#",
    "##.#####.##",
    ".#########.",
    "..##.#.##..",
    ".###.#.###.",
    "#.##...##.#",
    "#..#...#..#"
)

/**
 * CW Galaga (#187): enemies swoop in along curved paths and settle into a
 * formation at the top, then dive at the player one at a time. The learner
 * shoots each one by naming the character it carries — typing it after
 * hearing it (copy) or keying it after seeing it (send) — and consecutive
 * hits build a combo multiplier. The rules live in [GalagaGame] and the
 * flight paths in [GalagaPath]; this screen is the frame clock
 * ([withFrameNanos]), the sound, the input and the drawing.
 *
 * Twin of the iOS `GalagaView.swift`. Session state survives process death
 * as a score, not a game: the tally rides `rememberSaveable` and a reclaimed
 * run is closed out to [Stats] on restore, like Invaders, Contest and Rapid
 * Fire.
 */
@Composable
fun GalagaScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val prefs = remember { context.getSharedPreferences("amt_galaga", android.content.Context.MODE_PRIVATE) }

    var phase by rememberSaveable { mutableStateOf(GalPhase.SETUP) }
    // Setup choices persist across launches, like every other mode's. The
    // choices themselves are the arcade games' shared enums (Invaders.kt).
    var input by rememberSaveable {
        mutableStateOf(runCatching { InvadersInput.valueOf(prefs.getString("input", "") ?: "") }.getOrDefault(InvadersInput.ICR))
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

    /** The pool, letters first then digits (the recognition chart's order); the same two pools as Invaders. */
    fun characterPool(): List<Char> = when (characterSet) {
        InvadersCharacterSet.ACTIVE -> engine.activeCharacters
        InvadersCharacterSet.FULL -> MorseCode.kochOrder.filter { it.isLetterOrDigit() }
    }.map { it.toString() }.sortedWith(SessionRecord.characterOrder).map { it[0] }

    // Run state. The game dies with the process; the tally below does not.
    var game by remember { mutableStateOf<GalagaGame?>(null) }
    var field by remember { mutableStateOf<List<GalagaEnemy>>(emptyList()) }
    var columns by remember { mutableIntStateOf(4) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var wave by rememberSaveable { mutableIntStateOf(1) }
    var lives by rememberSaveable { mutableIntStateOf(3) }
    var multiplier by remember { mutableIntStateOf(1) }
    var bestCombo by rememberSaveable { mutableIntStateOf(0) }
    // The ramp: the speed enemies are sent at now, and the highest reached.
    var wpm by remember { mutableIntStateOf(0) }
    var bestWpm by rememberSaveable { mutableIntStateOf(0) }
    var runAttempts by rememberSaveable { mutableIntStateOf(0) }
    var runCorrect by rememberSaveable { mutableIntStateOf(0) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // When each enemy's Morse finished sounding (copy mode), by id, so a
    // hit's time-to-recognize runs from the end of the tone.
    val toneEnd = remember { HashMap<Int, Long>() }
    val charResults = remember { HashMap<Char, IntArray>() }   // attempts, correct

    // Send mode: the same decoder Sending Practice uses, plus a hardware key.
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    DisposableEffect(phase, input) {
        if (phase == GalPhase.RUNNING && input == InvadersInput.KEYING) {
            keyer.scope = scope
            keyer.start()
            midi.start(onKey = { down -> keyer.touchKey(down) }, onConnected = { midiDevice = it })
        }
        onDispose { midi.stop(); keyer.stop() }
    }

    fun syncHud(g: GalagaGame) {
        score = g.score
        wave = g.wave
        lives = g.lives
        multiplier = g.multiplier
        bestCombo = g.bestCombo
        wpm = g.currentWpm.roundToInt()
        bestWpm = g.bestWpm.roundToInt()
        runAttempts = g.hits + g.misses
        runCorrect = g.hits
        columns = g.formation.columns
        lastSeenMs = System.currentTimeMillis()
    }

    fun recordRun(attempts: Int, correct: Int, seconds: Int) {
        if (attempts <= 0) return
        val results = charResults.map { (ch, a) -> SessionRecord.CharResult(ch.toString(), a[0], a[1], null) }
        Stats.record(
            mode = "CW Galaga", attempts = attempts, correct = correct,
            score = score,   // the saveable mirror: valid on every path, including process-death recovery
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

    fun startGame() {
        val g = GalagaGame(GalagaGame.Config(
            characters = characterPool(), difficulty = difficulty,
            characterWpm = Settings.characterWpm
        ))
        game = g
        field = emptyList()
        toneEnd.clear()
        charResults.clear()
        flash = null
        startedAtMs = System.currentTimeMillis()
        syncHud(g)
        phase = GalPhase.RUNNING
    }

    fun finishGame() {
        val g = game ?: return
        player.stop()
        syncHud(g)
        recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
        EngineStore.save()
        phase = GalPhase.OVER
    }

    /** End a run early (Back, the mode switcher): record what was played. */
    fun abandonRun() {
        val g = game
        player.stop()
        if (g != null && phase == GalPhase.RUNNING) {
            recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
            EngineStore.save()
        }
        game = null
        phase = GalPhase.SETUP
    }

    fun switchTo(mode: TrainingMode) {
        if (phase == GalPhase.RUNNING) abandonRun() else player.stop()
        onSwitchMode(mode)
    }

    // A game the system reclaimed mid-way cannot resume — the engine died with
    // the process — but its score need not die with it. Close it out from the
    // saved tally, as finishGame would have, and land on setup.
    LaunchedEffect(Unit) {
        if (game != null) return@LaunchedEffect
        if (phase == GalPhase.RUNNING) {
            recordRun(runAttempts, runCorrect, ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0))
        }
        if (phase != GalPhase.SETUP) phase = GalPhase.SETUP
    }

    fun shoot(character: Char) {
        val g = game ?: return
        if (phase != GalPhase.RUNNING) return
        val now = System.currentTimeMillis()
        val threat = g.mostThreatening
        val shot = g.shoot(character)
        val hit = shot.enemy
        if (hit != null) {
            val end = toneEnd.remove(hit.id)
            val ttr = if (end != null) ((now - end).coerceAtLeast(0)) / 1000.0 else 0.0
            engine.noteAttempt(hit.character, hit.character, ttr)
            tally(hit.character, true)
            if (Settings.hapticsEnabled) haptics.success()
            flash = (if (shot.waveCleared) "Wave ${g.wave}!" else "+${shot.points}") to now + 800
        } else {
            // A wrong key: confused with whatever was the biggest threat.
            if (threat != null) {
                engine.noteAttempt(character.uppercaseChar(), threat.character, 0.0)
                tally(threat.character, false)
            }
            if (Settings.hapticsEnabled) haptics.error()
            flash = "miss" to now + 600
        }
        field = g.enemies
        syncHud(g)
    }

    // The frame clock: each frame moves the game on by the real time elapsed.
    LaunchedEffect(phase, game) {
        val g = game ?: return@LaunchedEffect
        if (phase != GalPhase.RUNNING) return@LaunchedEffect
        var last = -1L
        while (true) {
            val nanos = withFrameNanos { it }
            if (last < 0) { last = nanos; continue }
            // Cap a long gap (the app was backgrounded) so a dive does not
            // land in one step.
            val dt = ((nanos - last) / 1e9).coerceAtMost(0.1)
            last = nanos
            var over = false
            for (event in g.advance(dt)) {
                when (event) {
                    is GalagaEvent.Entered -> sendEnemy(player, event.enemy, g, input, toneEnd)
                    is GalagaEvent.Dived -> sendEnemy(player, event.enemy, g, input, toneEnd)
                    is GalagaEvent.Landed -> {
                        toneEnd.remove(event.enemy.id)
                        engine.noteMiss(event.enemy.character)
                        tally(event.enemy.character, false)
                        if (Settings.hapticsEnabled) haptics.error()
                        flash = "${event.enemy.character} got through" to System.currentTimeMillis() + 1000
                    }
                    GalagaEvent.GameOver -> over = true
                }
            }
            field = g.enemies
            syncHud(g)
            if (over) { finishGame(); return@LaunchedEffect }
        }
    }

    // Send mode: the first finalised character is the shot.
    LaunchedEffect(keyer.decodedText, keyer.isKeying, phase) {
        if (phase != GalPhase.RUNNING || input != InvadersInput.KEYING || keyer.isKeying) return@LaunchedEffect
        val ch = keyer.decodedText.trim().firstOrNull() ?: return@LaunchedEffect
        keyer.clear()
        shoot(ch)
    }

    // Hardware keyboard (copy mode): the character key is the shot.
    val hwFocus = remember { FocusRequester() }
    LaunchedEffect(phase) { if (phase == GalPhase.RUNNING) hwFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hwFocus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || phase != GalPhase.RUNNING || input != InvadersInput.ICR) return@onKeyEvent false
                val ch = event.utf16CodePoint.takeIf { it > 0 }?.toChar()?.uppercaseChar() ?: return@onKeyEvent false
                if (ch !in characterPool()) return@onKeyEvent false
                shoot(ch)
                true
            }
            .focusable()
    ) {
        when (phase) {
            GalPhase.SETUP -> {
                BackHandler { onBack() }
                GalagaSetup(
                    input = input, onInput = { input = it },
                    characterSet = characterSet, onCharacterSet = { characterSet = it },
                    pool = characterPool(),
                    difficulty = difficulty, onDifficulty = { difficulty = it },
                    onStart = { startGame() },
                    onBack = onBack,
                    onSwitchMode = ::switchTo
                )
            }
            GalPhase.RUNNING -> {
                BackHandler { abandonRun() }
                GalagaRun(
                    input = input,
                    field = field,
                    columns = columns,
                    score = score, wave = wave, lives = lives, multiplier = multiplier, wpm = wpm,
                    flash = flash,
                    pool = characterPool(),
                    decoded = keyer.decodedText,
                    keyPressed = keyPressed,
                    midiDevice = midiDevice,
                    onKey = { down -> keyPressed = down; keyer.touchKey(down) },
                    onShoot = { shoot(it) },
                    onReplay = { enemy ->
                        val g = game ?: return@GalagaRun
                        player.replaySound(MorseItem.Playable.Text(enemy.character.toString()), Settings.sidetoneHz, MorseTiming(g.currentWpm))
                    },
                    onEnd = { abandonRun() },
                    onSwitchMode = ::switchTo
                )
            }
            GalPhase.OVER -> {
                BackHandler { onBack() }
                GalagaOver(
                    score = score, wave = wave, bestCombo = bestCombo,
                    accuracy = if (runAttempts == 0) 0.0 else runCorrect.toDouble() / runAttempts,
                    bestWpm = if (input == InvadersInput.ICR) bestWpm else null,
                    onAgain = { startGame() },
                    onBack = onBack
                )
            }
        }
    }
}

/**
 * Copy mode sends an enemy's character as it enters and again as it dives,
 * at the ramp's current speed, not the session timing: a single character
 * has no gaps for Farnsworth to stretch. Dates the tone's end for the
 * time-to-recognize clock.
 */
private fun sendEnemy(player: MorsePlayer, enemy: GalagaEnemy, g: GalagaGame, input: InvadersInput, toneEnd: HashMap<Int, Long>) {
    if (input != InvadersInput.ICR) return
    val secs = player.replaySound(
        MorseItem.Playable.Text(enemy.character.toString()),
        Settings.sidetoneHz, MorseTiming(g.currentWpm)
    )
    toneEnd[enemy.id] = System.currentTimeMillis() + (secs * 1000).toLong()
}

@Composable
private fun GalagaSetup(
    input: InvadersInput, onInput: (InvadersInput) -> Unit,
    characterSet: InvadersCharacterSet, onCharacterSet: (InvadersCharacterSet) -> Unit,
    pool: List<Char>,
    difficulty: InvadersDifficulty, onDifficulty: (InvadersDifficulty) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_galaga), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.GALAGA, onSwitchMode)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.galaga_blurb), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            GalLabel(stringResource(R.string.invaders_how_to_answer))
            GalPills(InvadersInput.entries.map { it to it.label }, input, onInput)
            Text(
                stringResource(if (input == InvadersInput.ICR) R.string.galaga_copy_blurb else R.string.galaga_send_blurb),
                style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary
            )
            GalLabel(stringResource(R.string.invaders_characters))
            GalPills(InvadersCharacterSet.entries.map { it to it.label }, characterSet, onCharacterSet)
            Text(pool.joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            GalLabel(stringResource(R.string.invaders_difficulty))
            GalPills(InvadersDifficulty.entries.map { it to it.label }, difficulty, onDifficulty)
            // The ramp in words: where a game opens and where it climbs to.
            // Send mode sends nothing, so it names the decoder speed.
            val target = Settings.characterWpm.roundToInt()
            val start = GalagaGame.rampStart(Settings.characterWpm).roundToInt()
            val speedNote = when {
                input == InvadersInput.KEYING -> stringResource(R.string.invaders_keying_speed_note, target)
                start >= target -> stringResource(R.string.invaders_speed_flat_note, target)
                else -> stringResource(
                    R.string.galaga_speed_ramp_note,
                    start, GalagaGame.rampStep.roundToInt(), GalagaGame.hitsPerRampStep, target
                )
            }
            Text(speedNote, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.invaders_start), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GalagaRun(
    input: InvadersInput,
    field: List<GalagaEnemy>,
    columns: Int,
    score: Int, wave: Int, lives: Int, multiplier: Int, wpm: Int,
    flash: Pair<String, Long>?,
    pool: List<Char>,
    decoded: String,
    keyPressed: Boolean,
    midiDevice: String?,
    onKey: (Boolean) -> Unit,
    onShoot: (Char) -> Unit,
    onReplay: (GalagaEnemy) -> Unit,
    onEnd: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    val measurer = rememberTextMeasurer()
    // The tap handler reads the field as it is now without restarting on
    // every frame, which would cancel a tap in progress.
    val latestField = rememberUpdatedState(field)
    val latestColumns = rememberUpdatedState(columns)
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.invaders_end_game), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.GALAGA, onSwitchMode)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalStat(stringResource(R.string.invaders_score), score.toString())
            GalStat(stringResource(R.string.invaders_wave), wave.toString())
            GalStat(stringResource(R.string.invaders_lives), "♥".repeat(lives) + "♡".repeat((3 - lives).coerceAtLeast(0)))
            GalStat(stringResource(R.string.invaders_combo_label), "×$multiplier")
            if (input == InvadersInput.ICR) GalStat(stringResource(R.string.invaders_wpm), wpm.toString())
        }
        Spacer(Modifier.height(10.dp))
        val labelStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Brand.textPrimary)
        val flashStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        // Where the unit-space paths land on the canvas: the field keeps a
        // margin so the sprite stays whole at the edges of its swoop, and the
        // bottom of the dive is the player's line. The keying label sits
        // above the sprite, so that layout starts lower.
        fun canvasPoint(p: GalagaPath.Point, width: Float, height: Float, inset: Float, top: Float, bottom: Float): Offset =
            Offset(inset + p.x.toFloat() * (width - 2 * inset), top + p.y.toFloat() * (bottom - top))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Brand.cornerRadius))
                .background(Brand.navyElevated)
                .border(1.dp, Brand.hairline, RoundedCornerShape(Brand.cornerRadius))
                .pointerInput(input) {
                    if (input != InvadersInput.ICR) return@pointerInput
                    detectTapGestures { offset ->
                        val inset = 20.dp.toPx()
                        val top = 18.dp.toPx()
                        val bottom = size.height - 36.dp.toPx()
                        val cols = latestColumns.value
                        val nearest = latestField.value.minByOrNull { e ->
                            val p = canvasPoint(GalagaPath.position(e, cols), size.width.toFloat(), size.height.toFloat(), inset, top, bottom)
                            hypot(p.x - offset.x, p.y - offset.y)
                        }
                        if (nearest != null) onReplay(nearest)
                    }
                }
        ) {
            val ground = size.height - 18.dp.toPx()
            drawLine(Brand.teal.copy(alpha = 0.6f), Offset(0f, ground), Offset(size.width, ground), strokeWidth = 2.dp.toPx())
            // The player's ship: a small chevron on the line.
            val ship = Path().apply {
                val cx = size.width / 2
                val cy = ground - 12.dp.toPx()
                moveTo(cx, cy - 9.dp.toPx())
                lineTo(cx + 10.dp.toPx(), cy + 7.dp.toPx())
                lineTo(cx, cy + 2.dp.toPx())
                lineTo(cx - 10.dp.toPx(), cy + 7.dp.toPx())
                close()
            }
            drawPath(ship, Brand.teal)
            val inset = 20.dp.toPx()
            val top = (if (input == InvadersInput.KEYING) 30.dp else 18.dp).toPx()
            val bottom = size.height - 36.dp.toPx()
            // The sprite: 3 dp a pixel, 33×24 dp, the Invaders sprite's size.
            val px = 3.dp.toPx()
            fun drawSprite(cx: Float, cy: Float, tint: androidx.compose.ui.graphics.Color) {
                val ox = cx - GALAGA_SPRITE[0].length * px / 2
                val oy = cy - GALAGA_SPRITE.size * px / 2
                GALAGA_SPRITE.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        if (cell == '#') drawRect(tint, Offset(ox + c * px, oy + r * px), Size(px, px))
                    }
                }
            }
            for (enemy in field) {
                val p = canvasPoint(GalagaPath.position(enemy, columns), size.width, size.height, inset, top, bottom)
                val tint = if (enemy.state == GalagaEnemyState.DIVING) Brand.tealBright else Brand.teal
                // Copy mode keeps the character to the ear; send mode shows
                // it directly above the sprite to key.
                if (input == InvadersInput.KEYING) {
                    drawSprite(p.x, p.y + 6.dp.toPx(), tint)
                    val measured = measurer.measure(enemy.character.toString(), labelStyle)
                    drawText(measured, topLeft = Offset(p.x - measured.size.width / 2, p.y - 14.dp.toPx() - measured.size.height / 2))
                } else {
                    drawSprite(p.x, p.y, tint)
                }
            }
            if (flash != null && flash.second > System.currentTimeMillis()) {
                val measured = measurer.measure(flash.first, flashStyle)
                drawText(measured, topLeft = Offset(size.width / 2 - measured.size.width / 2, 6.dp.toPx()))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (input == InvadersInput.ICR) {
            Text(stringResource(R.string.galaga_tap_to_replay), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
            Spacer(Modifier.height(6.dp))
            GalagaKeyRows(pool = pool, onShoot = onShoot)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.galaga_key_the_diver), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(decoded.ifEmpty { "—" }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Brand.textPrimary)
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

/**
 * The copy-mode keyboard: the arcade games' [InvadersKeyboard.rows] laid out
 * QWERTY — a digit row when the pool has digits, the three letter rows
 * always, any punctuation below. A key outside the pool stays in place,
 * dimmed and dead, so the layout never shifts as the Koch set grows; the
 * hardware-keyboard handler in [GalagaScreen] presses the live ones too.
 */
@Composable
private fun GalagaKeyRows(pool: List<Char>, onShoot: (Char) -> Unit) {
    val live = pool.toSet()
    val rows = InvadersKeyboard.rows(pool)
    val gap = 4.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val keyWidth = (maxWidth - gap * 9) / 10
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                ) {
                    for (ch in row) {
                        val enabled = ch in live
                        Text(
                            text = ch.toString(),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = Brand.textPrimary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .width(keyWidth)
                                .alpha(if (enabled) 1f else 0.3f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Brand.navyRaised)
                                .clickable(enabled = enabled) { onShoot(ch) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalagaOver(
    score: Int, wave: Int, bestCombo: Int, accuracy: Double,
    bestWpm: Int?,
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
            Text(stringResource(R.string.invaders_game_over), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GalStat(stringResource(R.string.invaders_score), score.toString())
                GalStat(stringResource(R.string.invaders_wave), wave.toString())
                GalStat(stringResource(R.string.invaders_accuracy), "${(accuracy * 100).roundToInt()}%")
                GalStat(stringResource(R.string.invaders_best_combo), bestCombo.toString())
            }
            // The speed the ramp reached; null in send mode, which sends nothing.
            if (bestWpm != null) {
                Text(stringResource(R.string.invaders_speed_reached, bestWpm), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            }
            Button(
                onClick = onAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.invaders_play_again), fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

@Composable
private fun GalStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun GalLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
}

@Composable
private fun <T> GalPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
