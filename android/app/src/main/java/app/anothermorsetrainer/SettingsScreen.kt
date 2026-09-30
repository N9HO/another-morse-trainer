package app.anothermorsetrainer

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import app.anothermorsetrainer.morsekit.AnswerEntryMode
import app.anothermorsetrainer.morsekit.SettingsCatalog
import app.anothermorsetrainer.morsekit.SettingsCategory
import app.anothermorsetrainer.morsekit.SettingsSearchEntry
import app.anothermorsetrainer.morsekit.SettingsSection
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.Buddy
import app.anothermorsetrainer.morsekit.BuddyEntry
import app.anothermorsetrainer.morsekit.Leaderboard
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.PaddleKeyer
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/*
 * Settings is a root of categories with a search field on top (#236); each
 * category opens a sub-screen of titled sections. The map — which categories
 * exist, in what order, which sections each holds, and what search finds — is
 * `morsekit/SettingsCatalog.kt`, pinned to the iOS app's by
 * `fixtures/settings-catalog.json`. This file only draws the rows.
 *
 * Adding a setting:
 *   1. Put its row in the `SettingsSection` branch it belongs to in the
 *      `when (section)` inside [SettingsScreen]. A new section is a new
 *      [SettingsSection] entry, a branch there, and a line in `isShown`.
 *   2. Add one entry to `SettingsCatalog.entries`, in its section's place.
 *      If iOS has it too, add it to the fixture.
 * Mid-session the screen is scoped to the running mode ([SettingsMode],
 * #66): `isShown` hides sections for other modes, and a category with
 * nothing left in it drops off the root and out of search.
 */

/**
 * The training surface that opened Settings mid-session. Mirrors the iOS
 * issue-#66 behavior: sections that only matter to *other* modes are hidden,
 * so Q-Codes practice never scrolls past the word-pool or ladder knobs. The
 * home screen passes null and gets the full surface.
 */
enum class SettingsMode {
    CHARACTERS, WORDS, ABBREVIATIONS, QCODES, PROSIGNS, CONFUSION,
    /** The standalone CW 77 mode: its setup sheet, and its Quiz style's run. */
    CW77,
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
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.CW77, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION,
    SettingsMode.JOURNEY
)

/**
 * The screens a hardware key can drive: every mode served by QuizScreen, plus
 * Rapid Fire and Sending Practice. All of them wake a key through [HardwareKey],
 * so all of them are governed by the keyer mode.
 */
private val KEY_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.CW77, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION,
    SettingsMode.JOURNEY, SettingsMode.RAPID_FIRE, SettingsMode.SENDING
)

/** The screens that read the session-length setting. */
internal val DURATION_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.CW77, SettingsMode.ABBREVIATIONS,
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

/** The choice quizzes that take a typed answer (#232): every one but the Journey. */
internal val ANSWER_ENTRY_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.CW77, SettingsMode.ABBREVIATIONS,
    SettingsMode.QCODES, SettingsMode.PROSIGNS, SettingsMode.CONFUSION
)

/** The option's name in Settings and the in-drill menu (#232). */
internal val AnswerEntryMode.titleRes: Int
    get() = when (this) {
        AnswerEntryMode.CHOICES -> R.string.answer_entry_choices
        AnswerEntryMode.PROGRESSIVE -> R.string.answer_entry_progressive
        AnswerEntryMode.TYPED -> R.string.answer_entry_typed
    }

