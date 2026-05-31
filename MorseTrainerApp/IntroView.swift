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

    var body: some View {
        VStack(spacing: 28) {
            Spacer(minLength: 8)

            VStack(spacing: 12) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 64))
                    .foregroundStyle(.blue)
                Text("Morse Trainer")
                    .font(.largeTitle).bold()
                Text("Learn Morse code by ear — the proven Koch method.")
                    .font(.headline)
                    .foregroundStyle(.secondary)
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

            Spacer(minLength: 8)

            Button {
                model.startSession()
                onStart()
            } label: {
                Text("Start Training")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(24)
    }

    private func howItWorks(icon: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(.blue)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

#Preview {
    IntroView(onStart: {}).environmentObject(AppModel())
}
