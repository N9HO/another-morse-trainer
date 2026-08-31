package app.anothermorsetrainer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-run onboarding: welcome the learner and ask how much Morse they already
 * know, seeding the Characters Koch ladder accordingly. Mirrors the comfort-level
 * pick from the iOS IntroView setup. Shown once (gated by [Settings.onboardingDone]);
 * the level can be changed later from Settings.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var selected by remember { mutableStateOf(Settings.proficiency) }

    CenteredContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            Text(
                "Welcome to\nAnother Morse Trainer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Brand.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Learn to copy Morse by ear with the Koch method — full-speed characters, " +
                    "one at a time, building up as you go.",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))
            Text(
                "How much Morse do you already know?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))

            Proficiency.entries.forEach { level ->
                LevelCard(
                    label = level.label,
                    selected = selected == level,
                    onClick = { selected = level }
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { Settings.completeOnboarding(selected); onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("Start practicing", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun LevelCard(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) Brand.teal else Brand.navyElevated,
                shape = RoundedCornerShape(Brand.cornerRadius)
            )
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = Brand.hairline,
                shape = RoundedCornerShape(Brand.cornerRadius)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            color = if (selected) Brand.navy else Brand.textPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
