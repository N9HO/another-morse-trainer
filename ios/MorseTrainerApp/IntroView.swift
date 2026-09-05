import SwiftUI

/// Home screen shown before practice begins (first-run onboarding, the
/// proficiency question, is `OnboardingView`). Leads with a grid of tappable
/// training-mode tiles, then reveals the options that matter for the chosen
/// mode and how long to practice.
struct IntroView: View {
    @EnvironmentObject var model: AppModel
    var onStart: () -> Void
    /// When set on arrival, present the selected mode's pre-flight sheet
    /// immediately — "Change setup" on the session recap lands the user in the
    /// setup they asked for, not on the menu grid (issue #67).
    @Binding var openSetup: Bool

    @State private var showingSetup = false
    @State private var showingSettings = false
    @State private var showingStats = false
    @State private var showingReference = false
    @State private var showingStartHere = false
    @State private var showingDailyDit = false
    @State private var showingSendingDrill = false
    @State private var showingCWDecoder = false
    @State private var showingRepeater = false
    @StateObject private var repeater = RepeaterModel()
    @Environment(\.scenePhase) private var scenePhase

    private let tileColumns = [GridItem(.flexible(), spacing: 14),
                               GridItem(.flexible(), spacing: 14)]

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(spacing: 28) {
                    header

                    dailyDitCard

                    startHereButton

                    modePicker

                    Spacer(minLength: 8)
                }
                .padding(24)
                .readableWidth()
                .animation(.easeInOut(duration: 0.22), value: model.learningMode)
            }
        }
        .sheet(isPresented: $showingSetup) {
            SessionSetupSheet(onStart: onStart)
                .environmentObject(model)
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView().environmentObject(model)
        }
        .sheet(isPresented: $showingStats) {
            StatsView().environmentObject(model)
        }
        .sheet(isPresented: $showingReference) {
            ReferenceView().environmentObject(model)
        }
        .sheet(isPresented: $showingStartHere) {
            StartHereView().environmentObject(model)
        }
        .sheet(isPresented: $showingDailyDit) {
            DailyDitView().environmentObject(model)
        }
        .sheet(isPresented: $showingSendingDrill) {
            SendingDrillView().environmentObject(model)
        }
        .sheet(isPresented: $showingCWDecoder) {
            CWDecoderView().environmentObject(model)
        }
        .fullScreenCover(isPresented: $showingRepeater) {
            RepeaterView().environmentObject(repeater)
        }
        .onAppear {
            model.refreshDailyDit()
            if openSetup {
                openSetup = false
                showingSetup = true
            }
        }
        // Not just onAppear: the app can sit open across midnight, and coming
        // back to it the next morning does not re-run onAppear for a view
        // already on screen. Without this the card would still be advertising
        // yesterday's puzzle until something else redrew it.
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.refreshDailyDit() }
        }
    }

    // MARK: - Top bar

    /// A slim bar with the app-wide Settings entry, so shared preferences (your
    /// callsign, side tone, …) are reachable before a session ever starts —
    /// not buried inside a mode's setup sheet.
    private var topBar: some View {
        HStack {
            Button {
                let myCall = model.settings.qso.myCall.trimmingCharacters(in: .whitespacesAndNewlines)
                if repeater.callsign.hasPrefix("anon"), !myCall.isEmpty {
                    repeater.setCallsign(myCall)
                }
                showingRepeater = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.subheadline.weight(.semibold))
                    Text("Vail")
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(Theme.teal)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .overlay(
                    Capsule().strokeBorder(Theme.teal.opacity(0.6), lineWidth: 1.5)
                )
            }
            .accessibilityLabel("Vail repeater — go on the air")
            Spacer()
            Button { showingCWDecoder = true } label: {
                Image(systemName: "waveform")
                    .font(.title3)
                    .foregroundStyle(Theme.teal)
                    .padding(8)
            }
            .accessibilityLabel("CW decoder — turn received Morse audio into text")
            Button { showingReference = true } label: {
                Image(systemName: "book")
                    .font(.title3)
                    .foregroundStyle(Theme.teal)
                    .padding(8)
            }
            .accessibilityLabel("Reference — prosigns, Q-codes, abbreviations, and ham lingo")
            Button { showingSendingDrill = true } label: {
                Image(systemName: "square.and.pencil")
                    .font(.title3)
                    .foregroundStyle(Theme.teal)
                    .padding(8)
            }
            .accessibilityLabel("Sending drills — printable practice sheets")
            Button { showingStats = true } label: {
                Image(systemName: "chart.bar")
                    .font(.title3)
                    .foregroundStyle(Theme.teal)
                    .padding(8)
            }
            .accessibilityLabel("Your stats")
            Button { showingSettings = true } label: {
                Image(systemName: "gearshape")
                    .font(.title3)
                    .foregroundStyle(Theme.teal)
                    .padding(8)
            }
            .accessibilityLabel("Settings")
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 12) {
            logoMark
            Text("Another Morse Trainer")
                .font(.largeTitle).bold()
                .multilineTextAlignment(.center)
            Text("A proud part of the Carrier Wave ecosystem.")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            streakBadge
        }
        .padding(.top, 8)
    }

    /// Daily practice streak, shown only once the learner has an active streak
    /// (issue #20). A gentle nudge to come back tomorrow without nagging an
    /// absent or first-time user.
    @ViewBuilder
    private var streakBadge: some View {
        let days = model.currentStreak
        if days > 0 {
            let milestone = AppModel.milestoneTier(forDay: days)
            HStack(spacing: 6) {
                Image(systemName: "flame.fill")
                    .foregroundStyle(milestone == nil ? Theme.tealBright : .orange)
                Text("\(days)-day streak")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                if let milestone {
                    Text(milestone.emoji)
                        .font(.subheadline)
                }
                if model.longestStreak > days {
                    Text("· best \(model.longestStreak)")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Theme.navyElevated, in: Capsule())
            .overlay(Capsule().strokeBorder(milestone == nil ? Theme.hairline : Color.orange.opacity(0.5), lineWidth: 1))
            .padding(.top, 4)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(streakAccessibilityLabel(days: days))
        }
    }

    private func streakAccessibilityLabel(days: Int) -> String {
        var label = "\(days) day practice streak."
        if let m = AppModel.milestoneTier(forDay: days) { label += " \(m.day)-day milestone reached." }
        if model.longestStreak > days { label += " Best ever \(model.longestStreak) days." }
        return label
    }

    /// The day's puzzle (#155). It sits at the top of Home, above even "Start
    /// here", because unlike everything else on this screen it expires: a daily
    /// challenge you have to go looking for is a daily challenge nobody plays.
    private var dailyDitCard: some View {
        let game = model.dailyDit
        let done = game.isFinished
        return Button { showingDailyDit = true } label: {
            HStack(spacing: 12) {
                Image(systemName: done ? "checkmark.seal.fill" : "calendar.badge.clock")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(done ? Theme.tealBright : Theme.teal)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Daily Dit")
                        .font(.subheadline.weight(.semibold))
                    Text(dailyDitSubtitle)
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Theme.tealBright.opacity(done ? 0.35 : 0.7), lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Daily Dit. \(dailyDitSubtitle)")
    }

    private var dailyDitSubtitle: String {
        let game = model.dailyDit
        switch game.outcome {
        case .solved:
            let at = game.solvedWpm.map { " at \(DailyDit.format(wpm: $0)) WPM" } ?? ""
            return "#\(game.puzzleNumber) copied in \(game.guessesUsed)\(at)"
        case .lost:
            return "#\(game.puzzleNumber) — out of guesses"
        case .playing where game.guessesUsed > 0:
            return "#\(game.puzzleNumber) · \(game.guessesUsed) of \(DailyDit.maxGuesses) guesses used"
        case .playing:
            return "Today's word in Morse — your speed, up to \(Int(DailyDit.startingSpeeds.max() ?? 75)) WPM"
        }
    }

    /// The newcomer's way in (#96): the site's guide explains how to begin and
    /// why the code is fast, but nothing on the tile grid said so. One tap,
    /// always visible — it is as useful in week three as on day one.
    private var startHereButton: some View {
        Button { showingStartHere = true } label: {
            HStack(spacing: 10) {
                Image(systemName: "book.pages")
                    .font(.body.weight(.semibold))
                VStack(alignment: .leading, spacing: 1) {
                    Text("New to Morse? Start here")
                        .font(.subheadline.weight(.semibold))
                    Text("How to begin, what to expect, and why it sounds so fast")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.teal.opacity(0.6), lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Start here — how to begin, what to expect, and why the code sounds fast")
    }

    // MARK: - Mode picker (tiles)

    private var modePicker: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("Choose your practice", systemImage: "square.grid.2x2")
            LazyVGrid(columns: tileColumns, spacing: 14) {
                ForEach(TrainingMode.allCases) { mode in
                    // Every tile is a real button: one tap opens that mode's
                    // pre-flight options with Start right there — no separate
                    // Continue press (issue #60).
                    ModeTile(mode: mode,
                             isSelected: model.learningMode == mode) {
                        Haptics.selection()
                        model.learningMode = mode
                        showingSetup = true
                    }
                }
            }
        }
    }

    // MARK: - Building blocks

    private func sectionTitle(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.title3).bold()
            .foregroundStyle(.primary)
    }

    /// Brand mark for the welcome screen: the real logo if it's been added to
    /// the asset catalog, otherwise a styled placeholder in the brand colors.
    @ViewBuilder
    private var logoMark: some View {
        if let ui = UIImage(named: "AMTLogo") {
            Image(uiImage: ui)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 220)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        } else {
            ZStack {
                Circle()
                    .fill(Theme.teal.opacity(0.12))
                    .frame(width: 132, height: 132)
                Circle()
                    .strokeBorder(Theme.teal, lineWidth: 6)
                    .frame(width: 120, height: 120)
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 52))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
        }
    }

}


