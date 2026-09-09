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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
import app.anothermorsetrainer.morsekit.DefenderAsset
import app.anothermorsetrainer.morsekit.DefenderAttacker
import app.anothermorsetrainer.morsekit.DefenderEvent
import app.anothermorsetrainer.morsekit.DefenderGame
import app.anothermorsetrainer.morsekit.DefenderInput
import app.anothermorsetrainer.morsekit.DefenderRoute
import app.anothermorsetrainer.morsekit.InvadersCharacterSet
import app.anothermorsetrainer.morsekit.InvadersDifficulty
import app.anothermorsetrainer.morsekit.InvadersKeyboard
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.SessionRecord
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DefPhase { SETUP, RUNNING, OVER }

/**
 * A 9×7 attacker, drawn from this bitmap so there is no image asset; `#` is
 * a lit pixel. The same table is in `DefenderView.swift`.
 */
private val DEFENDER_ATTACKER_SPRITE = listOf(
    "....#....",
    "...###...",
    "..#####..",
    ".##.#.##.",
    "#########",
    "..#...#..",
    ".#.....#."
)

/** A 9×6 city block for a standing asset. */
private val DEFENDER_CITY_SPRITE = listOf(
    "....#....",
    "..#.#.#..",
    "..#####..",
    ".#######.",
    "#########",
    "#########"
)

/** The asset row's height at the bottom of the field: sprite plus label. */
private val DEFENDER_ASSET_ROW = 54.dp

private val DEFENDER_ATTACKER_COLOR = Color(0xFFFF9800)

/**
 * Morse Defender (#188): assets — cities and ships, each with a callsign —
 * line the bottom of the field; attackers come down from the top, each
 * sending the callsign of the asset it is heading for. The learner copies the
 * callsign and routes the defence by tapping that asset, or by typing the
 * callsign. The rules live in [DefenderGame]; this screen is the frame clock
 * ([withFrameNanos]), the sound, the input and the drawing.
 *
 * Twin of the iOS `DefenderView.swift`. Session state survives process death
 * as a score, not a game: the tally rides `rememberSaveable` and a reclaimed
 * run is closed out to [Stats] on restore, like Invaders and Rapid Fire.
 */
