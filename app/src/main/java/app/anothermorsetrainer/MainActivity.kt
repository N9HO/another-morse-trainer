package app.anothermorsetrainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.anothermorsetrainer.morsekit.ConfusionQuiz
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.PhraseQuiz
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.QuizSource
import app.anothermorsetrainer.morsekit.TrainerEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        Stats.init(this)
        JourneyStore.init(this)
        setContent {
            AmtTheme {
                AppBackground {
                    AppRoot()
                }
            }
        }
    }
}

/** A selectable training mode, each backed by a ported [QuizSource]. */
data class QuizMode(val title: String, val subtitle: String, val make: () -> QuizSource)

/** The menu of modes the home screen offers — every one drives the same QuizScreen. */
val QUIZ_MODES: List<QuizMode> = listOf(
    QuizMode("Characters", "Koch-method ladder, A–Z 0–9") {
        val engine = TrainerEngine(Settings.engineConfig())
        Settings.applyProficiency(engine)
        ProgressiveCharacters(engine)
    },
    QuizMode("Common Words", "Hear the word, pick the word") {
        PhraseQuiz("Words", MorseData.topWordItems(Settings.wordCount), Settings.phraseConfig())
    },
    QuizMode("Abbreviations", "CW shorthand → meaning") {
        PhraseQuiz("Abbreviations", MorseData.abbreviationItems, Settings.phraseConfig())
    },
    QuizMode("Q-Codes", "QRZ, QSY, QTH …") {
        PhraseQuiz("Q-Codes", MorseData.qCodeItems, Settings.phraseConfig())
    },
    QuizMode("Prosigns", "Run-together signals") {
        PhraseQuiz("Prosigns", MorseData.prosignItems, Settings.phraseConfig())
    },
    // Targeted review of the character pairs you mix up. A standalone engine
    // starts with no recorded confusions, so ConfusionQuiz falls back to your
    // slowest active characters paired with their nearest sound-alikes.
    QuizMode("Confusion Drill", "Drill your mix-ups") {
        val engine = TrainerEngine(Settings.engineConfig())
        Settings.applyProficiency(engine)
        ConfusionQuiz(engine)
    }
)

private sealed interface Route {
    data object Onboarding : Route
    data object Home : Route
    data object Journey : Route
    data class Quiz(val mode: QuizMode) : Route
    data object Pileup : Route
    data object Exam : Route
    data object Listen : Route
    data object HeadCopy : Route
    data object TypeIt : Route
    data object Qrq : Route
    data object RapidFire : Route
    data object Story : Route
    data object Sending : Route
    data object Repeater : Route
    data object Reference : Route
    data object Settings : Route
    data object Stats : Route
}

@Composable
private fun AppRoot() {
    var route by remember {
        mutableStateOf<Route>(if (Settings.onboardingDone) Route.Home else Route.Onboarding)
    }
    when (val r = route) {
        Route.Onboarding -> OnboardingScreen(onDone = { route = Route.Home })
        Route.Journey -> JourneyScreen(onBack = { route = Route.Home })
        Route.Home -> HomeScreen(
            onPickJourney = { route = Route.Journey },
            onPickQuiz = { route = Route.Quiz(it) },
            onPickPileup = { route = Route.Pileup },
            onPickExam = { route = Route.Exam },
            onPickListen = { route = Route.Listen },
            onPickHeadCopy = { route = Route.HeadCopy },
            onPickTypeIt = { route = Route.TypeIt },
            onPickQrq = { route = Route.Qrq },
            onPickRapidFire = { route = Route.RapidFire },
            onPickStory = { route = Route.Story },
            onPickSending = { route = Route.Sending },
            onPickRepeater = { route = Route.Repeater },
            onPickReference = { route = Route.Reference },
            onPickSettings = { route = Route.Settings },
            onPickStats = { route = Route.Stats }
        )
        is Route.Quiz -> QuizScreen(
            title = r.mode.title,
            onBack = { route = Route.Home },
            makeSource = r.mode.make
        )
        Route.Pileup -> PileupScreen(onBack = { route = Route.Home })
        Route.Exam -> CodeExamScreen(onBack = { route = Route.Home })
        Route.Listen -> ListenScreen(onBack = { route = Route.Home })
        Route.HeadCopy -> HeadCopyScreen(onBack = { route = Route.Home })
        Route.TypeIt -> TypedQuizScreen(
            title = "Type It",
            onBack = { route = Route.Home },
            makeSource = { PhraseQuiz("Type It", MorseData.wordAndCallSignItems) }
        )
        Route.Qrq -> QrqScreen(onBack = { route = Route.Home })
        Route.RapidFire -> RapidFireScreen(onBack = { route = Route.Home })
        Route.Story -> StoryScreen(onBack = { route = Route.Home })
        Route.Sending -> SendingPracticeScreen(onBack = { route = Route.Home })
        Route.Repeater -> RepeaterScreen(onBack = { route = Route.Home })
        Route.Reference -> ReferenceScreen(onBack = { route = Route.Home })
        Route.Settings -> SettingsScreen(onBack = { route = Route.Home })
        Route.Stats -> StatsScreen(onBack = { route = Route.Home })
    }
}

/** Subtitle for the pileup menu card. */
const val PILEUP_SUBTITLE = "Call CQ and work a CW pileup"
