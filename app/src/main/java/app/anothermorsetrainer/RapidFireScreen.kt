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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.CallsignFormat
import app.anothermorsetrainer.morsekit.Drill
import app.anothermorsetrainer.morsekit.RapidFireContent
import app.anothermorsetrainer.morsekit.RapidFirePace
import app.anothermorsetrainer.morsekit.RapidFireQuiz
import app.anothermorsetrainer.morsekit.RapidFireResponse
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK = Color(0xFF2E7D32)
private val ERR = Color(0xFFC62828)

private enum class RfPhase { SETUP, RUNNING, SUMMARY }

/** One streamed item and (for type/head-copy) the learner's copy of it. */
private data class RfResult(val sent: String, val typed: String?, val correct: Boolean?)

/**
 * The "Rapid Fire" mode: a stream of call signs / words / number groups / state
 * abbreviations sent back to back at a chosen pace. Type each one as it lands,
 * copy it in your head then type, or just listen and review the full list at the
 * end.
 *
 * Ported from MorseKit/RapidFire.swift and the iOS ContentView Rapid Fire flow.
 * The AppModel-driven stream loop becomes a step-counter state machine here.
 */
@Composable
fun RapidFireScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }

    var phase by remember { mutableStateOf(RfPhase.SETUP) }

    // Setup selections (defaults mirror the iOS RapidFireSettings).
    var content by remember { mutableStateOf(RapidFireContent.CALLSIGNS) }
    var response by remember { mutableStateOf(RapidFireResponse.TYPE) }
    var pace by remember { mutableStateOf(RapidFirePace.STEADY) }
    var wordMin by remember { mutableStateOf(3) }
    var wordMax by remember { mutableStateOf(6) }
    var numberCount by remember { mutableStateOf(5) }
    var usOnly by remember { mutableStateOf(true) }

    // Run state.
    var quiz by remember { mutableStateOf<RapidFireQuiz?>(null) }
    var drill by remember { mutableStateOf<Drill?>(null) }
    var step by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var toneEndedStep by remember { mutableStateOf(-1) }
    var revealBox by remember { mutableStateOf(false) }
    val transcript = remember { mutableStateListOf<RfResult>() }
    var startedAtMs by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun buildConfig() = RapidFireQuiz.Config(
        content = content,
        callsignFormats = CallsignFormat.commonDefaults,
        callsignUSOnly = usOnly,
        wordMinLength = wordMin,
        wordMaxLength = wordMax,
        numberCount = numberCount
    )

    fun startRun() {
        val q = RapidFireQuiz(buildConfig())
        quiz = q
        transcript.clear()
        drill = q.nextDrill()
        typed = ""
        toneEndedStep = -1
        revealBox = response == RapidFireResponse.TYPE
        startedAtMs = System.currentTimeMillis()
        phase = RfPhase.RUNNING
        step = 1
    }

    fun finishRun() {
        player.stop()
        val attempts = transcript.count { it.correct != null }
        val correct = transcript.count { it.correct == true }
        val secs = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()
        if (response != RapidFireResponse.REVIEW && attempts > 0) {
            Stats.record(mode = "Rapid Fire", attempts = attempts, correct = correct, bestTtrMs = null, durationSeconds = secs)
        }
        phase = RfPhase.SUMMARY
    }

    // Grade the current item (type/head-copy) or just log it (review), then stream the next.
    fun advance() {
        val q = quiz ?: return
        val d = drill ?: return
        if (response == RapidFireResponse.REVIEW) {
            transcript.add(RfResult(d.correct, null, null))
        } else {
            val ok = q.record(typed, 0.0).correct
            if (Settings.hapticsEnabled) { if (ok) haptics.success() else haptics.error() }
            transcript.add(RfResult(d.correct, typed, ok))
        }
        drill = q.nextDrill()
        typed = ""
        revealBox = response == RapidFireResponse.TYPE
        step += 1
    }

    // Play each streamed item.
    LaunchedEffect(step) {
        if (phase != RfPhase.RUNNING || step <= 0) return@LaunchedEffect
        val d = drill ?: return@LaunchedEffect
        revealBox = response == RapidFireResponse.TYPE
        player.play(d.playable, Settings.sidetoneHz, Settings.timing()) { toneEndedStep = step }
    }

    // After a tone ends, wait the pace gap, then advance (auto-stream).
    LaunchedEffect(toneEndedStep) {
        if (toneEndedStep <= 0 || phase != RfPhase.RUNNING || toneEndedStep != step) return@LaunchedEffect
        if (response == RapidFireResponse.HEAD_COPY) revealBox = true
        delay((pace.seconds * 1000).toLong())
        if (toneEndedStep == step && phase == RfPhase.RUNNING) advance()
    }

    when (phase) {
        RfPhase.SETUP -> {
            BackHandler { onBack() }
            RapidFireSetup(
                content = content, onContent = { content = it },
                response = response, onResponse = { response = it },
                pace = pace, onPace = { pace = it },
                wordMin = wordMin, wordMax = wordMax,
                onWordMin = { wordMin = it.coerceIn(2, wordMax) },
                onWordMax = { wordMax = it.coerceIn(wordMin, 12) },
                numberCount = numberCount, onNumberCount = { numberCount = it.coerceIn(1, 10) },
                usOnly = usOnly, onUsOnly = { usOnly = it },
                onStart = { startRun() },
                onBack = onBack
            )
        }

        RfPhase.RUNNING -> {
            BackHandler { finishRun() }
            RapidFireRun(
                summary = quiz?.summary ?: "",
                count = transcript.size,
                response = response,
                revealBox = revealBox,
                typed = typed,
                onTyped = { typed = it },
                onNext = {
                    // Submit the current copy now and stream the next.
                    if (response != RapidFireResponse.REVIEW) advance() else { /* review: auto only */ }
                },
                onDone = { finishRun() }
            )
        }

        RfPhase.SUMMARY -> {
            BackHandler { onBack() }
            RapidFireSummary(
                results = transcript.toList(),
                response = response,
                onAgain = { phase = RfPhase.SETUP },
                onBack = onBack
            )
        }
    }
}

