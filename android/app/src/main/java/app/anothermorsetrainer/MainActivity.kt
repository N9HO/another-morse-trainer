package app.anothermorsetrainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.anothermorsetrainer.morsekit.ConfusionQuiz
import app.anothermorsetrainer.morsekit.Cw77Style
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.PhraseQuiz
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.QuizSource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        AudioFocus.init(this)
        AdapterKeyer.init(this)
        PileupSettings.init(this)
        Stats.init(this)
        DailyDitStore.init(this)
        FirstFourStore.init(this)
        OperatingProcedureStore.init(this)
        JourneyStore.init(this)
        EngineStore.init(this)
        VoiceProfileStore.init(this)
        AnswerEntryStore.init(this)
        LeaderboardClient.init(this)
        // Account sync: optional, off until the user signs in (Settings ›
        // Account & Sync). After the stores above, which its hooks read.
        SyncCoordinator.init(this)
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
        // The app can sit open across midnight, and a resumed activity re-runs
        // no composition-scoped effect, so coming back the next morning would
        // otherwise still show yesterday's Daily Dit.
        DailyDitStore.refresh()
        // The buddy's day, likewise: the home line and the reminder read a
        // cache, and coming back to the app is when it is worth refreshing
        // (at most every 15 minutes, and only for an install that is paired
        // or has an invite out).
        BuddyClient.refreshIfStale()
        // Account sync pushes what is queued and pulls what other devices
        // did, on launch and on a return (at most every five minutes).
        // Signed out, it does nothing.
        SyncCoordinator.onForeground()
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
 * The CW 77 mode's Quiz style (the #240 follow-up): Common Words' drill over
 * the CW 77 list, with your callsign and name when included. Not in
 * [QUIZ_MODES]: its one home tile opens a setup sheet that picks between this
 * and the Listen style, so it is reached through [Route.Cw77].
 */
val CW77_QUIZ: QuizMode = QuizMode("CW 77", "Hear it, pick it", SettingsMode.CW77) {
    PhraseQuiz(
        MorseData.CW77_NAME,
        MorseData.cw77Pool(Cw77Style.QUIZ, Settings.cw77Personal()),
        Settings.phraseConfig(),
        summaryNoun = "items"
    )
}

/**
 * A mode the home menu can launch, described well enough for the pre-flight
 * [SessionSetupSheet] to ask about it before the run starts.
 *
 * [wantsStage] is set where a pinned track stage actually takes effect: the
 * modes that drill the persisted Koch ladder restored through [EngineStore] —
 * the Characters quiz and Sending Practice, which share one track exactly as
 * iOS's AppModel hands both the same charLadder.
 */
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
    /** The CW 77 mode: the Listen screen or the quiz, per [Settings.cw77Style]. */
    data object Cw77 : Route
    data object HeadCopy : Route
    data object TypeIt : Route
    data object Qrq : Route
    data object RapidFire : Route
    /** The Games sub-menu (#207): the six arcade games behind one home tile. */
    data object Games : Route
    data object Invaders : Route
    data object Galaga : Route
    data object Defender : Route
    data object Dungeon : Route
    data object Frogger : Route
    data object Asteroids : Route
    data object Story : Route
    data object Sending : Route
    data object SendingDrills : Route
    /** The Sending Analyzer (#241, #234, #235). */
    data object SendingAnalyzer : Route
    data object Repeater : Route
    data object CwDecoder : Route
    data object Reference : Route
    data object StartHere : Route
    data object DailyDit : Route
    /** First Four (#265): the guided path to a first POTA contact. */
    data object FirstFour : Route
    /** CW Operating Procedure (#294, #295): lessons, the zero-beat drill, "What should you do?". */
    data object OperatingProcedure : Route
    data object Settings : Route
    data object Stats : Route
    /** The shared leaderboard, opened from Stats (docs/high-scores-design.md, step 2). */
    data object Leaderboard : Route
    /** The same board opened from the Games sub-menu (#226): Back returns there, and it opens on a game. */
    data object GamesLeaderboard : Route
}