/// The options that only matter for the currently-selected mode. They lived
/// inline on the intro screen back when a tile only *selected* a mode and a
/// pinned Continue button launched it; tiles now launch directly (issue #60),
/// so the choices ride along on the pre-flight sheet instead.
private struct ModeOptionsCard: View {
    @EnvironmentObject var model: AppModel
    @State private var showingCustomWords = false
    @State private var showingJourneyMap = false

    private var listenContentBinding: Binding<ListenContent> {
        Binding(
            get: { model.settings.listenContent },
            set: { model.settings.listenContent = $0 }
        )
    }

    private var listenGapBinding: Binding<AnswerGap> {
        Binding(
            get: { model.settings.listenGap },
            set: { model.settings.listenGap = $0 }
        )
    }

    private var wordTierBinding: Binding<WordTier> {
        Binding(
            get: { model.settings.wordTier },
            set: { model.settings.wordTier = $0 }
        )
    }

    private var qrqSpeedBinding: Binding<QrqSpeed> {
        Binding(
            get: { model.settings.qrqSpeed },
            set: { model.settings.qrqSpeed = $0 }
        )
    }

    private var voiceResponseBinding: Binding<Bool> {
        Binding(
            get: { model.settings.voiceResponse },
            set: {
                model.settings.voiceResponse = $0
                if $0 { model.settings.keyingResponse = false }  // mutually exclusive
            }
        )
    }

