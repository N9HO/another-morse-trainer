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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        PileupSettings.init(this)
        Stats.init(this)
        JourneyStore.init(this)
        EngineStore.init(this)
        VoiceProfileStore.init(this)
        setContent {
            AmtTheme {
                AppBackground {
                    AppRoot()
                }
            }
        }
    }

    // The background-noise floor (issue #29) is a foreground comfort: it exists
    // to keep a Bluetooth route awake while you practise, so it follows the app
    // on and off screen rather than hissing on in the background.
    override fun onStart() {
        super.onStart()
        BackgroundNoise.onForeground()
    }

    override fun onStop() {
        BackgroundNoise.onBackground()
        super.onStop()
    }
}

/** A selectable training mode, each backed by a ported [QuizSource]. */
data class QuizMode(
    val title: String,
    val subtitle: String,
    /** Scopes the mid-session Settings overlay to this mode's sections. */
    val settingsMode: SettingsMode,
    val make: () -> QuizSource
)

/** The menu of modes the home screen offers — every one drives the same QuizScreen. */
val QUIZ_MODES: List<QuizMode> = listOf(
    // The Characters track is restored from EngineStore so the Koch ladder
    // resumes where it left off (and saves back after every answer).
    QuizMode("Characters", "Koch-method ladder, A–Z 0–9", SettingsMode.CHARACTERS) {
        EngineStore.characters()
    },
    // The pool is the ranked Top-N ham words, or the learner's own list when
    // one is enabled in Settings.
    QuizMode("Common Words", "Hear the word, pick the word", SettingsMode.WORDS) {
        PhraseQuiz("Words", Settings.wordPoolItems(), Settings.phraseConfig())
    },
    QuizMode("Abbreviations", "CW shorthand → meaning", SettingsMode.ABBREVIATIONS) {
        PhraseQuiz("Abbreviations", MorseData.abbreviationItems, Settings.phraseConfig())
    },
    QuizMode("Q-Codes", "QRZ, QSY, QTH …", SettingsMode.QCODES) {
        PhraseQuiz("Q-Codes", MorseData.qCodeItems, Settings.phraseConfig())
    },
    QuizMode("Prosigns", "Run-together signals", SettingsMode.PROSIGNS) {
        PhraseQuiz("Prosigns", MorseData.prosignItems, Settings.phraseConfig())
    },
    // Targeted review of the character pairs you mix up. Shares the persisted
    // Characters engine, so it drills the confusions actually recorded in past
    // practice; with none recorded yet it falls back to your slowest active
    // characters paired with their nearest sound-alikes.
    QuizMode("Confusion Drill", "Drill your mix-ups", SettingsMode.CONFUSION) {
        ConfusionQuiz(EngineStore.characters().engine)
    }
)

/**
 * A mode the home menu can launch, described well enough for the pre-flight
 * [SessionSetupSheet] to ask about it before the run starts.
 *
 * [wantsStage] is set where a pinned track stage actually takes effect: the
 * modes that drill the persisted Koch ladder restored through [EngineStore] —
 * the Characters quiz and Sending Practice, which share one track exactly as
 * iOS's AppModel hands both the same charLadder.
 */
/** The modes drilling the shared, persisted Koch ladder, where a stage pin bites. */
private val STAGE_PIN_MODES = setOf(SettingsMode.CHARACTERS, SettingsMode.SENDING)

private data class SetupTarget(
    val route: Route,
    val title: String,
    val blurb: String,
    val settingsMode: SettingsMode,
    val wantsStage: Boolean = false
)

private sealed interface Route {
    data object Onboarding : Route
    data object Home : Route
    data object Journey : Route
    data class Quiz(val mode: QuizMode) : Route
    data object Pileup : Route
    data object Contest : Route
    data object Exam : Route
    data object Listen : Route
    data object HeadCopy : Route
    data object TypeIt : Route
    data object Qrq : Route
    data object RapidFire : Route
    data object Story : Route
    data object Sending : Route
    data object SendingDrills : Route
    data object Repeater : Route
    data object CwDecoder : Route
    data object Reference : Route
    data object Settings : Route
    data object Stats : Route
}

