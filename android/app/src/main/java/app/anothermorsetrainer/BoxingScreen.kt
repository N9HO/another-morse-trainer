package app.anothermorsetrainer

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.anothermorsetrainer.morsekit.BkRing
import app.anothermorsetrainer.morsekit.BoxingCallout
import app.anothermorsetrainer.morsekit.BoxingCareer
import app.anothermorsetrainer.morsekit.BoxingFight
import app.anothermorsetrainer.morsekit.BoxingJudged
import app.anothermorsetrainer.morsekit.BoxingKeying
import app.anothermorsetrainer.morsekit.BoxingOutcome
import app.anothermorsetrainer.morsekit.BoxingPhase
import app.anothermorsetrainer.morsekit.BoxingRules
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import kotlin.math.roundToInt

private enum class BkView { SPLASH, TITLE, CARD, FIGHT, RESULTS }

/** How long the result shows in the ring before the results screen. */
private const val OVER_MS = 3200
/** The title splash moves on by itself after this long. */
private const val SPLASH_MS = 7000

private val BK_BRASS = Color(0xFFE8B020)
private val BK_RED = Color(0xFFE83A3A)
private val BK_BG = Color(0xFF120E1C)
private val BK_PANEL = Color(0xFF0E0C16)
private val BK_PALE = Color(0xFFF4EEE0)

/**
 * Brass Knuckles Knock Out: a boxing game that teaches Morse, created by Derick (KO6PBZ) for
 * Kristen's (K5MME) HamCube PocketCW firmware and used here with permission (GPL-3.0-or-later).
 * The opponent sends a one- or two-character tell; key it back before the punch lands to dodge,
 * then every character you send is a punch. The rules live in [BoxingFight]; this screen is the
 * frame clock, the tell's sound, the key and the drawing (the ring is the game's pixel art from
 * [BkRing]; the scoreboard and panels are native Compose).
 *
 * Twin of the iOS and desktop screens. Fight state is not saved across process death: only the
 * career (unlocked fighters, bests) is.
 */
