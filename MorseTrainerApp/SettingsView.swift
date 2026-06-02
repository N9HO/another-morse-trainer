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
                              range: 15...60, step: 1,
                              format: { "\(Int($0)) WPM" })
                    if model.settings.wpm >= 40 {
                        Label {
                            Text("QRQ territory — \(Int(model.settings.wpm)) WPM. Great for pushing instant recognition once 30+ feels comfortable.")
                        } icon: {
                            Image(systemName: "hare.fill")
                        }
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    }
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
                .listRowBackground(Theme.navyElevated)

                Section {
                    Toggle("Farnsworth spacing", isOn: $model.settings.farnsworth)
                    if model.settings.farnsworth {
                        sliderRow(title: "Effective speed",
                                  value: $model.settings.effectiveWpm,
                                  range: 8...max(9, model.settings.wpm), step: 1,
                                  format: { "\(Int($0)) WPM" })
                    }
                } header: {
                    Text("Farnsworth (multi-character)")
                } footer: {
                    Text("Keeps each character at full speed but adds extra space between characters, so you have time to recognize them. Applies to words, groups, and other multi-character content — single characters are unaffected.")
                }
                .listRowBackground(Theme.navyElevated)

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
                .listRowBackground(Theme.navyElevated)

                Section {
                    sliderRow(title: "Recognize within",
                              value: $model.settings.ttrThreshold,
                              range: 0.5...3.0, step: 0.1,
                              format: { String(format: "%.1f s", $0) })
                    Stepper(value: $model.settings.maxAnswerChoices,
                            in: AppSettings.answerChoiceRange) {
                        HStack {
                            Text("Answer choices")
                            Spacer()
                            Text("\(model.settings.maxAnswerChoices)")
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                    }
                } header: {
                    Text("Learning")
                } footer: {
                    Text("When you consistently recognize a letter within this time, a new letter is added. Answer choices only ever include characters you've already met — the number of buttons grows as you learn, up to this many.")
                }
                .listRowBackground(Theme.navyElevated)

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
                .listRowBackground(Theme.navyElevated)

                Section("Feedback") {
                    Toggle("Show right / wrong", isOn: $model.settings.showCorrectness)
                    Picker("Reveal the letter", selection: $model.settings.reveal) {
                        ForEach(RevealMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    Toggle("Show replay button", isOn: $model.settings.allowReplay)
                }
                .listRowBackground(Theme.navyElevated)

                Section {
                    Picker("Mode", selection: $model.settings.qso.mode) {
                        ForEach(QSOContestMode.allCases) { Text($0.label).tag($0) }
                    }
                    if model.settings.qso.mode.isPileup {
                        Stepper(value: $model.settings.qso.maxStations, in: 1...8) {
                            Text("Max callers: \(model.settings.qso.maxStations)")
                        }
                    }
                    sliderRow(title: "Min speed", value: $model.settings.qso.minWPM,
                              range: 12...45, step: 1, format: { "\(Int($0)) WPM" })
                    sliderRow(title: "Max speed", value: $model.settings.qso.maxWPM,
                              range: 12...45, step: 1, format: { "\(Int($0)) WPM" })
                    Toggle("Farnsworth spacing", isOn: $model.settings.qso.farnsworth)
                    sliderRow(title: "Tone spread", value: $model.settings.qso.toneSpread,
                              range: 0...500, step: 10,
                              format: { $0 < 10 ? "Zero-beat" : "±\(Int($0)) Hz" })
                } header: {
                    Text("QSO Simulator")
                } footer: {
                    Text("Changes apply to your next QSO session. Tone spread splits callers across the band; zero-beat stacks them all on your pitch.")
                }
                .listRowBackground(Theme.navyElevated)

                Section("QSO · Signals") {
                    Toggle("QSB (fading)", isOn: $model.settings.qso.qsbEnabled)
                    Picker("QRN (noise)", selection: $model.settings.qso.qrn) {
                        ForEach(QRNLevel.allCases) { Text($0.label).tag($0) }
                    }
                    sliderRow(title: "Min wait", value: $model.settings.qso.minDelay,
                              range: 0...3, step: 0.1, format: { String(format: "%.1f s", $0) })
                    sliderRow(title: "Max wait", value: $model.settings.qso.maxDelay,
                              range: 0...4, step: 0.1, format: { String(format: "%.1f s", $0) })
                }
                .listRowBackground(Theme.navyElevated)

                Section {
                    Toggle("Copy RST too", isOn: $model.settings.qso.rstRequired)
                    Picker("On a busted call", selection: $model.settings.qso.bustBehavior) {
                        ForEach(BustBehavior.allCases) { Text($0.label).tag($0) }
                    }
                    Toggle("Callers can give up", isOn: $model.settings.qso.giveUpEnabled)
                    Toggle("Cut numbers", isOn: $model.settings.qso.cutNumbersEnabled)
                    if model.settings.qso.cutNumbersEnabled {
                        ForEach(CutNumbers.cuttableDigits, id: \.self) { d in
                            Toggle("\(d) → \(CutNumbers.map[d].map(String.init) ?? "")",
                                   isOn: cutBinding(d))
                        }
                    }
                } header: {
                    Text("QSO · Realism")
                } footer: {
                    Text("Give-up: a station you keep busting drops out after a few misses, but the pileup continues. Cut numbers send numerals as letters (0→T, 9→N) — you can type either form.")
                }
                .listRowBackground(Theme.navyElevated)

                Section {
                    Toggle("US callsigns only", isOn: $model.settings.qso.usOnly)
                    ForEach(CallsignFormat.allCases) { f in
                        Toggle(f.label, isOn: formatBinding(f))
                    }
                } header: {
                    Text("QSO · Callsigns")
                } footer: {
                    Text("Which callsign shapes appear in pileups. Turn off US-only to mix in DX prefixes.")
                }
                .listRowBackground(Theme.navyElevated)

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
                .listRowBackground(Theme.navyElevated)

                Section {
                    Button(role: .destructive) {
                        confirmReset = true
                    } label: {
                        Label("Reset all progress", systemImage: "trash")
                    }
                }
                .listRowBackground(Theme.navyElevated)
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
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

    /// A toggle binding for one cut-number digit.
    private func cutBinding(_ digit: Character) -> Binding<Bool> {
        let key = String(digit)
        return Binding(
            get: { model.settings.qso.cutDigits.contains(key) },
            set: { isOn in
                if isOn { model.settings.qso.cutDigits.insert(key) }
                else { model.settings.qso.cutDigits.remove(key) }
            }
        )
    }

    /// A toggle binding for one callsign format.
    private func formatBinding(_ format: CallsignFormat) -> Binding<Bool> {
        Binding(
            get: { model.settings.qso.formats.contains(format) },
            set: { isOn in
                if isOn { model.settings.qso.formats.insert(format) }
                else if model.settings.qso.formats.count > 1 { model.settings.qso.formats.remove(format) }
            }
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
