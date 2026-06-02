import SwiftUI

/// Welcome / onboarding screen shown before practice begins. Explains the
/// method at a glance and lets the user pick a starting level.
struct IntroView: View {
    @EnvironmentObject var model: AppModel
    var onStart: () -> Void

    private var proficiency: Binding<Proficiency> {
        Binding(
            get: { model.settings.proficiency },
            set: { model.configureProficiency($0) }   // no audio on the intro
        )
    }

    private var learningModeBinding: Binding<TrainingMode> {
        Binding(
            get: { model.learningMode },
            set: { model.learningMode = $0 }
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

    private var voiceResponseBinding: Binding<Bool> {
        Binding(
            get: { model.settings.voiceResponse },
            set: { model.settings.voiceResponse = $0 }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 28) {
                    Spacer(minLength: 8)

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

            VStack(alignment: .leading, spacing: 18) {
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
            .padding(.horizontal, 8)

            VStack(alignment: .leading, spacing: 8) {
                Text("How do you want to learn?")
                    .font(.subheadline).foregroundStyle(.secondary)
                Picker("Learning style", selection: learningModeBinding) {
                    ForEach(TrainingMode.allCases) { m in
                        Label(m.title, systemImage: m.icon).tag(m)
                    }
                }
                .pickerStyle(.menu)
                Text(model.learningMode.blurb)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if model.learningMode == .listen {
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("What should it announce?")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Picker("Announce", selection: listenContentBinding) {
                            ForEach(ListenContent.allCases) { c in
                                Text(c.label).tag(c)
                            }
                        }
                        .pickerStyle(.menu)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Gap before the spoken answer")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Picker("Answer gap", selection: listenGapBinding) {
                            ForEach(AnswerGap.allCases) { g in
                                Text(g.label).tag(g)
                            }
                        }
                        .pickerStyle(.menu)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if model.learningMode == .words {
                VStack(alignment: .leading, spacing: 8) {
                    Text("How big a word pool?")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Picker("Word pool", selection: wordTierBinding) {
                        ForEach(WordTier.allCases) { t in
                            Text(t.label).tag(t)
                        }
                    }
                    .pickerStyle(.menu)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if model.learningMode == .characters || model.learningMode == .words {
                Toggle(isOn: voiceResponseBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Answer with your voice").font(.subheadline)
                        Text("Say your answer instead of tapping. Use phonetics for letters (“Bravo” for B); say words normally.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            VStack(spacing: 8) {
                Text("Where are you starting?")
                    .font(.subheadline).foregroundStyle(.secondary)
                Picker("Starting level", selection: proficiency) {
                    ForEach(Proficiency.allCases) { level in
                        Text(level.label).tag(level)
                    }
                }
                .pickerStyle(.menu)
            }

            VStack(spacing: 8) {
                Text("How long do you want to practice?")
                    .font(.subheadline).foregroundStyle(.secondary)
                Picker("Duration", selection: durationBinding) {
                    ForEach(PracticeDuration.allCases) { d in
                        Text(d.label).tag(d)
                    }
                }
                .pickerStyle(.menu)
            }

                }
                .padding(24)
            }

            Button {
                model.startSession()
                onStart()
            } label: {
                Text("Start Training")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 24)
            .padding(.bottom, 12)
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
                .clipShape(RoundedRectangle(cornerRadius: 28))
        } else {
            ZStack {
                Circle()
                    .strokeBorder(Theme.teal, lineWidth: 6)
                    .frame(width: 120, height: 120)
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 52))
                    .foregroundStyle(.white)
            }
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
    }
}

#Preview {
    IntroView(onStart: {}).environmentObject(AppModel())
}