@Composable
private fun RapidFireSetup(
    content: RapidFireContent, onContent: (RapidFireContent) -> Unit,
    response: RapidFireResponse, onResponse: (RapidFireResponse) -> Unit,
    pace: RapidFirePace, onPace: (RapidFirePace) -> Unit,
    wordMin: Int, wordMax: Int, onWordMin: (Int) -> Unit, onWordMax: (Int) -> Unit,
    numberCount: Int, onNumberCount: (Int) -> Unit,
    usOnly: Boolean, onUsOnly: (Boolean) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Brand.teal) }
            Text("Rapid Fire", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionLabel("WHAT TO COPY")
            Pills(RapidFireContent.entries.map { it to it.label }, content, onContent)
            when (content) {
                RapidFireContent.WORDS -> {
                    Stepper("Min length", wordMin, onWordMin)
                    Stepper("Max length", wordMax, onWordMax)
                }
                RapidFireContent.NUMBERS -> Stepper("Digits per group", numberCount, onNumberCount)
                RapidFireContent.CALLSIGNS, RapidFireContent.MIXED -> ToggleRow("US calls only", usOnly, onUsOnly)
                else -> {}
            }

            SectionLabel("HOW TO COPY")
            Column(modifier = Modifier.fillMaxWidth().brandCard()) {
                RapidFireResponse.entries.forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onResponse(r) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.label, color = if (r == response) Brand.teal else Brand.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text(r.blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
                        }
                        if (r == response) Text("✓", color = Brand.teal, fontWeight = FontWeight.Bold)
                    }
                }
            }

            SectionLabel("PACE")
            Pills(RapidFirePace.entries.map { it to it.label }, pace, onPace)

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Brand.cornerRadius)
            ) { Text("Start", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RapidFireRun(
    summary: String,
    count: Int,
    response: RapidFireResponse,
    revealBox: Boolean,
    typed: String,
    onTyped: (String) -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$count sent", style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(120.dp).background(Brand.navyRaised, RoundedCornerShape(60.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("📡", fontSize = 44.sp)
            }
            Spacer(Modifier.height(20.dp))

            when (response) {
                RapidFireResponse.REVIEW -> Text("Copy along — review the list when you're done.", color = Brand.textSecondary, textAlign = TextAlign.Center)
                RapidFireResponse.HEAD_COPY -> if (!revealBox) {
                    Text("Copy it in your head…", color = Brand.textSecondary, textAlign = TextAlign.Center)
                }
                else -> {}
            }

            if (response != RapidFireResponse.REVIEW && revealBox) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = onTyped,
                    singleLine = true,
                    placeholder = { Text("Type what you copy", color = Brand.textSecondary) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onNext() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand.teal,
                        unfocusedBorderColor = Brand.hairline,
                        focusedTextColor = Brand.textPrimary,
                        unfocusedTextColor = Brand.textPrimary,
                        cursorColor = Brand.teal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (response != RapidFireResponse.REVIEW) {
                OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Next ▸") }
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

@Composable
private fun RapidFireSummary(
    results: List<RfResult>,
    response: RapidFireResponse,
    onAgain: () -> Unit,
    onBack: () -> Unit
) {
    val graded = results.count { it.correct != null }
    val correct = results.count { it.correct == true }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Brand.teal) }
            Text("Rapid Fire — Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (response != RapidFireResponse.REVIEW && graded > 0) {
            Text(
                "$correct / $graded correct (${(100.0 * correct / graded).roundToInt()}%)",
                style = MaterialTheme.typography.titleMedium,
                color = Brand.teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else {
            Text("${results.size} sent", style = MaterialTheme.typography.titleMedium, color = Brand.teal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
        ) {
            items(results.size, key = { it }) { i ->
                val r = results[i]
                Row(
                    modifier = Modifier.fillMaxWidth().brandCard().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(r.sent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Brand.textPrimary, modifier = Modifier.weight(1f))
                    r.typed?.let {
                        Text("you: ${it.ifEmpty { "—" }}", fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
                    }
                    r.correct?.let {
                        Text(if (it) "✓" else "✗", color = if (it) OK else ERR, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Practice again") }
            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

// ---- Small reusable bits ----

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun <T> Pills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "Decrease", tint = Brand.teal) }
            Text("$value", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Brand.textPrimary, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Increase", tint = Brand.teal) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Brand.navy, checkedTrackColor = Brand.teal))
    }
}
