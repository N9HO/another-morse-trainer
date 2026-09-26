package app.anothermorsetrainer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.CallsignFormat
import app.anothermorsetrainer.morsekit.CallsignGenerator
import app.anothermorsetrainer.morsekit.KeyingRecorder
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.MorseDecoder
import app.anothermorsetrainer.morsekit.SendingAnalysis
import app.anothermorsetrainer.morsekit.SendingDrill
import app.anothermorsetrainer.morsekit.SendingKeyType
import app.anothermorsetrainer.morsekit.SendingRecord
import app.anothermorsetrainer.morsekit.SendingTargets
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the text to send comes from. */
enum class AnalyzerSource(val id: String) {
    PANGRAM("pangram"), CALLSIGNS("callsigns"), WORDS("words"), GROUPS("groups"), CUSTOM("custom");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: PANGRAM
    }
}

enum class AnalyzerInput(val id: String) {
    KEY("key"), MICROPHONE("microphone");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: KEY
    }
}

/** Microphone sensitivity: the detector's required tone-over-room ratio. */
enum class MicSensitivity(val id: String, val snr: Double) {
    LOW("low", 6.0), NORMAL("normal", 4.0), HIGH("high", 2.5);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/**
 * Drives the Sending Analyzer (#241, #234, #235): pick a text, send it on any
 * key — on-screen, a Vail adapter or other MIDI key, or a tone the microphone
 * hears — and get back what was copied, how it lines up against the text, and
 * how the elements and spacing compare with 1:3 / 1:3:7.
 *
 * The analysis is MorseKit's [SendingAnalysis]; this only collects key edges
 * into a [KeyingRecorder] and keeps the analyzer's own small settings and its
 * [SendingRecord]. Any keyer that can report "down at t / up at t" plugs in
 * through [keyEdge] — the on-screen paddles (#233) included. Port of the iOS
 * `SendingAnalyzerModel`.
 *
 * The attempt in progress is not saved across process death; the settings and
 * the record are (in preferences), so a reclaimed screen reopens at setup.
 */
class SendingAnalyzerController(private val context: Context) {
    enum class Phase { SETUP, SENDING, RESULTS }

    private val prefs = SendingStore.prefs(context)

    var phase by mutableStateOf(Phase.SETUP)
        private set
    var target by mutableStateOf("")
        private set
    var liveText by mutableStateOf("")
        private set
    var analysis by mutableStateOf<SendingAnalysis?>(null)
        private set
    /** Bumped whenever the (mutable) record changes, so readers recompose. */
    var recordRevision by mutableIntStateOf(0)
        private set
    val record: SendingRecord = SendingStore.loadRecord(context)

    var source by mutableStateOf(AnalyzerSource.fromId(prefs.getString("source", null)))
        private set
    var customText by mutableStateOf(prefs.getString("custom", "") ?: "")
        private set
    var keyType by mutableStateOf(SendingKeyType.fromId(prefs.getString("keyType", null)))
        private set
    var input by mutableStateOf(AnalyzerInput.fromId(prefs.getString("input", null)))
        private set
    var sensitivity by mutableStateOf(MicSensitivity.fromId(prefs.getString("sensitivity", null)))
        private set
    var targetWpm by mutableIntStateOf(prefs.getInt("wpm", 20).coerceIn(5, 50))
        private set
    var farnsworth by mutableStateOf(prefs.getBoolean("farnsworth", false))
        private set
    var effectiveWpm by mutableIntStateOf(prefs.getInt("effectiveWpm", 10).coerceIn(5, 50))
        private set

    /**
     * Whether this attempt was keyed on the on-screen paddles (#233). Their
     * keyer times every element at the app's Settings speed, so the attempt is
     * judged as paddles whatever the key picker says; the picker is for the
     * key the operator brings (a Vail adapter, a MIDI key, the microphone).
     */
    var onScreenPaddlesUsed by mutableStateOf(false)
        private set

    /** The key type the attempt is judged as. Mirrors iOS `judgedKeyType`. */
    val judgedKeyType: SendingKeyType
        get() = if (onScreenPaddlesUsed) SendingKeyType.KEYER else keyType

