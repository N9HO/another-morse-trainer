import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false
    @State private var copiedDiagnostics = false

    /// The adapter's keyer mode (issue #43). Stored under the repeater's key
    /// because it is one fact about the operator's hardware, not a per-screen
    /// preference: `RepeaterModel` and `SendingKeyer` both read it, and each
    /// asserts it on the adapter when it wakes it. Until it was settable here,
    /// only the Vail screen could change it — so a paddle configured anywhere
    /// else was overwritten with Straight Key the moment practice started.
    @AppStorage(RepeaterModel.keyerModeDefaultsKey) private var adapterKeyerMode: Int =
        MIDIOutput.KeyerMode.straightKey.rawValue
    /// Holds an adapter output for as long as this sheet is up, so a mode or
    /// speed picked here reaches a connected adapter at once — from the intro,
    /// where no drill is underneath to push it, as well as mid-session. Woken
    /// on the first change; released with the sheet. See `AdapterSettingsSync`.
    @StateObject private var adapterSync = AdapterSettingsSync()

    private func syncAdapter() {
        adapterSync.apply(keyerMode: adapterKeyerMode,
                          wpm: model.settings.wpm,
                          toneHz: model.settings.toneFrequency)
    }

    /// When opened from inside a session, the running mode: sections that only
    /// matter to *other* modes are hidden, so Q-Codes practice never scrolls
    /// past the QSO Simulator's knobs (issue #66). nil — the intro's app-wide
    /// entry — shows the full surface.
    var activeMode: TrainingMode? = nil

    /// Modes drawing from the progressive character ladder (proficiency,
    /// punctuation opt-ins, and the stage preview shape their drills).
    private static let ladderModes: Set<TrainingMode> = [.characters, .sending, .confusion]
    /// Where the proficiency answer bites: the ladder modes it seeds, and the
    /// Journey it unlocks as far as that answer reaches (#151).
    private static let proficiencyModes: Set<TrainingMode> = ladderModes.union([.journey])
    /// The modes drilling the shared Characters track, where a stage pin bites.
    private static let stagePinModes: Set<TrainingMode> = [.characters, .sending]
    /// The choice quizzes governed by the Learning section (recognition time
    /// and the answer-button count).
    private static let choiceQuizModes: Set<TrainingMode> =
        [.journey, .characters, .words, .abbreviations, .qCodes, .prosigns, .confusion]
    /// The pileup surfaces all four QSO sections configure.
    private static let pileupModes: Set<TrainingMode> = [.qso, .contest]
    /// The surfaces a hardware key can drive — every mode `usesKeyingResponse`
    /// answers yes for, since all of them route through `SendingKeyerView` and
    /// its `SendingKeyer`, which wakes the adapter. (The Vail repeater carries
    /// its own copy of this control.)
    private static let hardwareKeyModes: Set<TrainingMode> =
        [.sending, .characters, .words, .abbreviations, .qCodes, .prosigns,
         .confusion, .rapidFire, .invaders]
    /// Modes with a play → answer → reveal loop the Feedback section controls.
    private static let feedbackModes: Set<TrainingMode> =
        [.journey, .characters, .words, .abbreviations, .qCodes, .prosigns,
         .headCopy, .typed, .sending, .confusion, .qrq, .rapidFire]

    /// Whether a section that only matters for `modes` belongs on this surface.
    private func shown(for modes: Set<TrainingMode>) -> Bool {
        guard let activeMode else { return true }
        return modes.contains(activeMode)
    }

    /// The global Speed slider is a no-op where the mode fixes its own speed
    /// (QRQ's 35/40, the exam's 5/13/20); it still matters in the pileup modes,
    /// which key *your* transmissions at it.
    private var showsGlobalSpeed: Bool {
        activeMode != .qrq && activeMode != .exam
    }

    /// Farnsworth stretching applies to the standard drills; the pileup modes
    /// carry their own toggle and the fixed-format speeds ignore it.
    private var showsFarnsworth: Bool {
        guard let activeMode else { return true }
        return !(Self.pileupModes.contains(activeMode) || activeMode == .qrq || activeMode == .exam)
    }

    var body: some View {
        NavigationStack {
            Form {
                if shown(for: Self.pileupModes) {
                    Section {
                        HStack {
                            Text("Your callsign")
                            Spacer()
                            TextField("W1AW", text: $model.settings.qso.myCall)
                                .multilineTextAlignment(.trailing)
                                .textInputAutocapitalization(.characters)
                                .autocorrectionDisabled()
                                .font(.system(.body, design: .monospaced))
                        }
                    } header: {
                        Text("Your Station")
                    } footer: {
                        Text("Used across the app — sent when you call CQ and work stations in the QSO Simulator.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                Section("Sound") {
                    sliderRow(title: "Side tone",
                              value: $model.settings.toneFrequency,
                              range: 300...1000, step: 10,
                              format: { "\(Int($0)) Hz" })
                    Button {
                        model.replay()
                    } label: {
                        Label("Preview tone", systemImage: "speaker.wave.2.fill")
                    }
                    Toggle("Keep Bluetooth audio awake", isOn: $model.settings.bluetoothKeepAlive)
                    Label {
                        Text("Plays a floor deliberately just above silence — too quiet to hear — so Bluetooth earbuds never idle between transmissions: truly silent audio lets some headsets sleep, and they wake a moment late and clip the first character.")
                    } icon: {
                        Image(systemName: "airpods")
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    Picker("Band noise", selection: $model.settings.bandNoise) {
                        ForEach(BackgroundNoiseLevel.bandLevels) { Text($0.label).tag($0) }
                    }
                    Label {
                        Text("Adds audible band noise (QRN) under everything so practising is more like copying off the air; any level also keeps Bluetooth audio awake.")
                    } icon: {
                        Image(systemName: "waveform")
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    if showsGlobalSpeed {
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
                }
                .listRowBackground(Theme.navyElevated)

                if showsFarnsworth {
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
                }

                if shown(for: Self.proficiencyModes) {
                    Section {
                        Picker("I already know…", selection: proficiencyBinding) {
                            ForEach(Proficiency.allCases) { level in
                                Text(level.label).tag(level)
                            }
                        }
                    } header: {
                        Text("Proficiency")
                    } footer: {
                        Text("How much Morse you already know — sets where the Characters drill begins and unlocks the Journey that far. Changing this restarts your active set.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                if shown(for: [.characters]) {
                    Section {
                        Toggle("Introduce new characters", isOn: $model.settings.introduceNewCharacters)
                    } header: {
                        Text("New characters")
                    } footer: {
                        Text("Before a character or prosign joins the drill for the first time, show it on its own with its sound and a Replay button.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                // The way back from words to characters (#95). The track grows
                // singles → pairs → triples → words on its own, and until now
                // the only hold on it mid-session was the Developer jump below,
                // which clears the pin and widens the active set. The pin lives
                // on the setup sheet too; here it is one gear-tap from the drill.
                if shown(for: Self.stagePinModes) {
                    Section {
                        Picker("Track stage", selection: stagePinBinding) {
                            Text("Auto — grow as you improve")
                                .tag(nil as ProgressiveCharacters.Stage?)
                            ForEach(ProgressiveCharacters.Stage.allCases, id: \.self) { stage in
                                Text(stage.displayName).tag(Optional(stage))
                            }
                        }
                        if let previous = previousStage {
                            Button {
                                model.setCharacterStagePin(previous)
                            } label: {
                                Label("Back to \(previous.displayName)", systemImage: "arrow.uturn.backward")
                            }
                        }
                    } header: {
                        Text("Track stage")
                    } footer: {
                        Text(model.characterStageNote + " Takes effect on the next item.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                if shown(for: Self.choiceQuizModes) {
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
                }

                Section {
                    Toggle("Daily reminder", isOn: Binding(
                        get: { model.settings.dailyReminderEnabled },
                        set: { model.setDailyReminder(enabled: $0) }
                    ))
                    if model.settings.dailyReminderEnabled {
                        DatePicker("Remind me at",
                                   selection: reminderTimeBinding,
                                   displayedComponents: .hourAndMinute)
                    }
                } header: {
                    Text("Reminders")
                } footer: {
                    Text("A gentle daily nudge to practice so your streak stays alive. You can change this anytime in iOS Settings → Notifications.")
                }
                .listRowBackground(Theme.navyElevated)

                if shown(for: Self.ladderModes) {
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
                }

                if shown(for: Self.feedbackModes) {
                    Section("Feedback") {
                        Toggle("Show right / wrong", isOn: $model.settings.showCorrectness)
                        Picker("Reveal the letter", selection: $model.settings.reveal) {
                            ForEach(RevealMode.allCases) { mode in
                                Text(mode.label).tag(mode)
                            }
                        }
                        Toggle("Show replay button", isOn: $model.settings.allowReplay)
                        Toggle("Haptic feedback", isOn: $model.settings.hapticsEnabled)
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                if shown(for: Self.hardwareKeyModes) {
                    Section {
                        Picker("Keyer mode", selection: $adapterKeyerMode) {
                            ForEach(MIDIOutput.KeyerMode.allCases, id: \.rawValue) { mode in
                                Text(mode.displayName).tag(mode.rawValue)
                            }
                        }
                        // Push every change to a connected adapter as it is
                        // made: from the intro there is no drill underneath
                        // holding an output, and mid-session the drill's own
                        // push is diff-based, so the overlap is harmless.
                        .onChange(of: adapterKeyerMode) { _ in syncAdapter() }
                        .onChange(of: model.settings.wpm) { _ in syncAdapter() }
                        .onChange(of: model.settings.toneFrequency) { _ in syncAdapter() }
                        if (MIDIOutput.KeyerMode(rawValue: adapterKeyerMode) ?? .straightKey).adapterTimesSending {
                            Text("The adapter times the sending in this mode, at the speed you're practising at.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    } header: {
                        Text("Hardware key")
                    } footer: {
                        Text("How the Vail Adapter should read your key. Straight Key is the default; pick an iambic mode for a paddle. This describes your key rather than a drill, so it applies in Sending Practice and on the Vail screen alike.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                Section {
                    Toggle("Slashed zero", isOn: $model.settings.slashedZero)
                } header: {
                    Text("Display")
                } footer: {
                    Text("Show the digit 0 with a line through it — the operator's convention for telling 0 from O.")
                }
                .listRowBackground(Theme.navyElevated)

                if shown(for: [.headCopy]) {
                    Section {
                        Stepper(value: $model.settings.headCopyRepeats,
                                in: AppSettings.headCopyRepeatRange) {
                            HStack {
                                Text("Auto-repeats")
                                Spacer()
                                Text(model.settings.headCopyRepeats == 0
                                     ? "Off"
                                     : "\(model.settings.headCopyRepeats)×")
                                    .foregroundStyle(.secondary)
                                    .monospacedDigit()
                            }
                        }
                        sliderRow(title: "Auto-reveal after",
                                  value: $model.settings.headCopyRevealSeconds,
                                  range: AppSettings.headCopyRevealRange, step: 1,
                                  format: { $0 < 1 ? "Manual only" : "\(Int($0)) s" })
                    } header: {
                        Text("Head Copy")
                    } footer: {
                        Text("After the prompt plays, Head Copy can replay it a few times so you can re-hear it without mentally replaying, then count down to the answer. A manual Repeat button is always available.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                if shown(for: Self.pileupModes) {
                    Section {
                        Picker("Mode", selection: $model.settings.qso.mode) {
                            ForEach(QSOContestMode.allCases) { Text($0.label).tag($0) }
                        }
                        if model.settings.qso.mode.isPileup {
                            Stepper(value: $model.settings.qso.maxStations, in: 1...8) {
                                Text("Max callers: \(model.settings.qso.maxStations)")
                            }
                        }
                        // Same 60 WPM ceiling as the global character speed, so
                        // QRQ practice carries into the QSO simulator (issue #79).
                        sliderRow(title: "Min speed", value: $model.settings.qso.minWPM,
                                  range: 12...60, step: 1, format: { "\(Int($0)) WPM" })
                        sliderRow(title: "Max speed", value: $model.settings.qso.maxWPM,
                                  range: 12...60, step: 1, format: { "\(Int($0)) WPM" })
                        Toggle("Farnsworth spacing", isOn: $model.settings.qso.farnsworth)
                        sliderRow(title: "Tone spread", value: $model.settings.qso.toneSpread,
                                  range: 0...500, step: 10,
                                  format: { $0 < 10 ? "Zero-beat" : "±\(Int($0)) Hz" })
                    } header: {
                        Text("QSO Simulator")
                    } footer: {
                        Text("Max callers thins a pileup at once; the other settings reach callers as they arrive. Tone spread splits callers across the band; zero-beat stacks them all on your pitch.")
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
                        // Only exchanges that actually carry a signal report (POTA,
                        // Basic Contest, Single Caller) can be asked to copy it — the
                        // contest sprints send no RST, so the toggle would be a no-op.
                        if model.settings.qso.mode.includesRST {
                            Toggle("Copy RST too", isOn: $model.settings.qso.rstRequired)
                        }
                        Toggle("Keep partial call in box", isOn: $model.settings.qso.keepPartialCall)
                        Toggle("Key my side in Morse", isOn: $model.settings.qso.keyMySide)
                        Toggle("Pileup re-calls after TU", isOn: $model.settings.qso.autoRecall)
                        Picker("On a busted call", selection: $model.settings.qso.bustBehavior) {
                            ForEach(BustBehavior.allCases) { Text($0.label).tag($0) }
                        }
                        Toggle("Callers can give up", isOn: $model.settings.qso.giveUpEnabled)
                        if model.settings.qso.giveUpEnabled {
                            Picker("Tell me who got away", selection: $model.settings.qso.missedCallerFeedback) {
                                ForEach(MissedCallerFeedback.allCases) { Text($0.label).tag($0) }
                            }
                        }
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
                        Text("Keep partial call: a partly-copied call stays in the box so you can send “?” and add to it instead of retyping. Key my side: your CQ, calls and TU go out in Morse at your tone and speed before the stations reply; off, they are logged silently. Re-calls after TU: the stations still waiting call again on their own once you log a contact; off, send AGN or CQ yourself. Give-up: a station you keep busting drops out after a few misses, but the pileup continues — “Tell me who got away” then names the call you lost and what you had it as, either as it happens or in the end-of-run summary. Cut numbers send numerals as letters (0→T, 9→N) — you can type either form.")
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
                }

                if shown(for: Self.stagePinModes) {
                    Section {
                        ForEach(ProgressiveCharacters.Stage.allCases, id: \.self) { stage in
                            Button {
                                model.previewStage(stage)
                                dismiss()
                            } label: {
                                HStack {
                                    Text(stage.displayName)
                                    if model.characterStage == stage {
                                        Image(systemName: "checkmark")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(Theme.tealBright)
                                    }
                                    Spacer()
                                    Image(systemName: "play.circle")
                                }
                            }
                        }
                    } header: {
                        Text("Developer · Preview Stage")
                    } footer: {
                        Text("Jumps the Characters track to a stage for testing (✓ is where the track is now). Stages beyond Characters expand your active set to all letters & numbers, and a jump clears any hold. To hold the track at a stage during normal practice, use Track stage above.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                Section {
                    Button {
                        UIPasteboard.general.string = diagnosticInfo()
                        copiedDiagnostics = true
                        Haptics.success()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            copiedDiagnostics = false
                        }
                    } label: {
                        Label(copiedDiagnostics ? "Copied to clipboard" : "Copy diagnostic info",
                              systemImage: copiedDiagnostics ? "checkmark.circle" : "doc.on.doc")
                    }
                } header: {
                    Text("Bug reports")
                } footer: {
                    Text("Copies your app/iOS version, device, and current settings to the clipboard to paste into a bug report.")
                }
                .listRowBackground(Theme.navyElevated)

                // Mid-session the destructive reset stays out of reach — it
                // would yank the engine out from under the running drill. Only
                // from the intro's app-wide entry (Android parity).
                if activeMode == nil {
                    Section {
                        Button(role: .destructive) {
                            confirmReset = true
                        } label: {
                            Label("Reset all progress", systemImage: "trash")
                        }
                    }
                    .listRowBackground(Theme.navyElevated)
                }
            }
            .scrollContentBackground(.hidden)
            .readableWidth()
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

    /// The Characters track's learner-chosen stage hold (nil = automatic).
    private var stagePinBinding: Binding<ProgressiveCharacters.Stage?> {
        Binding(
            get: { model.characterStagePin },
            set: { model.setCharacterStagePin($0) }
        )
    }

    /// The stage before the one the track is at — what "go back a step" means
    /// here. Nil at the first stage, where there is nowhere back to go.
    private var previousStage: ProgressiveCharacters.Stage? {
        let stages = ProgressiveCharacters.Stage.allCases
        guard let i = stages.firstIndex(of: model.characterStage), i > 0 else { return nil }
        return stages[i - 1]
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

    /// The reminder time as a Date for the hour-and-minute picker, routed
    /// through the model so a change reschedules the pending notification.
    private var reminderTimeBinding: Binding<Date> {
        Binding(
            get: {
                var c = DateComponents()
                c.hour = model.settings.dailyReminderHour
                c.minute = model.settings.dailyReminderMinute
                return Calendar.current.date(from: c) ?? Date()
            },
            set: { date in
                let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                model.setDailyReminderTime(hour: c.hour ?? 19, minute: c.minute ?? 0)
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

    // MARK: - Diagnostics (issue #31)

    /// A compact, copy-pasteable snapshot for bug reports: build, OS, device,
    /// and the settings most likely to matter when reproducing an issue.
    private func diagnosticInfo() -> String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        let device = UIDevice.current
        let s = model.settings

        var lines = [
            "AMT \(version) (build \(build))",
            "\(device.systemName) \(device.systemVersion) · \(Self.deviceModelIdentifier())",
            "Mode: \(model.learningMode.title)",
            "WPM: \(Int(s.wpm))" + (s.farnsworth ? " · Farnsworth \(Int(s.effectiveWpm))" : ""),
            "Tone: \(Int(s.toneFrequency)) Hz",
        ]
        switch model.learningMode {
        case .words:
            lines.append(s.customWordsActive
                ? "Word pool: custom (\(s.customWords.count) words)"
                : "Word pool: \(s.wordTier.label)")
        case .qrq:
            lines.append("QRQ speed: \(s.qrqSpeed.label)")
        case .exam:
            lines.append("Exam: \(s.examSpeed.label) · \(s.examGrading.label)")
        case .listen:
            lines.append("Listen: \(s.listenContent.label) · \(s.listenGap.label)")
        case .rapidFire:
            lines.append("Rapid Fire: \(s.rapidFire.content.label) · \(s.rapidFire.response.label) · \(s.rapidFire.pace.label)")
        default:
            break
        }
        lines.append("Bluetooth keep-alive: \(s.bluetoothKeepAlive ? "on" : "off")")
        if s.bandNoise != .off {
            lines.append("Band noise: \(s.bandNoise.label)")
        }
        if !s.selectedPunctuation.isEmpty {
            lines.append("Punctuation: \(s.selectedPunctuation.sorted().joined())")
        }
        return lines.joined(separator: "\n")
    }

    /// Hardware identifier (e.g. "iPhone16,2"), falling back to the generic model.
    /// In the simulator `uname` returns the host arch, so prefer the simulated
    /// device the env exposes.
    private static func deviceModelIdentifier() -> String {
        if let simModel = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simModel
        }
        var sys = utsname()
        uname(&sys)
        let id = withUnsafeBytes(of: &sys.machine) { raw -> String in
            let bytes = raw.prefix { $0 != 0 }
            return String(decoding: bytes, as: UTF8.self)
        }
        return id.isEmpty ? UIDevice.current.model : id
    }
}

#Preview {
    SettingsView().environmentObject(AppModel())
}
