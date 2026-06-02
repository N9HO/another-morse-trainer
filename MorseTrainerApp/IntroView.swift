import SwiftUI

/// Welcome / onboarding screen shown before practice begins. Leads with a grid
/// of tappable training-mode tiles, then reveals the options that matter for
/// the chosen mode, the starting level, and how long to practice.
struct IntroView: View {
    @EnvironmentObject var model: AppModel
    var onStart: () -> Void

    private let tileColumns = [GridItem(.flexible(), spacing: 14),
                               GridItem(.flexible(), spacing: 14)]

    private var proficiency: Binding<Proficiency> {
        Binding(
            get: { model.settings.proficiency },
            set: { model.configureProficiency($0) }   // no audio on the intro
        )
    }

    private var durationBinding: Binding<PracticeDuration> {
        Binding(
            get: { model.settings.practiceDuration },
            set: { model.settings.practiceDuration = $0 }
        )
    }

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

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 28) {
                    header

                    howItWorksCard

                    modePicker

                    modeOptions

                    fieldCard(title: "Where are you starting?",
                              systemImage: "figure.stairs") {
                        Picker("Starting level", selection: proficiency) {
                            ForEach(Proficiency.allCases) { level in
                                Text(level.label).tag(level)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(Theme.tealBright)
                    }

                    fieldCard(title: "How long do you want to practice?",
                              systemImage: "timer") {
                        Picker("Duration", selection: durationBinding) {
                            ForEach(PracticeDuration.allCases) { d in
                                Text(d.label).tag(d)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(Theme.tealBright)
                    }

                    Spacer(minLength: 8)
                }
                .padding(24)
                .animation(.easeInOut(duration: 0.22), value: model.learningMode)
            }

            startBar
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 12) {
            logoMark
            Text("Another Morse Trainer")
                .font(.largeTitle).bold()
                .multilineTextAlignment(.center)
            Text("Learn Morse code by ear — the proven Koch method.")
                .font(.headline)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 8)
    }

    private var howItWorksCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            howItWorks(icon: "ear",
                       title: "Listen",
                       detail: "Hear a character at full speed (33 WPM) — fast enough to learn the sound, not count beeps.")
            howItWorks(icon: "hand.tap",
                       title: "Choose",
                       detail: "Tap what you heard from four close-sounding options.")
            howItWorks(icon: "chart.line.uptrend.xyaxis",
                       title: "Level up",
                       detail: "Get quick and accurate, and new characters unlock automatically.")
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
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

            if model.learningMode == .listen {
                inlinePicker(title: "What should it announce?",
                             selection: listenContentBinding) { (c: ListenContent) in c.label }
                inlinePicker(title: "Gap before the spoken answer",
                             selection: listenGapBinding) { (g: AnswerGap) in g.label }
            }

            if model.learningMode == .words {
                inlinePicker(title: "How big a word pool?",
                             selection: wordTierBinding) { (t: WordTier) in t.label }
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
            model.startSession()
            onStart()
        } label: {
            Text("Start Training")
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 54)
        }
        .buttonStyle(.borderedProminent)
        .tint(Theme.teal)
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .background(.ultraThinMaterial)
        .accessibilityHint("Begins a \(model.settings.practiceDuration.label) session of \(model.learningMode.title).")
    }

    // MARK: - Building blocks

    private func sectionTitle(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.title3).bold()
            .foregroundStyle(.primary)
    }

    /// A labelled container holding one control, in the brand card style.
    private func fieldCard<Content: View>(title: String,
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

    private func howItWorks(icon: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(Theme.teal)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
        }
        .accessibilityElement(children: .combine)
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

#Preview {
    ZStack {
        Theme.Background()
        IntroView(onStart: {}).environmentObject(AppModel())
    }
    .preferredColorScheme(.dark)
}