@Composable
fun BoxingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("amt_boxing", android.content.Context.MODE_PRIVATE) }
    val player = remember { MorsePlayer() }
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    val keying = remember { BoxingKeying() }

    val career = remember { loadCareer(prefs) }
    var view by remember { mutableStateOf(BkView.SPLASH) }
    var tick by remember { mutableIntStateOf(0) }   // bumped each frame so the canvas redraws
    var pick by remember { mutableIntStateOf(0) }
    var fight by remember { mutableStateOf<BoxingFight?>(null) }
    var stage by remember { mutableIntStateOf(0) }
    var splashAt by remember { mutableIntStateOf(0) }
    var seed by remember { mutableIntStateOf(0) }
    var overAt by remember { mutableIntStateOf(0) }
    var recorded by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var tellDone by remember { mutableStateOf(false) }
    var keyPressed by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var hints by remember { mutableStateOf(career.hints) }

    fun clock(): Int = SystemClock.elapsedRealtime().toInt()
    fun ditMs(): Int = (1200.0 / Settings.characterWpm).roundToInt().coerceAtLeast(20)

    fun goSplash() {
        splashAt = clock()
        seed = clock() xor 0x5BD1E995
        view = BkView.SPLASH
    }

    fun persist() {
        saveCareer(prefs, career)
    }

    fun judge(j: BoxingJudged, now: Int) {
        val f = fight ?: return
        val c = j.char
        if (c != null) f.key(c, j.quality, now) else f.keyUnknown(now)
    }

    fun keyEdge(down: Boolean, atMs: Int? = null) {
        val f = fight ?: return
        if (view != BkView.FIGHT || f.paused()) return
        val t = atMs ?: clock()
        keying.edge(down, t, ditMs())?.let { judge(it, t) }
    }

    fun finishRecord(f: BoxingFight, over: BoxingPhase.Over) {
        if (recorded) return
        recorded = true
        opened = career.finished(f.fighter, over.won, f.score)
        persist()
        val s = f.stats
        Stats.record(
            mode = "Brass Knuckles", attempts = s.tells, correct = s.dodged, bestTtrMs = null,
            durationSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt().coerceAtLeast(0),
            characterWpm = Settings.characterWpm.roundToInt(),
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt(),
            score = f.score
        )
    }

    fun beginFight(index: Int) {
        val f = BoxingFight(index, clock() xor 0x2545F491, hints, ditMs(), PileupSettings.myCall)
        fight = f
        keying.reset()
        stage = Integer.remainderUnsigned(clock() * 31 + index, BkRing.STAGES.size)
        recorded = false
        opened = false
        tellDone = false
        startedAt = SystemClock.elapsedRealtime()
        view = BkView.CARD
    }

    fun playTell(text: String, f: BoxingFight) {
        val foe = f.foe
        val base = Settings.characterWpm.roundToInt()
        val (wpm, eff) = foe.speeds(base, if (Settings.farnsworthEnabled) Settings.effectiveWpm.roundToInt() else 0)
        tellDone = false
        player.play(
            MorseItem.Playable.Text(text), Settings.sidetoneHz + foe.pitchOffsetHz,
            MorseTiming.farnsworth(wpm.toDouble(), eff.toDouble())
        ) { tellDone = true }
    }

    // The frame clock: runs the rules, the tell, the keying judge and the splash.
    LaunchedEffect(Unit) {
        goSplash()
        while (true) {
            withFrameNanos { }
            val now = clock()
            when (view) {
                BkView.SPLASH -> if (now - splashAt > SPLASH_MS) view = BkView.TITLE
                BkView.FIGHT -> {
                    val f = fight
                    if (f != null) {
                        if (!f.paused()) {
                            if (tellDone) { tellDone = false; f.tellPlayed(now) }
                            f.update(now)
                            keying.poll(now, ditMs())?.let { judge(it, now) }
                            f.takePlay()?.let { playTell(it, f) }
                            if (f.stop) { f.stop = false; player.stop() }
                            f.takeCopies { _, _ -> }   // fights read the letter stats, they do not write them
                        }
                        val phase = f.phase
                        if (phase is BoxingPhase.Over) {
                            if (overAt == 0) overAt = now
                            finishRecord(f, phase)
                            if (now - overAt > OVER_MS) { overAt = 0; view = BkView.RESULTS }
                        }
                    }
                }
                else -> {}
            }
            tick += 1
        }
    }

    // Sidetone and the hardware key only while fighting.
    DisposableEffect(view) {
        if (view == BkView.FIGHT) {
            keyer.scope = scope
            keyer.start()
            midi.start(onKey = { down -> keyer.touchKey(down); keyEdge(down) }, onConnected = { })
        }
        onDispose { midi.stop(); keyer.stop() }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Leaving the app pauses the fight; the player comes back to the pause card.
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) fight?.pause(clock())
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    fun leave() {
        player.stop()
        if (view == BkView.FIGHT) fight?.pause(clock())
        fight = null
        onBack()
    }

    when (view) {
        BkView.SPLASH -> {
            BackHandler { leave() }
            SplashView(t = clock() - splashAt, seed = seed, frame = tick, onContinue = { view = BkView.TITLE })
        }
        BkView.TITLE -> {
            BackHandler { leave() }
            TitleView(
                career = career, pick = pick, hints = hints,
                onHints = { hints = it; career.hints = it; persist() },
                onPick = { pick = it },
                onFight = { beginFight(pick) },
                onBack = { leave() },
            )
        }
        BkView.CARD -> {
            BackHandler { view = BkView.TITLE }
            val f = fight
            if (f != null) CardView(
                index = f.fighter,
                onBell = { f.start(clock()); view = BkView.FIGHT },
                onBack = { view = BkView.TITLE },
            )
        }
        BkView.FIGHT -> {
            val f = fight
            if (f != null) {
                BackHandler { f.pause(clock()) }
                FightView(
                    f = f, now = clock(), stage = stage, frame = tick, keyPressed = keyPressed,
                    marks = keying.marks(),
                    onPause = { f.pause(clock()) },
                    onResume = { f.resume(clock()) },
                    onQuit = { player.stop(); fight = null; view = BkView.TITLE },
                    onNextRound = { f.start(clock()) },
                    keyContent = { KeyPad(
                        pressed = keyPressed,
                        onDown = { keyPressed = true; keyer.touchKey(true); keyEdge(true) },
                        onUp = { keyPressed = false; keyer.touchKey(false); keyEdge(false) },
                        onPaddle = { down, ms ->
                            val lag = (System.currentTimeMillis() - ms).toInt().coerceAtLeast(0)
                            keyer.touchKey(down, ms); keyEdge(down, clock() - lag)
                        },
                    ) },
                )
            }
        }
        BkView.RESULTS -> {
            BackHandler { view = BkView.TITLE }
            val f = fight
            if (f != null) ResultsView(
                f = f, career = career, opened = opened,
                onAgain = { beginFight(f.fighter) },
                onNext = { pick = (f.fighter + 1).coerceAtMost(BoxingRules.FIGHTERS - 1); beginFight(pick) },
                onTitle = { view = BkView.TITLE },
            )
        }
    }
}

