package app.anothermorsetrainer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.ActivityLedger
import app.anothermorsetrainer.morsekit.CharacterStats
import app.anothermorsetrainer.morsekit.ConfusionMatrix
import app.anothermorsetrainer.morsekit.ConfusionPair
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.WPMBandSummary
import app.anothermorsetrainer.morsekit.WPMBands
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val DETAIL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")
private val ACTIVITY_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val ACTIVITY_MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
private val MASTERED = Color(0xFFEF9F27)
private val GOOD = Color(0xFF5DCAA5)

/**
 * The Brag Sheet: your progress at a glance — the Characters stage and goal,
 * daily streak with this week's practice strip, lifetime totals, personal
 * bests, the per-character table (weakest first), performance by speed, recent
 * sessions and most-confused pairs. A share button renders a card you can post.
 * Reads the persisted [Stats] singleton and the [EngineStore] snapshot.
 */
@Composable
fun StatsScreen(onBack: () -> Unit, onOpenLeaderboard: () -> Unit = {}) {
    // A tapped session opens its full detail record in place of the sheet.
    var selected by remember { mutableStateOf<SessionRecord?>(null) }
    BackHandler { if (selected != null) selected = null else onBack() }
    val detail = selected
    if (detail != null) {
        SessionDetail(record = detail, onBack = { selected = null })
        return
    }
    val context = LocalContext.current
    // The Characters track as last saved: stage, active set, the 20-attempt
    // per-character window and the confusion matrix. Read once per visit —
    // nothing on this screen records an answer.
    val snapshot = remember { EngineStore.snapshot() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Spacer(Modifier.weight(1f))
            if (Stats.totalSessions > 0) {
                IconButton(onClick = { ShareCard.share(context) }) {
                    Icon(Icons.Filled.IosShare, contentDescription = stringResource(R.string.stats_share_your_brag_sheet), tint = Brand.teal)
                }
            }
        }

        CenteredScrollColumn(
            contentModifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                stringResource(R.string.stats_brag_sheet),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = TextAlign.Center
            )

            StageHeader(snapshot)

            // The shared leaderboard (docs/high-scores-design.md, step 2):
            // above the empty-state return, since the board is public and
            // worth a look before the first session.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .brandCard(14.dp)
                    .clickable(onClick = onOpenLeaderboard)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Leaderboard, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.leaderboard_open), color = Brand.teal, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("›", color = Brand.textSecondary, fontWeight = FontWeight.Bold)
            }

            if (Stats.totalSessions == 0) {
                Spacer(Modifier.height(40.dp))
                Text(
                    stringResource(R.string.stats_empty),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                return@CenteredScrollColumn
            }

            StreakHero()

            // The daily activity grid (#181), right under the streak it
            // illustrates: half a year of columns on a phone, a full year
            // where the window is 600 dp or wider — iOS switches on the
            // regular size class at the same point.
            SectionLabel(stringResource(R.string.stats_activity))
            Text(
                stringResource(R.string.stats_activity_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(14.dp)) {
                ActivityGrid(ledger = Stats.activity, weeks = if (isWideScreen()) 52 else 26)
            }

            SectionLabel(stringResource(R.string.stats_lifetime))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(stringResource(R.string.stats_sessions), "${Stats.totalSessions}", Modifier.weight(1f))
                MetricTile(stringResource(R.string.common_answered), "${Stats.totalAttempts}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(stringResource(R.string.stats_practice_time), fmtDuration(Stats.totalPracticeSeconds), Modifier.weight(1f))
                MetricTile(stringResource(R.string.common_accuracy), "${(Stats.overallAccuracy * 100).roundToInt()}%", Modifier.weight(1f), GOOD)
            }

            SectionLabel(stringResource(R.string.stats_personal_bests))
            PersonalBests(snapshot)

            // Per-character performance over the engine's 20-attempt window,
            // weakest first (unmastered, then slowest) — the iOS StatsView
            // "Characters" table. Every active character gets a row, even one
            // never drilled, so the learner sees the whole ladder.
            val charRows = characterRows(snapshot)
            if (charRows.isNotEmpty()) {
                SectionLabel(stringResource(R.string.stats_characters))
                Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp)) {
                    charRows.forEachIndexed { i, row ->
                        CharacterRow(row)
                        if (i < charRows.size - 1) HairlineDivider()
                    }
                }
            }

            // Every stored session, not the 50-row summary list: the iOS twin
            // summarises the whole SessionHistory. Scored sessions only — a
            // Listen & Learn session has no accuracy to contribute (#183).
            val bands = WPMBands.summarize(
                Stats.history.filter { it.isScored }.map { r ->
                    WPMBands.Entry(
                        wpm = r.characterWPM,
                        attempts = r.attempts,
                        correct = r.correct,
                        medianTtrMs = r.medianTTR?.let { (it * 1000).roundToInt() }
                    )
                }
            )
            if (bands.isNotEmpty()) {
                SectionLabel(stringResource(R.string.stats_performance_by_speed))
                Text(
                    stringResource(R.string.stats_performance_by_speed_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
                Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp)) {
                    bands.forEachIndexed { i, band ->
                        SpeedBandRow(band)
                        if (i < bands.size - 1) HairlineDivider()
                    }
                }
            }

            SectionLabel(stringResource(R.string.stats_recent_sessions))
            Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp)) {
                val recent = Stats.recent.take(6)
                recent.forEachIndexed { i, s ->
                    // Rows with a stored detail record open it on tap; rows
                    // saved before details existed stay plain.
                    val record = Stats.sessionRecord(s.recordId)
                    SessionRow(
                        mode = s.mode,
                        date = LocalDate.ofEpochDay(s.epochDay).format(DATE_FMT),
                        attempts = s.attempts,
                        accuracy = s.accuracy,
                        scored = s.isScored,
                        onOpen = record?.let { { selected = it } }
                    )
                    if (i < recent.size - 1) HairlineDivider()
                }
            }

            // Both error directions summed, strongest first, top eight — the
            // same data the Confusion Drill trains on.
            val pairs = ConfusionMatrix().apply { restore(snapshot.engine.confusions) }
                .pairs(minCount = 1)
                .take(8)
            if (pairs.isNotEmpty()) {
                SectionLabel(stringResource(R.string.stats_most_confused_pairs))
                Text(
                    stringResource(R.string.stats_most_confused_pairs_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
                Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp)) {
                    pairs.forEachIndexed { i, pair ->
                        ConfusionRow(pair)
                        if (i < pairs.size - 1) HairlineDivider()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// MARK: - Stage header

/** The stage's full name, as the iOS `stageName` shows it (not the quiz's short pill labels). */
internal fun stageDisplayName(context: Context, stage: ProgressiveCharacters.Stage): String =
    context.getString(
        when (stage) {
            ProgressiveCharacters.Stage.Singles -> R.string.stats_stage_singles
            ProgressiveCharacters.Stage.Pairs -> R.string.stats_stage_pairs
            ProgressiveCharacters.Stage.Triples -> R.string.stats_stage_triples
            ProgressiveCharacters.Stage.Phrases -> R.string.stats_stage_phrases
        }
    )

/** Current stage, active character count and the recognize-within goal (iOS StatsView header). */
@Composable
private fun StageHeader(snapshot: ProgressiveCharacters.Snapshot) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(horizontal = 14.dp, vertical = 4.dp)) {
        DetailRow(stringResource(R.string.stats_current_stage), stageDisplayName(context, snapshot.stage))
        HairlineDivider()
        DetailRow(stringResource(R.string.stats_active_characters), "${snapshot.engine.activeCharacters.size}")
        HairlineDivider()
        DetailRow(
            stringResource(R.string.stats_recognize_within_goal),
            stringResource(R.string.stats_goal_value, Settings.recognitionTargetSec)
        )
    }
    Text(
        stringResource(R.string.stats_header_footer),
        style = MaterialTheme.typography.bodySmall,
        color = Brand.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

// MARK: - Per-character table

/** One row of the Characters table — the iOS `AppModel.CharStat`. */
private data class CharRow(
    val character: Char,
    val pattern: String,
    val attempts: Int,
    val accuracy: Double,
    val medianTTR: Double?,
    val mastered: Boolean
)

/**
 * Per-character performance for the active set over the engine's own
 * [CharacterStats.historyLimit]-attempt window, weakest first: unmastered
 * before mastered, then slowest first, never-copied at the top.
 */
private fun characterRows(snapshot: ProgressiveCharacters.Snapshot): List<CharRow> {
    val window = CharacterStats.historyLimit
    val threshold = Settings.recognitionTargetSec
    val byChar = snapshot.engine.stats.associateBy { it.character }
    return snapshot.engine.activeCharacters.map { ch ->
        val s = byChar[ch] ?: CharacterStats(ch)
        CharRow(
            character = ch,
            pattern = MorseCode.pattern(ch) ?: "",
            attempts = s.attempts.size,
            accuracy = s.accuracy(window),
            medianTTR = s.medianTTR(window),
            mastered = s.isMastered(threshold)
        )
    }.sortedWith(
        compareBy<CharRow> { it.mastered }
            .thenByDescending { it.medianTTR ?: Double.POSITIVE_INFINITY }
    )
}

@Composable
private fun CharacterRow(row: CharRow) {
    val ttr = row.medianTTR
    val ttrColor = when {
        ttr == null -> Brand.textSecondary
        row.mastered -> GOOD
        ttr > Settings.recognitionTargetSec -> MASTERED
        else -> Brand.textPrimary
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.width(56.dp)) {
            Text(row.character.toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Brand.textPrimary)
            Text(row.pattern, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
        }
        Column(modifier = Modifier.width(90.dp)) {
            Text(
                row.medianTTR?.let { stringResource(R.string.common_seconds_2dp, it) } ?: "—",
                fontWeight = FontWeight.Medium,
                color = ttrColor
            )
            Text(stringResource(R.string.stats_median_ttr), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
        Column(modifier = Modifier.width(70.dp)) {
            Text(
                if (row.attempts == 0) "—" else "${(row.accuracy * 100).roundToInt()}%",
                fontWeight = FontWeight.Medium,
                color = Brand.textPrimary
            )
            Text(stringResource(R.string.stats_tries, row.attempts), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        MasterySeal(row.mastered)
    }
}

/** The "mastered" seal: a filled check for mastered, a hollow ring otherwise (iOS checkmark.seal / circle.dashed). */
@Composable
private fun MasterySeal(mastered: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (mastered) Modifier.background(GOOD)
                else Modifier.border(1.5.dp, Brand.textSecondary.copy(alpha = 0.5f), CircleShape)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (mastered) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.stats_mastered),
                tint = Brand.navy,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// MARK: - Most-confused pairs

@Composable
private fun ConfusionRow(pair: ConfusionPair) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CharBadge(pair.a)
        Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = Brand.textSecondary, modifier = Modifier.size(16.dp))
        CharBadge(pair.b)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.stats_confusion_count, pair.count),
            fontWeight = FontWeight.Medium,
            color = MASTERED
        )
    }
}

@Composable
private fun CharBadge(ch: Char) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ch.toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Brand.textPrimary)
        Text(MorseCode.pattern(ch) ?: "", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
    }
}

