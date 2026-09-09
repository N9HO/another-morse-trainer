package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.anothermorsetrainer.morsekit.DungeonCharacterOutcome
import app.anothermorsetrainer.morsekit.DungeonEvent
import app.anothermorsetrainer.morsekit.DungeonGame
import app.anothermorsetrainer.morsekit.DungeonMonster
import app.anothermorsetrainer.morsekit.DungeonMonsterKind
import app.anothermorsetrainer.morsekit.DungeonPool
import app.anothermorsetrainer.morsekit.DungeonSpell
import app.anothermorsetrainer.morsekit.DungeonSpells
import app.anothermorsetrainer.morsekit.InvadersCharacterSet
import app.anothermorsetrainer.morsekit.InvadersDifficulty
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.LeaderboardItem
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.SessionRecord
import kotlin.math.roundToInt

private enum class DgnPhase { SETUP, RUNNING, OVER }

/**
 * The monsters and the hero, drawn from bitmaps so there is no image asset;
 * `#` is a lit pixel. The same tables are in `DungeonView.swift`.
 */
private object DungeonSprites {
    val slime = listOf(
        ".........",
        "...###...",
        "..#####..",
        ".#######.",
        ".##.#.##.",
        ".#######.",
        ".#######.",
        "..#####.."
    )
    val bat = listOf(
        "#.......#",
        "##.....##",
        "###...###",
        "#########",
        ".#######.",
        "..##.##..",
        "...#.#...",
        "........."
    )
    val skeleton = listOf(
        "...###...",
        "..#####..",
        "..#.#.#..",
        "..#####..",
        "...###...",
        ".#.###.#.",
        "..#####..",
        "...#.#...",
        "...#.#..."
    )
    val ghost = listOf(
        "...###...",
        "..#####..",
        ".###.#.#.",
        ".#######.",
        ".#######.",
        ".#######.",
        ".#######.",
        ".#.#.#.#."
    )
    val dragon = listOf(
        ".....##......",
        "....####.....",
        "...######.#..",
        "..##.####.##.",
        ".############",
        ".###########.",
        "..##.####.#..",
        "...#..#..#...",
        ".........#..."
    )
    val hero = listOf(
        "..###..",
        "..###..",
        ".#####.",
        "#.###.#",
        "#.###.#",
        "..###..",
        "..#.#..",
        "..#.#..",
        ".##.##."
    )

    fun bitmap(kind: DungeonMonsterKind?): List<String> = when (kind) {
        DungeonMonsterKind.SLIME -> slime
        DungeonMonsterKind.BAT -> bat
        DungeonMonsterKind.SKELETON -> skeleton
        DungeonMonsterKind.GHOST -> ghost
        DungeonMonsterKind.DRAGON -> dragon
        null -> hero
    }
}

/**
 * CW Dungeon (#186, #170): a small roguelike where monsters cast spell words
 * in Morse and the learner keys the counter word before the attack lands. The
 * rules live in [DungeonGame]; this screen is the frame clock
 * ([withFrameNanos]), the sound, the key and the drawing.
 *
 * Twin of the iOS `DungeonView.swift`. Session state survives process death
 * as a score, not a game: the tally rides `rememberSaveable` and a reclaimed
 * run is closed out to [Stats] on restore, like Invaders and Rapid Fire.
 */
