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
import androidx.compose.ui.res.stringResource
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
internal val LADDER_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.CONFUSION, SettingsMode.SENDING
)

/** The modes drilling the shared, persisted Koch ladder, where a stage pin bites. */
internal val STAGE_PIN_MODES = setOf(SettingsMode.CHARACTERS, SettingsMode.SENDING)

/** The choice drills governed by answer choices, recognition target, and reveal. */
private val CHOICE_QUIZ_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION,
    SettingsMode.JOURNEY
)

/**
 * The screens a hardware key can drive: every mode served by QuizScreen, plus
 * Rapid Fire and Sending Practice. All of them wake a key through [HardwareKey],
 * so all of them are governed by the keyer mode.
 */
private val KEY_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION,
    SettingsMode.JOURNEY, SettingsMode.RAPID_FIRE, SettingsMode.SENDING
)

/** The screens that read the session-length setting. */
internal val DURATION_MODES = setOf(
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

    // A hardware key can only be attached where the device speaks MIDI at all.
    val midiSupported = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)
    }

    // Opened from Home there is no module underneath holding the adapter's
    // port, so a keyer mode picked here had nowhere to go until the next
    // module opened (#107; #46 fixed the mid-session sheet, which does have a
    // module underneath). Hold the port for as long as this screen is up —
    // Home and the modules are exclusive routes, so nobody else has it — and
    // push changes down it the way a module does. Mid-session the module
    // keeps its own key; a second client would race it for the port.
    val homeKey = if (scope == null && midiSupported) remember { HardwareKey(context) } else null
    if (homeKey != null) {
        DisposableEffect(homeKey) {
            homeKey.start(onKey = {}, onConnected = {})
            onDispose { homeKey.stop() }
        }
        AdapterConfigSync(homeKey)
    }

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
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.common_back), color = Brand.teal) }

            CenteredScrollColumn(
                contentModifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(
                    stringResource(R.string.common_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textPrimary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                if (scope != null) {
                    Text(
                        stringResource(R.string.settings_scoped_note),
                        color = Brand.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                if (scope == null || scope !in OWN_SPEED_MODES) {
                    SectionHeader(stringResource(R.string.common_speed))
                    SettingsGroup {
                        val minWpm = Settings.MIN_CHARACTER_WPM.toFloat()
                        val maxWpm = Settings.MAX_CHARACTER_WPM.toFloat()
                        SliderSetting(
                            label = stringResource(R.string.settings_character_speed),
                            value = stringResource(R.string.common_wpm_value, Settings.characterWpm.roundToInt()),
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
                            label = stringResource(R.string.settings_farnsworth_speed),
                            value = if (farnsworthOn) stringResource(R.string.common_wpm_value, Settings.effectiveWpm.roundToInt()) else stringResource(R.string.common_off),
                            position = Settings.effectiveWpm.toFloat(),
                            range = minWpm..maxFarnsworth, steps = wholeWpmSteps(minWpm, maxFarnsworth),
                            onChange = { Settings.updateEffectiveWpm(it.toDouble()) }
                        )
                    }
                    val qrqNote = if (Settings.characterWpm >= 40) {
                        stringResource(R.string.settings_qrq_note, Settings.characterWpm.roundToInt())
                    } else ""
                    SectionFooter(
                        stringResource(R.string.settings_speed_footer) + qrqNote
                    )
                    // Twin of the iOS warning: slowing the characters is the one
                    // adjustment that works against the method, so say so rather
                    // than letting it pass silently.
                    if (Settings.characterWpm < KOCH_MIN_WPM) {
                        SpeedWarning(
                            stringResource(R.string.settings_speed_warning, KOCH_MIN_WPM.toInt(), KOCH_MIN_WPM.toInt())
                        )
                    }
                }

                SectionHeader(stringResource(R.string.settings_sound))
                SettingsGroup {
                    SliderSetting(
                        label = stringResource(R.string.settings_sidetone_pitch),
                        value = stringResource(R.string.common_hz_value, Settings.sidetoneHz.roundToInt()),
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
                        Text(stringResource(R.string.settings_preview_tone), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        OutlinedButton(onClick = {
                            player.play(MorseItem.Playable.Text("PARIS"), Settings.sidetoneHz, Settings.timing()) {}
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.settings_play_button))
                        }
                    }
                    GroupDivider()
                    BackgroundNoiseSetting()
                }
                SectionFooter(
                    stringResource(R.string.settings_sound_footer)
                )

                val showChoiceRows = shown(CHOICE_QUIZ_MODES)
                val showWordPool = shown(setOf(SettingsMode.WORDS))
                val showDuration = shown(DURATION_MODES)
                if (showChoiceRows || showWordPool || showDuration) {
                    SectionHeader(stringResource(R.string.settings_practice))
                    SettingsGroup {
                        var needDivider = false
                        if (showChoiceRows) {
                            SegmentedSetting(
                                label = stringResource(R.string.settings_answer_choices),
                                options = listOf("4" to 4, "5" to 5, "6" to 6),
                                selected = Settings.answerChoices,
                                onSelect = { Settings.updateAnswerChoices(it) }
                            )
                            GroupDivider()
                            SliderSetting(
                                label = stringResource(R.string.settings_recognition_target),
                                value = stringResource(R.string.settings_seconds_1dp, Settings.recognitionTargetSec),
                                position = Settings.recognitionTargetSec.toFloat(),
                                range = 0.5f..2.5f, steps = 7,
                                onChange = { Settings.updateRecognitionTargetSec(it.toDouble()) }
                            )
                            needDivider = true
                        }
                        if (showWordPool) {
                            if (needDivider) GroupDivider()
                            SegmentedSetting(
                                label = stringResource(R.string.settings_word_pool),
                                options = listOf("100" to 100, "300" to 300, "500" to 500, "1000" to 1000),
                                selected = Settings.wordCount,
                                onSelect = { Settings.updateWordCount(it) }
                            )
                            needDivider = true
                        }
                        if (showChoiceRows) {
                            if (needDivider) GroupDivider()
                            SegmentedSetting(
                                label = stringResource(R.string.settings_reveal_answer),
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
                SectionHeader(stringResource(R.string.settings_my_words))
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_use_my_word_list), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
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
                            placeholder = { Text(stringResource(R.string.settings_custom_words_placeholder)) },
                            minLines = 3,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                        )
                    }
                }
                SectionFooter(
                    if (Settings.useCustomWords) {
                        val n = Settings.customWords.size
                        stringResource(R.string.settings_custom_words_footer_lead) + when {
                            n >= 2 -> stringResource(R.string.settings_words_ready, n)
                            else -> stringResource(R.string.settings_custom_words_need_two)
                        }
                    } else {
                        stringResource(R.string.settings_custom_words_footer_off)
                    }
                )
                }

                if (shown(LADDER_MODES)) {
                SectionHeader(stringResource(R.string.settings_punctuation))
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
                    stringResource(R.string.settings_punctuation_footer)
                )

                SectionHeader(stringResource(R.string.settings_starting_level))
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
                                JourneyStore.unlockForProficiency()
                            }
                        )
                    }
                }
                SectionFooter(stringResource(R.string.settings_starting_level_footer))

                // A first meeting is shown, not sprung (#162). Characters only:
                // the sending and confusion drills never present a new item.
                if (shown(setOf(SettingsMode.CHARACTERS))) {
                SectionHeader(stringResource(R.string.settings_introduce_new))
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_introduce_new_toggle), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = Settings.introduceNewCharacters,
                            onCheckedChange = { Settings.updateIntroduceNewCharacters(it) },
                            colors = switchColors()
                        )
                    }
                }
                SectionFooter(stringResource(R.string.settings_introduce_new_footer))
                }
                }

                if (scope == null || scope !in NO_FEEDBACK_MODES) {
                val showVoiceRow = shown(VOICE_ANSWER_MODES)
                SectionHeader(stringResource(R.string.settings_feedback))
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_haptic_feedback), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
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
                            Text(stringResource(R.string.settings_voice_answers), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
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
                        stringResource(R.string.settings_feedback_footer)
                    else
                        stringResource(R.string.settings_feedback_footer_haptics_only)
                )
                }

                // The key plugged into a Vail Adapter. Only worth showing where a
                // key can be attached at all, and only on a device with MIDI.
                if (shown(KEY_MODES) && midiSupported) {
                    SectionHeader(stringResource(R.string.settings_hardware_key))
                    SettingsGroup { AdapterKeyerSetting(context) }
                    SectionFooter(
                        stringResource(R.string.settings_hardware_key_footer)
                    )
                }

                SectionHeader(stringResource(R.string.settings_display))
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_slashed_zero), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = Settings.slashedZero,
                            onCheckedChange = { Settings.updateSlashedZero(it) },
                            colors = switchColors()
                        )
                    }
                }
                SectionFooter(stringResource(R.string.settings_slashed_zero_footer))

                SectionHeader(stringResource(R.string.settings_reminders))
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_daily_reminder), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
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
                            Text(stringResource(R.string.common_time), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                            TextButton(onClick = { pickTime() }) {
                                Text(formatTime(Settings.reminderHour, Settings.reminderMinute), color = Brand.teal, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                SectionFooter(stringResource(R.string.settings_reminders_footer))

                // Mid-session the destructive reset stays out of reach — it would
                // yank the engine out from under the running drill. Home only.
                if (scope == null) {
                SectionHeader(stringResource(R.string.common_progress))
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { confirmReset = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_reset_all_progress), color = Color(0xFFF2788F), fontWeight = FontWeight.Medium)
                    }
                }
                SectionFooter(
                    stringResource(R.string.settings_reset_footer)
                )
                }

                Spacer(Modifier.height(24.dp))
            }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.settings_reset_confirm_title), color = Brand.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.settings_reset_confirm_body),
                    color = Brand.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    EngineStore.reset()
                    Stats.reset()
                    JourneyStore.reset()
                    confirmReset = false
                }) { Text(stringResource(R.string.settings_reset), color = Color(0xFFF2788F), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel), color = Brand.teal) }
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
            contentDescription = stringResource(R.string.common_settings),
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
            Text(stringResource(R.string.settings_session_length), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
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
            Text(stringResource(R.string.settings_background_noise), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
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

/**
 * Which key is plugged into the Vail Adapter (issue #43).
 *
 * The adapter has to be told a keyer mode as part of being woken into MIDI
 * mode, so before this existed outside the Vail repeater screen every practice
 * screen asserted "straight key" — overwriting an iambic paddle set anywhere
 * else, including at vailmorse.com. Writing the shared [AdapterKeyer] here
 * makes the choice reachable without going to the repeater, and every screen
 * that wakes a key picks it up.
 *
 * It still does not open a MIDI port of its own — one owner per device input
 * port, or two clients race to open it. The change reaches a connected adapter
 * because [AdapterKeyer] is observable and whoever holds the port is watching
 * it through [AdapterConfigSync]: mid-session that is the module underneath
 * this sheet (issue #46); from Home, where no module is composed, it is the
 * key [SettingsScreen] itself opens for the duration (#107). Storing it and
 * waiting for the next wake, as this used to do, meant the change did nothing
 * until the operator left the module — or, from Home, entered one.
 */
@Composable
private fun AdapterKeyerSetting(context: android.content.Context) {
    var mode by remember { mutableStateOf(AdapterKeyer.mode(context)) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_keyer_mode), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
            Text(
                mode.displayName,
                color = Brand.teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MidiKeyOutput.KeyerMode.entries.forEach { option ->
                val isSel = option == mode
                Box(
                    modifier = Modifier
                        .background(
                            if (isSel) Brand.teal else Brand.navyRaised,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .clickable { mode = option; AdapterKeyer.setMode(context, option) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        option.displayName,
                        color = if (isSel) Brand.navy else Brand.textSecondary,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
        if (AdapterKeyer.adapterTimesSending(mode)) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_keyer_mode_note),
                color = Brand.textSecondary,
                fontSize = 12.sp
            )
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