    private var keyingResponseBinding: Binding<Bool> {
        Binding(
            get: { model.settings.keyingResponse },
            set: { model.setKeyingResponse($0) }   // turns voice off: mutually exclusive
        )
    }

    private var useCustomWordsBinding: Binding<Bool> {
        Binding(
            get: { model.settings.useCustomWords },
            set: { model.settings.useCustomWords = $0 }
        )
    }

    private var examSpeedBinding: Binding<ExamSpeed> {
        Binding(
            get: { model.settings.examSpeed },
            set: { model.settings.examSpeed = $0 }
        )
    }

    private var examGradingBinding: Binding<ExamGrading> {
        Binding(
            get: { model.settings.examGrading },
            set: { model.settings.examGrading = $0 }
        )
    }

    private var examUseBundledBinding: Binding<Bool> {
        Binding(
            get: { model.settings.examUseBundled },
            set: { model.settings.examUseBundled = $0 }
        )
    }

    private var journeyDrainBinding: Binding<Bool> {
        Binding(
            get: { model.settings.journeyDrainOnMiss },
            set: { model.settings.journeyDrainOnMiss = $0 }
        )
    }

    // MARK: Short Stories bindings

    private var storyContentBinding: Binding<StoryContent> {
        Binding(get: { model.settings.story.content },
                set: { model.settings.story.content = $0 })
    }

    private var newsSourceBinding: Binding<NewsSource> {
        Binding(get: { model.settings.story.newsSource },
                set: { model.settings.story.newsSource = $0 })
    }

    private var newsFullStoryBinding: Binding<Bool> {
        Binding(get: { model.settings.story.newsFullStory },
                set: { model.settings.story.newsFullStory = $0 })
    }