// ---- Drawing helpers --------------------------------------------------------------------------

private fun bitmapOf(pixels: IntArray, w: Int, h: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}

/** Draws [image] scaled up without smoothing, filling the width of the canvas. */
@Composable
private fun PixelImage(image: ImageBitmap, modifier: Modifier, srcTop: Int = 0, srcRows: Int = image.height) {
    Canvas(modifier) {
        val scale = size.width / image.width
        drawImage(
            image = image,
            srcOffset = IntOffset(0, srcTop),
            srcSize = IntSize(image.width, srcRows),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(size.width.toInt(), (srcRows * scale).toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
private fun SplashView(t: Int, seed: Int, frame: Int, onContinue: () -> Unit) {
    // `frame` only makes this recompose each tick.
    val pixels = remember(frame) { BkRing.splash(t, seed) }
    val image = remember(frame) { bitmapOf(pixels, BkRing.SCREEN_W, BkRing.SCREEN_H) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0A0306)).clickable { onContinue() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PixelImage(image, Modifier.fillMaxWidth().height((BkRing.SCREEN_H * 1.0).dp))
        Text(stringResource(R.string.bk_sponsored), color = BK_BRASS, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.bk_created_by), color = BK_PALE, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.bk_built_on), color = BK_PALE.copy(alpha = 0.8f), fontSize = 12.sp)
        if (t >= BkRing.PROMPT_MS) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.bk_tap_to_start), color = BK_BRASS, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Portrait(fighter: Int, locked: Boolean, size: Int) {
    val image = remember(fighter, locked) {
        bitmapOf(BkRing.portrait(fighter, 0, locked, 0xFF120E1C.toInt()), BkRing.PORTRAIT, BkRing.PORTRAIT)
    }
    PixelImage(image, Modifier.size(size.dp))
}

@Composable
private fun TitleView(
    career: BoxingCareer, pick: Int, hints: Boolean,
    onHints: (Boolean) -> Unit, onPick: (Int) -> Unit, onFight: () -> Unit, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(BK_BG).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_brass_knuckles), color = BK_BRASS, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(stringResource(R.string.bk_lead), color = BK_PALE, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        for (i in 0 until BoxingRules.FIGHTERS) {
            val locked = i >= career.unlocked
            val foe = BoxingRules.LADDER[i]
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == pick) Color(0xFF2A2238) else BK_PANEL)
                    .border(1.dp, if (i == pick) BK_BRASS else Color(0xFF4A3C5A), RoundedCornerShape(10.dp))
                    .clickable(enabled = !locked) { onPick(i) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Portrait(i, locked, 56)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (locked) "???" else foe.name, color = BK_PALE, fontWeight = FontWeight.Bold)
                    Text(
                        if (locked) stringResource(R.string.bk_locked) else foe.from.lowercase().replaceFirstChar { it.uppercase() },
                        color = BK_PALE.copy(alpha = 0.7f), fontSize = 12.sp
                    )
                }
                if (!locked && career.bestScore(i) > 0) {
                    Text(career.bestScore(i).toString(), color = BK_BRASS, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bk_hints), color = BK_PALE, modifier = Modifier.weight(1f))
            Switch(checked = hints, onCheckedChange = onHints)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onFight, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bk_fight)) }
    }
}