    val mic = SendingMicInput()
    val sidetone = SidetoneGenerator(Settings.sidetoneHz)
    private val recorder = KeyingRecorder()
    private var finishJob: Job? = null
    var scope: CoroutineScope? = null

    init {
        mic.onEdge = { down, ms -> if (input == AnalyzerInput.MICROPHONE) keyEdge(down, ms) }
        newTarget()
    }

    private fun save() {
        prefs.edit {
            putString("source", source.id)
            putString("custom", customText)
            putString("keyType", keyType.id)
            putString("input", input.id)
            putString("sensitivity", sensitivity.id)
            putInt("wpm", targetWpm)
            putBoolean("farnsworth", farnsworth)
            putInt("effectiveWpm", effectiveWpm)
        }
    }

    fun pickSource(s: AnalyzerSource) {
        if (s == source) return
        source = s; save(); newTarget()
    }

    fun editCustom(text: String) {
        customText = text; save()
        if (source == AnalyzerSource.CUSTOM) newTarget()
    }

    fun pickKeyType(k: SendingKeyType) { keyType = k; save() }
    fun pickSensitivity(s: MicSensitivity) { sensitivity = s; save(); mic.setSensitivity(s.snr) }
    fun changeWpm(delta: Int) { targetWpm = (targetWpm + delta).coerceIn(5, 50); save() }
    fun toggleFarnsworth(on: Boolean) { farnsworth = on; save() }
    fun changeEffective(delta: Int) { effectiveWpm = (effectiveWpm + delta).coerceIn(5, targetWpm); save() }

    /** Only one input runs at a time: the microphone would hear the key's sidetone. */
    fun pickInput(i: AnalyzerInput) {
        input = i; save()
        applyInput()
    }

    fun applyInput() {
        when (input) {
            AnalyzerInput.KEY -> { mic.stop(); sidetone.start() }
            AnalyzerInput.MICROPHONE -> { sidetone.stop(); mic.start(context, sensitivity.snr) }
        }
    }

    fun stopAll() {
        finishJob?.cancel()
        sidetone.stop()
        mic.stop()
    }

    /** Re-arm the microphone's pitch search; an attempt in progress restarts with it. */
    fun refindPitch() {
        if (input != AnalyzerInput.MICROPHONE) return
        if (phase == Phase.SENDING) {
            finishJob?.cancel()
            recorder.reset()
            liveText = ""
        }
        mic.stop()
        mic.start(context, sensitivity.snr)
    }

    fun newTarget() {
        val text = when (source) {
            AnalyzerSource.PANGRAM -> SendingTargets.pangrams.filter { it != target }.random()
            AnalyzerSource.CALLSIGNS -> (0 until 5).joinToString(" ") {
                CallsignGenerator.generate(CallsignFormat.commonDefaults, usOnly = false, rng = Random.Default)
            }
            AnalyzerSource.WORDS -> {
                val pool = MorseData.rankedWords.take(200)
                (0 until 6).joinToString(" ") { pool.random() }
            }
            AnalyzerSource.GROUPS -> SendingDrill.generate(
                kind = SendingDrill.Kind.Studied, studied = studiedCharacters(),
                groupCount = 5, groupSize = 5, groupsPerRow = 5
            ).rows.joinToString(" ")
            AnalyzerSource.CUSTOM -> customText
        }
        target = SendingAnalysis.normalizedTarget(text)
        if (phase == Phase.RESULTS) phase = Phase.SETUP
    }

    val canStart: Boolean get() = target.isNotEmpty()

    fun startSending() {
        if (!canStart) return
        finishJob?.cancel()
        recorder.reset()
        onScreenPaddlesUsed = false
        liveText = ""
        analysis = null
        phase = Phase.SENDING
        if (input == AnalyzerInput.MICROPHONE && !mic.isListening) applyInput()
    }

    /** On-screen or MIDI key edge, in [System.nanoTime] terms. */
    fun keyEdgeNanos(down: Boolean, atNanos: Long) {
        if (input != AnalyzerInput.KEY) return
        keyEdge(down, atNanos / 1_000_000.0)
    }