// MARK: - Streak hero

@Composable
private fun StreakHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Brand.cornerRadius))
            .background(Brand.navyElevated)
            .border(1.dp, Brand.teal.copy(alpha = 0.28f), RoundedCornerShape(Brand.cornerRadius))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = if (Stats.currentStreak > 0) MASTERED else Brand.textSecondary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("${Stats.currentStreak}", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.stats_day_streak), style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.stats_longest), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                Text("${Stats.longestStreak}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
            }
        }
        Spacer(Modifier.height(16.dp))
        WeekStrip()
    }
}

@Composable
private fun WeekStrip() {
    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val practiced = Stats.recent.map { it.epochDay }.toHashSet()
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(modifier = Modifier.fillMaxWidth()) {
        for (i in 0..6) {
            val day = monday.plusDays(i.toLong())
            val didPractice = practiced.contains(day.toEpochDay())
            val isToday = day == today
            val isFuture = day.isAfter(today)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .then(
                            if (didPractice) Modifier.background(Brand.teal)
                            else Modifier.border(
                                1.5.dp,
                                if (isFuture) Brand.hairline else Brand.textSecondary.copy(alpha = 0.5f),
                                CircleShape
                            )
                        )
                        .then(if (isToday) Modifier.border(2.dp, Brand.tealBright, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (didPractice) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Brand.navy, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(labels[i], style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
            }
        }
    }
}

// MARK: - Activity grid (#181)

/**
 * The GitHub-style activity grid — the iOS `ActivityGridView`: one tile per
 * day, seven rows Monday to Sunday, one column per week ending with the
 * current week, shaded by that day's practice time from [ActivityLedger].
 * Sized from the card's width so the columns always fit, and each past day
 * carries its own content description ("Tue 3 Sep: 12 minutes").
 */
@Composable
private fun ActivityGrid(ledger: ActivityLedger, weeks: Int, today: LocalDate = LocalDate.now()) {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val firstDay = monday.minusWeeks((weeks - 1).toLong())
    val gutter = 22.dp          // weekday labels
    val headerHeight = 16.dp    // month labels
    val gap = 2.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tile = ((maxWidth - gutter - gap * (weeks - 1)) / weeks).coerceAtLeast(4.dp)
        Column(modifier = Modifier.fillMaxWidth()) {
            // A month label over the first column that holds the 1st of a
            // month, at most every third column so they never overlap.
            Row(modifier = Modifier.fillMaxWidth().height(headerHeight)) {
                Spacer(Modifier.width(gutter))
                var lastLabelWeek = -3
                for (week in 0 until weeks) {
                    val weekStart = firstDay.plusWeeks(week.toLong())
                    val firstOfMonth = (0..6).map { weekStart.plusDays(it.toLong()) }.firstOrNull { it.dayOfMonth == 1 }
                    val label = if (firstOfMonth != null && week - lastLabelWeek >= 3) {
                        lastLabelWeek = week
                        firstOfMonth.format(ACTIVITY_MONTH_FMT)
                    } else null
                    Box(modifier = Modifier.width(if (week < weeks - 1) tile + gap else tile)) {
                        if (label != null) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.textSecondary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                // Weekday labels down the gutter, on alternate rows.
                Column(modifier = Modifier.width(gutter)) {
                    val labels = listOf("M", null, "W", null, "F", null, null)
                    for (row in 0..6) {
                        if (row > 0) Spacer(Modifier.height(gap))
                        Box(modifier = Modifier.height(tile), contentAlignment = Alignment.CenterStart) {
                            labels[row]?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                for (week in 0 until weeks) {
                    if (week > 0) Spacer(Modifier.width(gap))
                    Column(modifier = Modifier.width(tile)) {
                        for (weekday in 0..6) {
                            if (weekday > 0) Spacer(Modifier.height(gap))
                            ActivityTile(ledger, firstDay.plusDays((week * 7 + weekday).toLong()), today, tile)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ActivityLegend()
        }
    }
}

/** One day's tile; days still to come are left blank. */
@Composable
private fun ActivityTile(ledger: ActivityLedger, day: LocalDate, today: LocalDate, size: Dp) {
    if (day.isAfter(today)) {
        Spacer(Modifier.size(size))
        return
    }
    val dayText = day.format(ACTIVITY_DAY_FMT)
    val seconds = ledger.seconds(day)
    val label = when {
        !ledger.isRecorded(day) -> stringResource(R.string.stats_activity_day_none, dayText)
        seconds < 60 -> stringResource(R.string.stats_activity_day_under_minute, dayText)
        else -> stringResource(R.string.stats_activity_day_minutes, dayText, seconds / 60)
    }
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(activityColor(ledger.level(day)))
            .then(if (day == today) Modifier.border(1.dp, Brand.tealBright, shape) else Modifier)
            .semantics { contentDescription = label }
    )
}

/** "Less ▢▢▢▢▢ More" — the five shades in order. */
@Composable
private fun ActivityLegend() {
    val legend = stringResource(R.string.stats_activity_legend)
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = legend },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.stats_activity_less), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        for (level in 0..ActivityLedger.MAX_LEVEL) {
            Spacer(Modifier.width(4.dp))
            Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(activityColor(level)))
        }
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.stats_activity_more), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
    }
}

/**
 * Shade for a level, 0 (nothing) to 4 (30 minutes or more): the teal accent
 * stepped up in opacity, the same five steps as the iOS grid.
 */
private fun activityColor(level: Int): Color = when (level) {
    0 -> Color.White.copy(alpha = 0.06f)
    1 -> Brand.teal.copy(alpha = 0.3f)
    2 -> Brand.teal.copy(alpha = 0.55f)
    3 -> Brand.teal.copy(alpha = 0.8f)
    else -> Brand.tealBright
}

// MARK: - Personal bests

@Composable
private fun PersonalBests(snapshot: ProgressiveCharacters.Snapshot) {
    // Scored sessions only: a Listen & Learn session's "attempts" are items
    // heard, with no accuracy to be best at (#183) — and a best-accuracy
    // badge from a 3-question session is meaningless, so at least ten drills.
    val scored = Stats.recent.filter { it.isScored }
    val realSessions = scored.filter { it.attempts >= 10 }
    val bestAcc = realSessions.maxOfOrNull { it.accuracy }
    val biggest = scored.maxOfOrNull { it.attempts }
    val mastered = ShareCard.masteredCount(snapshot)
    val total = MorseCode.kochOrder.size

    Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(horizontal = 14.dp, vertical = 4.dp)) {
        BestRow(Icons.Filled.Bolt, stringResource(R.string.stats_fastest_copy), Brand.teal,
            Stats.bestTtrMs?.let { fmtMs(it) } ?: "—", Brand.teal)
        HairlineDivider()
        BestRow(Icons.Filled.TrackChanges, stringResource(R.string.stats_best_session_accuracy), Brand.textSecondary,
            bestAcc?.let { "${(it * 100).roundToInt()}%" } ?: "—", if (bestAcc == null) Brand.textPrimary else GOOD)
        HairlineDivider()
        BestRow(Icons.Filled.BarChart, stringResource(R.string.stats_biggest_session), Brand.textSecondary,
            biggest?.let { stringResource(R.string.stats_biggest_session_value, it) } ?: "—", Brand.textPrimary)
        // Per-mode bests (docs/high-scores-design.md, step 1): one row per
        // mode that keeps a score and has been played, in home-menu order.
        // iOS shows the same rows on its Brag Sheet's Personal bests card.
        for (m in MODE_BESTS) {
            val best = Stats.bestScores[m.modeKey] ?: continue
            HairlineDivider()
            BestRow(m.icon, stringResource(R.string.stats_mode_best, stringResource(m.titleRes)), Brand.textSecondary,
                m.format(best), GOOD)
        }
        HairlineDivider()
        BestRow(Icons.Filled.WorkspacePremium, stringResource(R.string.stats_characters_mastered), MASTERED, "$mastered / $total", MASTERED)
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else mastered.toFloat() / total },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).clip(RoundedCornerShape(3.dp)),
            color = MASTERED,
            trackColor = Brand.navy
        )
    }
}

