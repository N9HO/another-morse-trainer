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
import app.anothermorsetrainer.morsekit.InvadersCharacterSet
import app.anothermorsetrainer.morsekit.InvadersDifficulty
import app.anothermorsetrainer.morsekit.InvadersEvent
import app.anothermorsetrainer.morsekit.InvadersGame
import app.anothermorsetrainer.morsekit.InvadersInput
import app.anothermorsetrainer.morsekit.InvadersKeyboard
import app.anothermorsetrainer.morsekit.Invader
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.Leaderboard
import app.anothermorsetrainer.morsekit.LeaderboardItem
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.SessionRecord
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class InvPhase { SETUP, RUNNING, OVER }

private const val INVADER_COLUMNS = 5

/**
 * The classic 11×8 "crab" invader (#179), drawn from this bitmap so there is
 * no image asset; `#` is a lit pixel. The same table is in `InvadersView.swift`.
 */
private val INVADER_SPRITE = listOf(
    "..#.....#..",
    "...#...#...",
    "..#######..",
    ".##.###.##.",
    "###########",
    "#.#######.#",
    "#.#.....#.#",
    "...##.##..."
)

/**
 * Morse Invaders (#170): characters descend the play field in columns and the
 * learner shoots each one by naming it — typing it after hearing it (ICR) or
 * keying it after seeing it. The rules live in [InvadersGame]; this screen is
 * the frame clock ([withFrameNanos]), the sound, the input and the drawing.
 *
 * Twin of the iOS `InvadersView.swift`. Session state survives process death
 * as a score, not a game: the tally rides `rememberSaveable` and a reclaimed
 * run is closed out to [Stats] on restore, like Contest and Rapid Fire.
 */
