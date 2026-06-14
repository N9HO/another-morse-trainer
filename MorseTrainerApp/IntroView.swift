import SwiftUI

/// Welcome / onboarding screen shown before practice begins. Leads with a grid
/// of tappable training-mode tiles, then reveals the options that matter for
/// the chosen mode, the starting level, and how long to practice.
struct IntroView: View {
    @EnvironmentObject var model: AppModel
    var onStart: () -> Void

    @State private var showingSetup = false
    @State private var showingSettings = false
    @State private var showingStats = false
    @State private var showingCustomWords = false
    @State private var showingJourneyMap = false
    @State private var showingRepeater = false
    @StateObject private var repeater = RepeaterModel()

    private let tileColumns = [GridItem(.flexible(), spacing: 14),
                               GridItem(.flexible(), spacing: 14)]

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
            set: {
                model.settings.keyingResponse = $0
                if $0 { model.settings.voiceResponse = false }   // mutually exclusive
            }
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

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(spacing: 28) {
                    header

                    modePicker

                    modeOptions

                    Spacer(minLength: 8)
                }
                .padding(24)
                .animation(.easeInOut(duration: 0.22), value: model.learningMode)
            }

            startBar
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
        .sheet(isPresented: $showingCustomWords) {
            CustomWordsSheet().environmentObject(model)
        }
        .sheet(isPresented: $showingJourneyMap) {
            JourneyMapView().environmentObject(model)
        }
        .fullScreenCover(isPresented: $showingRepeater) {
            RepeaterView().environmentObject(repeater)
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

    // MARK: - Mode picker (tiles)

    private var modePicker: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("Choose your practice", systemImage: "square.grid.2x2")
            LazyVGrid(columns: tileColumns, spacing: 14) {
                ForEach(TrainingMode.allCases) { mode in
                    ModeTile(mode: mode,
                             isSelected: model.learningMode == mode) {
                        guard model.learningMode != mode else { return }
                        Haptics.selection()
                        withAnimation(.easeInOut(duration: 0.22)) {
                            model.learningMode = mode
                        }
                    }
                }
            }
        }
    }

    /// Options that only matter for the currently-selected mode, plus its blurb.
    @ViewBuilder
    private var modeOptions: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label {
                Text(model.learningMode.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: "info.circle")
                    .foregroundStyle(Theme.teal)
            }

            // Mode-specific configuration (grouped so the surrounding builder
            // stays well under SwiftUI's per-block child limit).
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

            if model.learningMode == .words {
                if model.settings.customWords.isEmpty {
                    inlinePicker(title: "How big a word pool?",
                                 selection: wordTierBinding) { (t: WordTier) in t.label }
                }
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

            if model.learningMode == .characters || model.learningMode == .words {
                Divider().overlay(Theme.hairline)
                Toggle(isOn: voiceResponseBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Answer with your voice", systemImage: "mic.fill")
                            .font(.subheadline).bold()
                        Text("Say your answer instead of tapping. Use phonetics for letters (“Bravo” for B); say words normally.")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(Theme.teal)

                Toggle(isOn: keyingResponseBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Answer by keying", systemImage: "dot.radiowaves.left.and.right")
                            .font(.subheadline).bold()
                        Text("Send your answer on a Morse key — a hardware Vail/BLE MIDI key or the on-screen key — and it’s decoded back to letters.")
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
        .transition(.opacity)
    }

    // MARK: - Start

    private var startBar: some View {
        Button {
            Haptics.tap()
            if model.learningMode.needsSetup {
                showingSetup = true
            } else {
                model.startSession()
                onStart()
            }
        } label: {
            Text(model.learningMode.needsSetup ? "Continue" : "Start Training")
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 54)
        }
        .buttonStyle(.borderedProminent)
        .tint(Theme.teal)
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .background(.ultraThinMaterial)
        .accessibilityHint(model.learningMode.needsSetup
            ? "Opens session options for \(model.learningMode.title)."
            : "Begins a session of \(model.learningMode.title).")
    }

    // MARK: - Custom words (issue #32)

    @ViewBuilder
    private var customWordsControl: some View {
        let count = model.settings.customWords.count
        Button {
            showingCustomWords = true
        } label: {
            HStack {
                Label(count == 0 ? "Use a custom word list" : "Custom list: \(count) words",
                      systemImage: "list.bullet.rectangle")
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        if count > 0 {
            Text("Words mode is drawing from your custom list.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
    }

    // MARK: - Building blocks

    private func sectionTitle(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.title3).bold()
            .foregroundStyle(.primary)
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

/// One selectable training-mode tile: icon, name, and a short tagline. The
/// selected tile fills with the brand teal and shows a check.
private struct ModeTile: View {
    let mode: TrainingMode
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(isSelected ? Color.white.opacity(0.18) : Theme.navyRaised)
                        .frame(width: 46, height: 46)
                    Image(systemName: mode.icon)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(isSelected ? .white : Theme.teal)
                }

                Text(mode.title)
                    .font(.subheadline).bold()
                    .foregroundStyle(isSelected ? .white : .primary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)

                Text(mode.tagline)
                    .font(.caption2)
                    .foregroundStyle(isSelected ? Color.white.opacity(0.85) : Theme.textSecondary)
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
                        .foregroundStyle(.white)
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
/// Exam never asks "how long?" or "what do you already know?".
private struct SessionSetupSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var onStart: () -> Void

    private var proficiency: Binding<Proficiency> {
        Binding(
            get: { model.settings.proficiency },
            set: { model.configureProficiency($0) }   // no audio on the setup sheet
        )
    }

    private var durationBinding: Binding<PracticeDuration> {
        Binding(
            get: { model.settings.practiceDuration },
            set: { model.settings.practiceDuration = $0 }
        )
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

                        if model.learningMode.usesStartingLevel {
                            card(title: "Where are you starting?", systemImage: "figure.stairs") {
                                Picker("Starting level", selection: proficiency) {
                                    ForEach(Proficiency.allCases) { level in
                                        Text(level.label).tag(level)
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(Theme.tealBright)
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
                        .frame(maxWidth: .infinity, minHeight: 54)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
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
/// by new lines, commas, or spaces; saving replaces the active custom list.
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
                    Text("Paste your own words — one per line, or separated by commas or spaces. Words mode will draw only from this list.")
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

                    Text("\(parsedCount) word\(parsedCount == 1 ? "" : "s")")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)

                    Spacer()
                }
                .padding(20)
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
        IntroView(onStart: {}).environmentObject(AppModel())
    }
    .preferredColorScheme(.dark)
}