/**
 * A mode whose runs carry a score, keyed by the string its screen passes to
 * `Stats.record`, with how its best reads: Contest scores points, Pileup Runner
 * counts QSOs, Rapid Fire counts correct copies, a game's score is a number.
 */
private class ModeBestRow(
    val modeKey: String,
    val titleRes: Int,
    val icon: ImageVector,
    val format: @Composable (Int) -> String
)

private val MODE_BESTS: List<ModeBestRow> = listOf(
    ModeBestRow("Rapid Fire", R.string.mode_rapid_fire, Icons.Filled.FlashOn) { stringResource(R.string.stats_best_correct, it) },
    ModeBestRow("Pileup", R.string.mode_pileup_runner, Icons.Filled.RecordVoiceOver) { pluralStringResource(R.plurals.stats_best_qsos, it, it) },
    ModeBestRow("Contest", R.string.mode_contest, Icons.Filled.EmojiEvents) { stringResource(R.string.stats_best_points, it) },
    ModeBestRow("Morse Invaders", R.string.mode_invaders, Icons.Filled.SportsEsports) { it.toString() },
    ModeBestRow("CW Galaga", R.string.mode_galaga, Icons.Filled.Flight) { it.toString() },
    ModeBestRow("Morse Defender", R.string.mode_defender, Icons.Filled.Shield) { it.toString() },
    ModeBestRow("CW Dungeon", R.string.mode_dungeon, Icons.Filled.Castle) { it.toString() },
    ModeBestRow("CW Frogger", R.string.mode_frogger, Icons.Filled.Pets) { it.toString() },
    ModeBestRow("CW Asteroids", R.string.mode_asteroids, Icons.Filled.RocketLaunch) { it.toString() }
)