    /**
     * An edge from the on-screen paddles, at the time their keyer scheduled it
     * ([System.currentTimeMillis] terms, as [PaddleKeyerDriver] keeps it). The
     * analyzer's clock is [System.nanoTime], so the edge is moved onto it by
     * the two clocks' present offset; the element lengths recorded are the
     * keyer's, never the coroutine's wake-up jitter.
     */
    fun paddleEdge(down: Boolean, atWallMs: Long) {
        if (input != AnalyzerInput.KEY) return
        if (phase == Phase.SENDING) onScreenPaddlesUsed = true
        sidetone.setKeyDown(down)
        val offsetMs = System.nanoTime() / 1_000_000.0 - System.currentTimeMillis()
        keyEdge(down, atWallMs + offsetMs)
    }

    /** One edge from any input. */
    fun keyEdge(down: Boolean, atMs: Double) {
        if (phase != Phase.SENDING) return
        finishJob?.cancel()
        if (down) {
            recorder.keyDown(atMs)
            return
        }
        recorder.keyUp(atMs)
        val live = analyse()
        liveText = live.decodedText
        // Once everything has been sent, a pause finishes the attempt: longer
        // than any word gap at the speed actually sent, never under 1.5 s.
        val wanted = target.count { it != ' ' }
        if (live.sentCharacters.size < wanted) return
        val waitMs = (live.unitMs * 12).coerceIn(1500.0, 4000.0).toLong()
        finishJob = scope?.launch {
            delay(waitMs)
            if (!recorder.isDown) finish()
        }
    }

    /**
     * On the on-screen paddles the elements are the keyer's, made at the
     * Settings character speed, so that is the speed they are read against (it
     * decides dit from dah when a text is all one kind, like "5" or "E").
     * Speed is not judged for a keyer, so nothing is marked down for it.
     * Mirrors iOS `analyse()`.
     */
    private fun analyse(): SendingAnalysis {
        val characterWpm = if (onScreenPaddlesUsed) Settings.characterWpm else targetWpm.toDouble()
        return SendingAnalysis(
            recorder.marks, target, judgedKeyType, characterWpm,
            if (farnsworth) minOf(effectiveWpm.toDouble(), characterWpm) else characterWpm
        )
    }

    fun finish() {
        finishJob?.cancel()
        if (phase != Phase.SENDING) return
        val result = analyse()
        analysis = result
        if (!result.isEmpty) {
            record.record(result)
            SendingStore.saveRecord(context, record)
            recordRevision++
        }
        phase = Phase.RESULTS
    }

    fun cancelSending() {
        finishJob?.cancel()
        recorder.reset()
        onScreenPaddlesUsed = false
        liveText = ""
        phase = Phase.SETUP
    }

    fun clearRecord() {
        record.characters.clear()
        record.pairs.clear()
        record.mixups.restore(emptyMap())
        record.attempts.clear()
        SendingStore.clearRecord(context)
        recordRevision++
    }
}

@Composable
fun SendingAnalyzerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { SendingAnalyzerController(context) }
    val midi = remember { HardwareKey(context) }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    var midiDevice by remember { mutableStateOf<String?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // start() flags micDenied itself when the permission was refused.
        controller.applyInput()
    }

    fun chooseInput(i: AnalyzerInput) {
        if (i == AnalyzerInput.MICROPHONE &&
            ContextCompatCheck.missingMic(context)
        ) {
            controller.pickInput(i)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            controller.pickInput(i)
        }
    }

    DisposableEffect(Unit) {
        controller.scope = scope
        if (controller.input == AnalyzerInput.MICROPHONE && ContextCompatCheck.missingMic(context)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            controller.applyInput()
        }
        midi.start(
            onKey = { down -> if (controller.input == AnalyzerInput.KEY) controller.sidetone.setKeyDown(down) },
            onConnected = { name -> midiDevice = name },
            onKeyTimed = { down, nanos -> controller.keyEdgeNanos(down, nanos) }
        )
        onDispose {
            midi.stop()
            controller.stopAll()
        }
    }
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(
                stringResource(R.string.mode_sending_analyzer),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        CenteredContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (controller.phase) {
                    SendingAnalyzerController.Phase.SETUP -> SetupSection(controller, ::chooseInput)
                    SendingAnalyzerController.Phase.SENDING -> SendingSection(controller, midi, midiDevice)
                    SendingAnalyzerController.Phase.RESULTS -> controller.analysis?.let { ResultsSection(controller, it) }
                }
                RecordSection(controller)
            }
        }
    }
}