@Composable
private fun CardView(index: Int, onBell: () -> Unit, onBack: () -> Unit) {
    val foe = BoxingRules.LADDER[index]
    Column(
        Modifier.fillMaxSize().background(BK_BG).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Portrait(index, false, 160)
        Text(foe.name, color = BK_BRASS, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(foe.from, color = BK_PALE, fontSize = 13.sp)
        Text(foe.blurb, color = BK_PALE.copy(alpha = 0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.bk_card_chars, foe.set), color = BK_PALE, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        val fresh = foe.newChars(index)
        if (fresh.isNotEmpty()) {
            Text(stringResource(R.string.bk_card_new, fresh), color = BK_BRASS, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBell) { Text(stringResource(R.string.bk_ring_bell)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A2238))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).background(color))
    }
}

private fun mmss(ms: Int): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun calloutText(c: BoxingCallout): String = when (c) {
    BoxingCallout.Dodge -> stringResource(R.string.bk_callout_dodge)
    is BoxingCallout.Wrong -> stringResource(R.string.bk_callout_wrong, c.sent.toString())
    BoxingCallout.TooSlow -> stringResource(R.string.bk_callout_slow)
    BoxingCallout.Clean -> stringResource(R.string.bk_callout_clean)
    BoxingCallout.Star -> stringResource(R.string.bk_callout_star)
    BoxingCallout.Uppercut -> stringResource(R.string.bk_callout_uppercut)
    BoxingCallout.Blocked -> stringResource(R.string.bk_callout_blocked)
    BoxingCallout.Missed -> stringResource(R.string.bk_callout_missed)
    BoxingCallout.Down -> stringResource(R.string.bk_callout_down)
    BoxingCallout.Up -> stringResource(R.string.bk_callout_up)
}

@Composable
private fun FightView(
    f: BoxingFight, now: Int, stage: Int, frame: Int, keyPressed: Boolean, marks: String,
    onPause: () -> Unit, onResume: () -> Unit, onQuit: () -> Unit, onNextRound: () -> Unit,
    keyContent: @Composable () -> Unit,
) {
    val phase = f.phase
    val banner: BkRing.Banner? = when {
        phase == BoxingPhase.RoundStart -> BkRing.Banner("ROUND ${f.round}", 0xFFE8B020.toInt(), 36, 3, 0xFF3A0A0C.toInt())
        phase == BoxingPhase.FoeDown || phase == BoxingPhase.MeDown ->
            BkRing.Banner(f.count.toString(), 0xFFFFFFFF.toInt(), 36, 3, 0xFF3A0A0C.toInt())
        phase == BoxingPhase.RoundEnd -> BkRing.Banner("ROUND OVER", 0xFFE8B020.toInt(), 36, 2, 0xFF3A0A0C.toInt())
        phase is BoxingPhase.Over -> BkRing.Banner(
            when {
                phase.how == BoxingOutcome.KO -> if (phase.won) "K.O.!" else "K.O."
                phase.how == BoxingOutcome.TKO -> "T.K.O."
                else -> if (phase.won) "YOU WIN" else "YOU LOSE"
            },
            if (phase.won) 0xFFE8B020.toInt() else 0xFFE83A3A.toInt(), 36, 3, 0xFF3A0A0C.toInt()
        )
        else -> null
    }
    val pixels = remember(frame) {
        BkRing.ring(
            f.fighter, f.foePose(now), f.mePose(now), f.foeHp * 4 <= f.foe.hp, now, stage, banner
        )
    }
    val image = remember(frame) { bitmapOf(pixels, BkRing.ART_W, BkRing.ART_H) }
    Column(Modifier.fillMaxSize().background(BK_BG)) {
        // Scoreboard.
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bk_you), color = BK_PALE, fontSize = 11.sp)
                Bar(f.myHp / BoxingRules.PLAYER_HP.toFloat(), Color(0xFF30C860), Modifier.fillMaxWidth())
            }
            Column(Modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bk_round_of, f.round, BoxingRules.ROUNDS), color = BK_BRASS, fontSize = 11.sp)
                Text(mmss(f.clockMs(now)), color = BK_PALE, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(f.foe.name, color = BK_PALE, fontSize = 11.sp, maxLines = 1)
                Bar(f.foeHp / f.foe.hp.toFloat(), BK_RED, Modifier.fillMaxWidth())
            }
        }
        PixelImage(image, Modifier.fillMaxWidth(), srcTop = BkRing.STAGE_Y, srcRows = BkRing.ART_H - BkRing.STAGE_Y)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("★".repeat(f.stars) + "☆".repeat(BoxingRules.MAX_STARS - f.stars), color = BK_BRASS, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text(f.score.toString(), color = BK_PALE, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            TextButton(onClick = onPause) { Text(stringResource(R.string.bk_pause), color = Brand.teal) }
        }
        // The tell and your keying.
        Column(Modifier.fillMaxWidth().background(BK_PANEL).padding(10.dp)) {
            val shown = when {
                f.starring() -> f.word
                phase == BoxingPhase.MeDown -> stringResource(R.string.bk_get_up, f.getUp.toString())
                f.tellShown -> f.tell
                else -> ""
            }
            Text(
                shown.ifEmpty { if (f.telling()) "· · ·" else " " },
                color = BK_BRASS, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp
            )
            val typed = if (f.starring()) f.typed else f.answer
            Text(
                typed + (if (marks.isNotEmpty()) "  $marks" else ""),
                color = BK_PALE, fontFamily = FontFamily.Monospace, fontSize = 18.sp, maxLines = 1
            )
            f.window(now)?.let { (left, whole) -> Bar(left / whole.coerceAtLeast(1).toFloat(), BK_BRASS, Modifier.fillMaxWidth().padding(top = 4.dp)) }
            f.callout?.let { (c, _) -> Text(calloutText(c), color = BK_RED, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        Spacer(Modifier.weight(1f))
        if (f.paused()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bk_paused), color = BK_PALE, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onResume) { Text(stringResource(R.string.bk_resume)) }
                    TextButton(onClick = onQuit) { Text(stringResource(R.string.bk_quit), color = BK_RED) }
                }
            }
        } else if (phase == BoxingPhase.RoundEnd) {
            Button(onClick = onNextRound, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.bk_next_round)) }
        } else {
            Box(Modifier.fillMaxWidth().padding(10.dp)) { keyContent() }
        }
    }
}