@Composable
fun DungeonScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val prefs = remember { context.getSharedPreferences("amt_dungeon", android.content.Context.MODE_PRIVATE) }

    var phase by rememberSaveable { mutableStateOf(DgnPhase.SETUP) }
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

    // The shared Koch ladder: its active set picks the spell book, and its
    // stats and confusion matrix take every keyed character.
    val track = remember { EngineStore.characters() }
    val engine = track.engine

    /** The pool, letters first then digits (the recognition chart's order). */
    fun characterPool(): List<Char> = when (characterSet) {
        InvadersCharacterSet.ACTIVE -> engine.activeCharacters
        InvadersCharacterSet.FULL -> MorseCode.kochOrder.filter { it.isLetterOrDigit() }
    }.map { it.toString() }.sortedWith(SessionRecord.characterOrder).map { it[0] }

    /** The spell book for the chosen set: what it spells, or the starters. */
    fun spellPool(): DungeonPool = DungeonSpells.pool(characterPool())

    // Run state. The game dies with the process; the tally below does not.
    var game by remember { mutableStateOf<DungeonGame?>(null) }
    var monsters by remember { mutableStateOf<List<DungeonMonster>>(emptyList()) }
    var casterId by remember { mutableStateOf<Int?>(null) }
    var sending by remember { mutableStateOf(false) }
    var windowFraction by remember { mutableStateOf<Float?>(null) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var room by rememberSaveable { mutableIntStateOf(1) }
    var lives by rememberSaveable { mutableIntStateOf(3) }
    var maxLives by rememberSaveable { mutableIntStateOf(3) }
    var multiplier by remember { mutableIntStateOf(1) }
    var bestCombo by rememberSaveable { mutableIntStateOf(0) }
    var roomsCleared by rememberSaveable { mutableIntStateOf(0) }
    var wpm by remember { mutableIntStateOf(0) }
    var bestWpm by rememberSaveable { mutableIntStateOf(0) }
    var runAttempts by rememberSaveable { mutableIntStateOf(0) }
    var runCorrect by rememberSaveable { mutableIntStateOf(0) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val charResults = remember { HashMap<Char, IntArray>() }   // attempts, correct
    // The shared leaderboard (docs/high-scores-design.md, step 2): the run's
    // registration and one item per shot or decision, each carrying the
    // ramp speed it was sent at (the HUD's `wpm`, synced before every item
    // resolves). Plain state, not saveable: a game the process lost has no
    // transcript and is never submitted.
    var lbRun by remember { mutableStateOf<LeaderboardClient.RunHandle?>(null) }
    val lbItems = remember { ArrayList<LeaderboardItem>() }
    var lbLine by remember { mutableStateOf<String?>(null) }

    // The key: the same decoder Sending Practice uses, plus a hardware key.
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    DisposableEffect(phase) {
        if (phase == DgnPhase.RUNNING) {
            keyer.scope = scope
            keyer.start()
            midi.start(onKey = { down -> keyer.touchKey(down) }, onConnected = { midiDevice = it })
        }
        onDispose { midi.stop(); keyer.stop() }
    }

    fun syncScene(g: DungeonGame) {
        monsters = g.monsters
        val cast = g.pendingCast
        casterId = cast?.monsterId
        sending = g.isSending
        windowFraction = if (cast != null && !g.isSending) {
            ((g.windowRemaining ?: 0.0) / maxOf(0.001, cast.window)).coerceIn(0.0, 1.0).toFloat()
        } else null
    }

    fun syncHud(g: DungeonGame) {
        score = g.score
        room = g.room
        lives = g.lives
        maxLives = g.maxLives
        multiplier = g.multiplier
        bestCombo = g.bestCombo
        roomsCleared = g.roomsCleared
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
        lbLine = context.getString(R.string.leaderboard_submitting)
        LeaderboardClient.submit(h, items) { lbLine = it }
    }

    fun recordRun(attempts: Int, correct: Int, seconds: Int) {
        if (attempts <= 0) return
        val results = charResults.map { (ch, a) -> SessionRecord.CharResult(ch.toString(), a[0], a[1], null) }
        Stats.record(
            mode = "CW Dungeon", attempts = attempts, correct = correct,
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

    /**
     * One keyed counter word, graded character by character: a match is a
     * correct recognition, a different character a miss confused with what
     * was keyed, a position nothing reached a plain miss — the same feed as
     * an Invaders shot.
     */
    fun noteOutcomes(outcomes: List<DungeonCharacterOutcome>) {
        for (o in outcomes) {
            val chosen = o.chosen
            if (chosen != null) {
                engine.noteAttempt(chosen, o.target, 0.0)
                tally(o.target, chosen == o.target)
            } else {
                engine.noteMiss(o.target)
                tally(o.target, false)
            }
        }
    }

    fun startGame() {
        val g = DungeonGame(DungeonGame.Config(
            spells = spellPool().spells, difficulty = difficulty,
            characterWpm = Settings.characterWpm,
            effectiveWpm = if (Settings.farnsworthEnabled) Settings.effectiveWpm else null
        ))
        game = g
        charResults.clear()
        flash = null
        keyer.clear()
        startedAtMs = System.currentTimeMillis()
        lbItems.clear()
        lbLine = null
        lbRun = LeaderboardClient.beginRun(
            statsMode = "CW Dungeon",
            characterWpm = Settings.characterWpm.roundToInt(),
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt()
        )
        syncScene(g)
        syncHud(g)
        phase = DgnPhase.RUNNING
    }

    fun finishGame() {
        val g = game ?: return
        player.stop()
        syncHud(g)
        recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
        EngineStore.save()
        phase = DgnPhase.OVER
    }

    /** End a run early (Back, the mode switcher): record what was played. */
    fun abandonRun() {
        val g = game
        player.stop()
        if (g != null && phase == DgnPhase.RUNNING) {
            recordRun(g.hits + g.misses, g.hits, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())
            EngineStore.save()
        }
        game = null
        phase = DgnPhase.SETUP
    }

    fun switchTo(mode: TrainingMode) {
        if (phase == DgnPhase.RUNNING) abandonRun() else player.stop()
        onSwitchMode(mode)
    }

    // A game the system reclaimed mid-way cannot resume — the engine died with
    // the process — but its score need not die with it. Close it out from the
    // saved tally, as finishGame would have, and land on setup.
    LaunchedEffect(Unit) {
        if (game != null) return@LaunchedEffect
        if (phase == DgnPhase.RUNNING) {
            recordRun(runAttempts, runCorrect, ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0))
        }
        if (phase != DgnPhase.SETUP) phase = DgnPhase.SETUP
    }

    fun castWord(word: String) {
        val g = game ?: return
        if (phase != DgnPhase.RUNNING) return
        val result = g.cast(word) ?: return
        player.stop()
        noteOutcomes(DungeonGame.characterOutcomes(result.spell.counter, result.keyed))
        // The spell is the item: it is the Morse that actually played, so the
        // server's timing bound is honest. Countered in time, the answer is
        // the spell itself (a correct copy); keyed wrong or echoed, it is
        // what was keyed. Same rule on iOS.
        lbItems.add(LeaderboardItem(result.spell.spell, if (result.isCountered) result.spell.spell else result.keyed, 0, wpm))
        keyer.clear()
        val now = System.currentTimeMillis()
        if (result.isCountered) {
            if (Settings.hapticsEnabled) haptics.success()
            flash = when {
                result.roomCleared -> "Room ${g.room - 1} cleared! +${DungeonGame.roomClearBonus}" to now + 1200
                result.healed -> "+${result.points} and a life back" to now + 1000
                else -> "+${result.points}" to now + 800
            }
        } else {
            if (Settings.hapticsEnabled) haptics.error()
            flash = (if (result.isEcho) "That was the spell — key ${result.spell.counter}"
                     else "${result.keyed} is not ${result.spell.counter}") to now + 1200
        }
        syncScene(g)
        syncHud(g)
        if (result.gameOver) finishGame()
    }

    // The frame clock: each frame moves the game on by the real time elapsed.
    LaunchedEffect(phase, game) {
        val g = game ?: return@LaunchedEffect
        if (phase != DgnPhase.RUNNING) return@LaunchedEffect
        var last = -1L
        while (true) {
            val nanos = withFrameNanos { it }
            if (last < 0) { last = nanos; continue }
            // Cap a long gap (the app was backgrounded) so a whole window does
            // not close in one step.
            val dt = ((nanos - last) / 1e9).coerceAtMost(0.1)
            last = nanos
            var over = false
            for (event in g.advance(dt)) {
                when (event) {
                    is DungeonEvent.Cast -> {
                        keyer.clear()
                        player.replaySound(
                            MorseItem.Playable.Text(event.cast.spell.spell),
                            Settings.sidetoneHz, event.cast.timing
                        )
                    }
                    is DungeonEvent.Attacked -> {
                        // Late: whatever was keyed is graded against the
                        // counter, and the rest of it is missed.
                        noteOutcomes(DungeonGame.characterOutcomes(event.cast.spell.counter, keyer.decodedText.trim()))
                        // Late is never a copy: the answer is what was keyed, which
                        // cannot equal the spell (it was a counter attempt).
                        lbItems.add(LeaderboardItem(event.cast.spell.spell, keyer.decodedText.trim().uppercase(), 0, wpm))
                        keyer.clear()
                        if (Settings.hapticsEnabled) haptics.error()
                        flash = "${event.cast.spell.spell} hit you — ${event.cast.spell.counter}" to System.currentTimeMillis() + 1200
                    }
                    DungeonEvent.GameOver -> over = true
                }
            }
            syncScene(g)
            syncHud(g)
            if (over) { finishGame(); return@LaunchedEffect }
        }
    }

    // Keying: characters assemble into a word. It is cast the moment it
    // matches the counter, or when a word gap of silence arrives (the decoder
    // appends a space) — whatever was keyed by then is the answer.
    LaunchedEffect(keyer.decodedText, phase) {
        if (phase != DgnPhase.RUNNING) return@LaunchedEffect
        val expected = game?.pendingCast?.spell?.counter ?: return@LaunchedEffect
        val text = keyer.decodedText
        val word = text.trim()
        if (word.isEmpty()) return@LaunchedEffect
        if (word == expected || text.endsWith(" ")) castWord(word)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (phase) {
            DgnPhase.SETUP -> {
                BackHandler { onBack() }
                DungeonSetup(
                    characterSet = characterSet, onCharacterSet = { characterSet = it },
                    pool = spellPool(),
                    difficulty = difficulty, onDifficulty = { difficulty = it },
                    onStart = { startGame() },
                    onBack = onBack,
                    onSwitchMode = ::switchTo
                )
            }
            DgnPhase.RUNNING -> {
                BackHandler { abandonRun() }
                DungeonRun(
                    monsters = monsters, casterId = casterId, sending = sending, windowFraction = windowFraction,
                    room = room, score = score, lives = lives, maxLives = maxLives, multiplier = multiplier, wpm = wpm,
                    flash = flash,
                    spells = spellPool().spells,
                    decoded = keyer.decodedText,
                    keyPressed = keyPressed,
                    midiDevice = midiDevice,
                    onKey = { down -> keyPressed = down; keyer.touchKey(down) },
                    onReplay = {
                        val g = game ?: return@DungeonRun
                        val cast = g.pendingCast ?: return@DungeonRun
                        player.replaySound(MorseItem.Playable.Text(cast.spell.spell), Settings.sidetoneHz, cast.timing)
                    },
                    onEnd = { abandonRun() },
                    onSwitchMode = ::switchTo
                )
            }
            DgnPhase.OVER -> {
                BackHandler { onBack() }
                DungeonOver(
                    score = score, roomsCleared = roomsCleared, bestCombo = bestCombo,
                    accuracy = if (runAttempts == 0) 0.0 else runCorrect.toDouble() / runAttempts,
                    bestWpm = bestWpm,
                    leaderboard = lbLine,
                    onAgain = { startGame() },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun DungeonSetup(
    characterSet: InvadersCharacterSet, onCharacterSet: (InvadersCharacterSet) -> Unit,
    pool: DungeonPool,
    difficulty: InvadersDifficulty, onDifficulty: (InvadersDifficulty) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_dungeon), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.DUNGEON, onSwitchMode)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.dungeon_blurb), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            DgnLabel(stringResource(R.string.dungeon_characters))
            DgnPills(InvadersCharacterSet.entries.map { it to it.label }, characterSet, onCharacterSet)
            DgnLabel(stringResource(R.string.dungeon_spell_book))
            Text(stringResource(R.string.dungeon_spell_book_note), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            DungeonSpellBook(pool.spells)
            if (pool.fallback) {
                Text(
                    stringResource(R.string.dungeon_fallback_note, DungeonSpells.minPool),
                    style = MaterialTheme.typography.bodySmall, color = Brand.teal
                )
            }
            DgnLabel(stringResource(R.string.dungeon_difficulty))
            DgnPills(InvadersDifficulty.entries.map { it to it.label }, difficulty, onDifficulty)
            // The ramp in words: where a game opens and where it climbs to.
            val target = Settings.characterWpm.roundToInt()
            val start = DungeonGame.rampStart(Settings.characterWpm).roundToInt()
            val spacing = if (Settings.farnsworthEnabled) " " + stringResource(R.string.dungeon_farnsworth_note, Settings.effectiveWpm.roundToInt()) else ""
            val speedNote = if (start >= target) {
                stringResource(R.string.dungeon_speed_flat_note, target)
            } else {
                stringResource(R.string.dungeon_speed_ramp_note, start, DungeonGame.rampStep.roundToInt(), DungeonGame.hitsPerRampStep, target)
            }
            Text(speedNote + spacing, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.dungeon_start), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The pairs of the book in two columns, in book order; ♥ marks a heal. */
@Composable
private fun DungeonSpellBook(spells: List<DungeonSpell>) {
    val rows = spells.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (s in row) {
                    Row(modifier = Modifier.weight(1f)) {
                        Text("${s.spell} → ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
                        Text(s.counter, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = Brand.textPrimary)
                        if (s.heals) Text(" ♥", style = MaterialTheme.typography.bodySmall, color = Color.Red)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DungeonRun(
    monsters: List<DungeonMonster>, casterId: Int?, sending: Boolean, windowFraction: Float?,
    room: Int, score: Int, lives: Int, maxLives: Int, multiplier: Int, wpm: Int,
    flash: Pair<String, Long>?,
    spells: List<DungeonSpell>,
    decoded: String,
    keyPressed: Boolean,
    midiDevice: String?,
    onKey: (Boolean) -> Unit,
    onReplay: () -> Unit,
    onEnd: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    val measurer = rememberTextMeasurer()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.dungeon_end_game), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.DUNGEON, onSwitchMode)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DgnStat(stringResource(R.string.dungeon_score), score.toString())
            DgnStat(stringResource(R.string.dungeon_room), room.toString())
            DgnStat(stringResource(R.string.dungeon_lives), "♥".repeat(lives) + "♡".repeat((maxLives - lives).coerceAtLeast(0)))
            DgnStat(stringResource(R.string.dungeon_combo), "×$multiplier")
            DgnStat(stringResource(R.string.dungeon_wpm), wpm.toString())
        }
        Spacer(Modifier.height(10.dp))
        val nameStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = Brand.textPrimary)
        val dimNameStyle = nameStyle.copy(color = Brand.textSecondary)
        val dotsStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Brand.tealBright)
        val flashStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Brand.cornerRadius))
                .background(Brand.navyElevated)
                .border(1.dp, Brand.hairline, RoundedCornerShape(Brand.cornerRadius))
                .pointerInput(Unit) { detectTapGestures { onReplay() } }
        ) {
            // The floor: a tile grid inside a wall.
            val tile = 28.dp.toPx()
            val gridColour = Color.White.copy(alpha = 0.04f)
            var x = tile
            while (x < size.width) { drawLine(gridColour, Offset(x, 0f), Offset(x, size.height)); x += tile }
            var y = tile
            while (y < size.height) { drawLine(gridColour, Offset(0f, y), Offset(size.width, y)); y += tile }
            val inset = 4.dp.toPx()
            drawRoundRect(
                Brand.teal.copy(alpha = 0.35f), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx())
            )

            val px = 4.dp.toPx()
            fun drawSprite(kind: DungeonMonsterKind?, cx: Float, cy: Float, colour: Color) {
                val rows = DungeonSprites.bitmap(kind)
                val ox = cx - rows[0].length * px / 2
                val oy = cy - rows.size * px / 2
                rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        if (cell == '#') drawRect(colour, Offset(ox + c * px, oy + r * px), Size(px, px))
                    }
                }
            }

            val count = maxOf(1, monsters.size)
            val monsterY = size.height * 0.34f
            monsters.forEachIndexed { i, m ->
                val cx = size.width * (i + 0.5f) / count
                val alive = !m.isDown
                drawSprite(m.kind, cx, monsterY, if (alive) Brand.tealBright else Brand.textSecondary.copy(alpha = 0.3f))
                // Hit pips above the sprite.
                val pip = 8.dp.toPx()
                val gap = 3.dp.toPx()
                val pipsWidth = m.maxHits * pip + (m.maxHits - 1) * gap
                for (h in 0 until m.maxHits) {
                    val left = cx - pipsWidth / 2 + h * (pip + gap)
                    val top = monsterY - 34.dp.toPx()
                    if (h < m.hits) drawRect(Color.Red, Offset(left, top), Size(pip, pip))
                    else drawRect(Color.Red.copy(alpha = 0.4f), Offset(left, top), Size(pip, pip), style = Stroke(1.dp.toPx()))
                }
                val name = measurer.measure(m.kind.label, if (alive) nameStyle else dimNameStyle)
                drawText(name, topLeft = Offset(cx - name.size.width / 2, monsterY + 30.dp.toPx() - name.size.height / 2))
                if (alive && m.id == casterId) {
                    if (sending) {
                        val dots = measurer.measure("· · ·", dotsStyle)
                        drawText(dots, topLeft = Offset(cx - dots.size.width / 2, monsterY - 48.dp.toPx() - dots.size.height / 2))
                    } else if (windowFraction != null) {
                        // The attack window, draining under the caster.
                        val barWidth = 60.dp.toPx()
                        val barHeight = 6.dp.toPx()
                        val top = monsterY + 40.dp.toPx()
                        drawRoundRect(Brand.navyRaised, Offset(cx - barWidth / 2, top), Size(barWidth, barHeight), CornerRadius(3.dp.toPx()))
                        val colour = if (windowFraction > 0.4f) Brand.teal else Color.Red
                        drawRoundRect(colour, Offset(cx - barWidth / 2, top), Size(barWidth * windowFraction, barHeight), CornerRadius(3.dp.toPx()))
                    }
                }
            }

            // The hero, at the bottom.
            drawSprite(null, size.width / 2, size.height - 44.dp.toPx(), Brand.teal)

            if (flash != null && flash.second > System.currentTimeMillis()) {
                val measured = measurer.measure(flash.first, flashStyle)
                drawText(measured, topLeft = Offset(size.width / 2 - measured.size.width / 2, size.height / 2 + 24.dp.toPx() - measured.size.height / 2))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.dungeon_tap_to_replay), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in spells) {
                Text(
                    "${s.spell} → ${s.counter}" + (if (s.heals) " ♥" else ""),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dungeon_key_the_counter), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
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
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DungeonOver(
    score: Int, roomsCleared: Int, bestCombo: Int, accuracy: Double,
    bestWpm: Int,
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
            Text(stringResource(R.string.dungeon_game_over), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DgnStat(stringResource(R.string.dungeon_score), score.toString())
                DgnStat(stringResource(R.string.dungeon_rooms), roomsCleared.toString())
                DgnStat(stringResource(R.string.dungeon_accuracy), "${(accuracy * 100).roundToInt()}%")
                DgnStat(stringResource(R.string.dungeon_best_combo), bestCombo.toString())
            }
            Text(stringResource(R.string.dungeon_speed_reached, bestWpm), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            // The shared leaderboard's reply, or why the game was not posted; nothing when not opted in.
            if (leaderboard != null) {
                Text(leaderboard, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            }
            Button(
                onClick = onAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) { Text(stringResource(R.string.dungeon_play_again), fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

@Composable
private fun DgnStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DgnLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
}

@Composable
private fun <T> DgnPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
                    .clickable(onClick = { onSelect(value) })
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}
