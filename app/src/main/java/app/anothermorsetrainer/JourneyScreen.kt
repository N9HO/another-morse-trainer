package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Circle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.JourneyCurriculum
import app.anothermorsetrainer.morsekit.JourneyLevel
import app.anothermorsetrainer.morsekit.JourneyProgress
import app.anothermorsetrainer.morsekit.JourneyQuiz
import app.anothermorsetrainer.morsekit.JourneyScoring
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/**
 * The "Journey" mode: a gamified, level-based path with a progress bar that
 * fills on a correct answer and drains on a miss. Clearing a level unlocks the
 * next; a map lets the player jump to any unlocked level.
 *
 * Ported from MorseTrainerApp/ContentView.swift (the journey banner + loop) and
 * JourneyMapView.swift. iOS's AppModel-held journey state lives here in the
 * screen; progress persists through [JourneyStore].
 */
@Composable
fun JourneyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }

    val progress = remember { JourneyStore.load() }
    val quiz = remember {
        val startIndex = JourneyCurriculum.levels.indexOfFirst { it.number == progress.currentLevel }
        JourneyQuiz(
            startIndex = if (startIndex >= 0) startIndex else 0,
            scoring = JourneyScoring.Default,
            config = Settings.phraseConfig()
        )
    }

    var showMap by remember { mutableStateOf(false) }

    if (showMap) {
        BackHandler { showMap = false }
        JourneyMap(
            quiz = quiz,
            progress = progress,
            onPick = { number ->
                val index = quiz.levels.indexOfFirst { it.number == number }
                if (index >= 0 && progress.isUnlocked(number)) {
                    player.stop()
                    quiz.select(index)
                    progress.currentLevel = number
                    JourneyStore.save(progress)
                }
                showMap = false
            },
            onBack = { showMap = false }
        )
        return
    }

    // ---- Play loop ----
    var drill by remember { mutableStateOf(quiz.nextDrill()) }
    var round by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var lastTtr by remember { mutableStateOf(0.0) }
    var toneFinishedAt by remember { mutableStateOf(0L) }

    var summary by remember { mutableStateOf(quiz.summary) }
    var levelNumber by remember { mutableStateOf(quiz.levelNumber) }
    var levelTitle by remember { mutableStateOf(quiz.level.title) }
    var levelSection by remember { mutableStateOf(quiz.level.section) }
    var barProgress by remember { mutableStateOf(quiz.progress.toFloat()) }
    var lastCorrect by remember { mutableStateOf<Boolean?>(null) }
    var clearedLabel by remember { mutableStateOf<String?>(null) }

    val tally = remember { Tally() }
    val animatedBar by animateFloatAsState(targetValue = barProgress, label = "journeyBar")

    LaunchedEffect(round) {
        revealed = false
        chosen = null
        toneFinishedAt = 0L
        clearedLabel = null
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    LaunchedEffect(revealed) {
        if (revealed) {
            delay(1100)
            drill = quiz.nextDrill()
            round++
        }
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun finish() {
        progress.currentLevel = quiz.levelNumber
        JourneyStore.save(progress)
        Stats.record(mode = "Journey", attempts = tally.attempts, correct = tally.correct, bestTtrMs = tally.bestMs, durationSeconds = tally.elapsedSeconds())
        onBack()
    }

    BackHandler { finish() }

    fun answer(choice: String) {
        if (revealed) return
        val ttr = if (toneFinishedAt == 0L) 0.0 else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        lastTtr = ttr
        chosen = choice
        val levelBefore = quiz.levelNumber
        val outcome = quiz.record(choice = choice, ttr = ttr)
        lastCorrect = outcome.correct
        summary = quiz.summary
        levelNumber = quiz.levelNumber
        levelTitle = quiz.level.title
        levelSection = quiz.level.section
        barProgress = quiz.progress.toFloat()

        tally.attempts += 1
        val ms = (ttr * 1000).roundToInt()
        if (outcome.correct) {
            tally.correct += 1
            if (ms > 0 && (tally.bestMs == null || ms < tally.bestMs!!)) tally.bestMs = ms
        }
        if (drill.correct.length == 1) {
            Stats.recordChar(drill.correct, outcome.correct, if (outcome.correct && ms > 0) ms else null)
        }
        if (Settings.hapticsEnabled) { if (outcome.correct) haptics.success() else haptics.error() }

        if (outcome.unlocked != null) {
            progress.clear(levelBefore, quiz.levels.size)
            progress.currentLevel = quiz.levelNumber
            JourneyStore.save(progress)
            clearedLabel = outcome.unlocked
        }
        revealed = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { finish() }) { Text("‹ Back", color = Brand.teal) }
            OutlinedButton(onClick = { player.stop(); showMap = true }) {
                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Map")
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            // Journey banner: level header + progress bar.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Level $levelNumber", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(levelSection, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Text("$levelNumber / ${quiz.levels.size}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Brand.textSecondary)
                }
                if (levelTitle.isNotEmpty()) {
                    Text(levelTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Brand.teal)
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(12.dp)
                        .background(Brand.navyRaised, RoundedCornerShape(6.dp))
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(animatedBar.coerceIn(0f, 1f)).fillMaxHeight()
                            .background(if (lastCorrect == false) Color(0xFFE08A1E) else Brand.teal, RoundedCornerShape(6.dp))
                    )
                }
                clearedLabel?.let {
                    Text("✓ $it", color = OK_GREEN, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (revealed) {
                    val ok = chosen == drill.correct
                    val showAnswer = when (Settings.revealMode) {
                        RevealMode.ALWAYS -> true
                        RevealMode.ON_WRONG -> !ok
                        RevealMode.NEVER -> false
                    }
                    if (showAnswer) {
                        Text(drill.revealPrimary, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                        if (drill.revealSecondary.isNotEmpty()) {
                            Text(drill.revealSecondary, fontSize = 18.sp, color = Brand.textSecondary, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = when {
                            ok -> "✓ recalled in %.1f s".format(lastTtr)
                            showAnswer -> "✗ it was “${drill.correct}”"
                            else -> "✗ not quite"
                        },
                        color = if (ok) OK_GREEN else ERR_RED,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text("?", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Brand.teal)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing()) }) {
                        Text("▶ Replay")
                    }
                }
            }

            JourneyOptionsGrid(drill = drill, revealed = revealed, chosen = chosen, onPick = ::answer)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun JourneyOptionsGrid(
    drill: app.anothermorsetrainer.morsekit.Drill,
    revealed: Boolean,
    chosen: String?,
    onPick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        drill.options.chunked(2).forEach { rowOpts ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowOpts.forEach { option ->
                    val colors = when {
                        revealed && option == drill.correct -> ButtonDefaults.buttonColors(containerColor = OK_GREEN, contentColor = Color.White)
                        revealed && option == chosen -> ButtonDefaults.buttonColors(containerColor = ERR_RED, contentColor = Color.White)
                        revealed -> ButtonDefaults.buttonColors(containerColor = Brand.navyRaised, contentColor = Brand.textSecondary)
                        else -> ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
                    }
                    val short = option.length <= 3
                    Button(
                        onClick = { onPick(option) },
                        colors = colors,
                        shape = RoundedCornerShape(Brand.cornerRadius),
                        modifier = Modifier.weight(1f).heightIn(min = 76.dp)
                    ) {
                        Text(
                            option,
                            fontSize = if (short) 32.sp else 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = if (short) FontFamily.Monospace else null,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                }
                if (rowOpts.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The journey map: every level, grouped by section, with lock/complete/current state. */
@Composable
private fun JourneyMap(
    quiz: JourneyQuiz,
    progress: JourneyProgress,
    onPick: (Int) -> Unit,
    onBack: () -> Unit
) {
    // Levels grouped into their sections, preserving curriculum order.
    val sections = remember(quiz) {
        val order = mutableListOf<String>()
        val byName = linkedMapOf<String, MutableList<JourneyLevel>>()
        for (level in quiz.levels) {
            if (byName[level.section] == null) { order.add(level.section); byName[level.section] = mutableListOf() }
            byName[level.section]!!.add(level)
        }
        order.map { it to (byName[it] ?: emptyList()) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Brand.teal) }
            Text("Journey Map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(56.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
        ) {
            sections.forEach { (name, levels) ->
                item(key = "sec-$name") {
                    Text(name.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                items(levels.size, key = { "lvl-${levels[it].number}" }) { i ->
                    val level = levels[i]
                    JourneyMapRow(
                        level = level,
                        unlocked = progress.isUnlocked(level.number),
                        completed = progress.completed.contains(level.number),
                        isCurrent = level.number == quiz.levelNumber,
                        onClick = { onPick(level.number) }
                    )
                }
            }
        }
    }
}

@Composable
private fun JourneyMapRow(level: JourneyLevel, unlocked: Boolean, completed: Boolean, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .brandCard()
            .then(if (unlocked) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            completed -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = OK_GREEN, modifier = Modifier.size(24.dp))
            unlocked -> Icon(Icons.Outlined.Circle, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(24.dp))
            else -> Icon(Icons.Filled.Lock, contentDescription = null, tint = Brand.textSecondary, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Level ${level.number}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (unlocked) Brand.textPrimary else Brand.textSecondary)
            Text(level.title, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary, maxLines = 1)
        }
        if (isCurrent) {
            Box(modifier = Modifier.background(Brand.teal.copy(alpha = 0.2f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text("Current", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.teal)
            }
        }
    }
}