/** The quiz screen is the only surface with spoken answers. */
private val VOICE_ANSWER_MODES = setOf(
    SettingsMode.CHARACTERS, SettingsMode.WORDS, SettingsMode.CW77, SettingsMode.ABBREVIATIONS,
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
fun SettingsScreen(
    onBack: () -> Unit,
    scope: SettingsMode? = null,
    /**
     * Where to go once the developer Preview Stage row has jumped the
     * Characters track (iOS `previewStage`: jump, switch to Characters, save,
     * start). From Home the caller navigates into the Characters drill; a
     * Characters session underneath restarts its drill. Null falls back to
     * [onBack], the track already jumped and saved for the next start.
     */
    onPreviewStage: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val player = remember { MorsePlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    fun shown(modes: Set<SettingsMode>): Boolean = scope == null || scope in modes

    var confirmReset by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    // "Delete my scores" (docs/high-scores-design.md §5): confirm, call the
    // server, say what happened. The call is attested like a run start, so it
    // can take a few seconds; the row shows that rather than going dead.
    val uiScope = rememberCoroutineScope()
    var confirmDeleteScores by remember { mutableStateOf(false) }
    var deletingScores by remember { mutableStateOf(false) }
    var deleteScoresNote by remember { mutableStateOf<String?>(null) }

    // "Copy diagnostic info" (iOS issue #31): a two-second "Copied" confirmation.
    val haptics = remember { Haptics(context) }
    var copiedDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(copiedDiagnostics) {
        if (copiedDiagnostics) {
            delay(2000)
            copiedDiagnostics = false
        }
    }

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

    // #236: the root lists the categories under a search field; a category
    // shows its sections. `categoryId` null is the root. The search text and
    // the open category survive a configuration change.
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusName by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val category = SettingsCategory.byId(categoryId)
    val focus = SettingsSection.entries.firstOrNull { it.name == focusName }
    fun openCategory(target: SettingsCategory, section: SettingsSection?) {
        categoryId = target.id
        focusName = section?.name
    }
    fun closeCategory() {
        categoryId = null
        focusName = null
    }
    // Back from a category returns to the root; from the root it leaves.
    BackHandler { if (categoryId != null) closeCategory() else onBack() }

    val showChoiceRows = shown(CHOICE_QUIZ_MODES)
    val showWordPool = shown(setOf(SettingsMode.WORDS))
    val showDuration = shown(DURATION_MODES)

    /** Whether a section belongs on this surface — every gate the flat list had before #236. */
    fun isShown(section: SettingsSection): Boolean = when (section) {
        SettingsSection.SOUND, SettingsSection.REMINDERS, SettingsSection.DISPLAY,
        SettingsSection.LEADERBOARD, SettingsSection.BUDDY,
        SettingsSection.BUG_REPORTS, SettingsSection.ABOUT -> true
        SettingsSection.SPEED -> scope == null || scope !in OWN_SPEED_MODES
        SettingsSection.PROFICIENCY, SettingsSection.PUNCTUATION -> shown(LADDER_MODES)
        SettingsSection.NEW_CHARACTERS -> shown(LADDER_MODES) && shown(setOf(SettingsMode.CHARACTERS))
        SettingsSection.PREVIEW_STAGE -> shown(STAGE_PIN_MODES)
        SettingsSection.RESET -> scope == null
        SettingsSection.PRACTICE -> showChoiceRows || showWordPool || showDuration
        SettingsSection.ANSWER_ENTRY -> shown(ANSWER_ENTRY_MODES)
        SettingsSection.MY_WORDS -> showWordPool
        SettingsSection.FEEDBACK -> scope == null || scope !in NO_FEEDBACK_MODES
        SettingsSection.HEAD_COPY -> shown(setOf(SettingsMode.HEAD_COPY))
        SettingsSection.HARDWARE_KEY -> shown(KEY_MODES) && midiSupported
        // The on-screen key needs no MIDI, so it stays on every device (#233).
        SettingsSection.ON_SCREEN_KEY -> shown(KEY_MODES)
        SettingsSection.PILEUP -> scope == null
    }
    val visibleCategories = SettingsCategory.entries.filter { c -> c.sections.any { isShown(it) } }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(
            onClick = { if (categoryId != null) closeCategory() else onBack() },
            modifier = Modifier.padding(8.dp)
        ) { Text(stringResource(R.string.common_back), color = Brand.teal) }

        // A fresh scroll position for each screen: the root and every category.
        key(categoryId) {
            CenteredScrollColumn(
                contentModifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(
                    category?.title ?: stringResource(R.string.common_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textPrimary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                if (category == null) {
                    if (scope != null) {
                        Text(
                            stringResource(R.string.settings_scoped_note),
                            color = Brand.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                    SettingsSearchField(query = query, onChange = { query = it })
                    if (SettingsCatalog.words(query).isEmpty()) {
                        SettingsGroup {
                            visibleCategories.forEachIndexed { i, c ->
                                if (i > 0) GroupDivider()
                                CategoryRow(c) { openCategory(c, null) }
                            }
                        }
                    } else {
                        val results = SettingsCatalog.search(query, SettingsCatalog.entries.filter { isShown(it.section) })
                        if (results.isEmpty()) {
                            SectionFooter(stringResource(R.string.settings_search_none, query.trim()))
                        } else {
                            SectionHeader(stringResource(R.string.settings_search_results))
                            SettingsGroup {
                                results.forEachIndexed { i, entry ->
                                    if (i > 0) GroupDivider()
                                    SearchResultRow(entry) { openCategory(entry.category, entry.section) }
                                }
                            }
                        }
                    }
                } else {
                    category.sections.filter { isShown(it) }.forEach { section ->
                    AnchoredSection(section, focus) {
                    when (section) {
                        SettingsSection.SOUND -> {
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
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_keep_bluetooth_awake), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                                    Switch(
                                        checked = Settings.bluetoothKeepAlive,
                                        onCheckedChange = { Settings.updateBluetoothKeepAlive(it) },
                                        colors = switchColors()
                                    )
                                }
                                GroupDivider()
                                BandNoiseSetting()
                            }
                            SectionFooter(
                                stringResource(R.string.settings_sound_footer) + "\n\n" +
                                    stringResource(R.string.settings_keep_bluetooth_awake_footer) + "\n\n" +
                                    stringResource(R.string.settings_band_noise_footer)
                            )
                        }
                        SettingsSection.SPEED -> {
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
                                // Farnsworth is a switch with its own effective speed
                                // (iOS parity): the slider only appears while it is on.
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_farnsworth_spacing), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                                    Switch(
                                        checked = Settings.farnsworthEnabled,
                                        onCheckedChange = { Settings.updateFarnsworthEnabled(it) },
                                        colors = switchColors()
                                    )
                                }
                                if (Settings.farnsworthEnabled) {
                                    GroupDivider()
                                    // Farnsworth only ever *slows* the spacing, so its top is
                                    // the character speed itself — and it has to follow that
                                    // speed up, or a 60 WPM session couldn't be spaced at 45
                                    // (issue #79). The floor is iOS's 8 WPM.
                                    val minEffective = Settings.MIN_EFFECTIVE_WPM.toFloat()
                                    val maxFarnsworth = maxOf(minEffective + 1f, Settings.characterWpm.toFloat())
                                    SliderSetting(
                                        label = stringResource(R.string.settings_effective_speed),
                                        value = stringResource(R.string.common_wpm_value, Settings.effectiveWpm.roundToInt()),
                                        position = Settings.effectiveWpm.toFloat().coerceIn(minEffective, maxFarnsworth),
                                        range = minEffective..maxFarnsworth, steps = wholeWpmSteps(minEffective, maxFarnsworth),
                                        onChange = { Settings.updateEffectiveWpm(it.toDouble()) }
                                    )
                                }
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
                        SettingsSection.PROFICIENCY -> {
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
                        }
                        SettingsSection.NEW_CHARACTERS -> {
                            // A first meeting is shown, not sprung (#162). Characters only:
                            // the sending and confusion drills never present a new item.
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
                        SettingsSection.PUNCTUATION -> {
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
                        }
                        SettingsSection.PREVIEW_STAGE -> {
                            // Developer aid (iOS `previewStage`): jump the Characters track
                            // to a stage and start drilling it. Shown where the Track-stage
                            // pin is, since both act on the same shared ladder.
                            SectionHeader(stringResource(R.string.settings_preview_stage))
                            SettingsGroup {
                                val track = remember { EngineStore.current() }
                                ProgressiveCharacters.Stage.entries.forEachIndexed { i, stage ->
                                    if (i > 0) GroupDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                track.jumpToStage(stage)
                                                EngineStore.save()
                                                onPreviewStage?.invoke() ?: onBack()
                                            }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(stage.displayName, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                                            if (track.stage == stage) Text("✓", color = Brand.tealBright, fontWeight = FontWeight.Bold)
                                        }
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Brand.textSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            SectionFooter(stringResource(R.string.settings_preview_stage_footer))
                        }
                        SettingsSection.RESET -> {
                            // Mid-session the destructive reset stays out of reach — it would
                            // yank the engine out from under the running drill. Home only.
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
                        SettingsSection.PRACTICE -> {
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
                                        // 0.5–3.0 s in tenths, the iOS range and step.
                                        range = 0.5f..3.0f, steps = 24,
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
                                    // The CWOps list as the pool instead of a Top N
                                    // (#240) — iOS lists it in the same pool picker;
                                    // a fifth chip does not fit beside the label here.
                                    GroupDivider()
                                    val cw77 = Settings.wordCount == Settings.WORD_POOL_CW77
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(stringResource(R.string.settings_word_pool_cw77), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                                        Switch(
                                            checked = cw77,
                                            onCheckedChange = { Settings.updateWordCount(if (it) Settings.WORD_POOL_CW77 else 100) },
                                            colors = switchColors()
                                        )
                                    }
                                    if (cw77 && !(Settings.useCustomWords && Settings.customWords.size >= 2)) {
                                        Cw77Options(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                                    }
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
                        // Keyboard-entry answers (#232), in the choice
                        // quizzes that take them (not the Journey).
                        SettingsSection.ANSWER_ENTRY -> {
                            SectionHeader(stringResource(R.string.settings_answer_entry))
                            SettingsGroup {
                                SegmentedSetting(
                                    label = stringResource(R.string.settings_answer_entry),
                                    options = AnswerEntryMode.entries.map { stringResource(it.titleRes) to it },
                                    selected = Settings.answerEntry,
                                    onSelect = { Settings.updateAnswerEntry(it) }
                                )
                            }
                            SectionFooter(stringResource(R.string.settings_answer_entry_footer))
                        }
                        SettingsSection.MY_WORDS -> {
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
                        SettingsSection.FEEDBACK -> {
                            val showVoiceRow = shown(VOICE_ANSWER_MODES)
                            SectionHeader(stringResource(R.string.settings_feedback))
                            SettingsGroup {
                                // The iOS Feedback section's two toggles: right/wrong
                                // colouring, and a Replay button before answering.
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_show_correctness), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                                    Switch(
                                        checked = Settings.showCorrectness,
                                        onCheckedChange = { Settings.updateShowCorrectness(it) },
                                        colors = switchColors()
                                    )
                                }
                                GroupDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_show_replay), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                                    Switch(
                                        checked = Settings.allowReplay,
                                        onCheckedChange = { Settings.updateAllowReplay(it) },
                                        colors = switchColors()
                                    )
                                }
                                GroupDivider()
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
                        SettingsSection.HEAD_COPY -> {
                            // Head Copy's re-hearing: auto-repeat count and reveal countdown
                            // (iOS parity). The drill screen carries the same two controls.
                            SectionHeader(stringResource(R.string.settings_head_copy))
                            SettingsGroup {
                                SegmentedSetting(
                                    label = stringResource(R.string.settings_head_copy_repeats),
                                    options = (0..Settings.MAX_HEAD_COPY_REPEATS).map { n ->
                                        (if (n == 0) stringResource(R.string.common_off) else stringResource(R.string.settings_repeat_times, n)) to n
                                    },
                                    selected = Settings.headCopyRepeats,
                                    onSelect = { Settings.updateHeadCopyRepeats(it) }
                                )
                                GroupDivider()
                                SliderSetting(
                                    label = stringResource(R.string.settings_head_copy_reveal),
                                    value = if (Settings.headCopyRevealSec < 1) stringResource(R.string.settings_head_copy_manual)
                                            else stringResource(R.string.settings_seconds_whole, Settings.headCopyRevealSec),
                                    position = Settings.headCopyRevealSec.toFloat(),
                                    range = 0f..Settings.MAX_HEAD_COPY_REVEAL_SEC.toFloat(),
                                    steps = Settings.MAX_HEAD_COPY_REVEAL_SEC - 1,
                                    onChange = { Settings.updateHeadCopyRevealSec(it.roundToInt()) }
                                )
                            }
                            SectionFooter(stringResource(R.string.settings_head_copy_footer))
                        }
                        SettingsSection.HARDWARE_KEY -> {
                            // The key plugged into a Vail Adapter. Only worth showing where a
                            // key can be attached at all, and only on a device with MIDI.
                            SectionHeader(stringResource(R.string.settings_hardware_key))
                            SettingsGroup { AdapterKeyerSetting(context) }
                            SectionFooter(
                                stringResource(R.string.settings_hardware_key_footer)
                            )
                        }
                        SettingsSection.ON_SCREEN_KEY -> {
                            // The on-screen key (#233): straight key or touch paddles,
                            // wherever a screen offers one. Unlike the hardware key
                            // above, it needs no MIDI.
                            SectionHeader(stringResource(R.string.settings_on_screen_key))
                            SettingsGroup { OnScreenKeySetting() }
                            SectionFooter(
                                if (Settings.onScreenKey == OnScreenKeyType.PADDLES)
                                    stringResource(R.string.settings_on_screen_key_footer_paddles)
                                else
                                    stringResource(R.string.settings_on_screen_key_footer_straight)
                            )
                        }
                        SettingsSection.PILEUP -> {
                            // The Pileup Runner's own options (#236), the same composable its
                            // setup screen shows, so iOS and Android both keep them under
                            // QSO & Pileups. Home only: mid-run the engine already holds its config.
                            SectionHeader(stringResource(R.string.mode_pileup_runner))
                            Column(
                                modifier = Modifier.fillMaxWidth().brandCard().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) { PileupOptions() }
                            SectionFooter(stringResource(R.string.settings_pileup_footer))
                        }
                        SettingsSection.REMINDERS -> {
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
                        }
                        SettingsSection.DISPLAY -> {
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
                        }
                        SettingsSection.LEADERBOARD -> {
                            // The shared leaderboard (docs/high-scores-design.md, step 2):
                            // opt-in, off by default, a display name the server's rule
                            // accepts, and the delete button both stores require. The
                            // toggle is offered even before the maintainer has filled the
                            // Cloud project number, so the preference survives into the
                            // build that turns attestation on; the footnote says why
                            // nothing is posted meanwhile.
                            SectionHeader(stringResource(R.string.settings_leaderboard))
                            SettingsGroup {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        stringResource(R.string.settings_leaderboard_share),
                                        color = Brand.textPrimary, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                                    )
                                    Switch(
                                        checked = Settings.leaderboardEnabled,
                                        onCheckedChange = { Settings.updateLeaderboardEnabled(it) },
                                        colors = switchColors()
                                    )
                                }
                                if (Settings.leaderboardEnabled) {
                                    GroupDivider()
                                    val nameOk = Leaderboard.isValidDisplayName(Settings.leaderboardName)
                                    OutlinedTextField(
                                        value = Settings.leaderboardName,
                                        onValueChange = { Settings.updateLeaderboardName(it) },
                                        label = { Text(stringResource(R.string.settings_leaderboard_name)) },
                                        placeholder = { Text(stringResource(R.string.settings_leaderboard_name_hint)) },
                                        singleLine = true,
                                        isError = Settings.leaderboardName.isNotEmpty() && !nameOk,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                    if (!nameOk) {
                                        Text(
                                            stringResource(R.string.settings_leaderboard_name_invalid),
                                            color = Brand.warning, fontSize = 12.sp,
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                        )
                                    }
                                    GroupDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !deletingScores) { confirmDeleteScores = true }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            stringResource(if (deletingScores) R.string.settings_leaderboard_deleting else R.string.settings_leaderboard_delete),
                                            color = if (deletingScores) Brand.textSecondary else Color(0xFFF2788F),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    deleteScoresNote?.let { note ->
                                        Text(
                                            note, color = Brand.textSecondary, fontSize = 12.sp,
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                        )
                                    }
                                }
                                if (!LeaderboardClient.isConfigured) {
                                    GroupDivider()
                                    Text(
                                        stringResource(R.string.settings_leaderboard_unconfigured),
                                        color = Brand.warning, fontSize = 12.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                            SectionFooter(stringResource(R.string.settings_leaderboard_footer))
                        }
                        SettingsSection.BUDDY -> {
                            // Buddy streak (docs/buddy-streak-design.md, #219, #237): its own composable.
                            BuddySection()
                            // The home screen's buddy line (#253): on by default, offered
                            // before any pairing too. Only HomeScreen reads it. iOS:
                            // SettingsView.buddySection, same words. No header of its own:
                            // it belongs to the Buddy streak section above.
                            Spacer(Modifier.height(16.dp))
                            SettingsGroup {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        stringResource(R.string.settings_buddy_on_home),
                                        color = Brand.textPrimary, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                                    )
                                    Switch(
                                        checked = Settings.buddyOnHome,
                                        onCheckedChange = { Settings.updateBuddyOnHome(it) },
                                        colors = switchColors()
                                    )
                                }
                            }
                            SectionFooter(stringResource(R.string.settings_buddy_on_home_footer))
                        }
                        SettingsSection.BUG_REPORTS -> {
                            // Bug reports (iOS issue #31): build, OS, device and the
                            // settings most likely to matter, onto the clipboard.
                            SectionHeader(stringResource(R.string.settings_bug_reports))
                            SettingsGroup {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            clipboard?.setPrimaryClip(ClipData.newPlainText("Diagnostics", diagnosticInfo(context, scope)))
                                            copiedDiagnostics = true
                                            if (Settings.hapticsEnabled) haptics.success()
                                        }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        if (copiedDiagnostics) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        tint = Brand.teal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        stringResource(if (copiedDiagnostics) R.string.settings_copied_diagnostics else R.string.settings_copy_diagnostics),
                                        color = Brand.teal, fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            SectionFooter(stringResource(R.string.settings_bug_reports_footer))
                        }
                        SettingsSection.ABOUT -> {
                            // Support the project (iOS parity). Links out to the website's
                            // own page rather than straight to a tipping site: Play's
                            // payments policy reads an in-app link to external tipping as
                            // a purchase mechanism, a link to the project's homepage is
                            // not, and the coffee button lives there.
                            SectionHeader(stringResource(R.string.settings_about))
                            SettingsGroup {
                                LinkRow(stringResource(R.string.settings_support_project)) { uriHandler.openUri(SUPPORT_URL) }
                                GroupDivider()
                                LinkRow(stringResource(R.string.settings_join_discord)) { uriHandler.openUri(DISCORD_URL) }
                                GroupDivider()
                                LinkRow(stringResource(R.string.settings_source_github)) { uriHandler.openUri(GITHUB_URL) }
                                GroupDivider()
                                LinkRow(stringResource(R.string.settings_licenses)) { showLicenses = true }
                            }
                            SectionFooter(stringResource(R.string.settings_about_footer))
                        }
                    }
                    }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
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

    if (confirmDeleteScores) {
        AlertDialog(
            onDismissRequest = { confirmDeleteScores = false },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.settings_leaderboard_delete_confirm_title), color = Brand.textPrimary) },
            text = { Text(stringResource(R.string.settings_leaderboard_delete_confirm_body), color = Brand.textSecondary) },
            confirmButton = {
                // Resource strings read at composition (lint: LocalContextGetResources);
                // the failure template takes its argument with String.format.
                val deleteDoneText = stringResource(R.string.settings_leaderboard_delete_done)
                val deleteFailedTemplate = stringResource(R.string.settings_leaderboard_delete_failed)
                TextButton(onClick = {
                    confirmDeleteScores = false
                    deletingScores = true
                    deleteScoresNote = null
                    uiScope.launch {
                        val failure = LeaderboardClient.deleteMyScores()
                        deletingScores = false
                        deleteScoresNote = if (failure == null) deleteDoneText else deleteFailedTemplate.format(failure)
                    }
                }) { Text(stringResource(R.string.settings_leaderboard_delete), color = Color(0xFFF2788F), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteScores = false }) { Text(stringResource(R.string.common_cancel), color = Brand.teal) }
            }
        )
    }

    // The notices the licenses oblige the app to carry (iOS parity). The GPL
    // asks an interactive program to show its terms and no-warranty statement,
    // and the vendored decoder's MIT notice must accompany every copy — the
    // APK ships no text file, so this dialog is the copy that travels with it.
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.settings_licenses), color = Brand.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.licenses_app_title), color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.licenses_app_notice), color = Brand.textSecondary, fontSize = 13.sp)
                    Text(
                        stringResource(R.string.licenses_read_full),
                        color = Brand.teal, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { uriHandler.openUri(LICENSE_URL) }
                    )
                    Text(stringResource(R.string.licenses_cw_decoder_title), color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.licenses_cw_decoder_footer), color = Brand.textSecondary, fontSize = 13.sp)
                    Text(
                        stringResource(R.string.licenses_cw_decoder_mit),
                        color = Brand.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                    // The Play Integrity SDK behind the shared leaderboard
                    // (CLAUDE.md, Licensing: a dependency with a notice goes
                    // here). Not open source: Google's SDK licence and the
                    // Play Integrity terms, linked rather than reproduced.
                    Text(stringResource(R.string.licenses_play_integrity_title), color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.licenses_play_integrity_footer), color = Brand.textSecondary, fontSize = 13.sp)
                    Text(
                        stringResource(R.string.licenses_play_integrity_link),
                        color = Brand.teal, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { uriHandler.openUri(PLAY_INTEGRITY_TERMS_URL) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.common_close), color = Brand.teal) }
            }
        )
    }
}

