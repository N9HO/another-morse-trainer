import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    var onExit: () -> Void = {}
    @State private var showSettings = false
    @State private var showStats = false
    @State private var typedAnswer = ""
    @State private var examCopy = ""
    @State private var qsoText = ""
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
                } else if model.isQSO {
                    qsoView
                } else {
                    statusArea
                        .frame(maxHeight: .infinity)

                    if model.settings.allowReplay, model.drill != nil {
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
                    } else {
                        choiceGrid
                        bottomBar
                    }
                }
            }
            .padding()
            }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { modeMenu }
                ToolbarItem(placement: .topBarLeading) {
                    Text(model.summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
                SettingsView().environmentObject(model)
            }
            .sheet(isPresented: $showStats) {
                StatsView().environmentObject(model)
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
            ForEach(TrainingMode.allCases) { m in
                Button {
                    // Changing modes ends the current session and shows its
                    // summary; the next session begins only on an explicit start.
                    model.setMode(m)
                } label: {
                    Label(m.title, systemImage: m.icon)
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(model.mode.title).font(.headline)
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
                        .font(.system(size: 40, weight: .bold, design: .monospaced))
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
                Label("New: \(unlocked)", systemImage: "star.fill")
                    .font(.callout).bold()
                    .foregroundStyle(.orange)
                    .padding(.top, 4)
            }
        }
    }

    // MARK: - Head copy

    @ViewBuilder
    private var revealView: some View {
        VStack(spacing: 8) {
            Text("You heard:").font(.subheadline).foregroundStyle(.secondary)
            Text(model.drill?.revealPrimary ?? "")
                .font(.system(size: 44, weight: .bold, design: .monospaced))
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
            Button {
                Haptics.tap()
                model.revealHeadCopy()
            } label: {
                Text("Reveal")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
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
                Button {
                    Haptics.success()
                    model.gradeHeadCopy(true)
                } label: {
                    Label("Got it", systemImage: "checkmark")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent).tint(.green)
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
                Text("Public-domain fable · continuous copy")
                    .font(.caption).foregroundStyle(.secondary)
            }

            // Copy area: hidden until revealed.
            ScrollView {
                if model.storyRevealed {
                    Text(model.storyText)
                        .font(.system(.title3, design: .monospaced))
                        .lineSpacing(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .transition(.opacity)
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: model.storyPlaying
                              ? "dot.radiowaves.left.and.right" : "book.closed")
                            .font(.system(size: 56))
                            .foregroundStyle(Theme.teal)
                        Text(model.storyPlaying
                             ? "Sending… copy along"
                             : "Press Play, then copy what you hear")
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
    }

    @ViewBuilder
    private var storyControls: some View {
        HStack(spacing: 12) {
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
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
            }

            if !model.storyRevealed {
                Button {
                    model.revealStory()
                } label: {
                    Label("Reveal", systemImage: "eye")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.bordered)
                .disabled(model.storyPlaying)
            }

            Button {
                model.nextStory()
            } label: {
                Label("Next", systemImage: "forward.fill")
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)
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
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
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
            Button {
                model.submitExamCopy(examCopy)
            } label: {
                Text("Grade my copy")
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
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
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 50)
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
                        .font(.system(.body, design: .monospaced))
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
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
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
                    .font(.system(size: 44, weight: .bold, design: .monospaced))
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

    // MARK: - Typed free-recall

    // MARK: - QSO Simulator (pileup)

    @ViewBuilder
    private var qsoView: some View {
        VStack(spacing: 16) {
            qsoStatusCard
            qsoLogList
                .frame(maxHeight: .infinity)
            qsoInputBar
        }
        .onChange(of: model.qsoReadyToLog) { ready in
            if ready { Haptics.success() }
        }
    }

    private var qsoStatusCard: some View {
        VStack(spacing: 10) {
            Text(model.qsoMode.label)
                .font(.subheadline).foregroundStyle(Theme.textSecondary)
            Text(qsoStatusLine)
                .font(.title2).bold()
                .foregroundStyle(model.qsoReadyToLog ? .green : Theme.tealBright)
                .multilineTextAlignment(.center)
                .animation(.easeInOut(duration: 0.2), value: qsoStatusLine)
            HStack(spacing: 28) {
                qsoStat("Logged", "\(model.qsoCount)")
                qsoStat("Rate", "\(Int(model.qsoRate))/hr")
                qsoStat("Acc", "\(Int((model.qsoAccuracy * 100).rounded()))%")
            }
            if model.qsoBusy {
                Label("Receiving…", systemImage: "dot.radiowaves.left.and.right")
                    .font(.caption).foregroundStyle(Theme.teal)
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .brandCard()
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
                                .font(.system(.body, design: .monospaced)).bold()
                            Spacer()
                            Text(q.exchange)
                                .font(.system(.body, design: .monospaced))
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
        } else {
            model.qsoPrimaryAction(qsoText)
        }
        qsoText = ""
    }

    @ViewBuilder
    private var typedEntry: some View {
        VStack(spacing: 12) {
            TextField("Type what you heard", text: $typedAnswer)
                .textFieldStyle(.roundedBorder)
                .font(.system(.title2, design: .monospaced))
                .multilineTextAlignment(.center)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .submitLabel(.send)
                .focused($typedFocused)
                .disabled(model.phase == .answered)
                .onSubmit(submitTyped)
            Button(action: submitTyped) {
                Text("Submit")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.phase != .awaiting
                      || typedAnswer.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .onChange(of: model.drill) { _ in typedAnswer = "" }
        .onChange(of: model.phase) { newPhase in
            if newPhase == .awaiting { typedFocused = true }
        }
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
                if model.mode == .characters {
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
                            .font(.headline).frame(maxWidth: .infinity, minHeight: 52)
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

    private var choiceGrid: some View {
        LazyVGrid(columns: columns, spacing: 16) {
            ForEach(model.drill?.options ?? [], id: \.self) { option in
                Button {
                    model.select(option)
                } label: {
                    Text(option)
                        .font(optionFont(option))
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, minHeight: 80)
                        .padding(.horizontal, 4)
                }
                .buttonStyle(.borderedProminent)
                .tint(tint(for: option))
                .disabled(model.phase == .answered)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.phase)
    }

    /// Big monospaced for short tokens, smaller for word-y meanings.
    private func optionFont(_ option: String) -> Font {
        option.count <= 3
            ? .system(size: 38, weight: .semibold, design: .monospaced)
            : .system(size: 18, weight: .semibold)
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
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
        } else {
            Color.clear.frame(height: 50)
        }
    }

    // MARK: - Session timer bar

    @ViewBuilder
    private var sessionBar: some View {
        HStack {
            Label(sessionTimeText, systemImage: "timer")
                .monospacedDigit()
            Spacer()
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
        let timed = s.duration.seconds != nil
        return VStack(spacing: 22) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 64))
                .foregroundStyle(.green)
            VStack(spacing: 4) {
                Text(timed ? "Time's up!" : "Session complete")
                    .font(.largeTitle).bold()
                Text("\(s.duration.label) · \(s.mode.title)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            VStack(spacing: 14) {
                if s.mode == .qso {
                    summaryRow("QSOs logged", "\(model.qsoCount)")
                    summaryRow("Rate", "\(Int(model.qsoSessionRate.rounded()))/hr")
                    summaryRow("Clean copy", model.qsoCount + model.qsoBusts == 0
                               ? "—" : "\(Int((model.qsoAccuracy * 100).rounded()))%")
                    summaryRow("Busts", "\(model.qsoBusts)")
                } else {
                    summaryRow("Answered", "\(s.attempts)")
                    summaryRow("Accuracy", s.attempts == 0 ? "—" : "\(Int((s.accuracy * 100).rounded()))%")
                    summaryRow("Fastest", s.fastest.map { String(format: "%.2f s", $0) } ?? "—")
                    summaryRow("Median TTR", s.medianTTR.map { String(format: "%.2f s", $0) } ?? "—")
                }
            }
            .padding()
            .frame(maxWidth: .infinity)
            .brandCard()

            VStack(spacing: 12) {
                Button {
                    model.startSession()
                } label: {
                    Label("Practice again", systemImage: "arrow.clockwise")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    onExit()
                } label: {
                    Text("Change setup")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding()
    }

    private func summaryRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.title3.monospacedDigit()).bold()
        }
    }
}

#Preview {
    ContentView().environmentObject(AppModel())
}