@Composable
private fun BestRow(icon: ImageVector, label: String, iconColor: Color, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// MARK: - Lifetime + recent

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Brand.textPrimary) {
    Column(modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Brand.navyElevated).padding(14.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
    }
}

@Composable
private fun SessionRow(
    mode: String,
    date: String,
    attempts: Int,
    accuracy: Double,
    scored: Boolean,
    onOpen: (() -> Unit)? = null
) {
    // A session that graded nothing — Listen & Learn, Stories — has no
    // accuracy, and must not read as 0% (#183): its count is items heard.
    val summary = when {
        scored -> "$attempts · ${(accuracy * 100).roundToInt()}%"
        SessionRecord.isScoredMode(mode) -> "$attempts · ${stringResource(R.string.stats_not_applicable)}"
        else -> "${stringResource(R.string.stats_heard_value, attempts)} · ${stringResource(R.string.stats_not_applicable)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(mode, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
            Text(date, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
        Text(
            summary,
            fontWeight = FontWeight.Medium,
            color = if (!scored) Brand.textPrimary else if (accuracy >= 0.9) GOOD else MASTERED
        )
        if (onOpen != null) {
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 18.sp, color = Brand.textSecondary)
        }
    }
}

// MARK: - Session detail

/**
 * One session in full: when and how long, the aggregate results, and the
 * per-character recognition chart for single-character sessions. The Android
 * face of the iOS per-session detail (SessionRecord.chartRows).
 */