/** The buddy call a Settings row is waiting on, so the row can say so and the others go quiet. */
private enum class BuddyAction { INVITE, JOIN, LEAVE }

/**
 * Settings › Leaderboard & Buddy › Buddy streak (docs/buddy-streak-design.md,
 * #219, #237): the
 * buddy list — each buddy's streak and day at a glance, with Leave on each —
 * then Invite and Join while the list has room. Its own composable so the
 * Settings screen places it with one line. Its own consent too — the
 * leaderboard switch does not gate it; only the display name is shared. The
 * buttons run the attested calls through [BuddyClient] and say what happened
 * inline; the list reads the cache, refreshed when the section appears (at
 * most every 15 minutes). The iOS twin is `BuddySettingsSection.swift`.
 */
@Composable
private fun BuddySection() {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    // Which attested call is in flight (the row shows it), the inline refusal,
    // the Join field and the buddy a Leave confirmation is about. The list
    // itself lives in Settings.buddyStatus.
    var busy by remember { mutableStateOf<BuddyAction?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    var leaving by remember { mutableStateOf<BuddyEntry?>(null) }
    var copiedCode by remember { mutableStateOf(false) }
    LaunchedEffect(copiedCode) {
        if (copiedCode) {
            delay(2000)
            copiedCode = false
        }
    }

    SectionHeader(stringResource(R.string.settings_buddy))
    // Only with a buddy or an invite out, as on iOS: a status call creates an
    // identity server-side, and pairing is the consent.
    LaunchedEffect(Unit) { BuddyClient.refreshIfStale() }
    val status = Settings.buddyStatus
    val buddies = status?.buddies ?: emptyList()
    val full = status?.isFull == true
    val today = Buddy.today()
    val hasName = BuddyClient.displayName() != null
    val ready = hasName && LeaderboardClient.isConfigured && busy == null
    // Resource strings read at composition (lint: LocalContextGetResources).
    val errorTemplate = stringResource(R.string.settings_buddy_error)
    val shareTitle = stringResource(R.string.settings_buddy_share_title)
    val shareTemplate = stringResource(R.string.settings_buddy_share_text)
    val words = BuddyWords.current()
    SettingsGroup {
        if (status != null && buddies.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.settings_buddy_count, status.maxBuddies, buddies.size, status.maxBuddies),
                color = Brand.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
            )
            buddies.forEachIndexed { i, buddy ->
                if (i > 0) GroupDivider()
                val practised = status.practisedOn(buddy, today)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (practised) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (practised) Brand.teal else Brand.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(buddy.name, color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(
                                R.string.settings_buddy_row,
                                words.streak(buddy.streak),
                                stringResource(if (practised) R.string.settings_buddy_row_practised else R.string.settings_buddy_row_not_yet)
                            ),
                            color = Brand.textSecondary, fontSize = 12.sp
                        )
                    }
                    val leavingThis = busy == BuddyAction.LEAVE && leaving == buddy
                    TextButton(enabled = ready, onClick = { leaving = buddy }) {
                        Text(
                            stringResource(if (leavingThis) R.string.settings_buddy_leaving else R.string.settings_buddy_leave),
                            color = if (ready) Color(0xFFF2788F) else Brand.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.settings_buddy_unpaired),
                color = Brand.textPrimary, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(16.dp)
            )
        }
        if (!hasName) {
            Text(
                stringResource(R.string.settings_buddy_no_name),
                color = Brand.warning, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
        GroupDivider()
        if (full) {
            Text(
                stringResource(R.string.settings_buddy_full),
                color = Brand.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = ready) {
                        busy = BuddyAction.INVITE
                        note = null
                        uiScope.launch {
                            when (val result = BuddyClient.invite()) {
                                is BuddyClient.InviteResult.Failed -> note = errorTemplate.format(result.reason)
                                is BuddyClient.InviteResult.Ok -> {}
                            }
                            busy = null
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val inviting = busy == BuddyAction.INVITE
                Text(
                    stringResource(
                        when {
                            inviting -> R.string.settings_buddy_inviting
                            buddies.isNotEmpty() -> R.string.settings_buddy_invite_another
                            else -> R.string.settings_buddy_invite
                        }
                    ),
                    color = if (inviting) Brand.textSecondary else Brand.teal,
                    fontWeight = FontWeight.Medium
                )
            }
            // An invite this install issued and the server still honours: the
            // code, large, with copy and share. It stays until it expires,
            // someone joins with it, or the list fills.
            val inviteCode = Settings.buddyInviteCode
            val inviteExpiresAt = Settings.buddyInviteExpiresAt
            if (inviteCode.isNotEmpty() && inviteExpiresAt > System.currentTimeMillis()) {
                val expiryLabel = remember(inviteExpiresAt) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(inviteExpiresAt))
                }
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    Text(
                        inviteCode,
                        color = Brand.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                    Text(
                        stringResource(R.string.settings_buddy_code_hint, expiryLabel),
                        color = Brand.textSecondary, fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Buddy code", inviteCode))
                            copiedCode = true
                        }) {
                            Text(
                                stringResource(if (copiedCode) R.string.settings_buddy_copied else R.string.settings_buddy_copy),
                                color = Brand.teal, fontWeight = FontWeight.SemiBold
                            )
                        }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareTemplate.format(inviteCode))
                            }
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }) {
                            Text(stringResource(R.string.settings_buddy_share), color = Brand.teal, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            GroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = ready) { showJoin = !showJoin }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_buddy_join), color = Brand.teal, fontWeight = FontWeight.Medium)
            }
            if (showJoin) {
                // The field says no before the server does, by the same rule
                // (Buddy.normalizeInviteCode); spaces and dashes are allowed
                // in, stripped on the way out.
                val normalizedCode = Buddy.normalizeInviteCode(joinCode)
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { joinCode = it.uppercase().take(10) },
                    placeholder = { Text(stringResource(R.string.settings_buddy_join_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    isError = joinCode.isNotEmpty() && normalizedCode == null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    val joining = busy == BuddyAction.JOIN
                    TextButton(
                        enabled = ready && normalizedCode != null,
                        onClick = {
                            val code = normalizedCode ?: return@TextButton
                            busy = BuddyAction.JOIN
                            note = null
                            uiScope.launch {
                                val failure = BuddyClient.join(code)
                                if (failure == null) {
                                    showJoin = false
                                    joinCode = ""
                                } else {
                                    note = errorTemplate.format(failure)
                                }
                                busy = null
                            }
                        }
                    ) {
                        Text(
                            stringResource(if (joining) R.string.settings_buddy_joining else R.string.settings_buddy_join_go),
                            color = Brand.teal, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        note?.let { n ->
            Text(
                n, color = Brand.warning, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
        if (!LeaderboardClient.isConfigured) {
            GroupDivider()
            Text(
                stringResource(R.string.settings_leaderboard_unconfigured),
                color = Brand.warning, fontSize = 12.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    SectionFooter(stringResource(R.string.settings_buddy_footer))

    leaving?.let { buddy ->
        if (busy == BuddyAction.LEAVE) return@let
        AlertDialog(
            onDismissRequest = { leaving = null },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.settings_buddy_leave_confirm_title, buddy.name), color = Brand.textPrimary) },
            text = { Text(stringResource(R.string.settings_buddy_leave_confirm_body, buddy.name), color = Brand.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    busy = BuddyAction.LEAVE
                    note = null
                    uiScope.launch {
                        val failure = BuddyClient.leave(buddy)
                        if (failure != null) note = errorTemplate.format(failure)
                        busy = null
                        leaving = null
                    }
                }) {
                    Text(
                        stringResource(R.string.settings_buddy_leave_named, buddy.name),
                        color = Color(0xFFF2788F), fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = null }) { Text(stringResource(R.string.common_cancel), color = Brand.teal) }
            }
        )
    }
}

/** The Settings root's search field (#236): filters [SettingsCatalog] as you type. */
@Composable
private fun SettingsSearchField(query: String, onChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Brand.textSecondary) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_search_clear), tint = Brand.textSecondary)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    )
}

/** The icon beside a category on the Settings root. */
private val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.SOUND -> Icons.Filled.GraphicEq
        SettingsCategory.SPEED -> Icons.Filled.Speed
        SettingsCategory.CHARACTERS -> Icons.Filled.School
        SettingsCategory.PRACTICE -> Icons.Filled.TrackChanges
        SettingsCategory.KEYS -> Icons.Filled.Piano
        SettingsCategory.QSO -> Icons.Filled.SettingsInputAntenna
        SettingsCategory.REMINDERS -> Icons.Filled.Notifications
        SettingsCategory.DISPLAY -> Icons.Filled.TextFields
        SettingsCategory.LEADERBOARD -> Icons.Filled.EmojiEvents
        SettingsCategory.ABOUT -> Icons.Filled.Info
    }

