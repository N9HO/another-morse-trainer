package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The distilled "Start here" for a newcomer: how to begin, what to expect, and
 * why the characters come at you so fast. A condensed, in-app version of the
 * website guide's opening sections, one tap from the home screen (#96) — the
 * site explained all of this, but a new user on the tile grid had no idea it
 * existed. Everything here is stated on the site too; keep the two in step.
 */
@Composable
fun StartHereScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val stepLeads = stringArrayResource(R.array.start_here_step_leads)
    val stepBodies = stringArrayResource(R.array.start_here_step_bodies)
    val stageNames = stringArrayResource(R.array.start_here_stage_names)
    val stageRoughly = stringArrayResource(R.array.start_here_stage_roughly)
    val stageWork = stringArrayResource(R.array.start_here_stage_work)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(
                stringResource(R.string.start_here_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Brand.textPrimary
            )
        }

        // The scroll spans the window and the readable cap sits inside it, so
        // a tablet's gutters scroll too (#112).
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Body(stringResource(R.string.start_here_intro))

                SectionTitle(stringResource(R.string.start_here_steps_title))
                stepLeads.indices.forEach { i ->
                    StepRow(i + 1, stepLeads[i], stepBodies.getOrElse(i) { "" })
                }
                Callout(stringResource(R.string.start_here_tag_tip), stringResource(R.string.start_here_tip))

                SectionTitle(stringResource(R.string.start_here_speed_title))
                Body(stringResource(R.string.start_here_speed_two))
                SpeedCard(
                    stringResource(R.string.start_here_speed_char_title),
                    stringResource(R.string.start_here_speed_char_what),
                    stringResource(R.string.start_here_speed_char_target)
                )
                SpeedCard(
                    stringResource(R.string.start_here_speed_eff_title),
                    stringResource(R.string.start_here_speed_eff_what),
                    stringResource(R.string.start_here_speed_eff_target)
                )
                Callout(stringResource(R.string.start_here_tag_key), stringResource(R.string.start_here_speed_key))
                Body(stringResource(R.string.start_here_speed_why))
                if (Settings.characterWpm < 33.0) {
                    Callout(
                        stringResource(R.string.start_here_tag_check),
                        stringResource(R.string.start_here_speed_check, Settings.characterWpm.roundToInt())
                    )
                }

                SectionTitle(stringResource(R.string.start_here_method_title))
                Body(stringResource(R.string.start_here_koch))
                Body(stringResource(R.string.start_here_ttr))

                SectionTitle(stringResource(R.string.start_here_stages_title))
                Column(modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = 14.dp)) {
                    stageNames.indices.forEach { i ->
                        if (i > 0) HorizontalDivider(color = Brand.hairline)
                        StageRow(stageNames[i], stageRoughly.getOrElse(i) { "" }, stageWork.getOrElse(i) { "" })
                    }
                }

                SectionTitle(stringResource(R.string.start_here_aim_title))
                Body(stringResource(R.string.start_here_aim_body))

                SectionTitle(stringResource(R.string.start_here_know_title))
                Body(stringResource(R.string.start_here_know_body))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Brand.textPrimary,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
}

@Composable
private fun StepRow(number: Int, lead: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(26.dp).background(Brand.teal, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", color = Brand.navy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(lead, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
        }
    }
}

@Composable
private fun Callout(tag: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .brandCard(cornerRadius = 14.dp)
            .border(1.dp, Brand.teal.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            tag,
            color = Brand.teal,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 11.sp,
            modifier = Modifier.width(48.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
    }
}

@Composable
private fun SpeedCard(title: String, what: String, target: String) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = 14.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
        Text(what, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
        Text(target, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
    }
}

@Composable
private fun StageRow(stage: String, roughly: String, work: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(roughly, style = MaterialTheme.typography.labelSmall, color = Brand.tealBright)
        }
        Text(work, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
    }
}
