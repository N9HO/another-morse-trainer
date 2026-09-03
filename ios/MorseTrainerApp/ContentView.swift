import SwiftUI
import GameController

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    var onExit: () -> Void = {}
    /// Tracks whether a physical (Bluetooth/USB/Smart) keyboard is attached,
    /// to surface the 1–9 answer hints on choice buttons (issue #69).
    @StateObject private var hardwareKeyboard = HardwareKeyboardObserver()
    @State private var showSettings = false
    @State private var showStats = false
    @State private var showBrag = false
    @State private var showJourneyMap = false
    @State private var detailRecord: SessionRecord?
    @State private var typedAnswer = ""
    @State private var examCopy = ""
    @State private var qsoText = ""
    /// Learner aid: reveal who's calling / the expected copy (Android parity).
    @State private var qsoHintShown = false
    @FocusState private var typedFocused: Bool
    @FocusState private var examCopyFocused: Bool
    @FocusState private var qsoFocused: Bool

    private let columns = [GridItem(.flexible(), spacing: 16),
                           GridItem(.flexible(), spacing: 16)]

    var body: some View {
        NavigationStack {
            Group {
            if model.sessionEnded {
                sessionSummaryView
            } else {
            VStack(spacing: 24) {
                sessionBar

                if model.isListen {
                    listenView
                } else if model.isStory {
                    storyView
                } else if model.isExam {
                    examView
                } else if model.isQSO || model.isContest {
                    qsoView
                } else if model.isRapidFireReview {
                    rapidFireReviewView
                } else if let intro = model.introduction {
                    introductionView(intro)
                } else {
                    if model.isJourney { journeyBanner }

                    statusArea
                        .frame(maxHeight: .infinity)

                    // Sending Practice always offers Replay — you can't key back
                    // what you didn't catch (Android parity); elsewhere it's the
                    // opt-in feedback setting. A miss offers it regardless:
                    // re-hearing what was actually sent while the answer shows
                    // is the point of the correction (issue #77), and the wrong-
                    // answer pause already waits for Next.
                    if model.settings.allowReplay || model.isSending
                        || (model.phase == .answered && model.lastCorrect == false),
                       model.drill != nil, !model.isHeadCopy {
                        Button {
                            model.replay()
                        } label: {
                            Label("Replay", systemImage: "speaker.wave.2.fill")
                                .font(.headline)
                        }
                        .buttonStyle(.bordered)
                    }

                    if model.isHeadCopy {
                        headCopyControls
                    } else if model.usesTypedEntry {
                        typedEntry
                        bottomBar
                    } else if model.usesVoiceResponse {
                        voiceResponseView
                        bottomBar
                    } else if model.usesKeyingResponse {
                        SendingKeyerView(wpm: model.settings.wpm,
                                         toneHz: model.settings.toneFrequency)
                        bottomBar
                    } else {
                        choiceGrid
                        bottomBar
                    }
                }
            }
            .padding()
            .readableWidth()
            }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { modeMenu }
                // NOTE: the session-scope readout ("11 chars", "155 words &
                // calls") used to sit here as a topBarLeading chip, then in the
                // session bar — both were reported as clutter (issues #61,
                // #74), so it's gone entirely.
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showBrag = true } label: {
                        Image(systemName: "rosette")
                    }
                    .accessibilityLabel("Brag sheet")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showStats = true } label: {
                        Image(systemName: "chart.bar")
                    }
                    .accessibilityLabel("Your stats")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showSettings) {
                // Scoped to the running mode so mid-session settings only show
                // what applies to it (issue #66); the intro's Settings entry
                // stays the full app-wide surface.
                SettingsView(activeMode: model.mode).environmentObject(model)
            }
            .sheet(isPresented: $showStats) {
                StatsView().environmentObject(model)
            }
            .sheet(isPresented: $showBrag) {
                BragSheetView().environmentObject(model)
            }
            .sheet(isPresented: $showJourneyMap) {
                JourneyMapView().environmentObject(model)
            }
            .sheet(item: $detailRecord) { record in
                NavigationStack {
                    SessionDetailView(
                        record: record,
                        idealMS: Int((model.settings.ttrThreshold * 1000).rounded())
                    )
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { detailRecord = nil }
                        }
                    }
                }
            }
            .onChange(of: model.lastCorrect) { correct in
                guard let correct else { return }
                correct ? Haptics.success() : Haptics.error()
            }
            .onAppear {
                if model.drill == nil && !model.sessionEnded
                    && !model.isListening && !model.storyActive && !model.isExam
                    && !model.qsoActive {
                    model.startSession()
                }
            }
        }
    }

    private var modeMenu: some View {
        Menu {
            // A Picker (not plain buttons) so the chosen mode is checkmarked.
            // Changing modes ends the current session and shows its summary;
            // the next session begins only on an explicit start — so on the
            // summary screen the label and checkmark moving to the picked mode
            // are the visible effect (issue #42).
            Picker("Mode", selection: Binding(
                get: { model.learningMode },
                set: { model.setMode($0) }
            )) {
                ForEach(TrainingMode.allCases) { m in
                    Label(m.title, systemImage: m.icon).tag(m)
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(model.learningMode.title).font(.headline)
                Image(systemName: "chevron.down").font(.caption2)
            }
        }
    }

    // MARK: - Status / feedback

    @ViewBuilder
    private var statusArea: some View {
        VStack(spacing: 12) {
            switch model.phase {
            case .idle:
                Text("Tap a button to begin")
                    .foregroundStyle(.secondary)
            case .playing:
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 60))
                    .foregroundStyle(Theme.teal)
                Text("Listen…").font(.title3).foregroundStyle(.secondary)
            case .awaiting:
                Image(systemName: model.isHeadCopy ? "brain.head.profile" : "ear")
                    .font(.system(size: 60))
                    .foregroundStyle(Theme.teal)
                Text(awaitingPrompt)
                    .font(.title3).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            case .revealed:
                revealView
            case .answered:
                feedbackView
            }
        }
        .multilineTextAlignment(.center)
        .animation(.spring(response: 0.35, dampingFraction: 0.62), value: model.phase)
    }

    /// During the answer phase, prefer a drill-specific question (the QSO
    /// simulator sets one) over the generic per-mode prompt.
    private var awaitingPrompt: String {
        if let q = model.drill?.question, !q.isEmpty { return q }
        return model.mode.prompt
    }

    @ViewBuilder
    private var feedbackView: some View {
        VStack(spacing: 10) {
            if model.settings.showCorrectness, let correct = model.lastCorrect {
                Image(systemName: correct ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(correct ? .green : .red)
                    .transition(.scale(scale: 0.4).combined(with: .opacity))
                Text(correct ? "Correct" : "Not quite")
                    .font(.title3).bold()
                    .foregroundStyle(correct ? .green : .red)
            }
            if model.shouldReveal, let drill = model.drill {
                VStack(spacing: 2) {
                    Text(drill.revealPrimary)
                        .font(Theme.copyFont(size: 40, weight: .bold, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                    if !drill.revealSecondary.isEmpty {
                        Text(drill.revealSecondary)
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if let ttr = model.lastTTR {
                Text(String(format: "%.2f s", ttr))
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let unlocked = model.justUnlocked {
                // Journey surfaces a full sentence ("Level 3 complete!"); other
                // modes unlock a single item, so prefix it with "New:".
                Label(model.isJourney ? unlocked : "New: \(unlocked)", systemImage: "star.fill")
                    .font(.callout).bold()
                    .foregroundStyle(.orange)
                    .padding(.top, 4)
            }
        }
    }

    // MARK: - New-item introduction (#162)

    /// The first sight of a character or prosign: on its own, large, with its
    /// pattern in symbols and in "dah-di-dah", its sound on arrival and on
    /// Replay, and the way into the drill that is waiting behind it. Until
    /// now a new character's first appearance was as a question, so the only
    /// way to learn its sound was to guess wrong at it.
    private func introductionView(_ intro: CharacterIntroduction) -> some View {
        VStack(spacing: 18) {
            Spacer(minLength: 0)
            Label(intro.isProsign ? "New prosign" : "New character", systemImage: "star.fill")
                .font(.callout).bold()
                .foregroundStyle(.orange)
            Text(intro.display)
                .font(Theme.copyFont(size: 96, weight: .bold, monospaced: true,
                                     slashedZero: model.settings.slashedZero))
            VStack(spacing: 6) {
                Text(intro.symbolPattern)
                    .font(.title2.monospaced())
                    .foregroundStyle(.secondary)
                Text(intro.spokenPattern)
                    .font(.title3)
                if let meaning = intro.meaning {
                    Text(meaning)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            Text("Listen as many times as you like. When the sound feels familiar, start the drill — it will be one of the answers from now on.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button {
                model.playIntroduction()
            } label: {
                Label("Replay", systemImage: "speaker.wave.2.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.bordered)
            // The drill's own keys (issue #69): R repeats, Return goes on.
            .keyboardShortcut(KeyEquivalent("r"), modifiers: [])
            Button {
                model.finishIntroduction()
            } label: {
                Text("Start drilling")
                    .font(.headline)
                    .foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .keyboardShortcut(.defaultAction)
        }
        .onAppear { model.playIntroduction() }
        // Two new items back to back (K, then M, for a brand-new learner)
        // reuse this view, so the second one has to announce itself too.
        .onChange(of: intro.id) { _ in model.playIntroduction() }
    }

    // MARK: - Head copy

    @ViewBuilder
    private var revealView: some View {
        VStack(spacing: 8) {
            Text("You heard:").font(.subheadline).foregroundStyle(.secondary)
            Text(model.drill?.revealPrimary ?? "")
                .font(Theme.copyFont(size: 44, weight: .bold, monospaced: true,
                                     slashedZero: model.settings.slashedZero))
            if let ttr = model.lastTTR {
                Text(String(format: "recalled in %.1f s", ttr))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var headCopyControls: some View {
        switch model.phase {
        case .awaiting:
            VStack(spacing: 12) {
                if let n = model.headCopyCountdown {
                    Label("Revealing in \(n)…", systemImage: "timer")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .transition(.opacity)
                }
                HStack(spacing: 16) {
                    // Hardware keys drive the whole loop (issue #69):
                    // R repeats, Return reveals, then Return / X self-grade.
                    Button {
                        Haptics.tap()
                        model.headCopyRepeatNow()
                    } label: {
                        Label("Repeat", systemImage: "arrow.clockwise")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 56)
                    }
                    .buttonStyle(.bordered)
                    .keyboardShortcut(KeyEquivalent("r"), modifiers: [])
                    Button {
                        Haptics.tap()
                        model.revealHeadCopy()
                    } label: {
                        Text("Reveal")
                            .font(.headline)
                            .foregroundStyle(Theme.navy)
                            .frame(maxWidth: .infinity, minHeight: 56)
                    }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: model.headCopyCountdown)
        case .revealed:
            HStack(spacing: 16) {
                Button {
                    Haptics.error()
                    model.gradeHeadCopy(false)
                } label: {
                    Label("Missed it", systemImage: "xmark")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent).tint(.red)
                .keyboardShortcut(KeyEquivalent("x"), modifiers: [])
                Button {
                    Haptics.success()
                    model.gradeHeadCopy(true)
                } label: {
                    Label("Got it", systemImage: "checkmark")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent).tint(.green)
                .keyboardShortcut(.defaultAction)
            }
        default:
            Color.clear.frame(height: 56)
        }
    }

    // MARK: - Short Stories (continuous copy)

    private var storyView: some View {
        VStack(spacing: 20) {
            VStack(spacing: 4) {
                Text(model.storyTitle)
                    .font(.title3).bold()
                    .multilineTextAlignment(.center)
                Text(model.storySubtitle)
                    .font(.caption).foregroundStyle(.secondary)
            }

            // Copy area: hidden until revealed.
            ScrollView {
                if model.storyRevealed {
                    Text(model.storyText)
                        .font(Theme.copyFont(style: .title3, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                        .lineSpacing(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .transition(.opacity)
                } else if model.isNewsStory && model.newsFetching {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("Fetching todays headlines…")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 160)
                } else if model.isNewsStory, let error = model.newsError {
                    VStack(spacing: 12) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 44))
                            .foregroundStyle(.secondary)
                        Text(error)
                            .font(.callout).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        Button {
                            model.refreshNews()
                        } label: {
                            Label("Try Again", systemImage: "arrow.clockwise")
                                .font(.headline)
                        }
                        .buttonStyle(.bordered)
                    }
                    .frame(maxWidth: .infinity, minHeight: 160)
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: model.storyPlaying
                              ? "dot.radiowaves.left.and.right"
                              : (model.isNewsStory ? "newspaper" : "book.closed"))
                            .font(.system(size: 56))
                            .foregroundStyle(Theme.teal)
                        Text(model.storyPlaying
                             ? "Sending… copy along"
                             : (model.isNewsStory
                                ? "Press Play, then decode the headline"
                                : "Press Play, then copy what you hear"))
                            .font(.callout).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, minHeight: 160)
                }
            }
            .frame(maxHeight: .infinity)
            .padding()
            .brandCard()

            storyControls
        }
        .animation(.easeInOut(duration: 0.2), value: model.storyRevealed)
        .animation(.easeInOut(duration: 0.2), value: model.storyPlaying)
        .animation(.easeInOut(duration: 0.2), value: model.newsFetching)
    }

    @ViewBuilder
    private var storyControls: some View {
        HStack(spacing: 12) {
            if model.storyCanGoBack {
                Button {
                    model.previousStory()
                } label: {
                    Image(systemName: "backward.fill")
                        .font(.headline)
                        .frame(width: 52, height: 52)
                }
                .buttonStyle(.bordered)
                .disabled(model.storyPlaying)
                .accessibilityLabel("Previous part")
            }

            if model.storyPlaying {
                Button {
                    model.stopStory()
                } label: {
                    Label("Stop", systemImage: "stop.fill")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.bordered)
            } else {
                Button {
                    model.playStory()
                } label: {
                    Label(model.storyRevealed ? "Replay" : "Play",
                          systemImage: "play.fill")
                        .font(.headline).foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.storyText.isEmpty || (model.isNewsStory && model.newsFetching))
            }

            if !model.storyRevealed {
                Button {
                    model.revealStory()
                } label: {
                    Label("Reveal", systemImage: "eye")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.bordered)
                .disabled(model.storyPlaying || model.storyText.isEmpty)
            }

            Button {
                model.nextStory()
            } label: {
                Label("Next", systemImage: "forward.fill")
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)
            .disabled(model.storyText.isEmpty || (model.isNewsStory && model.newsFetching))
        }
    }

    // MARK: - Code Exam (ARRL/FCC-style proficiency exam)

    @ViewBuilder
    private var examView: some View {
        switch model.examStage {
        case .ready:    examReadyView
        case .playing:  examPlayingView
        case .copy:     examCopyView
        case .question: examQuestionView
        case .results:  examResultsView
        }
    }

    private var examHeader: some View {
        VStack(spacing: 4) {
            Text("Code Proficiency Exam")
                .font(.title3).bold()
                .multilineTextAlignment(.center)
            Text("\(model.examSpeed.label) · \(model.examGrading.label)")
                .font(.caption).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var examReadyView: some View {
        VStack(spacing: 24) {
            Spacer()
            examHeader
            Image(systemName: "checkmark.seal")
                .font(.system(size: 64))
                .foregroundStyle(Theme.teal)
            Text(model.examGrading == .solidCopy
                 ? "Listen to the whole transmission and copy it. To pass, get \(model.examRequiredRun) characters in a row correct."
                 : "Listen to the whole transmission, then answer questions about what was sent.")
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Spacer()
            Button {
                model.playExam()
            } label: {
                Label("Start Sending", systemImage: "play.fill")
                    .font(.headline).foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
        }
        .frame(maxHeight: .infinity)
    }

    private var examPlayingView: some View {
        VStack(spacing: 24) {
            Spacer()
            examHeader
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 72))
                .foregroundStyle(.blue)
            Text("Sending… copy along")
                .font(.title3).foregroundStyle(.secondary)
            Spacer()
            Button {
                model.stopExam()
            } label: {
                Label("Stop", systemImage: "stop.fill")
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)
        }
        .frame(maxHeight: .infinity)
    }

    private var examCopyView: some View {
        VStack(spacing: 16) {
            examHeader
            Text("Type everything you copied:")
                .font(.subheadline).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            TextEditor(text: $examCopy)
                .font(.system(.body, design: .monospaced))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .focused($examCopyFocused)
                .frame(minHeight: 160)
                .padding(8)
                .background(Color(.secondarySystemBackground),
                            in: RoundedRectangle(cornerRadius: 12))
                .morseKeyboardRow(text: $examCopy) { examCopyFocused = false }
            Button {
                model.submitExamCopy(examCopy)
            } label: {
                Text("Grade my copy")
                    .font(.headline).foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
            .disabled(examCopy.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Spacer(minLength: 0)
        }
        .onAppear { examCopyFocused = true }
    }

    private var examQuestionView: some View {
        VStack(spacing: 18) {
            HStack {
                Text("Question \(model.examQuestionNumber) of \(model.examQuestionCount)")
                    .font(.subheadline).foregroundStyle(.secondary)
                Spacer()
            }
            Text(model.examQuestion?.prompt ?? "")
                .font(.title3).bold()
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)

            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(model.examQuestion?.options ?? [], id: \.self) { option in
                    Button {
                        model.answerExamQuestion(option)
                    } label: {
                        Text(option)
                            .font(optionFont(option))
                            .foregroundStyle(Theme.prominentLabel(on: examTint(for: option)))
                            .multilineTextAlignment(.center)
                            .minimumScaleFactor(0.6)
                            .frame(maxWidth: .infinity, minHeight: 72)
                            .padding(.horizontal, 4)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(examTint(for: option))
                    .disabled(model.examAnswerCorrect != nil)
                }
            }

            if let correct = model.examAnswerCorrect {
                Text(correct ? "Correct" : "Not quite")
                    .font(.headline)
                    .foregroundStyle(correct ? .green : .red)
            }

            Spacer(minLength: 0)

            if model.examAnswerCorrect != nil {
                Button {
                    model.nextExamQuestion()
                } label: {
                    Text("Next")
                        .font(.headline).foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
            } else {
                Color.clear.frame(height: 50)
            }
        }
    }

    private func examTint(for option: String) -> Color {
        guard model.examAnswerCorrect != nil else { return .blue }
        if option == model.examQuestion?.answer { return .green }
        if option == model.examSelected, model.examAnswerCorrect == false { return .red }
        return .gray
    }

    private var examResultsView: some View {
        ScrollView {
            VStack(spacing: 20) {
                examHeader
                Image(systemName: model.examPassed ? "checkmark.seal.fill" : "xmark.seal.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(model.examPassed ? .green : .red)
                Text(model.examPassed ? "Passed" : "Not yet")
                    .font(.largeTitle).bold()
                    .foregroundStyle(model.examPassed ? .green : .red)

                if model.examGrading == .solidCopy, let r = model.examCopyResult {
                    Text("Longest solid run: \(r.longestRun) / \(r.required) characters")
                        .font(.headline)
                        .multilineTextAlignment(.center)
                } else {
                    Text("Score: \(model.examScoreText)")
                        .font(.headline)
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("What was sent:")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Text(model.examPassageText)
                        .font(Theme.copyFont(style: .body, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                        .lineSpacing(5)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding()
                .background(Color(.secondarySystemBackground),
                            in: RoundedRectangle(cornerRadius: 12))

                Button {
                    examCopy = ""
                    model.newExam()
                } label: {
                    Label("New exam", systemImage: "arrow.clockwise")
                        .font(.headline).foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    // MARK: - Listen & Learn (hands-free)

    private var listenView: some View {
        VStack(spacing: 28) {
            Spacer()

            Image(systemName: model.phase == .playing
                  ? "dot.radiowaves.left.and.right" : "headphones")
                .font(.system(size: 72))
                .foregroundStyle(Theme.teal)

            if model.listenPaused {
                Text("Paused").font(.title3).foregroundStyle(.secondary)
            } else if model.phase == .playing {
                Text("Listen…").font(.title3).foregroundStyle(.secondary)
            } else if !model.listenDisplay.isEmpty {
                Text(model.listenDisplay)
                    .font(Theme.copyFont(size: 44, weight: .bold, monospaced: true,
                                         slashedZero: model.settings.slashedZero))
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.5)
                    .transition(.opacity)
            } else {
                Text("Getting ready…").font(.title3).foregroundStyle(.secondary)
            }

            Spacer()

            Button {
                model.toggleListening()
            } label: {
                Label(model.listenPaused ? "Resume" : "Pause",
                      systemImage: model.listenPaused ? "play.fill" : "pause.fill")
                    .font(.headline)
                    .foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)

            Text("Plays with the screen locked — pocket your phone and keep listening. Control it from the lock screen too.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxHeight: .infinity)
        .animation(.easeInOut(duration: 0.2), value: model.listenDisplay)
        .animation(.easeInOut(duration: 0.2), value: model.phase)
    }

    // MARK: - Rapid Fire (review / just listen)

    private var rapidFireReviewView: some View {
        VStack(spacing: 28) {
            Spacer()

            Image(systemName: model.phase == .playing
                  ? "dot.radiowaves.left.and.right" : "bolt.fill")
                .font(.system(size: 72))
                .foregroundStyle(Theme.teal)

            if model.phase == .playing {
                Text("Listen…").font(.title3).foregroundStyle(.secondary)
            } else if let sent = model.drill?.revealPrimary, !sent.isEmpty {
                VStack(spacing: 6) {
                    Text("Sent")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Text(sent)
                        .font(Theme.copyFont(size: 46, weight: .bold, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.5)
                        .transition(.opacity)
                }
            } else {
                Text("Getting ready…").font(.title3).foregroundStyle(.secondary)
            }

            Text("\(model.rapidFireTranscript.count) sent")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)

            Spacer()

            Text("Copy along on paper or in your head. Tap End to see the full list of what was transmitted.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxHeight: .infinity)
        .animation(.easeInOut(duration: 0.2), value: model.drill)
        .animation(.easeInOut(duration: 0.2), value: model.phase)
    }

    // MARK: - Typed free-recall

    // MARK: - QSO Simulator (pileup)

    @ViewBuilder
    private var qsoView: some View {
        VStack(spacing: 16) {
            qsoStatusCard
            if let missed = model.qsoLastMissed { missedCallerBanner(missed) }
            qsoLogList
                .frame(maxHeight: .infinity)
            qsoInputBar
        }
        .onChange(of: model.qsoReadyToLog) { ready in
            if ready { Haptics.success() }
        }
    }

    /// A caller just walked off. Says which call you lost and what you had it
    /// as, so the miss is a lesson rather than a station that quietly vanished.
    private func missedCallerBanner(_ missed: PileupEngine.MissedCaller) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "person.fill.xmark")
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(missed.call) gave up").font(.subheadline).bold()
                Text(missedCallerDetail(missed))
                    .font(.caption).foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Button {
                model.dismissMissedCaller()
            } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.textSecondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss")
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
        .transition(.opacity)
        .animation(.easeOut(duration: 0.2), value: missed.id)
    }

    /// "you had them as N9HS" when you got close, otherwise just the count —
    /// there is nothing to compare against if you never got near the call.
    private func missedCallerDetail(_ missed: PileupEngine.MissedCaller) -> String {
        if let had = missed.miscopiedAs {
            return "You had them as \(had) — they came back \(missed.attempts) "
                 + (missed.attempts == 1 ? "time" : "times") + " before moving on."
        }
        return "They came back \(missed.attempts) "
             + (missed.attempts == 1 ? "time" : "times") + " and you never got the call."
    }

    private var qsoStatusCard: some View {
        VStack(spacing: 10) {
            Text(model.isContest ? model.contestType.name : model.qsoMode.label)
                .font(.subheadline).foregroundStyle(Theme.textSecondary)
            Text(qsoStatusLine)
                .font(.title2).bold()
                .foregroundStyle(model.qsoReadyToLog ? .green : Theme.tealBright)
                .multilineTextAlignment(.center)
                .animation(.easeInOut(duration: 0.2), value: qsoStatusLine)
            if model.isContest {
                HStack(spacing: 22) {
                    qsoStat("Score", "\(model.contestScore)")
                    // QSOs only when the score isn't simply the QSO count (a
                    // multiplier applies, or each QSO is worth more than a point).
                    if model.contestShowsQSOCount {
                        qsoStat("QSOs", "\(model.qsoCount)")
                    }
                    if let mult = model.contestType.multiplierLabel {
                        qsoStat(mult, "\(model.contestMultipliers)")
                    }
                    qsoStat("Rate", "\(Int(model.qsoRate))/hr")
                    // Drop accuracy when a multiplier column already fills the row.
                    if !model.contestType.usesMultipliers {
                        qsoStat("Acc", "\(Int((model.qsoAccuracy * 100).rounded()))%")
                    }
                }
            } else {
                HStack(spacing: 28) {
                    qsoStat("Logged", "\(model.qsoCount)")
                    qsoStat("Rate", "\(Int(model.qsoRate))/hr")
                    qsoStat("Acc", "\(Int((model.qsoAccuracy * 100).rounded()))%")
                }
            }
            if model.qsoBusy {
                Label("Receiving…", systemImage: "dot.radiowaves.left.and.right")
                    .font(.caption).foregroundStyle(Theme.teal)
            }
            if qsoHintShown, let hint = qsoHintLine {
                Text(hint)
                    .font(Theme.copyFont(style: .caption1, monospaced: true,
                                         slashedZero: model.settings.slashedZero))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
            }
            if qsoHintLine != nil || qsoHintShown {
                Button(qsoHintShown ? "Hide hint" : "Show hint") {
                    qsoHintShown.toggle()
                }
                .font(.caption)
                .tint(Theme.teal)
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .brandCard()
    }

    /// What the hint reveals for the current phase: the calls in the pileup, or
    /// the exact exchange the engine expects from the station being worked.
    private var qsoHintLine: String? {
        if model.qsoWorkingCall != nil || model.qsoReadyToLog {
            return model.qsoHintExpected.map { "expecting: \($0)" }
        }
        if model.qsoActiveCount > 0, !model.qsoHintCalling.isEmpty {
            return "calling: " + model.qsoHintCalling.joined(separator: ", ")
        }
        return nil
    }

    private func qsoStat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.system(.title3, design: .rounded)).bold()
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private var qsoStatusLine: String {
        if model.qsoReadyToLog, let c = model.qsoWorkingCall { return "✓ \(c) — send TU" }
        if let c = model.qsoWorkingCall { return "Working \(c)" }
        if model.qsoActiveCount > 0 { return "\(model.qsoActiveCount) calling" }
        return "Press CQ to call"
    }

    private var qsoPlaceholder: String {
        if model.qsoReadyToLog { return "Send TU to log" }
        if model.qsoWorkingCall != nil { return "Copy their exchange" }
        if model.qsoActiveCount > 0 { return "Type a call (partial OK)" }
        return "Press CQ to call"
    }

    @ViewBuilder
    private var qsoLogList: some View {
        if model.qsoLog.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.largeTitle).foregroundStyle(Theme.teal.opacity(0.5))
                Text("Your log is empty — work some stations!")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(model.qsoLog) { q in
                        HStack {
                            Text(q.call)
                                .font(Theme.copyFont(style: .body, weight: .bold, monospaced: true,
                                                     slashedZero: model.settings.slashedZero))
                            Spacer()
                            Text(q.exchange)
                                .font(Theme.copyFont(style: .body, monospaced: true,
                                                     slashedZero: model.settings.slashedZero))
                                .foregroundStyle(Theme.textSecondary)
                            Text("\(q.wpm)w")
                                .font(.caption2).foregroundStyle(.secondary)
                                .frame(width: 42, alignment: .trailing)
                        }
                        .padding(.vertical, 8)
                        .padding(.horizontal, 12)
                        Divider().overlay(Theme.hairline)
                    }
                }
            }
            .brandCard()
        }
    }

    private var qsoInputBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                TextField(qsoPlaceholder, text: $qsoText)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.title2, design: .monospaced))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.send)
                    .focused($qsoFocused)
                    .onSubmit(qsoPrimary)
                    .morseKeyboardRow(text: $qsoText) { qsoFocused = false }
                if model.qsoCanRepeat {
                    Button { model.qsoRepeat() } label: {
                        Image(systemName: "questionmark")
                            .font(.headline)
                            .frame(minWidth: 44, minHeight: 38)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Ask for a repeat")
                }
            }
            Button(action: qsoPrimary) {
                Text(model.qsoActionLabel)
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(model.qsoReadyToLog ? .green : Theme.teal)
        }
    }

    private func qsoPrimary() {
        if model.qsoActionLabel == "CQ" {
            model.qsoCQ()
            qsoText = ""
        } else if model.qsoPrimaryAction(qsoText) {
            // Cleared unless "keep partial call" kept a still-being-copied call
            // in the box (issue #29).
            qsoText = ""
        } else {
            // Kept a still-being-copied call. If the send was a typed "?" repeat
            // request, drop the trailing "?" so it isn't baked into the call the
            // user keeps building on (issue #49).
            var kept = qsoText.trimmingCharacters(in: .whitespaces)
            while kept.hasSuffix("?") { kept.removeLast() }
            qsoText = kept.trimmingCharacters(in: .whitespaces)
        }
    }

    @ViewBuilder
    private var typedEntry: some View {
        // Head copy hides the box while the code plays so you can't type along —
        // you hold the item in your head and type it once it finishes. The field
        // stays mounted (just invisible) so focus lands cleanly on reveal.
        let headHidden = model.isRapidFireHeadType && model.phase == .playing
        VStack(spacing: 12) {
            ZStack {
                TextField(typedPlaceholder, text: $typedAnswer)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.title2, design: .monospaced))
                    .multilineTextAlignment(.center)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.send)
                    .focused($typedFocused)
                    .disabled(model.phase == .answered || headHidden)
                    .opacity(headHidden ? 0 : 1)
                    .onSubmit(submitTyped)
                    .morseKeyboardRow(text: $typedAnswer) { typedFocused = false }
                if headHidden {
                    Label("Copy it in your head…", systemImage: "brain.head.profile")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }
            }
            Button(action: submitTyped) {
                Text("Submit")
                    .font(.headline)
                    .foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.phase != .awaiting
                      || typedAnswer.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .onChange(of: model.drill) { _ in typedAnswer = "" }
        .onChange(of: model.phase) { newPhase in
            // Focus when it's time to answer; also focus during playback for
            // "type as you hear it" so you can copy in real time.
            if newPhase == .awaiting || (newPhase == .playing && model.isRapidFireLiveType) {
                typedFocused = true
            }
        }
    }

    /// Placeholder tuned to how the current mode wants you to type.
    private var typedPlaceholder: String {
        if model.isRapidFireLiveType { return "Type as you hear it" }
        if model.isRapidFireHeadType { return "Type what you copied" }
        return "Type what you heard"
    }

    private func submitTyped() {
        model.submitTyped(typedAnswer)
    }

    // MARK: - Voice response

    @ViewBuilder
    private var voiceResponseView: some View {
        switch model.voiceState {
        case .inactive:
            Color.clear.frame(height: 80)

        case .listening:
            VStack(spacing: 10) {
                Image(systemName: "mic.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(Theme.teal)
                    .symbolEffectPulseIfAvailable()
                Text("Speak your answer").font(.headline)
                if model.mode == .characters || model.mode == .confusion {
                    Text("Tip: use phonetics for single letters — say “Bravo” for B, “Niner” for 9.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity)

        case .confirming:
            VStack(spacing: 14) {
                Text("Did you say…")
                    .font(.subheadline).foregroundStyle(.secondary)
                Text(model.voiceGuess ?? "")
                    .font(.system(size: 40, weight: .bold, design: .monospaced))
                HStack(spacing: 16) {
                    Button {
                        Haptics.tap()
                        model.confirmVoiceGuess(false)
                    } label: {
                        Label("No", systemImage: "xmark")
                            .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(.bordered)
                    Button { model.confirmVoiceGuess(true) } label: {
                        Label("Yes", systemImage: "checkmark")
                            .font(.headline).foregroundStyle(Theme.navy)
                            .frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

        case .fallback:
            VStack(spacing: 10) {
                if let heard = model.voiceHeardText, !heard.isEmpty {
                    Text("Heard “\(heard)” — pick the closest:")
                        .font(.footnote).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                } else {
                    Text("Tap your answer:")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                voiceFallbackGrid
            }
        }
    }

    private var voiceFallbackGrid: some View {
        LazyVGrid(columns: columns, spacing: 16) {
            ForEach(model.voiceFallbackOptions, id: \.self) { option in
                Button {
                    model.selectVoiceFallback(option)
                } label: {
                    Text(option)
                        .font(optionFont(option))
                        .foregroundStyle(Theme.navy)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, minHeight: 80)
                        .padding(.horizontal, 4)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    // MARK: - Choices

    // MARK: - Journey

    /// Level header + progress bar shown above the choice grid in Journey mode.
    private var journeyBanner: some View {
        VStack(spacing: 6) {
            HStack {
                Text("Level \(model.journeyLevelNumber)")
                    .font(.headline)
                Text(model.journeyLevelSection)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(model.journeyLevelNumber) / \(model.journeyTotalLevels)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                // Mid-session hop to any unlocked level (Android's header has
                // one; iOS used to reach the map only from the setup card).
                Button {
                    showJourneyMap = true
                } label: {
                    Image(systemName: "map")
                        .font(.subheadline)
                        .foregroundStyle(Theme.teal)
                }
                .accessibilityLabel("Journey map — choose a level")
            }
            if !model.journeyLevelTitle.isEmpty {
                HStack {
                    Text(model.journeyLevelTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Theme.teal)
                    Spacer()
                }
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.secondary.opacity(0.2))
                    Capsule()
                        .fill(model.lastCorrect == false ? Color.orange : Theme.teal)
                        .frame(width: max(0, geo.size.width * model.journeyBarProgress))
                }
            }
            .frame(height: 12)
            .animation(.spring(response: 0.4, dampingFraction: 0.8), value: model.journeyBarProgress)
            .accessibilityLabel("Level progress")
            .accessibilityValue("\(Int(model.journeyBarProgress * 100)) percent")

            if let cleared = model.journeyLevelCleared {
                Label("Level \(cleared) cleared!", systemImage: "checkmark.seal.fill")
                    .font(.callout.bold())
                    .foregroundStyle(.green)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.horizontal, 4)
        .animation(.spring(response: 0.4, dampingFraction: 0.7), value: model.journeyLevelCleared)
    }

    private var choiceGrid: some View {
        let options = model.drill?.options ?? []
        // Hardware-keyboard answering (issue #69). A single-character option is
        // always answered by its own key; only the longer options take a 1–9
        // position, and never a digit an option has already claimed. Choosing
        // the scheme per drill instead of per option meant one multi-character
        // distractor renumbered the whole grid, so a digit you heard picked the
        // Nth option (android#30).
        let keys = AnswerKeys.assign(options)
        return LazyVGrid(columns: columns, spacing: 16) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                Button {
                    model.select(option)
                } label: {
                    Text(option)
                        .font(optionFont(option))
                        .foregroundStyle(Theme.prominentLabel(on: tint(for: option)))
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, minHeight: 80)
                        .padding(.horizontal, 4)
                }
                .buttonStyle(.borderedProminent)
                .tint(tint(for: option))
                .disabled(model.phase == .answered)
                .keyboardShortcut(keys[index].map { KeyboardShortcut(KeyEquivalent($0), modifiers: []) })
                .overlay(alignment: .topLeading) {
                    // Surface the position hint only when a physical keyboard is
                    // attached; character keys need no hint — the key is the label.
                    if hardwareKeyboard.isConnected,
                       AnswerKeys.needsPositionHint(options, at: index),
                       let key = keys[index] {
                        Text(String(key))
                            .font(.caption2.monospacedDigit().bold())
                            .foregroundStyle(Theme.navy.opacity(0.7))
                            .padding(5)
                    }
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.phase)
    }

    /// Big monospaced for short tokens, smaller for word-y meanings. Digits
    /// carry the slashed zero so 0 and O options can't be confused (#62).
    private func optionFont(_ option: String) -> Font {
        option.count <= 3
            ? Theme.copyFont(size: 38, weight: .semibold, monospaced: true,
                             slashedZero: model.settings.slashedZero)
            : Theme.copyFont(size: 18, weight: .semibold,
                             slashedZero: model.settings.slashedZero)
    }

    private func tint(for option: String) -> Color {
        guard model.phase == .answered else { return Theme.teal }
        if option == model.drill?.correct, model.settings.showCorrectness { return .green }
        if option == model.lastSelected, model.lastCorrect == false { return .red }
        return .gray
    }

    // MARK: - Bottom bar

    @ViewBuilder
    private var bottomBar: some View {
        if model.showsNextButton {
            Button {
                model.next()
            } label: {
                Text("Next")
                    .font(.headline)
                    .foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            // Return advances from a hardware keyboard (issue #69) — except in
            // the typed flows, where Return already belongs to the text field.
            .keyboardShortcut(model.usesTypedEntry ? nil : .defaultAction)
        } else {
            Color.clear.frame(height: 50)
        }
    }

    // MARK: - Session timer bar

    @ViewBuilder
    private var sessionBar: some View {
        HStack {
            if model.mode.usesSessionLength {
                Menu {
                    Button("Add 5 minutes") { model.addSessionTime(300) }
                    Button("Add 1 minute") { model.addSessionTime(60) }
                    if model.sessionIsTimed {
                        Button("Subtract 1 minute") { model.reduceSessionTime(60) }
                        Button("Remove time limit") { model.makeSessionOpenEnded() }
                    }
                } label: {
                    Label(sessionTimeText, systemImage: "timer")
                        .monospacedDigit()
                    Image(systemName: "chevron.down").font(.caption2)
                }
                .accessibilityLabel("Adjust the session timer. \(sessionTimeText).")
            } else {
                Label(sessionTimeText, systemImage: "timer")
                    .monospacedDigit()
            }
            Spacer()
            // No passive session readout here: the pool-size counts ("155
            // words & calls") were reported as clutter twice (issues #61, #74),
            // and every meaningful figure already lives on the mode's own
            // surface (exam question count, journey banner, QSO log).
            Button(role: .destructive) {
                model.endSession()
            } label: {
                Text("End")
            }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
    }

    private var sessionTimeText: String {
        guard let remaining = model.sessionRemaining else { return "No time limit" }
        let total = Int(remaining.rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    // MARK: - Session summary

    private var sessionSummaryView: some View {
        let s = model.sessionSummary
        // Contest runs its own clock (and names the event), so read its length
        // and title rather than the generic practice-duration record.
        let durationLabel = s.mode == .contest
            ? model.settings.contest.length.label : s.duration.label
        let timed = s.mode == .contest
            ? model.settings.contest.length.seconds != nil : s.duration.seconds != nil
        let titleLabel = s.mode == .contest ? model.contestType.name : s.mode.title
        let rfReview = s.mode == .rapidFire && model.settings.rapidFire.response == .review
        return ScrollView {
        VStack(spacing: 22) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 64))
                .foregroundStyle(.green)
            VStack(spacing: 4) {
                Text(timed ? "Time's up!" : "Session complete")
                    .font(.largeTitle).bold()
                Text("\(durationLabel) · \(titleLabel)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if let milestone = model.newMilestone {
                let tier = AppModel.milestoneTier(forDay: milestone)
                VStack(spacing: 4) {
                    Text(tier?.emoji ?? "🎉").font(.system(size: 40))
                    Text("\(milestone)-day streak!")
                        .font(.title3).bold()
                        .foregroundStyle(.orange)
                    Text("New milestone reached — keep it going.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color.orange.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .strokeBorder(Color.orange.opacity(0.4), lineWidth: 1))
                .accessibilityElement(children: .combine)
            }

            VStack(spacing: 14) {
                if s.mode == .contest {
                    summaryRow("Score", "\(model.contestScore)")
                    if model.contestShowsQSOCount {
                        summaryRow("QSOs", "\(model.qsoCount)")
                    }
                    if let mult = model.contestType.multiplierLabel {
                        summaryRow(mult, "\(model.contestMultipliers)")
                    }
                    summaryRow("Rate", "\(Int(model.qsoSessionRate.rounded()))/hr")
                    summaryRow("Clean copy", model.qsoCount + model.qsoBusts == 0
                               ? "—" : "\(Int((model.qsoAccuracy * 100).rounded()))%")
                    summaryRow("Busts", "\(model.qsoBusts)")
                } else if s.mode == .qso {
                    summaryRow("QSOs logged", "\(model.qsoCount)")
                    summaryRow("Rate", "\(Int(model.qsoSessionRate.rounded()))/hr")
                    summaryRow("Clean copy", model.qsoCount + model.qsoBusts == 0
                               ? "—" : "\(Int((model.qsoAccuracy * 100).rounded()))%")
                    summaryRow("Busts", "\(model.qsoBusts)")
                } else {
                    summaryRow(rfReview ? "Sent" : "Answered", "\(s.attempts)")
                    // Listen & Learn, Short Stories, and Rapid Fire's "just
                    // listen" review play without grading an answer, so accuracy
                    // isn't applicable — show a placeholder instead of a
                    // misleading 0% (issue #36).
                    summaryRow("Accuracy",
                               (s.mode == .listen || s.mode == .story || rfReview || s.attempts == 0)
                               ? "—" : "\(Int((s.accuracy * 100).rounded()))%")
                    summaryRow("Fastest", s.fastest.map { String(format: "%.2f s", $0) } ?? "—")
                    summaryRow("Median TTR", s.medianTTR.map { String(format: "%.2f s", $0) } ?? "—")
                }
            }
            .padding()
            .frame(maxWidth: .infinity)
            .brandCard()

            if s.mode == .contest || s.mode == .qso,
               model.settings.qso.missedCallerFeedback != .off,
               !model.qsoMissedCallers.isEmpty {
                missedCallersCard
            }

            if s.mode == .rapidFire, !model.rapidFireTranscript.isEmpty {
                rapidFireTranscriptCard
            }

            // The run's worked log on the scorecard (Android parity): every
            // contact with its exchange and speed, newest first.
            if s.mode == .contest || s.mode == .qso, !model.qsoLog.isEmpty {
                workedLogCard
            }

            VStack(spacing: 12) {
                if let record = model.lastSessionRecord {
                    Button {
                        detailRecord = record
                    } label: {
                        Label("Session detail", systemImage: "chart.bar.xaxis")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(.bordered)
                }

                // Starting from the recap adopts the selected mode, so after a
                // mid-session mode pick this is the direct way into it — say so
                // instead of promising to repeat the finished mode (issue #67).
                Button {
                    model.startSession()
                } label: {
                    Label(model.learningMode == s.mode
                            ? "Practice again"
                            : "Start \(model.learningMode.title)",
                          systemImage: model.learningMode == s.mode
                            ? "arrow.clockwise" : "play.fill")
                        .font(.headline)
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)

                // Named for where it lands, not for the sheet it opens on the
                // way (issue #90): "Change setup" read as a settings screen,
                // while what most people want after a run is simply out. The
                // #67 behavior is unchanged — the intro still opens the
                // selected mode's setup sheet, which is how you change setup
                // before starting again.
                Button {
                    onExit()
                } label: {
                    Text("Return home")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding()
        .readableWidth()
        }
    }

    /// Every contact worked this run — call, exchange, speed — for the
    /// Who walked off, and what you had them as — the end-of-run half of the
    /// give-up feedback. A caller that vanishes mid-run teaches nothing unless
    /// you can see which call you lost and how close you were to it.
    private var missedCallersCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("GOT AWAY (\(model.qsoMissedCallers.count))")
                .font(.system(size: 10, weight: .bold)).tracking(1)
                .foregroundStyle(Theme.textSecondary)
            ForEach(model.qsoMissedCallers) { missed in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(missed.call)
                        .font(Theme.copyFont(size: 15, weight: .semibold, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                        .foregroundStyle(.white)
                    if let had = missed.miscopiedAs {
                        Text("you had \(had)")
                            .font(.caption).foregroundStyle(.orange)
                    } else {
                        Text("never copied")
                            .font(.caption).foregroundStyle(Theme.textSecondary)
                    }
                    Spacer()
                    Text("\(missed.attempts)×")
                        .font(.caption.monospaced()).foregroundStyle(Theme.textSecondary)
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }

    /// contest/QSO scorecard. The summary already scrolls, so a plain stack.
    private var workedLogCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Worked")
                .font(.subheadline).foregroundStyle(.secondary)
            LazyVStack(spacing: 0) {
                ForEach(model.qsoLog) { q in
                    HStack {
                        Text(q.call)
                            .font(.system(.body, design: .monospaced)).bold()
                        Spacer()
                        Text(q.exchange)
                            .font(.system(.body, design: .monospaced))
                            .foregroundStyle(Theme.textSecondary)
                        Text("\(q.wpm)w")
                            .font(.caption2).foregroundStyle(.secondary)
                            .frame(width: 42, alignment: .trailing)
                    }
                    .padding(.vertical, 6)
                    Divider().overlay(Theme.hairline)
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity)
        .brandCard()
    }

    /// The full list of what Rapid Fire transmitted this session, with a
    /// right/wrong mark and the learner's copy for type/key responses.
    private var rapidFireTranscriptCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("What was transmitted")
                .font(.subheadline).foregroundStyle(.secondary)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(model.rapidFireTranscript) { item in
                        HStack(spacing: 10) {
                            if let correct = item.correct {
                                Image(systemName: correct ? "checkmark.circle.fill" : "xmark.circle.fill")
                                    .foregroundStyle(correct ? .green : .red)
                            }
                            Text(item.text)
                                .font(.system(.body, design: .monospaced)).bold()
                            Spacer()
                            if let typed = item.typed, item.correct == false {
                                Text(typed.isEmpty ? "—" : typed)
                                    .font(.system(.body, design: .monospaced))
                                    .foregroundStyle(.red)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.6)
                            }
                        }
                        .padding(.vertical, 7)
                        Divider().overlay(Theme.hairline)
                    }
                }
            }
            .frame(maxHeight: 220)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .brandCard()
    }

    private func summaryRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.title3.monospacedDigit()).bold()
        }
    }
}

/// Watches for a physical keyboard (Bluetooth, USB, or a Smart-style attached
/// one) via GameController, so the choice grid can show its 1–9 answer hints
/// only when they can actually be typed (issue #69). The shortcuts themselves
/// are always installed — they're inert without a keyboard.
@MainActor
final class HardwareKeyboardObserver: ObservableObject {
    @Published private(set) var isConnected = GCKeyboard.coalesced != nil
    /// Released with the observer, which is when the registrations go away.
    /// Held through a wrapper rather than as raw tokens because this class is
    /// main-actor isolated and its `deinit` is not, so it may not touch a
    /// non-`Sendable` token itself; the wrapper's plain `deinit` can.
    private var observations: [NotificationObservation] = []

    init() {
        // Both observers are registered on `.main`, so the blocks already run
        // on the main thread; `assumeIsolated` just tells the checker what the
        // queue argument guarantees (it traps if that ever stops being true).
        let center = NotificationCenter.default
        observations.append(NotificationObservation(
            center.addObserver(forName: .GCKeyboardDidConnect,
                               object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.isConnected = true }
            }))
        observations.append(NotificationObservation(
            center.addObserver(forName: .GCKeyboardDidDisconnect,
                               object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.isConnected = GCKeyboard.coalesced != nil }
            }))
    }
}

/// Owns one block-based `NotificationCenter` registration and removes it when
/// released. Deliberately not isolated to any actor: its whole job is a
/// `deinit` that hands the token back, and `NotificationCenter` is thread-safe.
private final class NotificationObservation {
    private let token: NSObjectProtocol
    init(_ token: NSObjectProtocol) { self.token = token }
    deinit { NotificationCenter.default.removeObserver(token) }
}

#Preview {
    ContentView().environmentObject(AppModel())
}