/** One category on the Settings root: tap to open its sub-screen. */
@Composable
private fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(category.icon, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(20.dp))
        Text(category.title, color = Brand.textPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("›", color = Brand.textSecondary, fontSize = 20.sp)
    }
}

/** A search hit: the setting, and the category it opens. */
@Composable
private fun SearchResultRow(entry: SettingsSearchEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
            Text(entry.category.title, color = Brand.textSecondary, fontSize = 12.sp)
        }
        Text("›", color = Brand.textSecondary, fontSize = 20.sp)
    }
}

/**
 * One section of a category sub-screen. When a search result opened the
 * category on this section, it is scrolled to the top and tinted for a moment
 * so the eye finds it (iOS does the same with its list's scroll proxy).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnchoredSection(
    section: SettingsSection,
    focus: SettingsSection?,
    content: @Composable ColumnScope.() -> Unit
) {
    val requester = remember { BringIntoViewRequester() }
    var width by remember { mutableStateOf(1f) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(focus) {
        if (section == focus) {
            delay(150)
            // A rect far taller than the screen, from the section's top: the
            // smallest scroll that shows its leading edge puts the section at
            // the top instead of just peeking in at the bottom.
            requester.bringIntoView(Rect(0f, 0f, width, 100_000f))
            flashing = true
            delay(1500)
            flashing = false
        }
    }
    val tint by animateColorAsState(
        if (flashing) Brand.teal.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(600),
        label = "settingsFocus"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .onGloballyPositioned { width = it.size.width.toFloat().coerceAtLeast(1f) }
            .background(tint, RoundedCornerShape(12.dp)),
        content = content
    )
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
fun SessionSettingsOverlay(scope: SettingsMode, onClose: () -> Unit, onPreviewStage: (() -> Unit)? = null) {
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
        SettingsScreen(onBack = onClose, scope = scope, onPreviewStage = onPreviewStage)
    }
}

/**
 * A compact, copy-pasteable snapshot for bug reports — the twin of the iOS
 * `diagnosticInfo()`: build, OS, device, and the settings most likely to
 * matter when reproducing an issue. [scope] is the mode Settings was opened
 * from, so the mode-specific line matches what the reporter was doing.
 */