/**
 * Whether band noise sounds on this screen (#331): a mode, game or lesson that
 * plays Morse. Home, the menus, Settings, Stats and the tools that do not play
 * practice Morse (Reference, the printable drills, the mic-driven Analyzer and
 * Decoder, the Repeater) stay quiet — the same set as the Apple apps, where the
 * noise follows the player's audio session. Exhaustive on purpose: a new route
 * has to be placed on one side or the other.
 */
private val Route.playsBandNoise: Boolean
    get() = when (this) {
        Route.Journey, is Route.Quiz, Route.Pileup, Route.Contest, Route.Exam,
        Route.Listen, Route.Cw77, Route.HeadCopy, Route.TypeIt, Route.Qrq,
        Route.RapidFire, Route.Invaders, Route.Galaga, Route.Defender,
        Route.Dungeon, Route.Frogger, Route.Asteroids, Route.Story, Route.Sending,
        Route.DailyDit, Route.FirstFour, Route.OperatingProcedure -> true
        Route.Onboarding, Route.Home, Route.Games, Route.SendingDrills,
        Route.SendingAnalyzer, Route.Repeater, Route.CwDecoder, Route.Reference,
        Route.StartHere, Route.Settings, Route.Stats, Route.Leaderboard,
        Route.GamesLeaderboard -> false
    }

/**
 * Nav state as a string, so it can go in the saved-instance-state bundle.
 *
 * `android:configChanges` (see the manifest) keeps rotation from recreating the
 * activity at all, which is what makes practice survive being turned sideways.
 * It does nothing for *process death*, though: background the app long enough
 * for Android to reclaim it and the whole nav stack is gone, so you come back to
 * Home from wherever you were. That is what these savers are for.
 *
 * An unrecognised tag restores as `null`, which `rememberSaveable` reads as
 * "could not restore" and falls back to the initial value — Home. That is the
 * behaviour we want if a route is ever renamed or removed between the save and
 * the restore, rather than a crash on a stale bundle.
 */
private fun routeTag(route: Route): String = when (route) {
    Route.Onboarding -> "onboarding"
    Route.Home -> "home"
    Route.Journey -> "journey"
    is Route.Quiz -> "quiz:${route.mode.title}"
    Route.Pileup -> "pileup"
    Route.Contest -> "contest"
    Route.Exam -> "exam"
    Route.Listen -> "listen"
    Route.Cw77 -> "cw77"
    Route.HeadCopy -> "headCopy"
    Route.TypeIt -> "typeIt"
    Route.Qrq -> "qrq"
    Route.RapidFire -> "rapidFire"
    Route.Games -> "games"
    Route.Invaders -> "invaders"
    Route.Galaga -> "galaga"
    Route.Defender -> "defender"
    Route.Dungeon -> "dungeon"
    Route.Frogger -> "frogger"
    Route.Asteroids -> "asteroids"
    Route.Story -> "story"
    Route.Sending -> "sending"
    Route.SendingDrills -> "sendingDrills"
    Route.SendingAnalyzer -> "sendingAnalyzer"
    Route.Repeater -> "repeater"
    Route.CwDecoder -> "cwDecoder"
    Route.Reference -> "reference"
    Route.StartHere -> "startHere"
    Route.DailyDit -> "dailyDit"
    Route.FirstFour -> "firstFour"
    Route.OperatingProcedure -> "operatingProcedure"
    Route.Settings -> "settings"
    Route.Stats -> "stats"
    Route.Leaderboard -> "leaderboard"
    Route.GamesLeaderboard -> "gamesLeaderboard"
}

