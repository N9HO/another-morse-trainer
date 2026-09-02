package app.anothermorsetrainer

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.SendingDrill
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val SHEET_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Generates a printable *sending* practice sheet from the characters you've
 * studied. The app can't grade your fist, so the deliverable is the sheet
 * itself: pick a drill type and length, and share or print pages of random
 * groups to key on your paddle.
 *
 * Ported from MorseTrainerApp/SendingDrillView.swift; iOS ShareLink/AirPrint
 * become the Android share sheet and the system print dialog ([SheetPrinter]).
 */
@Composable
fun SendingDrillScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    BackHandler { onBack() }

    var kind by remember { mutableStateOf(SendingDrill.Kind.Studied) }
    var groupCount by remember { mutableIntStateOf(50) }
    var groupSize by remember { mutableIntStateOf(5) }
    var regen by remember { mutableIntStateOf(0) }

    val drill = remember(kind, groupCount, groupSize, regen) {
        SendingDrill.generate(
            kind = kind,
            studied = studiedCharacters(),
            weights = if (kind == SendingDrill.Kind.Personalized) sendingDrillWeights() else emptyMap(),
            groupCount = groupCount,
            groupSize = groupSize
        )
    }
    val subtitle = stringResource(R.string.drills_sheet_subtitle, Settings.characterWpm.roundToInt(), LocalDate.now().format(SHEET_DATE))
    val sheetText = drill.plainText(title = stringResource(R.string.drills_cw_sending_practice), subtitle = subtitle)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_sending_drills), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { regen++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.drills_generate_a_new_sheet), tint = Brand.teal)
            }
        }

      CenteredContent {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DrillPills(
                    SendingDrill.Kind.allCases.map { it to it.title },
                    kind
                ) { kind = it }
                Text(kind.blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
                DrillSlider(
                    label = stringResource(R.string.drills_groups),
                    readout = stringResource(R.string.drills_groups_readout, groupCount),
                    value = groupCount,
                    range = 10..100,
                    step = 5
                ) { groupCount = it }
                DrillSlider(
                    label = stringResource(R.string.drills_group_size),
                    readout = stringResource(R.string.drills_group_size_readout, groupSize),
                    value = groupSize,
                    range = 3..7,
                    step = 1
                ) { groupSize = it }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .brandCard()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                drill.rows.forEach { row ->
                    Text(
                        row,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = Brand.textPrimary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.drills_cw_sending_practice))
                            putExtra(Intent.EXTRA_TEXT, sheetText)
                        }
                        context.startActivity(Intent.createChooser(send, resources.getString(R.string.drills_share_practice_sheet)))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.drills_share)) }
                Button(
                    onClick = { SheetPrinter.print(context, sheetText, resources.getString(R.string.drills_cw_sending_practice)) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.drills_print)) }
            }
        }
      }
    }
}

/**
 * The characters the learner has studied — the declared Koch seed plus anything
 * with recorded recognition data. (iOS reads the live engine's active ladder;
 * Android's engines are per-session, so the persisted stats stand in.)
 */
private fun studiedCharacters(): List<Char> {
    val seed = Settings.seedCharacters()
    val drilled = Stats.charStats.keys.mapNotNull { it.firstOrNull() }
    return (seed + drilled).distinct()
}

/**
 * Per-character difficulty weights for a personalized sheet: higher means
 * "drill this more." Weak (low accuracy) and slow (high median time relative to
 * the goal) characters score higher; comfortable ones sit near the floor.
 * Ported from the iOS AppModel.sendingDrillWeights().
 */
private fun sendingDrillWeights(): Map<Char, Double> {
    val goal = maxOf(0.3, Settings.recognitionTargetSec)
    val weights = mutableMapOf<Char, Double>()
    for ((key, agg) in Stats.charStats) {
        val ch = key.firstOrNull() ?: continue
        val missPenalty = (1.0 - agg.accuracy) * 4.0                       // 0…4
        val slowPenalty = agg.medianMs?.let { minOf(it / 1000.0 / goal, 3.0) } ?: 1.0
        weights[ch] = maxOf(0.5, 0.5 + missPenalty + slowPenalty)
    }
    return weights
}

@Composable
private fun DrillSlider(
    label: String,
    readout: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(readout, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            colors = SliderDefaults.colors(
                thumbColor = Brand.teal,
                activeTrackColor = Brand.teal,
                inactiveTrackColor = Brand.navy
            )
        )
    }
}

@Composable
private fun <T> DrillPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
