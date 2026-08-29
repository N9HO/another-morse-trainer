package app.anothermorsetrainer

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings as SettingsGlyph
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseItem
import kotlin.math.roundToInt

/**
 * The training surface that opened Settings mid-session. Mirrors the iOS
 * issue-#66 behavior: sections that only matter to *other* modes are hidden,
 * so Q-Codes practice never scrolls past the word-pool or ladder knobs. The
 * home screen passes null and gets the full surface.
 */
enum class SettingsMode {
    CHARACTERS, WORDS, ABBREVIATIONS, QCODES, PROSIGNS, CONFUSION,
    JOURNEY, TYPE_IT, QRQ, HEAD_COPY, LISTEN, STORY, EXAM,
    PILEUP, CONTEST, RAPID_FIRE, SENDING
}

/** Modes drawing from the Koch ladder — punctuation opt-ins and the starting level shape them. */
private val LADDER_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.CONFUSION, SettingsMode.SENDING
)

/** The choice drills governed by answer choices, recognition target, and reveal. */
private val CHOICE_QUIZ_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION,
    SettingsMode.JOURNEY
)

/** The screens that read the session-length setting. */
private val DURATION_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION,
    SettingsMode.TYPE_IT, SettingsMode.QRQ, SettingsMode.HEAD_COPY,
    SettingsMode.LISTEN, SettingsMode.STORY
)

/** Modes that fix their own speed (QRQ's presets, the exam's 5/13/20 WPM). */
private val OWN_SPEED_MODES = setOf(SettingsMode.QRQ, SettingsMode.EXAM)

/**
 * Compose counts a slider's `steps` as the stops *between* the two ends, so a
 * range walked in whole WPM has one fewer stop than it spans. Derived rather
 * than hardcoded, since the Farnsworth slider's top moves with the character
 * speed (issue #79).
 */
private fun wholeWpmSteps(min: Float, max: Float): Int =
    maxOf(0, (max - min).roundToInt() - 1)

/** Modes with no answer to buzz about — Feedback would be noise. */
private val NO_FEEDBACK_MODES = setOf(
    SettingsMode.LISTEN, SettingsMode.STORY, SettingsMode.EXAM
)

/** The quiz screen is the only surface with spoken answers. */
private val VOICE_ANSWER_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION
)