private fun diagnosticInfo(context: Context, scope: SettingsMode?): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val version = info?.versionName ?: "?"
    val build = info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "?"
    val lines = mutableListOf(
        "AMT $version (build $build)",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
        "Mode: ${scope?.name ?: "Home"}",
        "WPM: ${Settings.characterWpm.roundToInt()}" +
            (if (Settings.farnsworthEnabled) " · Farnsworth ${Settings.effectiveWpm.roundToInt()}" else ""),
        "Tone: ${Settings.sidetoneHz.roundToInt()} Hz"
    )
    when (scope) {
        SettingsMode.WORDS -> lines.add(
            if (Settings.useCustomWords && Settings.customWords.size >= 2) "Word pool: custom (${Settings.customWords.size} words)"
            else if (Settings.wordCount == Settings.WORD_POOL_CW77) "Word pool: CW 77 (CWOps)"
            else "Word pool: Top ${Settings.wordCount}"
        )
        SettingsMode.LISTEN -> lines.add("Listen: ${Settings.listenContent.label} · ${Settings.listenGap.label}")
        SettingsMode.CW77 -> lines.add(
            "CW 77: ${Settings.cw77Style.label} · include me ${if (Settings.cw77IncludeMe) "on" else "off"}"
        )
        SettingsMode.HEAD_COPY -> lines.add("Head Copy: repeats ${Settings.headCopyRepeats} · reveal ${Settings.headCopyRevealSec} s")
        else -> {}
    }
    lines.add("Session: ${Settings.practiceDuration.label} · reveal ${Settings.revealMode.label} · " +
        "choices ${Settings.answerChoices} · recognize ${"%.1f".format(Settings.recognitionTargetSec)} s")
    lines.add("Bluetooth keep-alive: ${if (Settings.bluetoothKeepAlive) "on" else "off"}")
    if (Settings.bandNoise != BackgroundNoiseLevel.OFF) {
        lines.add("Band noise: ${Settings.bandNoise.label}")
    }
    if (Settings.punctuationChars.isNotEmpty()) {
        lines.add("Punctuation: ${Settings.punctuationChars.sorted().joinToString("")}")
    }
    return lines.joinToString("\n")
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
 * Band-noise picker (issues #29, #169): five options with word labels, so it
 * stacks the label above a scrollable pill row like [DurationSetting] rather
 * than crowding them beside the label as [SegmentedSetting] does. The
 * inaudible keep-alive floor is the switch above it, not a level here.
 */
@Composable
private fun BandNoiseSetting() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_band_noise), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
            Text(
                Settings.bandNoise.label,
                color = Brand.teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BackgroundNoiseLevel.bandLevels.forEach { option ->
                val isSel = option == Settings.bandNoise
                Box(
                    modifier = Modifier
                        .background(
                            if (isSel) Brand.teal else Brand.navyRaised,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .clickable { Settings.updateBandNoise(option) }
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

/**
 * On-screen key type, and — for paddles — the keyer mode and the left-handed
 * swap (#233). Mirrors the iOS "On-screen key" section in `SettingsView`.
 */
@Composable
private fun OnScreenKeySetting() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        ChipPicker(
            label = stringResource(R.string.settings_on_screen_key),
            options = OnScreenKeyType.entries,
            selected = Settings.onScreenKey,
            name = { it.label },
            onPick = { Settings.updateOnScreenKey(it) }
        )
        if (Settings.onScreenKey == OnScreenKeyType.PADDLES) {
            Spacer(Modifier.height(14.dp))
            ChipPicker(
                label = stringResource(R.string.settings_paddle_mode),
                options = PaddleKeyer.Mode.entries,
                selected = Settings.paddleMode,
                name = { it.label },
                onPick = { Settings.updatePaddleMode(it) }
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.settings_paddle_swap), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                Switch(
                    checked = Settings.paddleSwap,
                    onCheckedChange = { Settings.updatePaddleSwap(it) },
                    colors = switchColors()
                )
            }
        }
    }
}

/** A labelled row of selectable chips, styled like the adapter's keyer-mode picker. */
@Composable
private fun <T> ChipPicker(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    onPick: (T) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Text(name(selected), color = Brand.teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSel = option == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSel) Brand.teal else Brand.navyRaised,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .clickable { onPick(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    name(option),
                    color = if (isSel) Brand.navy else Brand.textSecondary,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
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

/** Outbound links from the About section. The support page is the one place
 *  the coffee button lives; see the comment at the section. */
private const val SUPPORT_URL = "https://anothermorsetrainer.app/support/"
private const val DISCORD_URL = "https://discord.gg/qgyk3TPUd9"
private const val GITHUB_URL = "https://github.com/N9HO/another-morse-trainer"
private const val LICENSE_URL = "https://github.com/N9HO/another-morse-trainer/blob/main/LICENSE"
/** The licence the Play Integrity artifact's POM names (integrity-1.6.0.pom). */
private const val PLAY_INTEGRITY_TERMS_URL = "https://developer.android.com/google/play/integrity/overview#tos"

/** A tappable row that opens something outside the app; teal like the
 *  diagnostics row, so it reads as an action rather than a toggle. */
@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Brand.teal, fontWeight = FontWeight.Medium)
    }
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