    /// Selected long tale, falling back to the first bundled serial when the
    /// saved id is empty or no longer exists.
    private var serialBinding: Binding<String> {
        Binding(
            get: {
                let id = model.settings.story.serialId
                return MorseData.serials.contains { $0.id == id }
                    ? id : (MorseData.serials.first?.id ?? "")
            },
            set: { model.settings.story.serialId = $0 }
        )
    }

    // MARK: Rapid Fire bindings

    private var rapidFireContentBinding: Binding<RapidFireContent> {
        Binding(get: { model.settings.rapidFire.content },
                set: { model.settings.rapidFire.content = $0 })
    }

    private var rapidFireResponseBinding: Binding<RapidFireResponse> {
        Binding(get: { model.settings.rapidFire.response },
                set: { model.settings.rapidFire.response = $0 })
    }

    private var rapidFirePaceBinding: Binding<RapidFirePace> {
        Binding(get: { model.settings.rapidFire.pace },
                set: { model.settings.rapidFire.pace = $0 })
    }

    private var rapidFireUSOnlyBinding: Binding<Bool> {
        Binding(get: { model.settings.rapidFire.callsignUSOnly },
                set: { model.settings.rapidFire.callsignUSOnly = $0 })
    }

    private var rapidFireSectionsBinding: Binding<Bool> {
        Binding(get: { model.settings.rapidFire.statesIncludeSections },
                set: { model.settings.rapidFire.statesIncludeSections = $0 })
    }

    private var rapidFireSerialCutBinding: Binding<Bool> {
        Binding(get: { model.settings.rapidFire.serialCutNumbers },
                set: { model.settings.rapidFire.serialCutNumbers = $0 })
    }

    /// A toggle binding for one Rapid Fire call-sign format (keeps at least one on).
    private func rapidFireFormatBinding(_ format: CallsignFormat) -> Binding<Bool> {
        Binding(
            get: { model.settings.rapidFire.callsignFormats.contains(format) },
            set: { isOn in
                if isOn { model.settings.rapidFire.callsignFormats.insert(format) }
                else if model.settings.rapidFire.callsignFormats.count > 1 {
                    model.settings.rapidFire.callsignFormats.remove(format)
                }
            }
        )
    }

    /// Modes whose every knob already has a card on the sheet (duration,
    /// stage, contest…) render no extra options card.
    private var hasOptions: Bool {
        switch model.learningMode {
        case .listen, .exam, .story, .words, .qrq, .journey, .rapidFire:
            return true
        default:
            return model.learningMode.supportsVoiceAnswers
        }
    }