/**
 * Tune playback to taste: speed, Farnsworth spacing, sidetone pitch, and haptic
 * feedback. Laid out as iOS-style grouped sections (header → rounded card of
 * rows → footer caption). Writes straight through [Settings] (persisted); the
 * Preview button keys a sample so changes are audible immediately.
 *
 * [scope] is the mode that opened this mid-session (null from Home): sections
 * irrelevant to that mode are hidden, matching iOS issue #66.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, scope: SettingsMode? = null) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    BackHandler { onBack() }

    fun shown(modes: Set<SettingsMode>): Boolean = scope == null || scope in modes

    val farnsworthOn = Settings.effectiveWpm < Settings.characterWpm
    var confirmReset by remember { mutableStateOf(false) }

    // Daily reminder: enabling may need the POST_NOTIFICATIONS runtime permission (API 33+).
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Settings.updateRemindersEnabled(true)
            Reminders.schedule(context)
        }
    }
    fun enableReminders() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Settings.updateRemindersEnabled(true)
            Reminders.schedule(context)
        }
    }
    fun disableReminders() {
        Settings.updateRemindersEnabled(false)
        Reminders.cancel(context)
    }
    fun pickTime() {
        TimePickerDialog(
            context,
            { _, h, m ->
                Settings.updateReminderTime(h, m)
                if (Settings.remindersEnabled) Reminders.schedule(context)
            },
            Settings.reminderHour, Settings.reminderMinute, false
        ).show()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("‹ Back", color = Brand.teal) }

        CenteredContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textPrimary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                if (scope != null) {
                    Text(
                        "Showing what applies here — the full settings live on the home screen.",
                        color = Brand.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                if (scope == null || scope !in OWN_SPEED_MODES) {
                    SectionHeader("Speed")
                    SettingsGroup {
                        val minWpm = Settings.MIN_CHARACTER_WPM.toFloat()
                        val maxWpm = Settings.MAX_CHARACTER_WPM.toFloat()
                        SliderSetting(
                            label = "Character speed",
                            value = "${Settings.characterWpm.roundToInt()} WPM",
                            position = Settings.characterWpm.toFloat(),
                            range = minWpm..maxWpm, steps = wholeWpmSteps(minWpm, maxWpm),
                            onChange = { Settings.updateCharacterWpm(it.toDouble()) }
                        )
                        GroupDivider()
                        // Farnsworth only ever *slows* the spacing, so its top is
                        // the character speed itself — and it has to follow that
                        // speed up, or a 60 WPM session couldn't be spaced at 45
                        // (issue #79).
                        val maxFarnsworth = maxOf(minWpm + 1f, Settings.characterWpm.toFloat())
                        SliderSetting(
                            label = "Farnsworth speed",
                            value = if (farnsworthOn) "${Settings.effectiveWpm.roundToInt()} WPM" else "Off",
                            position = Settings.effectiveWpm.toFloat(),
                            range = minWpm..maxFarnsworth, steps = wholeWpmSteps(minWpm, maxFarnsworth),
                            onChange = { Settings.updateEffectiveWpm(it.toDouble()) }
                        )
                    }
                    val qrqNote = if (Settings.characterWpm >= 40) {
                        "\n\nQRQ territory — ${Settings.characterWpm.roundToInt()} WPM. " +
                            "Great for pushing instant recognition once 30+ feels comfortable."
                    } else ""
                    SectionFooter(
                        "Character speed is how fast the dits and dahs are sent. Farnsworth " +
                            "stretches the gaps between characters (set it below the character " +
                            "speed) to give you more time to recognise each one." + qrqNote
                    )
                    // Twin of the iOS warning: slowing the characters is the one
                    // adjustment that works against the method, so say so rather
                    // than letting it pass silently.
                    if (Settings.characterWpm < KOCH_MIN_WPM) {
                        SpeedWarning(
                            "Below ${KOCH_MIN_WPM.toInt()} WPM it's easy to start counting the dits and dahs " +
                                "instead of hearing each character as a single sound. Training at " +
                                "${KOCH_MIN_WPM.toInt()}+ WPM builds instant, by-ear recognition, which is the " +
                                "whole point of the Koch method. If you need more time to answer, " +
                                "raise \"Recognition target\" or lower the Farnsworth speed instead of " +
                                "slowing the code."
                        )
                    }
                }

                SectionHeader("Sound")
                SettingsGroup {
                    SliderSetting(
                        label = "Sidetone pitch",
                        value = "${Settings.sidetoneHz.roundToInt()} Hz",
                        position = Settings.sidetoneHz.toFloat(),
                        range = 300f..1000f, steps = 0,
                        onChange = { Settings.updateSidetoneHz(it.toDouble()) }
                    )
                    GroupDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Preview tone", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        OutlinedButton(onClick = {
                            player.play(MorseItem.Playable.Text("PARIS"), Settings.sidetoneHz, Settings.timing()) {}
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(" Play")
                        }
                    }
                    GroupDivider()
                    BackgroundNoiseSetting()
                }
                SectionFooter(
                    "The frequency of the practice tone you hear.\n\n" +
                        "Background noise plays a faint hiss under everything. On Bluetooth " +
                        "earbuds the silence between transmissions lets the link idle, so it " +
                        "wakes a moment late and clips the first character — costing you the " +
                        "answer. A faint floor keeps the link up, which is why Whisper is the " +
                        "default. Turn it up for band noise (QRN) to copy through, or off if " +
                        "you'd rather have silence."
                )

                val showChoiceRows = shown(CHOICE_QUIZ_MODES)
                val showWordPool = shown(setOf(SettingsMode.WORDS))
                val showDuration = shown(DURATION_MODES)
                if (showChoiceRows || showWordPool || showDuration) {
                    SectionHeader("Practice")
                    SettingsGroup {
                        var needDivider = false
                        if (showChoiceRows) {
                            SegmentedSetting(
                                label = "Answer choices",
                                options = listOf("4" to 4, "5" to 5, "6" to 6),
                                selected = Settings.answerChoices,
                                onSelect = { Settings.updateAnswerChoices(it) }
                            )
                            GroupDivider()
                            SliderSetting(
                                label = "Recognition target",
                                value = "%.1f s".format(Settings.recognitionTargetSec),
                                position = Settings.recognitionTargetSec.toFloat(),
                                range = 0.5f..2.5f, steps = 7,
                                onChange = { Settings.updateRecognitionTargetSec(it.toDouble()) }
                            )
                            needDivider = true
                        }
                        if (showWordPool) {
                            if (needDivider) GroupDivider()
                            SegmentedSetting(
                                label = "Word pool",
                                options = listOf("100" to 100, "300" to 300, "500" to 500),
                                selected = Settings.wordCount,
                                onSelect = { Settings.updateWordCount(it) }
                            )
                            needDivider = true
                        }
                        if (showChoiceRows) {
                            if (needDivider) GroupDivider()
                            SegmentedSetting(
                                label = "Reveal answer",
                                options = RevealMode.entries.map { it.shortLabel to it },
                                selected = Settings.revealMode,
                                onSelect = { Settings.updateRevealMode(it) }
                            )
                            needDivider = true
                        }
                        if (showDuration) {
                            if (needDivider) GroupDivider()
                            DurationSetting()
                        }
                    }
                    SectionFooter(
                        practiceFooter(showChoiceRows, showWordPool, showDuration)
                    )
                }

                if (showWordPool) {
                SectionHeader("My words")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Use my word list", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = Settings.useCustomWords,
                            onCheckedChange = { Settings.updateUseCustomWords(it) },
                            colors = switchColors()
                        )
                    }
                    if (Settings.useCustomWords) {
                        GroupDivider()
                        OutlinedTextField(
                            value = Settings.customWordsText,
                            onValueChange = { Settings.updateCustomWordsText(it) },
                            placeholder = { Text("CQ DX\nANTENNA\nMORSE …") },
                            minLines = 3,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                        )
                    }
                }
                SectionFooter(
                    if (Settings.useCustomWords) {
                        val n = Settings.customWords.size
                        "One word per line (spaces and commas split too). " + when {
                            n >= 2 -> "$n words ready — Common Words drills your list."
                            else -> "Add at least two words; until then the ranked pool is used."
                        }
                    } else {
                        "Practice your own words — a callsign, your name, club abbreviations — " +
                            "in Common Words instead of the ranked pool."
                    }
                )
                }

                if (shown(LADDER_MODES)) {
                SectionHeader("Punctuation")
                SettingsGroup {
                    MorseCode.pickablePunctuation.forEachIndexed { i, ch ->
                        if (i > 0) GroupDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(punctuationLabel(ch), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                            Switch(
                                checked = ch in Settings.punctuationChars,
                                onCheckedChange = {
                                    Settings.togglePunctuation(ch)
                                    // The live ladder picks the new order up immediately.
                                    EngineStore.applyStudyOrder()
                                },
                                colors = switchColors()
                            )
                        }
                    }
                }
                SectionFooter(
                    "Study punctuation too. Opted-in marks join the Characters ladder " +
                        "after the letters and numbers."
                )

                SectionHeader("Starting level")
                SettingsGroup {
                    Proficiency.entries.forEachIndexed { i, level ->
                        if (i > 0) GroupDivider()
                        RadioRow(
                            label = level.label,
                            selected = Settings.proficiency == level,
                            onClick = {
                                Settings.updateProficiency(level)
                                // Restart the ladder from the new seed; per-character
                                // stats and recorded confusions are kept.
                                EngineStore.reseed()
                            }
                        )
                    }
                }
                SectionFooter("How much Morse you already know — sets where the Characters drill begins. Changing this restarts your active set.")
                }

                if (scope == null || scope !in NO_FEEDBACK_MODES) {
                val showVoiceRow = shown(VOICE_ANSWER_MODES)
                SectionHeader("Feedback")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Haptic feedback", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = Settings.hapticsEnabled,
                            onCheckedChange = { Settings.updateHapticsEnabled(it) },
                            colors = switchColors()
                        )
                    }
                    if (showVoiceRow) {
                        GroupDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Voice answers", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                            Switch(
                                checked = Settings.voiceAnswersEnabled,
                                onCheckedChange = { Settings.updateVoiceAnswersEnabled(it) },
                                colors = switchColors()
                            )
                        }
                    }
                }
                SectionFooter(
                    if (showVoiceRow)
                        "Buzz on right and wrong answers. Voice answers let you speak instead of tap (uses the microphone)."
                    else
                        "Buzz on right and wrong answers."
                )
                }

                SectionHeader("Display")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Slashed zero", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = Settings.slashedZero,
                            onCheckedChange = { Settings.updateSlashedZero(it) },
                            colors = switchColors()
                        )
                    }
                }
                SectionFooter("Show the digit 0 with a line through it — the operator's convention for telling 0 from O.")

                SectionHeader("Reminders")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Daily reminder", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = Settings.remindersEnabled,
                            onCheckedChange = { if (it) enableReminders() else disableReminders() },
                            colors = switchColors()
                        )
                    }
                    if (Settings.remindersEnabled) {
                        GroupDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Time", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                            TextButton(onClick = { pickTime() }) {
                                Text(formatTime(Settings.reminderHour, Settings.reminderMinute), color = Brand.teal, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                SectionFooter("A daily nudge to keep your streak alive.")

                // Mid-session the destructive reset stays out of reach — it would
                // yank the engine out from under the running drill. Home only.
                if (scope == null) {
                SectionHeader("Progress")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { confirmReset = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Reset all progress", color = Color(0xFFF2788F), fontWeight = FontWeight.Medium)
                    }
                }
                SectionFooter(
                    "Clears your learned characters, stats, streak, and Journey progress. " +
                        "Settings are kept."
                )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Brand.navyElevated,
            title = { Text("Reset all progress?", color = Brand.textPrimary) },
            text = {
                Text(
                    "This clears your learned characters and stats. Settings are kept.",
                    color = Brand.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    EngineStore.reset()
                    Stats.reset()
                    JourneyStore.reset()
                    confirmReset = false
                }) { Text("Reset", color = Color(0xFFF2788F), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = Brand.teal) }
            }
        )
    }
}

/** The Practice footer, assembled from whichever rows the scope kept. */
private fun practiceFooter(choices: Boolean, wordPool: Boolean, duration: Boolean): String {
    val parts = buildList {
        if (choices) {
            add("how many options each drill shows")
            add("how fast you must answer to count as “mastered”")
        }
        if (wordPool) add("how big the Common Words pool is")
        if (choices) add("when to show the correct answer after you respond")
        if (duration) add("how long a session runs before it ends with a summary")
    }
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

/**
 * Gear for a session header: opens the mode-scoped Settings without leaving
 * the session (iOS has had this from the start; issue #66 scoped it).
 */
@Composable
fun SessionSettingsButton(onOpen: () -> Unit) {
    IconButton(onClick = onOpen) {
        Icon(
            Icons.Filled.SettingsGlyph,
            contentDescription = "Settings",
            tint = Brand.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The mode-scoped Settings drawn over a running session. The session's state —
 * its timer, current drill, tally — stays alive underneath (nothing unmounts)
 * and picks the changed settings up when this closes. Back closes it too.
 */
@Composable
fun SessionSettingsOverlay(scope: SettingsMode, onClose: () -> Unit) {
    BackHandler { onClose() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The same navy gradient AppBackground paints, so the overlay
            // reads as its own opaque screen, not a translucent sheet.
            .background(Brush.verticalGradient(listOf(Brand.gradientTop, Brand.navy)))
            // Swallow taps on empty areas so nothing reaches the session
            // controls still composed underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        SettingsScreen(onBack = onClose, scope = scope)
    }
}

/**
 * Session-length picker: its six options need their own row, so it stacks the
 * label above a scrollable pill row instead of using [SegmentedSetting].
 */
@Composable
private fun DurationSetting() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Session length", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
            Text(Settings.practiceDuration.label, color = Brand.teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PracticeDuration.entries.forEach { option ->
                val isSel = option == Settings.practiceDuration
                Box(
                    modifier = Modifier
                        .background(
                            if (isSel) Brand.teal else Brand.navyRaised,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .clickable { Settings.updatePracticeDuration(option) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        option.shortLabel,
                        color = if (isSel) Brand.navy else Brand.textSecondary,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Background-noise picker (issue #29): five options with word labels, so it
 * stacks the label above a scrollable pill row like [DurationSetting] rather
 * than crowding them beside the label as [SegmentedSetting] does.
 */
@Composable
private fun BackgroundNoiseSetting() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Background noise", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
            Text(
                Settings.backgroundNoise.label,
                color = Brand.teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BackgroundNoiseLevel.entries.forEach { option ->
                val isSel = option == Settings.backgroundNoise
                Box(
                    modifier = Modifier
                        .background(
                            if (isSel) Brand.teal else Brand.navyRaised,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .clickable { Settings.updateBackgroundNoise(option) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        option.label,
                        color = if (isSel) Brand.navy else Brand.textSecondary,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Brand.navy,
    checkedTrackColor = Brand.teal,
    uncheckedThumbColor = Brand.textSecondary,
    uncheckedTrackColor = Brand.navyRaised
)

private fun punctuationLabel(ch: Char): String = when (ch) {
    '.' -> "Period  ( . )"
    ',' -> "Comma  ( , )"
    '/' -> "Slash  ( / )"
    else -> "$ch"
}

private fun formatTime(hour: Int, minute: Int): String {
    val ampm = if (hour < 12) "AM" else "PM"
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h12, minute, ampm)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = Brand.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 6.dp)
    )
}

/** Below this the dits and dahs become countable; see [SpeedWarning]. Mirrors iOS. */
private const val KOCH_MIN_WPM = 33.0

/** Amber warning under the speed sliders. The grey [SectionFooter] reads as neutral
 *  guidance, and this is a "you are working against the method" note, so it gets
 *  the warning colour and icon the iOS build uses. */
@Composable
private fun SpeedWarning(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = Brand.warning,
            modifier = Modifier.size(16.dp)
        )
        Text(text, color = Brand.warning, fontSize = 12.sp)
    }
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text,
        color = Brand.textSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().brandCard()) { content() }
}

@Composable
private fun GroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(Brand.hairline)
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = if (selected) Brand.teal else Brand.textPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
        if (selected) Text("✓", color = Brand.teal, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun <T> SegmentedSetting(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (optLabel, value) ->
                val isSel = value == selected
                Box(
                    modifier = Modifier
                        .background(
                            if (isSel) Brand.teal else Brand.navyRaised,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        optLabel,
                        color = if (isSel) Brand.navy else Brand.textSecondary,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
            Text(value, color = Brand.teal, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = position,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Brand.teal,
                activeTrackColor = Brand.teal,
                inactiveTrackColor = Brand.navyRaised,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}