/** RECORD_AUDIO check, kept in one place for the screen. */
private object ContextCompatCheck {
    fun missingMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
}

// ---- Shared bits ----

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            title.uppercase(Locale.ROOT),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = Brand.textSecondary
        )
        content()
    }
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
                Text(
                    label,
                    color = if (sel) Brand.navy else Brand.textSecondary,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color = Brand.textSecondary) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun TargetText(text: String) {
    SlashableText(
        text = text.ifEmpty { "—" },
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        color = Brand.textPrimary
    )
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Brand.textPrimary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, modifier = Modifier.width(52.dp)) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onPlus, modifier = Modifier.width(52.dp)) { Text("+") }
    }
}

@Composable
private fun sourceLabel(s: AnalyzerSource) = stringResource(
    when (s) {
        AnalyzerSource.PANGRAM -> R.string.analyzer_source_pangram
        AnalyzerSource.CALLSIGNS -> R.string.analyzer_source_callsigns
        AnalyzerSource.WORDS -> R.string.analyzer_source_words
        AnalyzerSource.GROUPS -> R.string.analyzer_source_groups
        AnalyzerSource.CUSTOM -> R.string.analyzer_source_custom
    }
)

// ---- Setup ----

@Composable
private fun SetupSection(c: SendingAnalyzerController, chooseInput: (AnalyzerInput) -> Unit) {
    Card(stringResource(R.string.analyzer_send_this)) {
        Pills(AnalyzerSource.entries.map { it to sourceLabel(it) }, c.source) { c.pickSource(it) }
        if (c.source == AnalyzerSource.CUSTOM) {
            OutlinedTextField(
                value = c.customText,
                onValueChange = { c.editCustom(it) },
                placeholder = { Text(stringResource(R.string.analyzer_custom_placeholder)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        TargetText(c.target)
        if (c.source != AnalyzerSource.CUSTOM) {
            OutlinedButton(onClick = { c.newTarget() }) { Text(stringResource(R.string.analyzer_new_text)) }
        }
    }

    Card(stringResource(R.string.analyzer_your_key)) {
        Pills(SendingKeyType.entries.map { it to keyTypeLabel(it) }, c.keyType) { c.pickKeyType(it) }
        Note(
            stringResource(
                when (c.keyType) {
                    SendingKeyType.STRAIGHT -> R.string.analyzer_key_note_straight
                    SendingKeyType.BUG -> R.string.analyzer_key_note_bug
                    SendingKeyType.COOTIE -> R.string.analyzer_key_note_cootie
                    SendingKeyType.KEYER -> R.string.analyzer_key_note_keyer
                }
            )
        )
        if (c.input == AnalyzerInput.KEY && Settings.onScreenKey == OnScreenKeyType.PADDLES) {
            Note(stringResource(R.string.analyzer_onscreen_paddles_note), Brand.teal)
        }
    }

    Card(stringResource(R.string.analyzer_input)) {
        Pills(
            listOf(
                AnalyzerInput.KEY to stringResource(R.string.analyzer_input_key),
                AnalyzerInput.MICROPHONE to stringResource(R.string.analyzer_input_microphone)
            ),
            c.input
        ) { chooseInput(it) }
        if (c.input == AnalyzerInput.MICROPHONE) {
            MicPanel(c)
        } else {
            Note(stringResource(R.string.analyzer_input_key_note))
        }
    }

    Card(stringResource(R.string.analyzer_judged_against)) {
        Stepper(stringResource(R.string.common_wpm_value, c.targetWpm.toString()), { c.changeWpm(-1) }, { c.changeWpm(1) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.analyzer_farnsworth), color = Brand.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = c.farnsworth,
                onCheckedChange = { c.toggleFarnsworth(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Brand.navy, checkedTrackColor = Brand.teal)
            )
        }
        if (c.farnsworth) {
            Stepper(
                stringResource(R.string.analyzer_effective_wpm, minOf(c.effectiveWpm, c.targetWpm)),
                { c.changeEffective(-1) }, { c.changeEffective(1) }
            )
        }
    }

    Button(
        onClick = { c.startSending() },
        enabled = c.canStart,
        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
        shape = RoundedCornerShape(Brand.cornerRadius),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    ) { Text(stringResource(R.string.analyzer_start_sending), fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun keyTypeLabel(k: SendingKeyType) = stringResource(
    when (k) {
        SendingKeyType.STRAIGHT -> R.string.analyzer_key_straight
        SendingKeyType.BUG -> R.string.analyzer_key_bug
        SendingKeyType.COOTIE -> R.string.analyzer_key_cootie
        SendingKeyType.KEYER -> R.string.analyzer_key_keyer
    }
)

// ---- Microphone ----

@Composable
private fun MicPanel(c: SendingAnalyzerController) {
    val mic = c.mic
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val toneLabel = stringResource(if (mic.toneOn) R.string.analyzer_tone_heard else R.string.analyzer_no_tone)
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (mic.toneOn) Brand.tealBright else Brand.navyRaised)
                    .semantics { contentDescription = toneLabel }
            )
            Spacer(Modifier.width(10.dp))
            LevelMeter(mic.level, mic.noiseFloor, Modifier.weight(1f).height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hz = mic.pitchHz
            Text(
                when {
                    !mic.isListening -> stringResource(R.string.analyzer_mic_off)
                    hz == null -> stringResource(R.string.analyzer_listening_for_tone)
                    mic.pitchLocked -> stringResource(R.string.analyzer_pitch_locked, hz.roundToInt())
                    else -> stringResource(R.string.common_hz_value, hz.roundToInt())
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Brand.textSecondary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { c.refindPitch() }, enabled = mic.isListening) {
                Text(stringResource(R.string.analyzer_find_pitch_again), color = Brand.teal, fontSize = 13.sp)
            }
        }
        Pills(
            listOf(
                MicSensitivity.LOW to stringResource(R.string.analyzer_sensitivity_low),
                MicSensitivity.NORMAL to stringResource(R.string.analyzer_sensitivity_normal),
                MicSensitivity.HIGH to stringResource(R.string.analyzer_sensitivity_high)
            ),
            c.sensitivity
        ) { c.pickSensitivity(it) }
        if (mic.micDenied) {
            Note(stringResource(R.string.decoder_mic_denied), Color(0xFFE08A1E))
        } else {
            Note(stringResource(R.string.analyzer_mic_note))
        }
    }
}

/** Tone level on a log scale (−60…0 dB of full scale), with the floor marked. */
@Composable
private fun LevelMeter(level: Double, floor: Double, modifier: Modifier) {
    fun fraction(v: Double): Float {
        val db = 20 * log10(max(v, 1e-6))
        return ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
    }
    Box(modifier = modifier.clip(RoundedCornerShape(5.dp)).background(Brand.navyRaised)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction(level)).background(Brand.teal))
        Row(Modifier.fillMaxSize()) {
            val f = fraction(floor)
            if (f > 0f) Spacer(Modifier.weight(f))
            Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFFE08A1E)))
            if (f < 1f) Spacer(Modifier.weight(1f - f))
        }
    }
}