@Composable
private fun SessionDetail(record: SessionRecord, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.stats_back_to_sessions)) }
        }
        CenteredScrollColumn(
            contentModifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                record.mode,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Text(
                ZonedDateTime.ofInstant(record.date, ZoneId.systemDefault()).format(DETAIL_FMT),
                style = MaterialTheme.typography.labelMedium,
                color = Brand.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 16.dp),
                textAlign = TextAlign.Center
            )

            // Listen & Learn and Stories grade nothing: their count is items
            // heard and their accuracy is N/A, not 0% (#183).
            val passive = !SessionRecord.isScoredMode(record.mode)
            Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(horizontal = 14.dp, vertical = 4.dp)) {
                DetailRow(stringResource(if (passive) R.string.stats_heard else R.string.common_answered), "${record.attempts}")
                HairlineDivider()
                DetailRow(
                    stringResource(R.string.common_accuracy),
                    if (record.isScored) "${(record.accuracy * 100).roundToInt()}%" else stringResource(R.string.stats_not_applicable)
                )
                HairlineDivider()
                DetailRow(stringResource(R.string.common_fastest), record.fastestTTR?.let { stringResource(R.string.common_seconds_2dp, it) } ?: "—")
                HairlineDivider()
                DetailRow(stringResource(R.string.common_median), record.medianTTR?.let { stringResource(R.string.common_seconds_2dp, it) } ?: "—")
                HairlineDivider()
                DetailRow(stringResource(R.string.stats_duration), record.durationSeconds?.let { fmtDuration(it.roundToInt()) } ?: "—")
                if (record.characterWPM > 0) {
                    HairlineDivider()
                    val eff = if (record.effectiveWPM in 1 until record.characterWPM) stringResource(R.string.stats_eff_suffix, record.effectiveWPM) else ""
                    DetailRow(stringResource(R.string.common_speed), stringResource(R.string.stats_speed_value, record.characterWPM, eff))
                }
            }

            val rows = record.chartRows
            if (rows.isNotEmpty()) {
                SectionLabel(stringResource(R.string.stats_characters_this_session))
                Text(
                    stringResource(R.string.stats_characters_this_session_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
                Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(14.dp)) {
                    SessionChart(rows = rows, idealMs = (Settings.recognitionTargetSec * 1000).roundToInt())
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The per-character recognition chart with its scaffolding — the iOS
 * `RecognitionTimeChart`: hairline gridlines every 250 ms, a millisecond axis
 * along the bottom, and a dashed reference line at the recognize-within goal
 * labelled "ideal". The axis ceiling always includes the goal so the line is
 * on the chart even when every bar beats it.
 *
 * Laid out from fixed column widths rather than measured: the character
 * gutter and the trailing value column are constant, so the plot area is the
 * remaining width and every x can be a plain Dp.
 */
@Composable
private fun SessionChart(rows: List<SessionRecord.ChartRow>, idealMs: Int) {
    val observed = rows.mapNotNull { it.result?.medianMS }.maxOrNull() ?: 0
    val axisMax = SessionRecord.axisCeilingMS(maxOf(observed, idealMs))
    val gridValues = (250..axisMax step 250).toList()
    val gutter = 28.dp          // character-label column (SessionCharRow)
    val trailing = 60.dp        // value column: 52.dp + 8.dp start padding
    val rowHeight = 22.dp
    val rowGap = 8.dp
    val topInset = 18.dp        // room for the "ideal" label
    val axisHeight = 20.dp
    val rowsHeight = rowHeight * rows.size + rowGap * (rows.size - 1).coerceAtLeast(0)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val plotW = (maxWidth - gutter - trailing).coerceAtLeast(1.dp)
        fun xOf(ms: Int) = gutter + plotW * (ms.toFloat() / axisMax)

        Column(modifier = Modifier.fillMaxWidth().padding(top = topInset, bottom = axisHeight)) {
            rows.forEachIndexed { i, row ->
                SessionCharRow(row = row, axisMaxMs = axisMax)
                if (i < rows.size - 1) Spacer(Modifier.height(rowGap))
            }
        }
        // Gridlines and the dashed goal line, drawn over the bar tracks so they
        // stay visible against the filled navy pills.
        Canvas(modifier = Modifier.matchParentSize()) {
            val top = topInset.toPx()
            val bottom = top + rowsHeight.toPx()
            for (v in gridValues) {
                val x = xOf(v).toPx()
                drawLine(Brand.hairline, Offset(x, top), Offset(x, bottom), 1f)
            }
            val ix = xOf(idealMs).toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = Offset(ix, top),
                end = Offset(ix, bottom),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
        }
        Text(
            stringResource(R.string.stats_ideal_label, idealMs),
            style = MaterialTheme.typography.labelSmall,
            color = Brand.textSecondary,
            modifier = Modifier.offset(x = (xOf(idealMs) - 28.dp).coerceAtLeast(gutter), y = 0.dp)
        )
        for (v in listOf(0) + gridValues) {
            Text(
                "$v",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary,
                modifier = Modifier.offset(x = xOf(v) - 8.dp, y = topInset + rowsHeight + 2.dp)
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, color = Brand.textPrimary)
    }
}

/** One chart row: the character, its median-time bar, and accuracy this session. */
@Composable
private fun SessionCharRow(row: SessionRecord.ChartRow, axisMaxMs: Int) {
    val res = row.result
    val ms = res?.medianMS
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            row.character,
            modifier = Modifier.width(28.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = if (res == null) Brand.textSecondary.copy(alpha = 0.45f) else Brand.textPrimary
        )
        Box(
            modifier = Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(11.dp)).background(Brand.navy)
        ) {
            if (res != null && ms != null) {
                val fraction = (ms.toFloat() / axisMaxMs).coerceIn(0.04f, 1f)
                val barColor = when {
                    res.accuracy >= 0.9 -> Brand.teal
                    res.accuracy >= 0.7 -> Brand.tealBright
                    else -> MASTERED
                }
                Box(modifier = Modifier.fillMaxWidth(fraction).height(22.dp).clip(RoundedCornerShape(11.dp)).background(barColor))
            }
        }
        Text(
            when {
                res == null -> "—"
                ms != null -> fmtMs(ms)
                else -> "${res.correct}/${res.attempts}"
            },
            modifier = Modifier.width(52.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Brand.textSecondary
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Brand.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
private fun HairlineDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Brand.hairline))
}

/** One 5-WPM speed band: range + session count, accuracy, typical reaction time. */
@Composable
private fun SpeedBandRow(band: WPMBandSummary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.common_wpm_value, band.label), fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
            Text(
                pluralStringResource(R.plurals.stats_band_sessions, band.sessions, band.sessions),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${(band.accuracy * 100).roundToInt()}%",
                fontWeight = FontWeight.Medium,
                color = if (band.accuracy >= 0.9) GOOD else MASTERED
            )
            Text(
                band.medianMs?.let { stringResource(R.string.stats_reaction_value, fmtMs(it)) } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )
        }
    }
}

private fun fmtMs(ms: Int): String =
    if (ms >= 1000) "%.1fs".format(ms / 1000.0) else "${ms}ms"

/** "4h 12m" / "12m" / "45s" — compact practice-time formatting, matching iOS. */
private fun fmtDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${seconds}s"
    }
}