@Composable
fun InvadersScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val prefs = remember { context.getSharedPreferences("amt_invaders", android.content.Context.MODE_PRIVATE) }

    var phase by rememberSaveable { mutableStateOf(InvPhase.SETUP) }
    // Setup choices persist across launches, like every other mode's.
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

    /** The pool, letters first then digits (the recognition chart's order). */
    fun characterPool(): List<Char> = when (characterSet) {
        InvadersCharacterSet.ACTIVE -> engine.activeCharacters
        InvadersCharacterSet.FULL -> MorseCode.kochOrder.filter { it.isLetterOrDigit() }
    }.map { it.toString() }.sortedWith(SessionRecord.characterOrder).map { it[0] }

    // Run state. The game dies with the process; the tally below does not.
    var game by remember { mutableStateOf<InvadersGame?>(null) }
    var field by remember { mutableStateOf<List<Invader>>(emptyList()) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var wave by rememberSaveable { mutableIntStateOf(1) }
    var lives by rememberSaveable { mutableIntStateOf(3) }
    var multiplier by remember { mutableIntStateOf(1) }
    var bestCombo by rememberSaveable { mutableIntStateOf(0) }
    // The ramp (#178): the speed invaders are sent at now, and the highest reached.
    var wpm by remember { mutableIntStateOf(0) }
    var bestWpm by rememberSaveable { mutableIntStateOf(0) }
    var runAttempts by rememberSaveable { mutableIntStateOf(0) }
    var runCorrect by rememberSaveable { mutableIntStateOf(0) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // When each invader's Morse finished sounding (ICR), by id, so a hit's
    // time-to-recognize runs from the end of the tone.
    val toneEnd = remember { HashMap<Int, Long>() }
    val charResults = remember { HashMap<Char, IntArray>() }   // attempts, correct
    // The shared leaderboard (docs/high-scores-design.md, step 2): the run's
    // registration and one item per shot or decision, each carrying the
    // ramp speed it was sent at (the HUD's `wpm`, synced before every item
    // resolves). Plain state, not saveable: a game the process lost has no
    // transcript and is never submitted.
    var lbRun by remember { mutableStateOf<LeaderboardClient.RunHandle?>(null) }
    val lbItems = remember { ArrayList<LeaderboardItem>() }
    var lbLine by remember { mutableStateOf<String?>(null) }
    // Read at composition, not via context.getString in the helper below: lint
    // (LocalContextGetResources) wants resource reads to follow configuration.
    val lbSubmittingText = stringResource(R.string.leaderboard_submitting)

    // Keying mode: the same decoder Sending Practice uses, plus a hardware key.
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    DisposableEffect(phase, input) {
        if (phase == InvPhase.RUNNING && input == InvadersInput.KEYING) {
            keyer.scope = scope
            keyer.start()
            midi.start(onKey = { down -> keyer.touchKey(down) }, onConnected = { midiDevice = it })
        }
        onDispose { midi.stop(); keyer.stop() }
    }

    fun syncHud(g: InvadersGame) {
        score = g.score
        wave = g.wave
        lives = g.lives
        multiplier = g.multiplier
        bestCombo = g.bestCombo
        wpm = g.currentWpm.roundToInt()
        bestWpm = g.bestWpm.roundToInt()
        runAttempts = g.hits + g.misses
        runCorrect = g.hits
        lastSeenMs = System.currentTimeMillis()
    }

    /** Hand the finished game's transcript to the leaderboard; the end card shows the reply when it lands. */
    fun submitLeaderboard() {
        val h = lbRun ?: return
        lbRun = null
        val items = lbItems.toList()
        if (items.isEmpty()) return
        lbLine = lbSubmittingText
        LeaderboardClient.submit(h, items) { lbLine = it }
    }

    fun recordRun(attempts: Int, correct: Int, seconds: Int) {
        if (attempts <= 0) return
        val results = charResults.map { (ch, a) -> SessionRecord.CharResult(ch.toString(), a[0], a[1], null) }
        Stats.record(
            mode = "Morse Invaders", attempts = attempts, correct = correct,
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
        Stats.recordChar(ch.toString(), correct, null)
    }

    fun startGame() {
        val g = InvadersGame(InvadersGame.Config(
            characters = characterPool(), difficulty = difficulty, columns = INVADER_COLUMNS,
            characterWpm = Settings.characterWpm
        ))
        game = g
        field = emptyList()
        toneEnd.clear()
        charResults.clear()
        flash = null
        startedAtMs = System.currentTimeMillis()
        lbItems.clear()
        lbLine = null
        // Keying mode plays no Morse (see it, key it), so there is no audio for
        // the server's timing bound to measure; only hear-it runs are ranked.
        lbRun = if (input == InvadersInput.KEYING) null else LeaderboardClient.beginRun(
            statsMode = "Morse Invaders",
            characterWpm = Settings.characterWpm.roundToInt(),
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt()
        )
        syncHud(g)
        phase = InvPhase.RUNNING
    }

    fun finishGame() {
        val g = game ?: return
        player.stop()
        syncHud(g)
        recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
        EngineStore.save()
        phase = InvPhase.OVER
    }

    /** End a run early (Back, the mode switcher): record what was played. */
    fun abandonRun() {
        val g = game
        player.stop()
        if (g != null && phase == InvPhase.RUNNING) {
            recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
            EngineStore.save()
        }
        game = null
        phase = InvPhase.SETUP
    }

    fun switchTo(mode: TrainingMode) {
        if (phase == InvPhase.RUNNING) abandonRun() else player.stop()
        onSwitchMode(mode)
    }

    // A game the system reclaimed mid-way cannot resume — the engine died with
    // the process — but its score need not die with it. Close it out from the
    // saved tally, as finishGame would have, and land on setup.
    LaunchedEffect(Unit) {
        if (game != null) return@LaunchedEffect
        if (phase == InvPhase.RUNNING) {
            recordRun(runAttempts, runCorrect, ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0))
        }
        if (phase != InvPhase.SETUP) phase = InvPhase.SETUP
    }

    fun shoot(character: Char) {
        val g = game ?: return
        if (phase != InvPhase.RUNNING) return
        val now = System.currentTimeMillis()
        val lowest = g.lowest
        val shot = g.shoot(character)
        val hit = shot.invader
        if (hit != null) {
            val end = toneEnd.remove(hit.id)
            val ttr = if (end != null) ((now - end).coerceAtLeast(0)) / 1000.0 else 0.0
            engine.noteAttempt(hit.character, hit.character, ttr)
            tally(hit.character, true)
            lbItems.add(LeaderboardItem(hit.character.toString(), hit.character.toString(), Leaderboard.clampReaction((ttr * 1000).toLong()), wpm))
            if (Settings.hapticsEnabled) haptics.success()
            flash = (if (shot.waveCleared) "Wave ${g.wave}!" else "+${shot.points}") to now + 800
        } else {
            // A wrong key: confused with whatever was nearest the ground.
            if (lowest != null) {
                engine.noteAttempt(character.uppercaseChar(), lowest.character, 0.0)
                tally(lowest.character, false)
                lbItems.add(LeaderboardItem(lowest.character.toString(), character.uppercaseChar().toString(), 0, wpm))
            }
            if (Settings.hapticsEnabled) haptics.error()
            flash = "miss" to now + 600
        }
        field = g.invaders
        syncHud(g)
    }

    // The frame clock: each frame moves the game on by the real time elapsed.
    LaunchedEffect(phase, game) {
        val g = game ?: return@LaunchedEffect
        if (phase != InvPhase.RUNNING) return@LaunchedEffect
        var last = -1L
        while (true) {
            val nanos = withFrameNanos { it }
            if (last < 0) { last = nanos; continue }
            // Cap a long gap (the app was backgrounded) so the field does not
            // empty onto the ground in one step.
            val dt = ((nanos - last) / 1e9).coerceAtMost(0.1)
            last = nanos
            var over = false
            for (event in g.advance(dt)) {
                when (event) {
                    is InvadersEvent.Spawned -> if (input == InvadersInput.ICR) {
                        // At the ramp's current speed (#178), not the session
                        // timing: a single character has no gaps for
                        // Farnsworth to stretch.
                        val secs = player.replaySound(
                            MorseItem.Playable.Text(event.invader.character.toString()),
                            Settings.sidetoneHz, MorseTiming(g.currentWpm)
                        )
                        toneEnd[event.invader.id] = System.currentTimeMillis() + (secs * 1000).toLong()
                    }
                    is InvadersEvent.Escaped -> {
                        toneEnd.remove(event.invader.id)
                        engine.noteMiss(event.invader.character)
                        tally(event.invader.character, false)
                        lbItems.add(LeaderboardItem(event.invader.character.toString(), "", 0, wpm))
                        if (Settings.hapticsEnabled) haptics.error()
                        flash = "${event.invader.character} got through" to System.currentTimeMillis() + 1000
                    }
                    InvadersEvent.GameOver -> over = true
                }
            }
            field = g.invaders
            syncHud(g)
            if (over) { finishGame(); return@LaunchedEffect }
        }
    }

    // Keying: the first finalised character is the shot.
    LaunchedEffect(keyer.decodedText, keyer.isKeying, phase) {
        if (phase != InvPhase.RUNNING || input != InvadersInput.KEYING || keyer.isKeying) return@LaunchedEffect
        val ch = keyer.decodedText.trim().firstOrNull() ?: return@LaunchedEffect
        keyer.clear()
        shoot(ch)
    }

    // Hardware keyboard (ICR): the character key is the shot.
    val hwFocus = remember { FocusRequester() }
    LaunchedEffect(phase) { if (phase == InvPhase.RUNNING) hwFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hwFocus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || phase != InvPhase.RUNNING || input != InvadersInput.ICR) return@onKeyEvent false
                val ch = event.utf16CodePoint.takeIf { it > 0 }?.toChar()?.uppercaseChar() ?: return@onKeyEvent false
                if (ch !in characterPool()) return@onKeyEvent false
                shoot(ch)
                true
            }
            .focusable()
    ) {
        when (phase) {
            InvPhase.SETUP -> {
                BackHandler { onBack() }
                InvadersSetup(
                    input = input, onInput = { input = it },
                    characterSet = characterSet, onCharacterSet = { characterSet = it },
                    pool = characterPool(),
                    difficulty = difficulty, onDifficulty = { difficulty = it },
                    onStart = { startGame() },
                    onBack = onBack,
                    onSwitchMode = ::switchTo
                )
            }
            InvPhase.RUNNING -> {
                BackHandler { abandonRun() }
                InvadersRun(
                    input = input,
                    field = field,
                    score = score, wave = wave, lives = lives, multiplier = multiplier, wpm = wpm,
                    flash = flash,
                    pool = characterPool(),
                    decoded = keyer.decodedText,
                    keyPressed = keyPressed,
                    midiDevice = midiDevice,
                    onKey = { down -> keyPressed = down; keyer.touchKey(down) },
                    onShoot = { shoot(it) },
                    onReplay = { column ->
                        val g = game ?: return@InvadersRun
                        val nearest = g.invaders.minByOrNull { abs(it.column - column) } ?: return@InvadersRun
                        player.replaySound(MorseItem.Playable.Text(nearest.character.toString()), Settings.sidetoneHz, MorseTiming(g.currentWpm))
                    },
                    onEnd = { abandonRun() },
                    onSwitchMode = ::switchTo
                )
            }
            InvPhase.OVER -> {
                BackHandler { onBack() }
                InvadersOver(
                    score = score, wave = wave, bestCombo = bestCombo,
                    accuracy = if (runAttempts == 0) 0.0 else runCorrect.toDouble() / runAttempts,
                    bestWpm = if (input == InvadersInput.ICR) bestWpm else null,
                    leaderboard = lbLine,
                    onAgain = { startGame() },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun InvadersSetup(
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
            Text(stringResource(R.string.mode_invaders), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.INVADERS, onSwitchMode)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.invaders_blurb), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            InvLabel(stringResource(R.string.invaders_how_to_answer))
            InvPills(InvadersInput.entries.map { it to it.label }, input, onInput)
            Text(input.blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            InvLabel(stringResource(R.string.invaders_characters))
            InvPills(InvadersCharacterSet.entries.map { it to it.label }, characterSet, onCharacterSet)
            Text(pool.joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            InvLabel(stringResource(R.string.invaders_difficulty))
            InvPills(InvadersDifficulty.entries.map { it to it.label }, difficulty, onDifficulty)
            // The ramp in words (#178): where a game opens and where it climbs
            // to. Keying mode sends nothing, so it names the decoder speed.
            val target = Settings.characterWpm.roundToInt()
            val start = InvadersGame.rampStart(Settings.characterWpm).roundToInt()
            val speedNote = when {
                input == InvadersInput.KEYING -> stringResource(R.string.invaders_keying_speed_note, target)
                start >= target -> stringResource(R.string.invaders_speed_flat_note, target)
                else -> stringResource(
                    R.string.invaders_speed_ramp_note,
                    start, InvadersGame.rampStepUp.roundToInt(), InvadersGame.hitsPerRampStep,
                    InvadersGame.rampHold, target, InvadersGame.rampStepDown.roundToInt()
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
private fun InvadersRun(
    input: InvadersInput,
    field: List<Invader>,
    score: Int, wave: Int, lives: Int, multiplier: Int, wpm: Int,
    flash: Pair<String, Long>?,
    pool: List<Char>,
    decoded: String,
    keyPressed: Boolean,
    midiDevice: String?,
    onKey: (Boolean) -> Unit,
    onShoot: (Char) -> Unit,
    onReplay: (Int) -> Unit,
    onEnd: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    val measurer = rememberTextMeasurer()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.invaders_end_game), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.INVADERS, onSwitchMode)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            InvStat(stringResource(R.string.invaders_score), score.toString())
            InvStat(stringResource(R.string.invaders_wave), wave.toString())
            InvStat(stringResource(R.string.invaders_lives), "♥".repeat(lives) + "♡".repeat((3 - lives).coerceAtLeast(0)))
            InvStat(stringResource(R.string.invaders_combo_label), "×$multiplier")
            if (input == InvadersInput.ICR) InvStat(stringResource(R.string.invaders_wpm), wpm.toString())
        }
        Spacer(Modifier.height(10.dp))
        val labelStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Brand.textPrimary)
        val flashStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
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
                        onReplay((offset.x / size.width * INVADER_COLUMNS).toInt().coerceIn(0, INVADER_COLUMNS - 1))
                    }
                }
        ) {
            val ground = size.height - 18.dp.toPx()
            drawLine(Brand.teal.copy(alpha = 0.6f), Offset(0f, ground), Offset(size.width, ground), strokeWidth = 2.dp.toPx())
            val colWidth = size.width / INVADER_COLUMNS
            // Keying shows the character above its sprite, so the field
            // starts lower to leave the label room at the top.
            val top = (if (input == InvadersInput.KEYING) 30.dp else 18.dp).toPx()
            val reach = 18.dp.toPx()
            // The alien (#179) instead of a "?", which read as a literal
            // character: 3 dp a pixel, 33×24 dp, about what the glyph took.
            val px = 3.dp.toPx()
            fun drawSprite(cx: Float, cy: Float) {
                val ox = cx - INVADER_SPRITE[0].length * px / 2
                val oy = cy - INVADER_SPRITE.size * px / 2
                INVADER_SPRITE.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        if (cell == '#') drawRect(Brand.tealBright, Offset(ox + c * px, oy + r * px), Size(px, px))
                    }
                }
            }
            for (inv in field) {
                val x = (inv.column + 0.5f) * colWidth
                val y = top + inv.progress.toFloat() * (ground - top - reach)
                // ICR keeps the character to the ear; keying shows it directly
                // above the sprite to key.
                if (input == InvadersInput.KEYING) {
                    drawSprite(x, y + 6.dp.toPx())
                    val measured = measurer.measure(inv.character.toString(), labelStyle)
                    drawText(measured, topLeft = Offset(x - measured.size.width / 2, y - 14.dp.toPx() - measured.size.height / 2))
                } else {
                    drawSprite(x, y)
                }
            }
            if (flash != null && flash.second > System.currentTimeMillis()) {
                val measured = measurer.measure(flash.first, flashStyle)
                drawText(measured, topLeft = Offset(size.width / 2 - measured.size.width / 2, 6.dp.toPx()))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (input == InvadersInput.ICR) {
            Text(stringResource(R.string.invaders_tap_to_replay), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
            Spacer(Modifier.height(6.dp))
            InvadersKeyRows(pool = pool, onShoot = onShoot)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.invaders_key_the_lowest), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
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
 * The hear-it/type-it keyboard (#178): [InvadersKeyboard.rows] laid out QWERTY —
 * a digit row when the pool has digits, the three letter rows always, any
 * punctuation below. A key outside the pool stays in place, dimmed and dead,
 * so the layout never shifts as the Koch set grows; the hardware-keyboard
 * handler in [InvadersScreen] presses the live ones too. Ten keys across at
 * equal width, so the shorter rows sit centred the way a keyboard's do.
 */
@Composable
private fun InvadersKeyRows(pool: List<Char>, onShoot: (Char) -> Unit) {
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
private fun InvadersOver(
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
            Text(stringResource(R.string.invaders_game_over), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                InvStat(stringResource(R.string.invaders_score), score.toString())
                InvStat(stringResource(R.string.invaders_wave), wave.toString())
                InvStat(stringResource(R.string.invaders_accuracy), "${(accuracy * 100).roundToInt()}%")
                InvStat(stringResource(R.string.invaders_best_combo), bestCombo.toString())
            }
            // The speed the ramp reached (#178); null in keying mode, which sends nothing.
            if (bestWpm != null) {
                Text(stringResource(R.string.invaders_speed_reached, bestWpm), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            }
            // The shared leaderboard's reply, or why the game was not posted; nothing when not opted in.
            if (leaderboard != null) {
                Text(leaderboard, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
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
private fun InvStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InvLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
}

@Composable
private fun <T> InvPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