// ---- Sending ----

@Composable
private fun SendingSection(c: SendingAnalyzerController, midi: HardwareKey, midiDevice: String?) {
    Card(stringResource(R.string.analyzer_send_this)) { TargetText(c.target) }
    Card(stringResource(R.string.analyzer_copied_so_far)) {
        SlashableText(
            text = c.liveText.ifEmpty { "—" },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = if (c.liveText.isEmpty()) Brand.textSecondary else Brand.tealBright
        )
    }
    if (c.input == AnalyzerInput.KEY) {
        var keyPressed by remember { mutableStateOf(false) }
        // Straight key or paddles, as chosen in Settings › Keys & Sending ›
        // On-screen key (#233). The paddles' edges carry the time their keyer
        // scheduled them for.
        OnScreenKeySwitch(
            onPaddleKey = { down, ms -> c.paddleEdge(down, ms) },
            modifier = Modifier.fillMaxWidth().height(140.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(Brand.cornerRadius))
                    .background(if (keyPressed) Brand.teal else Brand.navyRaised)
                    .border(
                        width = if (keyPressed) 2.dp else 1.dp,
                        color = if (keyPressed) Brand.tealBright else Brand.hairline,
                        shape = RoundedCornerShape(Brand.cornerRadius)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                keyPressed = true
                                c.sidetone.setKeyDown(true)
                                c.keyEdgeNanos(true, System.nanoTime())
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    keyPressed = false
                                    c.sidetone.setKeyDown(false)
                                    c.keyEdgeNanos(false, System.nanoTime())
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⠿", fontSize = 26.sp, color = if (keyPressed) Brand.navy else Brand.teal)
                    Text(
                        stringResource(R.string.common_hold_to_key),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (keyPressed) Brand.navy else Brand.textSecondary
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (midiDevice != null) {
                Text("🎹 $midiDevice", style = MaterialTheme.typography.labelSmall, color = Brand.teal)
            } else {
                val unavailable = remember { midi.isUnavailable }
                Text(
                    stringResource(if (unavailable) R.string.sending_midi_unavailable else R.string.sending_no_hardware_key),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unavailable) Color(0xFFEF6C00) else Brand.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            BluetoothKeyButton()
        }
    } else {
        Card(stringResource(R.string.analyzer_input_microphone)) { MicPanel(c) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { c.cancelSending() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.common_cancel))
        }
        Button(
            onClick = { c.finish() },
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
        ) { Text(stringResource(R.string.analyzer_finish), fontWeight = FontWeight.SemiBold) }
    }
    Note(stringResource(R.string.analyzer_sending_note))
}

// ---- Results ----

@Composable
private fun ResultsSection(c: SendingAnalyzerController, a: SendingAnalysis) {
    if (a.isEmpty) {
        Card(stringResource(R.string.analyzer_nothing_heard)) {
            Note(
                stringResource(
                    if (c.input == AnalyzerInput.MICROPHONE) R.string.analyzer_nothing_heard_mic
                    else R.string.analyzer_nothing_heard_key
                )
            )
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth().brandCard().padding(vertical = 12.dp)) {
            SummaryStat("${(a.accuracy * 100).roundToInt()}%", stringResource(R.string.analyzer_correct), Modifier.weight(1f))
            SummaryStat("${a.characterWpm.roundToInt()}", stringResource(R.string.analyzer_char_wpm), Modifier.weight(1f))
            SummaryStat("${a.effectiveWpm.roundToInt()}", stringResource(R.string.analyzer_overall_wpm), Modifier.weight(1f))
        }

        Card(stringResource(R.string.analyzer_what_you_sent)) {
            Text(alignedText(a), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Note(stringResource(R.string.analyzer_alignment_legend))
            a.alignment.filter { it.kind != SendingAnalysis.AlignmentOp.Kind.MATCH }.forEach { op ->
                Text(
                    describe(op),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.textSecondary
                )
            }
        }

        Card(stringResource(R.string.analyzer_feedback)) {
            a.feedback.forEach { code ->
                Row {
                    Text(
                        if (code.isPraise) "✓" else "!",
                        color = if (code.isPraise) Brand.tealBright else Color(0xFFE08A1E),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(a.message(code), style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
                }
            }
            if (a.feedback.isEmpty()) Note(stringResource(R.string.analyzer_send_more))
        }

        Card(stringResource(R.string.analyzer_spacing)) {
            if (a.characterGaps.count > 0) {
                Histogram(
                    stringResource(R.string.analyzer_between_characters),
                    a.characterGapHistogram, SendingAnalysis.CHAR_GAP_BIN_LABELS, SendingAnalysis.IDEAL_CHAR_GAP_BIN
                )
            }
            if (a.wordGaps.count > 0) {
                Histogram(
                    stringResource(R.string.analyzer_between_words),
                    a.wordGapHistogram, SendingAnalysis.WORD_GAP_BIN_LABELS, SendingAnalysis.IDEAL_WORD_GAP_BIN
                )
            }
            Note(spacingSummary(a))
        }

        Card(stringResource(R.string.analyzer_elements)) {
            val targetUnit = 1200.0 / a.targetCharacterWpm
            ElementRow(stringResource(R.string.analyzer_dit), a.dits, targetUnit, a.targetCharacterWpm)
            ElementRow(stringResource(R.string.analyzer_dah), a.dahs, targetUnit, a.targetCharacterWpm)
            a.dahDitRatio?.let {
                KeyValue(stringResource(R.string.analyzer_dah_dit), String.format(Locale.US, "%.1f : 1 (aim 3 : 1)", it))
            }
            if (a.elementGaps.count > 0) {
                KeyValue(
                    stringResource(R.string.analyzer_gap_inside),
                    String.format(Locale.US, "%.1f ± %.1f units (aim 1)", a.elementGaps.mean, a.elementGaps.sd)
                )
            }
            if (!a.keyType.handTimesElements) Note(stringResource(R.string.analyzer_keyer_elements_note))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { c.newTarget() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.analyzer_new_text))
        }
        Button(
            onClick = { c.startSending() },
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
        ) { Text(stringResource(R.string.analyzer_send_again), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Brand.textPrimary, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
    }
}

private fun alignedText(a: SendingAnalysis): AnnotatedString = buildAnnotatedString {
    for (op in a.alignment) {
        when (op.kind) {
            SendingAnalysis.AlignmentOp.Kind.MATCH -> {
                pushStyle(SpanStyle(color = Brand.textPrimary)); append(op.expected ?: ' '); pop()
            }
            SendingAnalysis.AlignmentOp.Kind.SUBSTITUTE -> {
                pushStyle(SpanStyle(color = Color(0xFFE53935), textDecoration = TextDecoration.Underline))
                append(op.expected ?: ' '); pop()
            }
            SendingAnalysis.AlignmentOp.Kind.MISSING -> {
                pushStyle(SpanStyle(color = Color(0xFFE08A1E), textDecoration = TextDecoration.LineThrough))
                append(if (op.expected == ' ') "⎵" else (op.expected ?: ' ').toString()); pop()
            }
            SendingAnalysis.AlignmentOp.Kind.EXTRA -> {
                pushStyle(SpanStyle(color = Brand.textSecondary, textDecoration = TextDecoration.LineThrough))
                append(if (op.sent == ' ') "⎵" else (op.sent ?: ' ').toString()); pop()
            }
        }
    }
}

private fun pretty(pattern: String?): String = (pattern ?: "").map { if (it == '.') '·' else '−' }.joinToString("")

@Composable
private fun describe(op: SendingAnalysis.AlignmentOp): String {
    val expected = op.expected?.toString() ?: ""
    val unknown = stringResource(R.string.analyzer_unknown_character)
    return when (op.kind) {
        SendingAnalysis.AlignmentOp.Kind.SUBSTITUTE -> {
            val sent = if (op.sent == MorseDecoder.unknownMarker) unknown else op.sent.toString()
            stringResource(
                R.string.analyzer_sent_as, expected, pretty(op.expected?.let { MorseCode.pattern(it) }),
                sent, pretty(op.sentPattern)
            )
        }
        SendingAnalysis.AlignmentOp.Kind.MISSING ->
            if (op.expected == ' ') stringResource(R.string.analyzer_break_too_short)
            else stringResource(R.string.analyzer_left_out, expected)
        SendingAnalysis.AlignmentOp.Kind.EXTRA ->
            if (op.sent == ' ') stringResource(R.string.analyzer_gap_read_as_break)
            else stringResource(
                R.string.analyzer_extra,
                if (op.sent == MorseDecoder.unknownMarker) unknown else op.sent.toString(),
                pretty(op.sentPattern)
            )
        SendingAnalysis.AlignmentOp.Kind.MATCH -> expected
    }
}

@Composable
private fun Histogram(title: String, counts: List<Int>, labels: List<String>, ideal: Int) {
    val top = maxOf(1, counts.maxOrNull() ?: 1)
    val described = counts.indices.filter { counts[it] > 0 }.joinToString(", ") { "${counts[it]} at ${labels[it]}" }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics { contentDescription = "$title: $described" }
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        Row(
            modifier = Modifier.fillMaxWidth().height(100.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            counts.forEachIndexed { i, n ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (n > 0) "$n" else " ", fontSize = 10.sp, color = Brand.textSecondary)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(maxOf(2f, 64f * n / top).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i == ideal) Brand.tealBright else Brand.teal.copy(alpha = 0.45f))
                    )
                    Text(labels[i], fontSize = 10.sp, color = if (i == ideal) Brand.tealBright else Brand.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun spacingSummary(a: SendingAnalysis): String {
    val parts = mutableListOf<String>()
    if (a.characterGaps.count > 0) {
        parts += String.format(Locale.US, "Characters %.1f ± %.1f", a.characterGaps.mean, a.characterGaps.sd)
    }
    if (a.wordGaps.count > 0) {
        parts += String.format(Locale.US, "words %.1f ± %.1f", a.wordGaps.mean, a.wordGaps.sd)
    }
    if (parts.isEmpty()) return stringResource(R.string.analyzer_spacing_need_more)
    val unit = if (a.farnsworthFactor > 1.001) "Farnsworth spacing units" else "units"
    return parts.joinToString(", ") + " $unit."
}

@Composable
private fun ElementRow(name: String, s: SendingAnalysis.Stat, targetUnit: Double, wpm: Double) {
    if (s.count == 0) return
    KeyValue(
        "$name ×${s.count}",
        String.format(Locale.US, "%.0f ± %.0f ms (%.1f units at %d WPM)", s.mean, s.sd, s.mean / targetUnit, wpm.roundToInt())
    )
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Brand.textSecondary, textAlign = TextAlign.End)
    }
}

// ---- Record ----

@Composable
private fun RecordSection(c: SendingAnalyzerController) {
    @Suppress("UNUSED_VARIABLE") val rev = c.recordRevision
    val record = c.record
    if (record.isEmpty) return
    var confirming by remember { mutableStateOf(false) }
    Card(stringResource(R.string.analyzer_over_time)) {
        val chars = record.problemCharacters()
        val pairs = record.problemPairs()
        val mixups = record.mixups.entries().take(5)
        if (chars.isEmpty() && pairs.isEmpty() && mixups.isEmpty()) {
            Note(stringResource(R.string.analyzer_no_trouble_yet))
        }
        fun pct(t: SendingRecord.Tally) = "${(t.missRate * 100).roundToInt()}% missed (${t.misses}/${t.attempts})"
        if (chars.isNotEmpty()) RecordLine(stringResource(R.string.analyzer_characters), chars.map { "${it.first} ${pct(it.second)}" })
        if (pairs.isNotEmpty()) RecordLine(stringResource(R.string.analyzer_pairs), pairs.map { "${it.first} ${pct(it.second)}" })
        if (mixups.isNotEmpty()) RecordLine(stringResource(R.string.analyzer_mixups), mixups.map { "${it.target}→${it.chosen} ×${it.count}" })
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        record.attempts.takeLast(5).reversed().forEach { a ->
            Text(
                "${fmt.format(Date(a.epochMs))} · ${(a.accuracy * 100).roundToInt()}% · ${a.characterWpm.roundToInt()} WPM",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Brand.textSecondary
            )
        }
        if (confirming) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = { confirming = false; c.clearRecord() }) {
                    Text(stringResource(R.string.analyzer_clear_record_confirm), color = Color(0xFFE53935))
                }
            }
        } else {
            TextButton(onClick = { confirming = true }) {
                Text(stringResource(R.string.analyzer_clear_record), color = Color(0xFFE53935))
            }
        }
    }
}

@Composable
private fun RecordLine(title: String, items: List<String>) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        Text(items.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
    }
}