@Composable
fun DefenderScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val prefs = remember { context.getSharedPreferences("amt_defender", android.content.Context.MODE_PRIVATE) }

    var phase by rememberSaveable { mutableStateOf(DefPhase.SETUP) }
    // Setup choices persist across launches, like every other mode's.
    var input by rememberSaveable {
        mutableStateOf(runCatching { DefenderInput.valueOf(prefs.getString("input", "") ?: "") }.getOrDefault(DefenderInput.TAP))
    }
    var difficulty by rememberSaveable {
        mutableStateOf(runCatching { InvadersDifficulty.valueOf(prefs.getString("difficulty", "") ?: "") }.getOrDefault(InvadersDifficulty.NORMAL))
    }
    var callsigns by rememberSaveable {
        mutableStateOf(runCatching { InvadersCharacterSet.valueOf(prefs.getString("callsigns", "") ?: "") }.getOrDefault(InvadersCharacterSet.ACTIVE))
    }
    LaunchedEffect(input, difficulty, callsigns) {
        prefs.edit().putString("input", input.name).putString("difficulty", difficulty.name)
            .putString("callsigns", callsigns.name).apply()
    }

    // The shared Koch ladder: its active set spells the synthetic callsigns,
    // and its stats and confusion matrix take every character copied.
    val track = remember { EngineStore.characters() }
    val engine = track.engine

    // Run state. The game dies with the process; the tally below does not.
    var game by remember { mutableStateOf<DefenderGame?>(null) }
    var attackers by remember { mutableStateOf<List<DefenderAttacker>>(emptyList()) }
    var assets by remember { mutableStateOf<List<DefenderAsset>>(emptyList()) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var wave by rememberSaveable { mutableIntStateOf(1) }
    var live by rememberSaveable { mutableIntStateOf(0) }
    var total by rememberSaveable { mutableIntStateOf(0) }
    var multiplier by remember { mutableIntStateOf(1) }
    var bestCombo by rememberSaveable { mutableIntStateOf(0) }
    var wpm by remember { mutableIntStateOf(0) }
    var bestWpm by rememberSaveable { mutableIntStateOf(0) }
    var runAttempts by rememberSaveable { mutableIntStateOf(0) }
    var runCorrect by rememberSaveable { mutableIntStateOf(0) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }
    var typed by remember { mutableStateOf("") }
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var struck by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    // When each attacker's callsign finished sounding, by id, so a hit's
    // time-to-recognize runs from the end of the tone.
    val toneEnd = remember { HashMap<Int, Long>() }
    val charResults = remember { HashMap<Char, IntArray>() }   // attempts, correct

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun syncHud(g: DefenderGame) {
        score = g.score
        wave = g.wave
        live = g.liveAssets
        total = g.assets.size
        multiplier = g.multiplier
        bestCombo = g.bestCombo
        wpm = g.currentWpm.roundToInt()
        bestWpm = g.bestWpm.roundToInt()
        runAttempts = g.hits + g.misses
        runCorrect = g.hits
        lastSeenMs = System.currentTimeMillis()
    }

    fun recordRun(attempts: Int, correct: Int, seconds: Int) {
        if (attempts <= 0) return
        val results = charResults.map { (ch, a) -> SessionRecord.CharResult(ch.toString(), a[0], a[1], null) }
        Stats.record(
            mode = "Morse Defender", attempts = attempts, correct = correct,
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

    /**
     * One routed defence, character by character, position by position: a
     * copy of K1AB as K1AR records B confused with R; a shorter answer leaves
     * the unanswered characters as plain misses. Twin of the iOS
     * `AppModel.noteDefenderRoute`.
     */
    fun noteRoute(target: String, chosen: String, ttr: Double) {
        val sent = target.uppercase()
        val answer = chosen.uppercase()
        sent.forEachIndexed { i, ch ->
            val correct = if (i < answer.length) {
                engine.noteAttempt(answer[i], ch, ttr)
            } else {
                engine.noteMiss(ch)
                false
            }
            tally(ch, correct)
        }
    }

    /** An attacker reached its asset unanswered: a miss for every character it sent. */
    fun noteStrike(callsign: String) {
        for (ch in callsign.uppercase()) {
            engine.noteMiss(ch)
            tally(ch, false)
        }
    }

    fun sendCallsign(g: DefenderGame, callsign: String): Double {
        // At the ramp's current speed, with Farnsworth spacing honoured when
        // the switch is on: a callsign has gaps to stretch.
        val timing = DefenderGame.sendTiming(g.currentWpm, if (Settings.farnsworthEnabled) Settings.effectiveWpm else null)
        return player.replaySound(MorseItem.Playable.Text(callsign), Settings.sidetoneHz, timing)
    }

    fun startGame() {
        val g = DefenderGame(DefenderGame.Config(
            characters = engine.activeCharacters, callsigns = callsigns, difficulty = difficulty,
            characterWpm = Settings.characterWpm
        ))
        game = g
        attackers = emptyList()
        assets = g.assets
        toneEnd.clear()
        charResults.clear()
        typed = ""
        flash = null
        struck = null
        startedAtMs = System.currentTimeMillis()
        syncHud(g)
        phase = DefPhase.RUNNING
    }

    fun finishGame() {
        val g = game ?: return
        player.stop()
        syncHud(g)
        recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
        EngineStore.save()
        phase = DefPhase.OVER
    }

    /** End a run early (Back, the mode switcher): record what was played. */
    fun abandonRun() {
        val g = game
        player.stop()
        if (g != null && phase == DefPhase.RUNNING) {
            recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
            EngineStore.save()
        }
        game = null
        phase = DefPhase.SETUP
    }

    fun switchTo(mode: TrainingMode) {
        if (phase == DefPhase.RUNNING) abandonRun() else player.stop()
        onSwitchMode(mode)
    }

    // A game the system reclaimed mid-way cannot resume — the engine died with
    // the process — but its score need not die with it. Close it out from the
    // saved tally, as finishGame would have, and land on setup.
    LaunchedEffect(Unit) {
        if (game != null) return@LaunchedEffect
        if (phase == DefPhase.RUNNING) {
            recordRun(runAttempts, runCorrect, ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0))
        }
        if (phase != DefPhase.SETUP) phase = DefPhase.SETUP
    }

    fun settle(g: DefenderGame, result: DefenderRoute, chosen: String, nearest: DefenderAttacker?) {
        val now = System.currentTimeMillis()
        val hit = result.attacker
        if (hit != null) {
            val end = toneEnd.remove(hit.id)
            val ttr = if (end != null) ((now - end).coerceAtLeast(0)) / 1000.0 else 0.0
            noteRoute(hit.callsign, hit.callsign, ttr)
            if (Settings.hapticsEnabled) haptics.success()
            val fresh = result.reinforced
            flash = when {
                fresh != null -> "Wave ${g.wave}! ${fresh.callsign} joins" to now + 1200
                result.waveCleared -> "Wave ${g.wave}!" to now + 800
                else -> "+${result.points}" to now + 800
            }
        } else {
            // A wrong route: confused with whatever was nearest arrival.
            if (nearest != null) noteRoute(nearest.callsign, chosen, 0.0)
            if (Settings.hapticsEnabled) haptics.error()
            flash = "miss" to now + 600
        }
        attackers = g.attackers
        assets = g.assets
        syncHud(g)
    }

    /** Tap input: the defence goes to [asset]. */
    fun routeTo(asset: DefenderAsset) {
        val g = game ?: return
        if (phase != DefPhase.RUNNING) return
        val nearest = g.nearestArrival
        settle(g, g.route(asset.id), asset.callsign, nearest)
    }

    /**
     * Typed input: a key extends the copy; the defence routes itself the
     * moment the copy matches a standing asset, and a copy that has run past
     * the longest callsign without matching is a wasted shot.
     */
    fun typedKey(ch: Char) {
        val g = game ?: return
        if (phase != DefPhase.RUNNING) return
        typed += ch.uppercaseChar()
        val matches = g.assets.any { it.isAlive && it.callsign == typed }
        if (!matches && typed.length < maxOf(1, g.longestLiveCallsign)) return
        val nearest = g.nearestArrival
        val attempt = typed
        typed = ""
        settle(g, g.route(attempt), attempt, nearest)
    }

    // The frame clock: each frame moves the game on by the real time elapsed.
    LaunchedEffect(phase, game) {
        val g = game ?: return@LaunchedEffect
        if (phase != DefPhase.RUNNING) return@LaunchedEffect
        var last = -1L
        while (true) {
            val nanos = withFrameNanos { it }
            if (last < 0) { last = nanos; continue }
            // Cap a long gap (the app was backgrounded) so the field does not
            // empty onto the assets in one step.
            val dt = ((nanos - last) / 1e9).coerceAtMost(0.1)
            last = nanos
            var over = false
            for (event in g.advance(dt)) {
                when (event) {
                    is DefenderEvent.Launched -> {
                        val secs = sendCallsign(g, event.attacker.callsign)
                        toneEnd[event.attacker.id] = System.currentTimeMillis() + (secs * 1000).toLong()
                    }
                    is DefenderEvent.Struck -> {
                        toneEnd.remove(event.attacker.id)
                        noteStrike(event.attacker.callsign)
                        if (Settings.hapticsEnabled) haptics.error()
                        val now = System.currentTimeMillis()
                        struck = event.asset.id to now + 800
                        flash = "${event.asset.callsign} lost" to now + 1000
                    }
                    DefenderEvent.GameOver -> over = true
                }
            }
            attackers = g.attackers
            assets = g.assets
            syncHud(g)
            if (over) { finishGame(); return@LaunchedEffect }
        }
    }

    // Hardware keyboard (typed input): a character key extends the copy,
    // Backspace shortens it.
    val hwFocus = remember { FocusRequester() }
    LaunchedEffect(phase) { if (phase == DefPhase.RUNNING) hwFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hwFocus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || phase != DefPhase.RUNNING || input != DefenderInput.TYPED) return@onKeyEvent false
                if (event.key == Key.Backspace) {
                    if (typed.isNotEmpty()) typed = typed.dropLast(1)
                    return@onKeyEvent true
                }
                val g = game ?: return@onKeyEvent false
                val ch = event.utf16CodePoint.takeIf { it > 0 }?.toChar()?.uppercaseChar() ?: return@onKeyEvent false
                if (ch !in g.keyboardPool) return@onKeyEvent false
                typedKey(ch)
                true
            }
            .focusable()
    ) {
        when (phase) {
            DefPhase.SETUP -> {
                BackHandler { onBack() }
                DefenderSetup(
                    input = input, onInput = { input = it },
                    callsigns = callsigns, onCallsigns = { callsigns = it },
                    alphabet = DefenderGame.callsignAlphabet(engine.activeCharacters),
                    difficulty = difficulty, onDifficulty = { difficulty = it },
                    onStart = { startGame() },
                    onBack = onBack,
                    onSwitchMode = ::switchTo
                )
            }
            DefPhase.RUNNING -> {
                BackHandler { abandonRun() }
                DefenderRun(
                    input = input,
                    attackers = attackers,
                    assets = assets,
                    score = score, wave = wave, live = live, total = total, multiplier = multiplier, wpm = wpm,
                    flash = flash,
                    struck = struck,
                    typed = typed,
                    pool = game?.keyboardPool ?: emptyList(),
                    onRoute = { routeTo(it) },
                    onType = { typedKey(it) },
                    onBackspace = { if (typed.isNotEmpty()) typed = typed.dropLast(1) },
                    onReplay = { column ->
                        val g = game ?: return@DefenderRun
                        val nearest = g.attackers.minByOrNull { abs(it.column - column) } ?: return@DefenderRun
                        sendCallsign(g, nearest.callsign)
                    },
                    onEnd = { abandonRun() },
                    onSwitchMode = ::switchTo
                )
            }
            DefPhase.OVER -> {
                BackHandler { onBack() }
                DefenderOver(
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
private fun DefenderSetup(
    input: DefenderInput, onInput: (DefenderInput) -> Unit,
    callsigns: InvadersCharacterSet, onCallsigns: (InvadersCharacterSet) -> Unit,
    alphabet: List<Char>,
    difficulty: InvadersDifficulty, onDifficulty: (InvadersDifficulty) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_defender), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.DEFENDER, onSwitchMode)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.defender_blurb), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            DefLabel(stringResource(R.string.defender_how_to_route))
            DefPills(DefenderInput.entries.map { it to it.label }, input, onInput)
            Text(input.blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            DefLabel(stringResource(R.string.defender_callsigns))
            val active = stringResource(R.string.defender_callsigns_active)
            val real = stringResource(R.string.defender_callsigns_full)
            DefPills(
                InvadersCharacterSet.entries.map { it to (if (it == InvadersCharacterSet.ACTIVE) active else real) },
                callsigns, onCallsigns
            )
            val note = when (callsigns) {
                InvadersCharacterSet.ACTIVE -> stringResource(R.string.defender_callsigns_active_note, alphabet.joinToString(" "))
                InvadersCharacterSet.FULL -> stringResource(R.string.defender_callsigns_full_note)
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            DefLabel(stringResource(R.string.defender_difficulty))
            DefPills(InvadersDifficulty.entries.map { it to it.label }, difficulty, onDifficulty)
            // The ramp in words: where a game opens and where it climbs to,
            // with Farnsworth spacing honoured when it is on.
            val target = Settings.characterWpm.roundToInt()
            val start = DefenderGame.rampStart(Settings.characterWpm).roundToInt()
            val farnsworth = if (Settings.farnsworthEnabled) {
                " " + stringResource(R.string.defender_farnsworth_note, Settings.effectiveWpm.roundToInt())
            } else ""
            val speedNote = if (start >= target) {
                stringResource(R.string.defender_speed_flat_note, target)
            } else {
                stringResource(
                    R.string.defender_speed_ramp_note,
                    start, DefenderGame.rampStep.roundToInt(), DefenderGame.hitsPerRampStep, target
                )
            }
            Text(speedNote + farnsworth, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.defender_start), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DefenderRun(
    input: DefenderInput,
    attackers: List<DefenderAttacker>,
    assets: List<DefenderAsset>,
    score: Int, wave: Int, live: Int, total: Int, multiplier: Int, wpm: Int,
    flash: Pair<String, Long>?,
    struck: Pair<Int, Long>?,
    typed: String,
    pool: List<Char>,
    onRoute: (DefenderAsset) -> Unit,
    onType: (Char) -> Unit,
    onBackspace: () -> Unit,
    onReplay: (Int) -> Unit,
    onEnd: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val columns = DefenderGame.defaultColumns
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.defender_end_game), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.DEFENDER, onSwitchMode)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DefStat(stringResource(R.string.defender_score), score.toString())
            DefStat(stringResource(R.string.defender_wave), wave.toString())
            DefStat(stringResource(R.string.defender_assets), "$live/$total")
            DefStat(stringResource(R.string.defender_combo_label), "×$multiplier")
            DefStat(stringResource(R.string.defender_wpm), wpm.toString())
        }
        Spacer(Modifier.height(10.dp))
        val callStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Brand.textPrimary)
        val rubbleStyle = callStyle.copy(color = Brand.textSecondary.copy(alpha = 0.5f))
        val flashStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Brand.cornerRadius))
                .background(Brand.navyElevated)
                .border(1.dp, Brand.hairline, RoundedCornerShape(Brand.cornerRadius))
                .pointerInput(input, assets) {
                    detectTapGestures { offset ->
                        val rowTop = size.height - DEFENDER_ASSET_ROW.toPx()
                        if (offset.y >= rowTop) {
                            // A tap in the asset row routes the defence there (tap input).
                            if (input != DefenderInput.TAP || assets.isEmpty()) return@detectTapGestures
                            val slot = size.width.toFloat() / assets.size
                            val index = (offset.x / slot).toInt().coerceIn(0, assets.size - 1)
                            onRoute(assets[index])
                        } else {
                            onReplay((offset.x / size.width * columns).toInt().coerceIn(0, columns - 1))
                        }
                    }
                }
        ) {
            val rowTop = size.height - DEFENDER_ASSET_ROW.toPx()
            drawLine(Brand.teal.copy(alpha = 0.4f), Offset(0f, rowTop), Offset(size.width, rowTop), strokeWidth = 1.dp.toPx())
            val px = 3.dp.toPx()
            fun drawSprite(sprite: List<String>, cx: Float, cy: Float, color: Color) {
                val ox = cx - sprite[0].length * px / 2
                val oy = cy - sprite.size * px / 2
                sprite.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        if (cell == '#') drawRect(color, Offset(ox + c * px, oy + r * px), Size(px, px))
                    }
                }
            }
            // Assets along the bottom, evenly spaced; rubble stays in place.
            val now = System.currentTimeMillis()
            val slot = size.width / maxOf(1, assets.size)
            assets.forEachIndexed { i, asset ->
                val x = (i + 0.5f) * slot
                if (asset.isAlive) {
                    drawSprite(DEFENDER_CITY_SPRITE, x, rowTop + 16.dp.toPx(), Brand.tealBright)
                    val measured = measurer.measure(asset.callsign, callStyle)
                    drawText(measured, topLeft = Offset(x - measured.size.width / 2, rowTop + 40.dp.toPx() - measured.size.height / 2))
                } else {
                    drawRect(Brand.textSecondary.copy(alpha = 0.4f), Offset(x - 12.dp.toPx(), rowTop + 22.dp.toPx()), Size(24.dp.toPx(), 6.dp.toPx()))
                    val measured = measurer.measure(asset.callsign, rubbleStyle)
                    drawText(measured, topLeft = Offset(x - measured.size.width / 2, rowTop + 40.dp.toPx() - measured.size.height / 2))
                }
                if (struck != null && struck.first == asset.id && struck.second > now) {
                    drawCircle(Color.Red, radius = 16.dp.toPx(), center = Offset(x, rowTop + 16.dp.toPx()), style = Stroke(width = 2.dp.toPx()))
                }
            }
            // Attackers descend their columns; nothing on screen says where
            // each is heading — only the callsign it sent does.
            val colWidth = size.width / columns
            val top = 18.dp.toPx()
            for (a in attackers) {
                val x = (a.column + 0.5f) * colWidth
                val y = top + a.progress.toFloat() * (rowTop - top - 14.dp.toPx())
                drawSprite(DEFENDER_ATTACKER_SPRITE, x, y, DEFENDER_ATTACKER_COLOR)
            }
            if (flash != null && flash.second > now) {
                val measured = measurer.measure(flash.first, flashStyle)
                drawText(measured, topLeft = Offset(size.width / 2 - measured.size.width / 2, 6.dp.toPx()))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (input == DefenderInput.TAP) {
            Text(stringResource(R.string.defender_tap_hint), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.defender_tap_to_replay), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(typed.ifEmpty { "—" }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Brand.textPrimary)
            }
            Spacer(Modifier.height(6.dp))
            DefenderKeyRows(pool = pool, onType = onType, onBackspace = onBackspace)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Typed input: the QWERTY keyboard from Invaders (#178) — [InvadersKeyboard.rows]
 * laid out for the callsign alphabet — plus a backspace. A key outside the
 * alphabet stays in place, dimmed and dead; the hardware-keyboard handler in
 * [DefenderScreen] presses the live ones too.
 */
@Composable
private fun DefenderKeyRows(pool: List<Char>, onType: (Char) -> Unit, onBackspace: () -> Unit) {
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
                                .clickable(enabled = enabled) { onType(ch) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    text = "⌫",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = Brand.textPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .width(keyWidth * 2 + gap)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brand.navyRaised)
                        .clickable { onBackspace() }
                        .padding(vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun DefenderOver(
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
            Text(stringResource(R.string.defender_game_over), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DefStat(stringResource(R.string.defender_score), score.toString())
                DefStat(stringResource(R.string.defender_wave), wave.toString())
                DefStat(stringResource(R.string.defender_accuracy), "${(accuracy * 100).roundToInt()}%")
                DefStat(stringResource(R.string.defender_best_combo), bestCombo.toString())
            }
            Text(stringResource(R.string.defender_speed_reached, bestWpm), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Button(
                onClick = onAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.defender_play_again), fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

@Composable
private fun DefStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DefLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
}

@Composable
private fun <T> DefPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
