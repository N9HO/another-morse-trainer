package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LocalFireDepartment
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.MorseCode
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
private val MASTERED = Color(0xFFEF9F27)
private val GOOD = Color(0xFF5DCAA5)

/**
 * The Brag Sheet: your progress at a glance — daily streak with this week's
 * practice strip, lifetime totals, personal bests, the per-character recognition
 * chart, and recent sessions. A share button renders a card you can post. Reads
 * the persisted [Stats] singleton.
 */
@Composable
fun StatsScreen(onBack: () -> Unit) {
    // A tapped session opens its full detail record in place of the sheet.
    var selected by remember { mutableStateOf<SessionRecord?>(null) }
    BackHandler { if (selected != null) selected = null else onBack() }
    val detail = selected
    if (detail != null) {
        SessionDetail(record = detail, onBack = { selected = null })
        return
    }
    val context = LocalContext.current
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

            if (Stats.totalSessions == 0) {
                Spacer(Modifier.height(40.dp))
                Text(
                    stringResource(R.string.stats_empty),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                return@Column
            }

            StreakHero()

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
            PersonalBests()

            val charRows = Stats.charStats.entries
                .mapNotNull { e -> e.value.medianMs?.let { CharBar(e.key, it, e.value.accuracy) } }
                .sortedWith(compareBy(SessionRecord.characterOrder) { it.character })
            if (charRows.isNotEmpty()) {
                SectionLabel(stringResource(R.string.stats_recognition_speed))
                Text(
                    stringResource(R.string.stats_recognition_speed_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
                val axisMax = SessionRecord.axisCeilingMS(charRows.maxOf { it.medianMs })
                Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(14.dp)) {
                    charRows.forEachIndexed { i, row ->
                        RecognitionBar(row = row, axisMaxMs = axisMax)
                        if (i < charRows.size - 1) Spacer(Modifier.height(8.dp))
                    }
                }
            }

            val bands = WPMBands.summarize(
                Stats.recent.map { WPMBands.Entry(it.characterWpm, it.attempts, it.correct, it.medianTtrMs) }
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
                        onOpen = record?.let { { selected = it } }
                    )
                    if (i < recent.size - 1) HairlineDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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

// MARK: - Personal bests

@Composable
private fun PersonalBests() {
    val context = LocalContext.current
    val realSessions = Stats.recent.filter { it.attempts >= 10 }
    val bestAcc = realSessions.maxOfOrNull { it.accuracy }
    val biggest = Stats.recent.maxOfOrNull { it.attempts }
    val mastered = ShareCard.masteredCount()
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
    onOpen: (() -> Unit)? = null
) {
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
            if (attempts == 0) "—" else "$attempts · ${(accuracy * 100).roundToInt()}%",
            fontWeight = FontWeight.Medium,
            color = if (accuracy >= 0.9) GOOD else MASTERED
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

            Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(horizontal = 14.dp, vertical = 4.dp)) {
                DetailRow(stringResource(R.string.common_answered), "${record.attempts}")
                HairlineDivider()
                DetailRow(stringResource(R.string.common_accuracy), "${(record.accuracy * 100).roundToInt()}%")
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
                val axisMax = SessionRecord.axisCeilingMS(rows.mapNotNull { it.result?.medianMS }.maxOrNull() ?: 1000)
                Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(14.dp)) {
                    rows.forEachIndexed { i, row ->
                        SessionCharRow(row = row, axisMaxMs = axisMax)
                        if (i < rows.size - 1) Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
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

/** One row of the recognition chart: a character, its median copy time, and accuracy. */
private data class CharBar(val character: String, val medianMs: Int, val accuracy: Double)

@Composable
private fun RecognitionBar(row: CharBar, axisMaxMs: Int) {
    val fraction = (row.medianMs.toFloat() / axisMaxMs).coerceIn(0.04f, 1f)
    val barColor = when {
        row.accuracy >= 0.9 -> Brand.teal
        row.accuracy >= 0.7 -> Brand.tealBright
        else -> MASTERED
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(row.character, modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Brand.textPrimary)
        Box(
            modifier = Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(11.dp)).background(Brand.navy)
        ) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(22.dp).clip(RoundedCornerShape(11.dp)).background(barColor))
        }
        Text(fmtMs(row.medianMs), modifier = Modifier.width(52.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
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