private fun routeFrom(tag: String): Route? = when (tag) {
    "onboarding" -> Route.Onboarding
    "home" -> Route.Home
    "journey" -> Route.Journey
    "pileup" -> Route.Pileup
    "contest" -> Route.Contest
    "exam" -> Route.Exam
    "listen" -> Route.Listen
    "cw77" -> Route.Cw77
    "headCopy" -> Route.HeadCopy
    "typeIt" -> Route.TypeIt
    "qrq" -> Route.Qrq
    "rapidFire" -> Route.RapidFire
    "games" -> Route.Games
    "invaders" -> Route.Invaders
    "galaga" -> Route.Galaga
    "defender" -> Route.Defender
    "dungeon" -> Route.Dungeon
    "frogger" -> Route.Frogger
    "asteroids" -> Route.Asteroids
    "story" -> Route.Story
    "sending" -> Route.Sending
    "sendingDrills" -> Route.SendingDrills
    "sendingAnalyzer" -> Route.SendingAnalyzer
    "repeater" -> Route.Repeater
    "cwDecoder" -> Route.CwDecoder
    "reference" -> Route.Reference
    "startHere" -> Route.StartHere
    "dailyDit" -> Route.DailyDit
    "firstFour" -> Route.FirstFour
    "operatingProcedure" -> Route.OperatingProcedure
    "settings" -> Route.Settings
    "stats" -> Route.Stats
    "leaderboard" -> Route.Leaderboard
    "gamesLeaderboard" -> Route.GamesLeaderboard
    // Keyed by title rather than list index: a mode reordered in QUIZ_MODES
    // between save and restore would otherwise silently resume the wrong quiz.
    else -> tag.removePrefix("quiz:").takeIf { it != tag }
        ?.let { title -> QUIZ_MODES.firstOrNull { it.title == title } }
        ?.let(Route::Quiz)
}

private val RouteSaver: Saver<Route, String> = Saver(
    save = { routeTag(it) },
    restore = { routeFrom(it) }
)

/**
 * The pending pre-flight sheet. Saved alongside the route so a process death
 * while the sheet is open comes back to the sheet, not to a bare menu.
 *
 * An absent sheet saves as an empty list; restoring that yields null, which is
 * also the initial value, so the two paths agree.
 */
private val SetupSaver: Saver<SetupTarget?, Any> = listSaver(
    save = { target ->
        target?.let {
            listOf<Any>(routeTag(it.route), it.title, it.blurb, it.settingsMode.name, it.wantsStage)
        } ?: emptyList()
    },
    restore = { items ->
        val route = items.getOrNull(0)?.let { routeFrom(it as String) }
        val mode = items.getOrNull(3)
            ?.let { name -> runCatching { SettingsMode.valueOf(name as String) }.getOrNull() }
        if (route == null || mode == null) null
        else SetupTarget(route, items[1] as String, items[2] as String, mode, items[4] as Boolean)
    }
)