    var body: some View {
        if hasOptions {
            VStack(alignment: .leading, spacing: 16) {
            Group {
            if model.learningMode == .listen {
                inlinePicker(title: "What should it announce?",
                             selection: listenContentBinding) { (c: ListenContent) in c.label }
                inlinePicker(title: "Gap before the spoken answer",
                             selection: listenGapBinding) { (g: AnswerGap) in g.label }
            }

            if model.learningMode == .exam {
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Exam speed")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Picker("Exam speed", selection: examSpeedBinding) {
                            ForEach(ExamSpeed.allCases) { s in
                                Text(s.label).tag(s)
                            }
                        }
                        .pickerStyle(.menu)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("How to pass")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Picker("Grading", selection: examGradingBinding) {
                            ForEach(ExamGrading.allCases) { g in
                                Text(g.label).tag(g)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                    Toggle(isOn: examUseBundledBinding) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Use a built-in passage").font(.subheadline)
                            Text("Practice a ready-made exam text instead of a freshly generated one.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if model.learningMode == .story {
                inlinePicker(title: "What to copy",
                             selection: storyContentBinding) { (c: StoryContent) in c.label }
                if model.settings.story.content == .serials {
                    HStack {
                        Text("Which story")
                            .font(.subheadline)
                            .foregroundStyle(.primary)
                        Spacer(minLength: 12)
                        Picker("Which story", selection: serialBinding) {
                            ForEach(MorseData.serials) { serial in
                                Text(serial.title).tag(serial.id)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(Theme.tealBright)
                        .labelsHidden()
                    }
                    if let resume = model.serialResume(for: serialBinding.wrappedValue) {
                        Label {
                            Text(resume.part == 1
                                 ? "A longer tale sent in short parts. Your bookmark moves as you go, so you can pick the story back up any day."
                                 : "Your bookmark picks this story back up at part \(resume.part) of \(resume.of).")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        } icon: {
                            Image(systemName: "bookmark")
                                .foregroundStyle(Theme.teal)
                        }
                    }
                }
                if model.settings.story.content == .news {
                    inlinePicker(title: "News source",
                                 selection: newsSourceBinding) { (s: NewsSource) in s.label }
                    Toggle(isOn: newsFullStoryBinding) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Include the summary").font(.subheadline)
                            Text("Send each story summary after its headline, separated by a BT break. Turn off for quick headline-only copy.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Label {
                        Text("Fresh headlines are fetched over the internet and stay hidden until you reveal — decoding is the only way to read the news.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    } icon: {
                        Image(systemName: "newspaper")
                            .foregroundStyle(Theme.teal)
                    }
                }
            }

            if model.learningMode == .words {
                // The tier picker stays put even with a custom list: the list
                // is a switch, not a replacement, so it can be parked without
                // being deleted (Android parity).
                inlinePicker(title: "How big a word pool?",
                             selection: wordTierBinding) { (t: WordTier) in t.label }
                customWordsControl
            }

            if model.learningMode == .qrq {
                VStack(alignment: .leading, spacing: 8) {
                    Text("QRQ speed")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Picker("QRQ speed", selection: qrqSpeedBinding) {
                        ForEach(QrqSpeed.allCases) { s in
                            Text(s.label).tag(s)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if model.learningMode == .journey {
                VStack(alignment: .leading, spacing: 12) {
                    Button {
                        showingJourneyMap = true
                    } label: {
                        HStack {
                            Label("Level \(model.journeyLevelNumber): \(model.journeyLevelTitle)",
                                  systemImage: "map")
                                .font(.subheadline.weight(.medium))
                            Spacer()
                            Text("Choose level").font(.footnote)
                            Image(systemName: "chevron.right").font(.caption2)
                        }
                        .foregroundStyle(Theme.teal)
                    }
                    Toggle(isOn: journeyDrainBinding) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Misses drain the bar").font(.subheadline)
                            Text("A wrong answer pushes the progress bar back, so you have to stay sharp to clear a level. Turn off for a gentler, fill-only bar.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if model.learningMode == .rapidFire {
                rapidFireOptions
            }

            // Voice and keyed answers both apply to every choice quiz (all six
            // — Android parity). Keying is honoured per drill: only where the
            // text you heard is the answer, never for a meaning or a prosign
            // glyph, which fall back to the choices.
            if model.learningMode.supportsVoiceAnswers {
                Divider().overlay(Theme.hairline)
                Toggle(isOn: voiceResponseBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Answer with your voice", systemImage: "mic.fill")
                            .font(.subheadline).bold()
                        Text("Say your answer instead of tapping. Use phonetics for letters (“Bravo” for B); say words and meanings normally.")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(Theme.teal)
            }

            if model.learningMode.supportsKeyedAnswers {
                Toggle(isOn: keyingResponseBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Answer by keying", systemImage: "dot.radiowaves.left.and.right")
                            .font(.subheadline).bold()
                        Text("Send your answer on a Morse key — a hardware Vail/BLE MIDI key or the on-screen key — and it’s decoded back to letters. Drills whose answer is a meaning or a prosign show the choices instead. You can flip this mid-drill too.")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(Theme.teal)
            }
        }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .brandCard()
            .sheet(isPresented: $showingCustomWords) {
                CustomWordsSheet().environmentObject(model)
            }
            .sheet(isPresented: $showingJourneyMap) {
                JourneyMapView().environmentObject(model)
            }
        }
    }

    // MARK: - Rapid Fire options

    @ViewBuilder
    private var rapidFireOptions: some View {
        VStack(alignment: .leading, spacing: 14) {
            inlinePicker(title: "What to send",
                         selection: rapidFireContentBinding) { (c: RapidFireContent) in c.label }
            Text(model.settings.rapidFire.content.blurb)
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            // Per-content parameters.
            let content = model.settings.rapidFire.content
            if content == .states || content == .mixed {
                // The 71 ARRL/RAC sections (ContestData.arrlSections) join the
                // state pool: EPA, STX, SDG beside OH (#173).
                Toggle("Include ARRL/RAC Field Day sections", isOn: rapidFireSectionsBinding)
                    .font(.subheadline)
                    .tint(Theme.teal)
            }
            if content == .serials {
                // Sent as the pileup sends them (T for 0, N for 9 …); "TTA",
                // "001" and "1" all copy 001 (#173).
                Toggle("Cut numbers", isOn: rapidFireSerialCutBinding)
                    .font(.subheadline)
                    .tint(Theme.teal)
            }
            if content == .callsigns || content == .mixed {
                Toggle("US call signs only", isOn: rapidFireUSOnlyBinding)
                    .font(.subheadline)
                    .tint(Theme.teal)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Call-sign shapes")
                        .font(.subheadline).foregroundStyle(.secondary)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: 8)], spacing: 8) {
                        ForEach(CallsignFormat.allCases) { fmt in
                            rapidFireFormatChip(fmt)
                        }
                    }
                }
            }
            if content == .words {
                Stepper(value: Binding(
                    get: { model.settings.rapidFire.wordMinLength },
                    set: {
                        model.settings.rapidFire.wordMinLength = $0
                        if model.settings.rapidFire.wordMaxLength < $0 {
                            model.settings.rapidFire.wordMaxLength = $0
                        }
                    }),
                    in: RapidFireSettings.wordLengthRange) {
                    rapidFireStepperLabel("Min length", "\(model.settings.rapidFire.wordMinLength)")
                }
                Stepper(value: Binding(
                    get: { model.settings.rapidFire.wordMaxLength },
                    set: {
                        model.settings.rapidFire.wordMaxLength = $0
                        if model.settings.rapidFire.wordMinLength > $0 {
                            model.settings.rapidFire.wordMinLength = $0
                        }
                    }),
                    in: RapidFireSettings.wordLengthRange) {
                    rapidFireStepperLabel("Max length", "\(model.settings.rapidFire.wordMaxLength)")
                }
            }
            if content == .numbers {
                Stepper(value: Binding(
                    get: { model.settings.rapidFire.numberCount },
                    set: { model.settings.rapidFire.numberCount = $0 }),
                    in: RapidFireSettings.numberCountRange) {
                    rapidFireStepperLabel("Digits per group", "\(model.settings.rapidFire.numberCount)")
                }
            }

            Divider().overlay(Theme.hairline)

            inlinePicker(title: "How to answer",
                         selection: rapidFireResponseBinding) { (r: RapidFireResponse) in r.label }
            Text(model.settings.rapidFire.response.blurb)
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            inlinePicker(title: "Pace between items",
                         selection: rapidFirePaceBinding) { (p: RapidFirePace) in p.label }

            Text("Speed, Farnsworth, and side tone are in Settings.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func rapidFireFormatChip(_ format: CallsignFormat) -> some View {
        let on = model.settings.rapidFire.callsignFormats.contains(format)
        return Button {
            Haptics.selection()
            rapidFireFormatBinding(format).wrappedValue.toggle()
        } label: {
            Text(format.label)
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity, minHeight: 36)
                .background(on ? Theme.teal : Theme.navyElevated,
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .foregroundStyle(on ? Theme.navy : Theme.textSecondary)
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(on ? Theme.tealBright : Theme.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(format.label) call signs")
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
    }

    private func rapidFireStepperLabel(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).font(.subheadline)
            Spacer()
            Text(value).foregroundStyle(.secondary).monospacedDigit()
        }
    }

    // MARK: - Custom words (issue #32)

    /// An explicit "Use my word list" switch with the editor behind it, as on
    /// Android: the list only takes over from the ranked pool while the switch
    /// is on and it holds at least two words (one cannot offer a distractor).
    @ViewBuilder
    private var customWordsControl: some View {
        let count = model.settings.customWords.count
        Toggle(isOn: useCustomWordsBinding) {
            VStack(alignment: .leading, spacing: 2) {
                Label("Use my word list", systemImage: "list.bullet.rectangle")
                    .font(.subheadline).bold()
                Text(customWordsFootnote)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Theme.teal)
        if model.settings.useCustomWords {
            Button {
                showingCustomWords = true
            } label: {
                HStack {
                    Label(count == 0 ? "Add your words" : "Edit my words (\(count))",
                          systemImage: "square.and.pencil")
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
        }
    }

    /// Mirrors the Android footer: what the switch does while off, and once
    /// on, whether the list is big enough to be in use yet.
    private var customWordsFootnote: String {
        guard model.settings.useCustomWords else {
            return "Practice your own words — a callsign, your name, club abbreviations — in Words instead of the ranked pool."
        }
        let n = model.settings.customWords.count
        let lead = "One word per line (spaces and commas split too). "
        return lead + (n >= AppSettings.customWordsMinimum
            ? "\(n) words ready — Words drills your list."
            : "Add at least two words; until then the ranked pool is used.")
    }

    /// A row with a label and a trailing menu picker, sized for the option card.
    private func inlinePicker<T: Hashable & Identifiable & CaseIterable>(
        title: String,
        selection: Binding<T>,
        label: @escaping (T) -> String
    ) -> some View where T.AllCases: RandomAccessCollection {
        HStack {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.primary)
            Spacer(minLength: 12)
            Picker(title, selection: selection) {
                ForEach(Array(T.allCases)) { value in
                    Text(label(value)).tag(value)
                }
            }
            .pickerStyle(.menu)
            .tint(Theme.tealBright)
            .labelsHidden()
        }
    }
}

/// One selectable training-mode tile: icon, name, and a short tagline. The
/// selected tile fills with the brand teal and shows a check.
private struct ModeTile: View {
    let mode: TrainingMode
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            // The selected tile fills with the brand teal; its text and icon
            // are the deep navy, not white — white on the teal fails WCAG
            // contrast (issue #59).
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(isSelected ? Theme.navy.opacity(0.15) : Theme.navyRaised)
                        .frame(width: 46, height: 46)
                    Image(systemName: mode.icon)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(isSelected ? Theme.navy : Theme.teal)
                }

                Text(mode.title)
                    .font(.subheadline).bold()
                    .foregroundStyle(isSelected ? Theme.navy : .primary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)

                Text(mode.tagline)
                    .font(.caption2)
                    .foregroundStyle(isSelected ? Theme.navy.opacity(0.8) : Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity)
            .frame(minHeight: 132)
            .padding(.vertical, 14)
            .padding(.horizontal, 8)
            .background(
                RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .fill(isSelected ? Theme.teal : Theme.navyElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .strokeBorder(isSelected ? Theme.tealBright : Theme.hairline,
                                  lineWidth: isSelected ? 2 : 1)
            )
            .overlay(alignment: .topTrailing) {
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.navy)
                        .padding(8)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .shadow(color: isSelected ? Theme.teal.opacity(0.35) : .clear,
                    radius: 10, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(mode.title). \(mode.tagline)")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
}

/// Pre-session options for the chosen mode, shown when Start is tapped. Only the
/// knobs that actually change this mode's drill appear — so the fixed-format Code
/// Exam never asks "how long?".
///
/// The starting level is deliberately NOT asked here (#151). It is one
/// app-wide answer, given at first run (`OnboardingView`) and changeable under
/// Settings → Proficiency; re-asking it on every Characters, Confusion Drill
/// and Sending Practice launch read as three different questions, and tapping
/// the level you already had silently restarted the ladder. Those modes still
/// open the sheet sensibly without it: Characters and Sending Practice carry
/// the track-stage pin, and every one of them the duration picker.
private struct SessionSetupSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var onStart: () -> Void

    private var durationBinding: Binding<PracticeDuration> {
        Binding(
            get: { model.settings.practiceDuration },
            set: { model.settings.practiceDuration = $0 }
        )
    }

    /// The Characters track's learner-chosen stage hold (nil = automatic).
    private var stagePin: Binding<ProgressiveCharacters.Stage?> {
        Binding(
            get: { model.characterStagePin },
            set: { model.setCharacterStagePin($0) }
        )
    }

    /// The authentic speed band for the selected contest, shown under the picker.
    private var contestSpeedNote: String {
        let c = model.settings.contest.type
        return "Stations run \(Int(c.minWPM))–\(Int(c.maxWPM)) WPM. More pileup realism (signals, callsign shapes) in Settings."
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                ScrollView {
                    VStack(spacing: 20) {
                        Text(model.learningMode.blurb)
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)

                        ModeOptionsCard()

                        // The Characters track grows singles → pairs → triples →
                        // words & call signs on its own. Surface that here and let
                        // the learner hold it at a stage, so "Characters" serving
                        // words is never a mystery with no way back (issue #51).
                        // Sending Practice drills the same track, so it gets the
                        // same control — keying whole words is a big step up.
                        if model.learningMode == .characters || model.learningMode == .sending {
                            card(title: "Track stage", systemImage: "square.stack.3d.up") {
                                Picker("Track stage", selection: stagePin) {
                                    Text("Auto — grow as you improve")
                                        .tag(nil as ProgressiveCharacters.Stage?)
                                    ForEach(ProgressiveCharacters.Stage.allCases, id: \.self) { stage in
                                        Text(stage.displayName).tag(Optional(stage))
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(Theme.tealBright)
                                Text(model.characterStageNote)
                                    .font(.footnote)
                                    .foregroundStyle(Theme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }

                        if model.learningMode == .qso {
                            card(title: "QSO type", systemImage: "antenna.radiowaves.left.and.right") {
                                Picker("QSO type", selection: Binding(
                                    get: { model.settings.qso.mode },
                                    set: { model.settings.qso.mode = $0 }
                                )) {
                                    ForEach(QSOContestMode.allCases) { m in
                                        Text(m.label).tag(m)
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(Theme.tealBright)
                                Text(model.settings.qso.mode.blurb)
                                    .font(.footnote)
                                    .foregroundStyle(Theme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                Text("More pileup options in Settings.")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }

                        if model.learningMode == .contest {
                            card(title: "Which contest?", systemImage: "trophy") {
                                Picker("Contest", selection: Binding(
                                    get: { model.settings.contest.type },
                                    set: { model.settings.contest.type = $0 }
                                )) {
                                    ForEach(ContestType.allCases) { c in
                                        Text(c.shortName).tag(c)
                                    }
                                }
                                .pickerStyle(.segmented)
                                Text(model.settings.contest.type.blurb)
                                    .font(.footnote)
                                    .foregroundStyle(Theme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                Text(contestSpeedNote)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            card(title: "How long do you want to run?", systemImage: "timer") {
                                Picker("Length", selection: Binding(
                                    get: { model.settings.contest.length },
                                    set: { model.settings.contest.length = $0 }
                                )) {
                                    ForEach(ContestLength.allCases) { l in
                                        Text(l.label).tag(l)
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(Theme.tealBright)
                            }
                        }

                        if model.learningMode.usesSessionLength {
                            card(title: "How long do you want to practice?", systemImage: "timer") {
                                Picker("Duration", selection: durationBinding) {
                                    ForEach(PracticeDuration.allCases) { d in
                                        Text(d.label).tag(d)
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(Theme.tealBright)
                            }
                        }
                    }
                    .padding(24)
                    .readableWidth()
                }
            }
            .navigationTitle(model.learningMode.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Haptics.tap()
                    model.startSession()
                    onStart()
                    dismiss()
                } label: {
                    Text("Start Training")
                        .font(.headline)
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 54)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .readableWidth()
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .background(.ultraThinMaterial)
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// A labelled container holding one control, in the brand card style.
    @ViewBuilder
    private func card<Content: View>(title: String,
                                     systemImage: String,
                                     @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: systemImage)
                .font(.subheadline).bold()
                .foregroundStyle(Theme.textSecondary)
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }
}

/// Paste-in editor for a custom Words list (issue #32). Accepts words separated
/// by new lines, commas, or spaces; saving replaces the saved list. Whether the
/// list is *used* is the "Use my word list" switch on the setup sheet, and it
/// needs at least `AppSettings.customWordsMinimum` words to take effect.
private struct CustomWordsSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    private var parsedCount: Int { MorseData.parseWordList(text).count }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                VStack(alignment: .leading, spacing: 14) {
                    Text("Paste your own words — one per line, or separated by commas or spaces. With “Use my word list” on, Words mode draws only from this list.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    TextEditor(text: $text)
                        .font(.system(.body, design: .monospaced))
                        .scrollContentBackground(.hidden)
                        .padding(10)
                        .frame(maxWidth: .infinity, minHeight: 220)
                        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.characters)

                    Text("\(parsedCount) word\(parsedCount == 1 ? "" : "s")"
                         + (parsedCount < AppSettings.customWordsMinimum
                            ? " — add at least two to use the list" : ""))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)

                    Spacer()
                }
                .padding(20)
                .readableWidth()
            }
            .navigationTitle("Custom Word List")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button("Clear", role: .destructive) {
                        model.settings.customWords = []
                        dismiss()
                    }
                    .disabled(model.settings.customWords.isEmpty && text.isEmpty)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.settings.customWords = MorseData.parseWordList(text)
                        dismiss()
                    }
                    .disabled(parsedCount == 0)
                }
            }
            .onAppear { text = model.settings.customWords.joined(separator: "\n") }
        }
        .presentationDetents([.large])
    }
}

#Preview {
    ZStack {
        Theme.Background()
        IntroView(onStart: {}, openSetup: .constant(false)).environmentObject(AppModel())
    }
    .preferredColorScheme(.dark)
}