@Composable
private fun KeyPad(
    pressed: Boolean, onDown: () -> Unit, onUp: () -> Unit, onPaddle: (Boolean, Long) -> Unit,
) {
    OnScreenKeySwitch(onPaddleKey = onPaddle, modifier = Modifier.fillMaxWidth().height(120.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(Brand.cornerRadius))
                .background(if (pressed) Brand.teal else Brand.navyRaised)
                .border(if (pressed) 2.dp else 1.dp, if (pressed) Brand.tealBright else Brand.hairline, RoundedCornerShape(Brand.cornerRadius))
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onDown()
                        try { tryAwaitRelease() } finally { onUp() }
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.bk_key), color = Brand.textPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ResultsView(
    f: BoxingFight, career: BoxingCareer, opened: Boolean,
    onAgain: () -> Unit, onNext: () -> Unit, onTitle: () -> Unit,
) {
    val over = f.phase as? BoxingPhase.Over
    val s = f.stats
    Column(Modifier.fillMaxSize().background(BK_BG).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(
            if (over?.won == true) stringResource(R.string.bk_you_win) else stringResource(R.string.bk_you_lose),
            color = BK_BRASS, fontWeight = FontWeight.Bold, fontSize = 28.sp
        )
        Text(f.foe.name, color = BK_PALE)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.bk_score, f.score), color = BK_PALE, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
        Text(stringResource(R.string.bk_best, career.bestScore(f.fighter)), color = BK_BRASS, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        s.accuracy()?.let { Text(stringResource(R.string.bk_stat_dodged, it, s.dodged, s.tells), color = BK_PALE) }
        s.cleanPercent()?.let { Text(stringResource(R.string.bk_stat_clean, it, s.punches), color = BK_PALE) }
        Text(stringResource(R.string.bk_stat_damage, s.damage, s.taken), color = BK_PALE)
        Text(stringResource(R.string.bk_stat_knockdowns, s.knockdowns, s.uppercuts), color = BK_PALE)
        val worst = s.worst().take(5)
        if (worst.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.bk_worst), color = BK_BRASS, fontWeight = FontWeight.SemiBold)
            Text(worst.joinToString("   ") { (c, right, tries) -> "$c $right/$tries" }, color = BK_PALE, fontFamily = FontFamily.Monospace)
        }
        if (opened) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.bk_unlocked, BoxingRules.LADDER[(f.fighter + 1).coerceAtMost(BoxingRules.FIGHTERS - 1)].name), color = BK_BRASS, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        if (over?.won == true && f.fighter + 1 < BoxingRules.FIGHTERS) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bk_next_fighter)) }
        }
        Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bk_again)) }
        TextButton(onClick = onTitle) { Text(stringResource(R.string.bk_title), color = Brand.teal) }
    }
}

// ---- Career persistence -----------------------------------------------------------------------

private fun loadCareer(prefs: android.content.SharedPreferences): BoxingCareer {
    fun ints(key: String): IntArray {
        val parts = (prefs.getString(key, "") ?: "").split(',')
        return IntArray(BoxingRules.FIGHTERS) { parts.getOrNull(it)?.toIntOrNull() ?: 0 }
    }
    val c = BoxingCareer(
        unlocked = prefs.getInt("unlocked", 1).coerceIn(1, BoxingRules.FIGHTERS),
        hints = prefs.getBoolean("hints", true),
    )
    ints("best").copyInto(c.best)
    ints("wins").copyInto(c.wins)
    return c
}

private fun saveCareer(prefs: android.content.SharedPreferences, c: BoxingCareer) {
    prefs.edit()
        .putInt("unlocked", c.unlocked)
        .putBoolean("hints", c.hints)
        .putString("best", c.best.joinToString(","))
        .putString("wins", c.wins.joinToString(","))
        .apply()
}