@Composable
private fun AppRoot() {
    val resources = LocalResources.current
    var route by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf<Route>(if (Settings.onboardingDone) Route.Home else Route.Onboarding)
    }
    // The mode awaiting its pre-flight sheet. Home stays composed underneath, so
    // cancelling the sheet leaves the menu exactly as it was.
    var setup by rememberSaveable(stateSaver = SetupSaver) { mutableStateOf<SetupTarget?>(null) }

    // Band noise follows the screen (#331): on while a practice screen is
    // open, off on the menus. A restored route re-runs this, so it is right
    // after process death too.
    val practising = route.playsBandNoise
    LaunchedEffect(practising) { BackgroundNoise.setPractising(practising) }

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

    // The pre-flight targets, shared by the home tiles and the mid-session
    // mode switcher so both open the same sheet with the same words.
    fun journeyTarget() = SetupTarget(Route.Journey, resources.getString(R.string.mode_journey), resources.getString(R.string.home_leveled_path), SettingsMode.JOURNEY)
    fun cw77Target() = SetupTarget(Route.Cw77, resources.getString(R.string.mode_cw77), resources.getString(R.string.cw77_blurb), SettingsMode.CW77)
    fun listenTarget() = SetupTarget(Route.Listen, resources.getString(R.string.setup_listen), resources.getString(R.string.setup_hands_free_copy), SettingsMode.LISTEN)
    fun headCopyTarget() = SetupTarget(Route.HeadCopy, resources.getString(R.string.mode_head_copy), resources.getString(R.string.common_copy_in_your_head), SettingsMode.HEAD_COPY)
    fun typeItTarget() = SetupTarget(Route.TypeIt, resources.getString(R.string.mode_type_it), resources.getString(R.string.common_free_recall_typing), SettingsMode.TYPE_IT)
    fun qrqTarget() = SetupTarget(Route.Qrq, resources.getString(R.string.mode_qrq_speed), resources.getString(R.string.common_high_speed_copy), SettingsMode.QRQ)
    fun storyTarget() = SetupTarget(Route.Story, resources.getString(R.string.setup_story), resources.getString(R.string.setup_read_along_in_morse), SettingsMode.STORY)
    fun sendingTarget() = SetupTarget(
        Route.Sending, resources.getString(R.string.mode_sending_practice), resources.getString(R.string.common_key_it_back), SettingsMode.SENDING,
        wantsStage = true
    )

    /**
     * The mid-session mode switcher (iOS ContentView `modeMenu`, issue #42).
     * The screen has already closed its run out by the time this is called;
     * the picked mode then opens the way its home tile does — its pre-flight
     * sheet over Home, or its own setup screen — so the next session begins
     * only on an explicit start, never straight into a drill.
     */
    fun switchTo(mode: TrainingMode) {
        route = Route.Home
        when (mode) {
            TrainingMode.JOURNEY -> launch(journeyTarget())
            TrainingMode.CHARACTERS, TrainingMode.WORDS, TrainingMode.ABBREVIATIONS,
            TrainingMode.QCODES, TrainingMode.PROSIGNS, TrainingMode.CONFUSION ->
                mode.quizMode?.let { launch(quizTarget(it)) }
            TrainingMode.HEAD_COPY -> launch(headCopyTarget())
            TrainingMode.TYPE_IT -> launch(typeItTarget())
            TrainingMode.SENDING -> launch(sendingTarget())
            TrainingMode.LISTEN -> launch(listenTarget())
            TrainingMode.CW77 -> launch(cw77Target())
            TrainingMode.PILEUP -> route = Route.Pileup
            TrainingMode.CONTEST -> route = Route.Contest
            TrainingMode.STORY -> launch(storyTarget())
            TrainingMode.EXAM -> route = Route.Exam
            TrainingMode.QRQ -> launch(qrqTarget())
            TrainingMode.RAPID_FIRE -> route = Route.RapidFire
            TrainingMode.INVADERS -> route = Route.Invaders
            TrainingMode.GALAGA -> route = Route.Galaga
            TrainingMode.DEFENDER -> route = Route.Defender
            TrainingMode.DUNGEON -> route = Route.Dungeon
            TrainingMode.FROGGER -> route = Route.Frogger
            TrainingMode.ASTEROIDS -> route = Route.Asteroids
        }
    }

    when (val r = route) {
        // Onboarding's second button (#265) goes straight on to First Four.
        Route.Onboarding -> OnboardingScreen(onDone = { firstFour ->
            route = if (firstFour) Route.FirstFour else Route.Home
        })
        Route.Journey -> JourneyScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.Home -> HomeScreen(
            onPickStartHere = { route = Route.StartHere },
            onPickDailyDit = { route = Route.DailyDit },
            onPickFirstFour = { route = Route.FirstFour },
            // Journey asks about its scoring first (the iOS setup card's
            // "Misses drain the bar" toggle), so it goes through the sheet.
            onPickJourney = { launch(journeyTarget()) },
            onPickQuiz = { launch(quizTarget(it)) },
            onPickPileup = { route = Route.Pileup },
            onPickContest = { route = Route.Contest },
            onPickExam = { route = Route.Exam },
            onPickListen = { launch(listenTarget()) },
            onPickCw77 = { launch(cw77Target()) },
            onPickHeadCopy = { launch(headCopyTarget()) },
            onPickTypeIt = { launch(typeItTarget()) },
            onPickQrq = { launch(qrqTarget()) },
            onPickRapidFire = { route = Route.RapidFire },
            onPickGames = { route = Route.Games },
            onPickOperating = { route = Route.OperatingProcedure },
            onPickStory = { launch(storyTarget()) },
            onPickSending = { launch(sendingTarget()) },
            onPickSendingDrills = { route = Route.SendingDrills },
            onPickSendingAnalyzer = { route = Route.SendingAnalyzer },
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
            },
            onSwitchMode = { switchTo(it) }
        )
        Route.Pileup -> PileupScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.Contest -> ContestScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.Exam -> CodeExamScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.Listen -> ListenScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        // One route for both styles; the setup sheet has just chosen which.
        Route.Cw77 -> if (Settings.cw77Style == Cw77Style.LISTEN) {
            ListenScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) }, cw77 = true)
        } else {
            QuizScreen(
                title = CW77_QUIZ.title,
                onBack = { route = Route.Home },
                makeSource = CW77_QUIZ.make,
                settingsMode = CW77_QUIZ.settingsMode,
                onFinish = {
                    route = Route.Home
                    setup = cw77Target()
                },
                onSwitchMode = { switchTo(it) }
            )
        }
        Route.HeadCopy -> HeadCopyScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.TypeIt -> TypedQuizScreen(
            title = stringResource(R.string.mode_type_it),
            onBack = { route = Route.Home },
            makeSource = { PhraseQuiz("Type It", MorseData.wordAndCallSignItems, summaryNoun = "words & calls") },
            settingsMode = SettingsMode.TYPE_IT,
            onSwitchMode = { switchTo(it) }
        )
        Route.Qrq -> QrqScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.RapidFire -> RapidFireScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.Games -> GamesScreen(
            onBack = { route = Route.Home },
            onPickInvaders = { route = Route.Invaders },
            onPickGalaga = { route = Route.Galaga },
            onPickDefender = { route = Route.Defender },
            onPickDungeon = { route = Route.Dungeon },
            onPickFrogger = { route = Route.Frogger },
            onPickAsteroids = { route = Route.Asteroids },
            onOpenLeaderboard = { route = Route.GamesLeaderboard }
        )
        // Back from a game lands on the Games sub-menu it was picked from
        // (#207), the way Back from a Reference entry lands on the list.
        Route.Invaders -> InvadersScreen(onBack = { route = Route.Games }, onSwitchMode = { switchTo(it) })
        Route.Galaga -> GalagaScreen(onBack = { route = Route.Games }, onSwitchMode = { switchTo(it) })
        Route.Defender -> DefenderScreen(onBack = { route = Route.Games }, onSwitchMode = { switchTo(it) })
        Route.Dungeon -> DungeonScreen(onBack = { route = Route.Games }, onSwitchMode = { switchTo(it) })
        Route.Frogger -> FroggerScreen(onBack = { route = Route.Games }, onSwitchMode = { switchTo(it) })
        Route.Asteroids -> AsteroidsScreen(onBack = { route = Route.Games }, onSwitchMode = { switchTo(it) })
        Route.Story -> StoryScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.Sending -> SendingPracticeScreen(onBack = { route = Route.Home }, onSwitchMode = { switchTo(it) })
        Route.SendingDrills -> SendingDrillScreen(onBack = { route = Route.Home })
        Route.SendingAnalyzer -> SendingAnalyzerScreen(onBack = { route = Route.Home })
        Route.Repeater -> RepeaterScreen(onBack = { route = Route.Home })
        Route.CwDecoder -> CwDecoderScreen(onBack = { route = Route.Home })
        Route.Reference -> ReferenceScreen(onBack = { route = Route.Home })
        Route.StartHere -> StartHereScreen(onBack = { route = Route.Home })
        Route.DailyDit -> DailyDitScreen(onBack = { route = Route.Home })
        Route.FirstFour -> FirstFourScreen(onBack = { route = Route.Home })
        Route.OperatingProcedure -> OperatingProcedureScreen(
            onBack = { route = Route.Home },
            onOpenFirstFour = { route = Route.FirstFour }
        )
        Route.Settings -> SettingsScreen(
            onBack = { route = Route.Home },
            // Developer Preview Stage: the track has been jumped and saved;
            // start drilling it (iOS previewStage switches to Characters).
            onPreviewStage = {
                QUIZ_MODES.firstOrNull { it.settingsMode == SettingsMode.CHARACTERS }
                    ?.let { route = Route.Quiz(it) } ?: run { route = Route.Home }
            }
        )
        Route.Stats -> StatsScreen(onBack = { route = Route.Home }, onOpenLeaderboard = { route = Route.Leaderboard })
        // Back from the board lands where it was opened from: Stats, or the
        // Games sub-menu, which opens it on the first game's board (#226).
        Route.Leaderboard -> LeaderboardScreen(onBack = { route = Route.Stats })
        Route.GamesLeaderboard -> LeaderboardScreen(onBack = { route = Route.Games }, initialMode = "invaders")
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
