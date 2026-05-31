import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var showSettings = false
    @State private var showStats = false
    @State private var typedAnswer = ""
    @FocusState private var typedFocused: Bool

    private let columns = [GridItem(.flexible(), spacing: 16),
                           GridItem(.flexible(), spacing: 16)]

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
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
                } else if model.isTyped {
                    typedEntry
                    bottomBar
                } else {
                    choiceGrid
                    bottomBar
                }
            }
            .padding()
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
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView().environmentObject(model)
            }
            .sheet(isPresented: $showStats) {
                StatsView().environmentObject(model)
            }
            .onAppear {
                if model.drill == nil { model.start() }
            }
        }
    }

    private var modeMenu: some View {
        Menu {
            ForEach(TrainingMode.allCases) { m in
                Button {
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
                    .foregroundStyle(.blue)
                Text("Listen…").font(.title3).foregroundStyle(.secondary)
            case .awaiting:
                Image(systemName: model.isHeadCopy ? "brain.head.profile" : "ear")
                    .font(.system(size: 60))
                    .foregroundStyle(.blue)
                Text(model.mode.prompt).font(.title3).foregroundStyle(.secondary)
            case .revealed:
                revealView
            case .answered:
                feedbackView
            }
        }
        .multilineTextAlignment(.center)
        .animation(.easeInOut(duration: 0.15), value: model.phase)
    }

    @ViewBuilder
    private var feedbackView: some View {
        VStack(spacing: 10) {
            if model.settings.showCorrectness, let correct = model.lastCorrect {
                Image(systemName: correct ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(correct ? .green : .red)
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
                model.revealHeadCopy()
            } label: {
                Text("Reveal")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
        case .revealed:
            HStack(spacing: 16) {
                Button { model.gradeHeadCopy(false) } label: {
                    Label("Missed it", systemImage: "xmark")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent).tint(.red)
                Button { model.gradeHeadCopy(true) } label: {
                    Label("Got it", systemImage: "checkmark")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent).tint(.green)
            }
        default:
            Color.clear.frame(height: 56)
        }
    }

    // MARK: - Typed free-recall

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
    }

    /// Big monospaced for short tokens, smaller for word-y meanings.
    private func optionFont(_ option: String) -> Font {
        option.count <= 3
            ? .system(size: 38, weight: .semibold, design: .monospaced)
            : .system(size: 18, weight: .semibold)
    }

    private func tint(for option: String) -> Color {
        guard model.phase == .answered else { return .blue }
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
}

#Preview {
    ContentView().environmentObject(AppModel())
}
