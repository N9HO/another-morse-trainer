import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Sound") {
                    sliderRow(title: "Tone pitch",
                              value: $model.settings.toneFrequency,
                              range: 300...1000, step: 10,
                              format: { "\(Int($0)) Hz" })
                    Button {
                        model.replay()
                    } label: {
                        Label("Preview tone", systemImage: "speaker.wave.2.fill")
                    }
                    sliderRow(title: "Speed",
                              value: $model.settings.wpm,
                              range: 15...40, step: 1,
                              format: { "\(Int($0)) WPM" })
                    if model.settings.wpm < 33 {
                        Label {
                            Text("Below 33 WPM it's easy to start *counting* the dits and dahs instead of hearing each character as a single sound. Training at 33+ WPM builds instant, by-ear recognition — the whole point of the Koch method. If you need more time to answer, raise “Recognize within” instead of slowing the code.")
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill")
                        }
                        .font(.footnote)
                        .foregroundStyle(.orange)
                    }
                }

                Section {
                    Picker("I already know…", selection: proficiencyBinding) {
                        ForEach(Proficiency.allCases) { level in
                            Text(level.label).tag(level)
                        }
                    }
                } header: {
                    Text("Proficiency")
                } footer: {
                    Text("Sets which characters you start with. Changing this restarts your active set.")
                }

                Section {
                    sliderRow(title: "Recognize within",
                              value: $model.settings.ttrThreshold,
                              range: 0.5...3.0, step: 0.1,
                              format: { String(format: "%.1f s", $0) })
                    Toggle("Distractors from whole alphabet",
                           isOn: $model.settings.distractorsFromFullAlphabet)
                } header: {
                    Text("Learning")
                } footer: {
                    Text("When you consistently recognize a letter within this time, a new letter is added.")
                }

                Section {
                    ForEach(AppSettings.availablePunctuation, id: \.symbol) { entry in
                        Toggle(isOn: punctuationBinding(entry.symbol)) {
                            HStack {
                                Text(entry.name)
                                Text(entry.symbol)
                                    .font(.system(.body, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Text(MorseCode.pattern(for: Character(entry.symbol)) ?? "")
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Punctuation")
                } footer: {
                    Text("“?” is already part of the base letters & numbers. Turn on any of these extras to mix them into your practice.")
                }

                Section("Feedback") {
                    Toggle("Show right / wrong", isOn: $model.settings.showCorrectness)
                    Picker("Reveal the letter", selection: $model.settings.reveal) {
                        ForEach(RevealMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    Toggle("Show replay button", isOn: $model.settings.allowReplay)
                }

                Section {
                    ForEach(ProgressiveCharacters.Stage.allCases, id: \.self) { stage in
                        Button {
                            model.previewStage(stage)
                            dismiss()
                        } label: {
                            HStack {
                                Text(stage.displayName)
                                Spacer()
                                Image(systemName: "play.circle")
                            }
                        }
                    }
                } header: {
                    Text("Developer · Preview Stage")
                } footer: {
                    Text("Jumps the Characters track to a stage for testing. Stages beyond Characters expand your active set to all letters & numbers.")
                }

                Section {
                    Button(role: .destructive) {
                        confirmReset = true
                    } label: {
                        Label("Reset all progress", systemImage: "trash")
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .confirmationDialog("Reset all progress?",
                                isPresented: $confirmReset, titleVisibility: .visible) {
                Button("Reset", role: .destructive) {
                    model.resetProgress()
                    dismiss()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This clears your learned letters and stats. Settings are kept.")
            }
        }
    }

    /// Changing proficiency must reconfigure the engine, so route it through
    /// the model rather than binding straight to the stored setting.
    private var proficiencyBinding: Binding<Proficiency> {
        Binding(
            get: { model.settings.proficiency },
            set: { model.setProficiency($0) }
        )
    }

    /// A toggle binding for one optional punctuation symbol.
    private func punctuationBinding(_ symbol: String) -> Binding<Bool> {
        Binding(
            get: { model.settings.selectedPunctuation.contains(symbol) },
            set: { isOn in
                if isOn { model.settings.selectedPunctuation.insert(symbol) }
                else { model.settings.selectedPunctuation.remove(symbol) }
            }
        )
    }

    private func sliderRow(title: String,
                           value: Binding<Double>,
                           range: ClosedRange<Double>,
                           step: Double,
                           format: @escaping (Double) -> String) -> some View {
        VStack(alignment: .leading) {
            HStack {
                Text(title)
                Spacer()
                Text(format(value.wrappedValue))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: value, in: range, step: step)
        }
    }
}

#Preview {
    SettingsView().environmentObject(AppModel())
}