@Composable
private fun AppRoot() {
    var route by remember {
        mutableStateOf<Route>(if (Settings.onboardingDone) Route.Home else Route.Onboarding)
    }
    // The mode awaiting its pre-flight sheet. Home stays composed underneath, so
    // cancelling the sheet leaves the menu exactly as it was.
    var setup by remember { mutableStateOf<SetupTarget?>(null) }

    /** Ask first where there is something to ask; otherwise go straight in. */
    fun launch(target: SetupTarget) {
        if (sessionSetupHasOptions(target.settingsMode)) setup = target else route = target.route
    }

    fun quizTarget(mode: QuizMode) = SetupTarget(
        route = Route.Quiz(mode),
        title = mode.title,
        blurb = mode.subtitle,
        settingsMode = mode.settingsMode,
        wantsStage = mode.settingsMode in STAGE_PIN_MODES
    )

    when (val r = route) {
        Route.Onboarding -> OnboardingScreen(onDone = { route = Route.Home })
        Route.Journey -> JourneyScreen(onBack = { route = Route.Home })
        Route.Home -> HomeScreen(
            onPickJourney = { route = Route.Journey },
            onPickQuiz = { launch(quizTarget(it)) },
            onPickPileup = { route = Route.Pileup },
            onPickContest = { route = Route.Contest },
            onPickExam = { route = Route.Exam },
            onPickListen = {
                launch(SetupTarget(Route.Listen, "Listen", "Hands-free copy", SettingsMode.LISTEN))
            },
            onPickHeadCopy = {
                launch(SetupTarget(Route.HeadCopy, "Head Copy", "Copy in your head", SettingsMode.HEAD_COPY))
            },
            onPickTypeIt = {
                launch(SetupTarget(Route.TypeIt, "Type It", "Free-recall typing", SettingsMode.TYPE_IT))
            },
            onPickQrq = {
                launch(SetupTarget(Route.Qrq, "QRQ Speed", "High-speed copy", SettingsMode.QRQ))
            },
            onPickRapidFire = { route = Route.RapidFire },
            onPickStory = {
                launch(SetupTarget(Route.Story, "Story", "Read along in Morse", SettingsMode.STORY))
            },
            onPickSending = {
                launch(SetupTarget(
                    Route.Sending, "Sending Practice", "Key it back", SettingsMode.SENDING,
                    wantsStage = true
                ))
            },
            onPickSendingDrills = { route = Route.SendingDrills },
            onPickRepeater = { route = Route.Repeater },
            onPickCwDecoder = { route = Route.CwDecoder },
            onPickReference = { route = Route.Reference },
            onPickSettings = { route = Route.Settings },
            onPickStats = { route = Route.Stats }
        )
        is Route.Quiz -> QuizScreen(
            title = r.mode.title,
            onBack = { route = Route.Home },
            makeSource = r.mode.make,
            settingsMode = r.mode.settingsMode,
            // "Return home" from the recap lands on the menu with this mode's
            // setup sheet already open, so changing how the next run is shaped
            // is one tap rather than a hunt through Settings (iOS issue #67).
            // The plain Back arrow mid-session still just goes home.
            onFinish = {
                route = Route.Home
                setup = quizTarget(r.mode)
            }
        )
        Route.Pileup -> PileupScreen(onBack = { route = Route.Home })
        Route.Contest -> ContestScreen(onBack = { route = Route.Home })
        Route.Exam -> CodeExamScreen(onBack = { route = Route.Home })
        Route.Listen -> ListenScreen(onBack = { route = Route.Home })
        Route.HeadCopy -> HeadCopyScreen(onBack = { route = Route.Home })
        Route.TypeIt -> TypedQuizScreen(
            title = "Type It",
            onBack = { route = Route.Home },
            makeSource = { PhraseQuiz("Type It", MorseData.wordAndCallSignItems, summaryNoun = "words & calls") },
            settingsMode = SettingsMode.TYPE_IT
        )
        Route.Qrq -> QrqScreen(onBack = { route = Route.Home })
        Route.RapidFire -> RapidFireScreen(onBack = { route = Route.Home })
        Route.Story -> StoryScreen(onBack = { route = Route.Home })
        Route.Sending -> SendingPracticeScreen(onBack = { route = Route.Home })
        Route.SendingDrills -> SendingDrillScreen(onBack = { route = Route.Home })
        Route.Repeater -> RepeaterScreen(onBack = { route = Route.Home })
        Route.CwDecoder -> CwDecoderScreen(onBack = { route = Route.Home })
        Route.Reference -> ReferenceScreen(onBack = { route = Route.Home })
        Route.Settings -> SettingsScreen(onBack = { route = Route.Home })
        Route.Stats -> StatsScreen(onBack = { route = Route.Home })
    }

    setup?.let { target ->
        // Built here rather than inside the sheet so the pin is written through
        // EngineStore before the screen re-reads the track on start.
        val track = remember(target) {
            if (target.wantsStage) EngineStore.characters() else null
        }
        SessionSetupSheet(
            title = target.title,
            blurb = target.blurb,
            settingsMode = target.settingsMode,
            progressive = track,
            onStart = {
                setup = null
                route = target.route
            },
            onDismiss = { setup = null }
        )
    }
}

/** Subtitle for the pileup menu card. */
const val PILEUP_SUBTITLE = "Call CQ and work a CW pileup"
