import SwiftUI

/// **First Four** (#265, docs/first-four-design.md): just enough CW to hunt
/// one POTA activator. Your call, your state, "?" and "73" — heard and
/// typed, then keyed — and three short scenes: a busted call, a call nobody
/// answers, and a whole contact. The rules are MorseKit's `FirstFour`; this
/// screen only sounds, shows and records them. Twin of the Kotlin
/// `FirstFourScreen`.
///
/// A tutorial, not a session: no session record, no leaderboard. A graded
/// answer counts as practice for the day.
struct FirstFourView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var progress = FirstFourStore.load()
    @State private var openStage: FirstFourStage?
    @State private var editingStation = false
    @State private var callField = ""
    @State private var stateField = ""
    @State private var confirmingReset = false

    private var call: String { FirstFour.normalizeCall(model.settings.qso.myCall) }
    private var state: String { FirstFour.normalizeState(model.settings.qso.myState) }
    private var stationReady: Bool {
        !FirstFour.prefillCall(saved: call).isEmpty && FirstFour.isValidCall(call) && FirstFour.isValidState(state)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let stage = openStage {
                        stageScreen(stage)
                    } else if !stationReady || editingStation {
                        stationCard
                    } else {
                        home
                    }
                }
                .padding()
                .readableWidth()
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
            .navigationTitle(openStage.map(FirstFourView.title) ?? "First Four")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if openStage != nil {
                        Button {
                            model.stopFirstFour()
                            openStage = nil
                        } label: {
                            Label("Stages", systemImage: "chevron.left")
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear {
                callField = FirstFour.prefillCall(saved: model.settings.qso.myCall)
                stateField = state
            }
            .onDisappear { model.stopFirstFour() }
            .onChange(of: progress) { FirstFourStore.save($0) }
            .alert("Start First Four over?", isPresented: $confirmingReset) {
                Button("Start over", role: .destructive) {
                    progress = FirstFourProgress()
                    FirstFourStore.reset()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Every stage goes back to not passed. Your callsign and state stay as they are.")
            }
        }
    }

    // MARK: - Stage titles

    static func title(_ stage: FirstFourStage) -> String {
        switch stage {
        case .call:         return "Your call"
        case .state:        return "Your state"
        case .question:     return "? — say again"
        case .seventyThree: return "73 — best regards"
        case .bustedCall:   return "Busted call"
        case .noReply:      return "No reply"
        case .walkthrough:  return "The whole contact"
        }
    }

    private func subtitle(_ stage: FirstFourStage) -> String {
        switch stage {
        case .call:         return "\(call) — hear it, then send it"
        case .state:        return "\(state) — hear it, then send it"
        case .question:     return "Hear it, then send it"
        case .seventyThree: return "Hear it, then send it"
        case .bustedCall:   return "They catch only part of your call"
        case .noReply:      return "When nobody answers you"
        case .walkthrough:  return "A POTA contact, start to finish"
        }
    }

    // MARK: - Your station

    private var stationCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Just enough CW for your first POTA contact")
                .font(.title3.weight(.semibold))
            Text("Hunting a Parks on the Air activator takes four things: your callsign, your state, ? and 73. First Four teaches you to hear and send each one, then walks you through the contact itself.")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 6) {
                Text("Your callsign").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                TextField("N9HO", text: $callField)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(.title3, design: .monospaced))
                    .padding(12)
                    .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                Text("Your state or province").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                    .padding(.top, 6)
                TextField("WI", text: $stateField)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(.title3, design: .monospaced))
                    .padding(12)
                    .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            if !callField.isEmpty && !FirstFour.isValidCall(callField) {
                Text("A callsign is 3 to 10 letters and digits (and /), with at least one of each.")
                    .font(.caption).foregroundStyle(.orange)
            }
            if !stateField.isEmpty && !FirstFour.isValidState(stateField) {
                Text("Use the 2- or 3-letter abbreviation, like WI or ON.")
                    .font(.caption).foregroundStyle(.orange)
            }
            Text("Saved to Settings › QSO & Pileups, where the rest of the app reads them too.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
            Button {
                Haptics.tap()
                model.saveFirstFourStation(call: callField, state: stateField)
                editingStation = false
            } label: {
                Text(editingStation ? "Save" : "Start")
                    .font(.headline)
                    .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            .disabled(!FirstFour.isValidCall(callField) || !FirstFour.isValidState(stateField))
        }
        .padding(18)
        .brandCard()
    }

    // MARK: - Stage list

    private var home: some View {
        VStack(spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(call) · \(state)")
                        .font(Theme.copyFont(size: 22, weight: .semibold, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                    Text("\(progress.passedCount) of \(FirstFourStage.allCases.count) stages passed")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Button("Change") {
                    callField = call
                    stateField = state
                    editingStation = true
                }
                .font(.subheadline)
            }

            if progress.isComplete { FirstFourFinale() }

            if let next = progress.nextStage {
                Button {
                    Haptics.selection()
                    openStage = next
                } label: {
                    Label(progress.passedCount == 0 ? "Begin: \(FirstFourView.title(next))"
                                                    : "Continue: \(FirstFourView.title(next))",
                          systemImage: "play.fill")
                        .font(.headline)
                        .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
            }

            VStack(spacing: 10) {
                ForEach(Array(FirstFourStage.allCases.enumerated()), id: \.element) { i, stage in
                    stageRow(number: i + 1, stage: stage)
                }
            }

            Text("Everything plays at your own speed (\(Int(model.settings.wpm)) WPM) — change it in Settings. Any stage can be opened at any time.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)

            if progress.passedCount > 0 {
                Button("Start over", role: .destructive) { confirmingReset = true }
                    .font(.subheadline)
            }
        }
    }

    private func stageRow(number: Int, stage: FirstFourStage) -> some View {
        let passed = progress.hasPassed(stage)
        return Button {
            Haptics.selection()
            openStage = stage
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(passed ? Theme.teal : Theme.navyRaised).frame(width: 32, height: 32)
                    if passed {
                        Image(systemName: "checkmark").font(.subheadline.weight(.bold)).foregroundStyle(Theme.navy)
                    } else {
                        Text("\(number)").font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(FirstFourView.title(stage)).font(.subheadline.weight(.semibold))
                    Text(subtitle(stage)).font(.caption).foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Stage \(number), \(FirstFourView.title(stage))\(passed ? ", passed" : "")")
    }

    // MARK: - A stage

    @ViewBuilder
    private func stageScreen(_ stage: FirstFourStage) -> some View {
        let next: () -> Void = {
            model.stopFirstFour()
            openStage = progress.nextStage
        }
        if let text = FirstFour.element(stage, call: call, state: state) {
            FirstFourElementStage(stage: stage, text: text, progress: $progress, onNext: next)
                .id(stage)
        } else {
            FirstFourSceneStage(stage: stage, call: call, state: state, progress: $progress, onNext: next)
                .id(stage)
        }
    }
}

// MARK: - The finale

/// Shown once the walkthrough has passed: ready for the air, and a nudge to
/// say thank you to the activator afterwards (#265).
struct FirstFourFinale: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("You're ready for your first POTA contact", systemImage: "antenna.radiowaves.left.and.right")
                .font(.headline)
                .foregroundStyle(Theme.tealBright)
            Text("Find an activator on a spotting page, listen until you have their call, and call them.")
                .font(.subheadline)
            Text("A first CW contact is a big deal — for you, and often for the activator too. Afterwards, consider following up: send a QSL card, drop them an email, or just a thank-you note. Many activators love hearing they were someone's first.")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.tealBright.opacity(0.7), lineWidth: 1.5))
    }
}

// MARK: - Element stages: copy, then send

private struct FirstFourElementStage: View {
    @EnvironmentObject var model: AppModel
    let stage: FirstFourStage
    let text: String
    @Binding var progress: FirstFourProgress
    let onNext: () -> Void

    @State private var phase: FirstFourPhase = .copy
    @State private var typed = ""
    @State private var feedback: (String, Bool)?
    @State private var stagePassed = false
    @State private var keyReset = 0
    @FocusState private var typing: Bool

    private var needed: Int { phase == .copy ? FirstFour.copyStreakToPass : FirstFour.sendStreakToPass }

    var body: some View {
        VStack(spacing: 16) {
            Text(explanation)
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)

            HStack {
                phasePill("Copy", active: phase == .copy, done: progress.copyPassed.contains(stage))
                phasePill("Send", active: phase == .send, done: progress.hasPassed(stage))
                Spacer()
                streakDots
            }

            if stagePassed {
                passedCard
            } else if phase == .copy {
                copyPanel
            } else {
                sendPanel
            }

            if let feedback {
                Text(feedback.0)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(feedback.1 ? Theme.tealBright : .orange)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
        }
        .onAppear {
            phase = progress.openingPhase(stage)
            if phase == .copy { play() }
        }
    }

    private var explanation: String {
        switch stage {
        case .call:
            return "Your call is the one thing you send in every contact — and the thing you most need to hear when it comes back to you."
        case .state:
            return "Hunters send their state as their part of a POTA exchange."
        case .question:
            return "? (di-di-dah-dah-di-dit) means “say again.” An activator sends it when they only caught part of a call."
        case .seventyThree:
            return "73 means best regards. It is how a contact ends."
        default:
            return ""
        }
    }

    private func phasePill(_ title: String, active: Bool, done: Bool) -> some View {
        HStack(spacing: 4) {
            if done { Image(systemName: "checkmark") }
            Text(title)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(active ? Theme.navy : .white)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(active ? Theme.teal : Theme.navyRaised, in: Capsule())
    }

    private var streakDots: some View {
        HStack(spacing: 5) {
            ForEach(0..<needed, id: \.self) { i in
                Circle()
                    .fill(i < progress.streak ? Theme.tealBright : Theme.navyRaised)
                    .frame(width: 10, height: 10)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(progress.streak) of \(needed) in a row")
    }

    private var copyPanel: some View {
        VStack(spacing: 12) {
            Button { play() } label: {
                Label("Play", systemImage: "play.fill")
                    .font(.headline)
                    .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            HStack {
                TextField("Type what you heard", text: $typed)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(.title3, design: .monospaced))
                    .focused($typing)
                    .submitLabel(.done)
                    .onSubmit(checkCopy)
                    .padding(12)
                    .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                Button("Check", action: checkCopy)
                    .buttonStyle(.bordered)
                    .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Text("\(needed) right in a row to move on to sending.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(14)
        .brandCard()
    }

    private var sendPanel: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("SEND").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                    Text(text)
                        .font(Theme.copyFont(size: 30, weight: .bold, monospaced: true, slashedZero: model.settings.slashedZero))
                }
                Spacer()
                Button { play() } label: { Label("Hear it", systemImage: "speaker.wave.2.fill") }
                    .buttonStyle(.bordered)
            }
            FirstFourKeyPanel(expected: text, resetToken: keyReset, wpm: model.settings.wpm,
                              toneHz: model.settings.toneFrequency, onSubmit: checkSend)
            Text("\(needed) right in a row to pass this stage.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(14)
        .brandCard()
    }

    private var passedCard: some View {
        VStack(spacing: 12) {
            Label("Stage passed", systemImage: "checkmark.seal.fill")
                .font(.headline)
                .foregroundStyle(Theme.tealBright)
            Button {
                Haptics.selection()
                onNext()
            } label: {
                Text(progress.nextStage.map { "Next: \(FirstFourView.title($0))" } ?? "Back to the stages")
                    .font(.headline)
                    .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            Button("Practise it again") {
                stagePassed = false
                feedback = nil
                phase = .copy
                play()
            }
            .font(.subheadline)
        }
        .padding(14)
        .brandCard()
    }

    private func play() {
        model.playFirstFour(text)
    }

    private func checkCopy() {
        guard !typed.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let correct = FirstFour.copyMatches(typed, expected: text)
        record(correct: correct, heard: typed)
        typed = ""
    }

    private func checkSend(_ sent: String) {
        let correct = FirstFour.sendMatches(sent, expected: text)
        record(correct: correct, heard: sent)
        keyReset += 1
    }

    private func record(correct: Bool, heard: String) {
        model.noteFirstFourPractice()
        let done = progress.recordElement(stage, phase: phase, correct: correct)
        if correct { Haptics.success() } else { Haptics.error() }
        if done && phase == .copy {
            feedback = ("Copied \(FirstFour.copyStreakToPass) in a row. Now send it.", true)
            phase = .send
        } else if done {
            feedback = nil
            stagePassed = true
        } else if correct {
            feedback = ("Right — \(progress.streak) of \(needed).", true)
            if phase == .copy {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { play() }
            }
        } else {
            let shown = FirstFour.compact(heard).isEmpty ? "nothing" : "“\(heard.uppercased())”"
            feedback = ("That was \(shown), not \(text). Try again.", false)
        }
    }
}

// MARK: - Scenes

private struct FirstFourSceneStage: View {
    @EnvironmentObject var model: AppModel
    let stage: FirstFourStage
    let call: String
    let state: String
    @Binding var progress: FirstFourProgress
    let onNext: () -> Void

    @State private var scene = FirstFourScene(beats: [])
    @State private var activator = FirstFour.activators[0]
    @State private var round = 0
    @State private var log: [(who: String, text: String)] = []
    @State private var heard = false
    @State private var typed = ""
    @State private var feedback: String?
    @State private var keyReset = 0
    @State private var result: (clean: Bool, passedNow: Bool)?
    @State private var playToken = 0

    var body: some View {
        VStack(spacing: 16) {
            Text(intro)
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)

            HStack {
                Text(counter).font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                Spacer()
                if progress.hasPassed(stage) {
                    Label("Passed", systemImage: "checkmark.seal.fill").font(.caption.weight(.semibold)).foregroundStyle(Theme.tealBright)
                }
            }

            transcript

            if let result {
                resultCard(result)
            } else if let beat = scene.current {
                beatPanel(beat)
            }

            if let feedback {
                Text(feedback)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
        }
        .onAppear {
            round = progress.cleanRuns(stage)
            startRun()
        }
    }

    private var intro: String {
        switch stage {
        case .bustedCall:
            return "Activators often catch only part of a call. When they send a piece of yours with a ?, send your full call again — then stop and wait for them to come back to you."
        case .noReply:
            return "Calling and hearing nothing back is normal: the activator may not hear you, or may be answering someone else. Here is what to do in each case."
        case .walkthrough:
            return "A whole minimal POTA contact from the hunter's side: they call CQ, you call them, they send your report and their state, you send yours and 73."
        default:
            return ""
        }
    }

    private var counter: String {
        let needed = FirstFour.cleanRunsToPass(stage) ?? 0
        let runs = min(progress.cleanRuns(stage), needed)
        switch stage {
        case .noReply:
            return "Scene \(FirstFour.noReplyUsesSceneB(cleanRuns: progress.cleanRuns(stage)) ? "B" : "A") · \(runs) of \(needed) clean"
        default:
            return "\(runs) of \(needed) clean runs"
        }
    }

    private var transcript: some View {
        VStack(alignment: .leading, spacing: 6) {
            if log.isEmpty {
                Text("Listen…").font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            ForEach(Array(log.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(line.who)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(line.who == "You" ? Theme.tealBright : Theme.textSecondary)
                        .frame(width: 64, alignment: .leading)
                    Text(line.text)
                        .font(Theme.copyFont(size: 17, weight: .semibold, monospaced: true, slashedZero: model.settings.slashedZero))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func beatPanel(_ beat: FirstFourBeat) -> some View {
        VStack(spacing: 12) {
            Text(cueText(beat))
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            switch beat.kind {
            case .hear:
                HStack {
                    Button { replay(beat) } label: { Label("Replay", systemImage: "arrow.counterclockwise") }
                        .buttonStyle(.bordered)
                    Spacer()
                    continueButton.disabled(!heard)
                }
            case .silence:
                HStack { Spacer(); continueButton }
            case .copy:
                Button { replay(beat) } label: { Label("Replay", systemImage: "arrow.counterclockwise") }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack {
                    TextField("Their state", text: $typed)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .font(.system(.title3, design: .monospaced))
                        .submitLabel(.done)
                        .onSubmit { respond(.copied(typed)) }
                        .padding(12)
                        .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    Button("Check") { respond(.copied(typed)) }
                        .buttonStyle(.bordered)
                        .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            case .send:
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("SEND").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                        Text(beat.text)
                            .font(Theme.copyFont(size: 26, weight: .bold, monospaced: true, slashedZero: model.settings.slashedZero))
                    }
                    Spacer()
                }
                FirstFourKeyPanel(expected: beat.text, resetToken: keyReset, wpm: model.settings.wpm,
                                  toneHz: model.settings.toneFrequency) { respond(.sent($0)) }
            case .wait:
                Button { respond(.waited) } label: {
                    Label("Wait and listen", systemImage: "ear")
                        .font(.headline)
                        .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                FirstFourKeyPanel(expected: call, resetToken: keyReset, wpm: model.settings.wpm,
                                  toneHz: model.settings.toneFrequency) { respond(.sent($0)) }
            }
        }
        .padding(14)
        .brandCard()
    }

    private var continueButton: some View {
        Button { respond(.continued) } label: {
            Label("Continue", systemImage: "arrow.right")
                .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
        }
        .buttonStyle(.borderedProminent)
        .tint(Theme.teal)
    }

    private func resultCard(_ r: (clean: Bool, passedNow: Bool)) -> some View {
        VStack(spacing: 12) {
            if r.clean {
                Label(r.passedNow ? "Stage passed" : "Clean run", systemImage: "checkmark.seal.fill")
                    .font(.headline)
                    .foregroundStyle(Theme.tealBright)
            } else {
                Text("Finished, with \(scene.mistakes) \(scene.mistakes == 1 ? "slip" : "slips"). Only a clean run counts — go again.")
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.center)
            }
            if stage == .walkthrough && progress.hasPassed(.walkthrough) {
                FirstFourFinale()
            }
            if progress.hasPassed(stage) {
                Button {
                    Haptics.selection()
                    onNext()
                } label: {
                    Text(progress.nextStage.map { "Next: \(FirstFourView.title($0))" } ?? "Back to the stages")
                        .font(.headline)
                        .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
            }
            Button(progress.hasPassed(stage) ? "Run it again" : "Go again") { startRun() }
                .buttonStyle(.bordered)
        }
        .padding(14)
        .brandCard()
    }

    // MARK: Driving the scene

    private func startRun() {
        model.stopFirstFour()
        activator = FirstFour.activator(index: Int.random(in: 0..<FirstFour.activators.count), excluding: call)
        let beats: [FirstFourBeat]
        switch stage {
        case .bustedCall:
            beats = FirstFour.bustedCallScene(call: call, partial: FirstFour.partial(call: call, round: round),
                                              activator: activator)
        case .noReply:
            if FirstFour.noReplyUsesSceneB(cleanRuns: progress.cleanRuns(.noReply)) {
                let other = FirstFour.otherHunter(index: Int.random(in: 0..<FirstFour.otherHunters.count), excluding: call)
                beats = FirstFour.noReplySceneB(call: call, activator: activator, otherHunter: other)
            } else {
                beats = FirstFour.noReplySceneA(call: call, activator: activator)
            }
        default:
            beats = FirstFour.walkthroughScene(call: call, state: state, activator: activator)
        }
        scene = FirstFourScene(beats: beats)
        log = []
        result = nil
        feedback = nil
        typed = ""
        keyReset += 1
        beginBeat()
    }

    /// Sound the beat if it is a transmission, and log it once heard.
    private func beginBeat() {
        heard = false
        guard let beat = scene.current else { return }
        switch beat.kind {
        case .hear, .copy:
            playToken += 1
            let token = playToken
            let seconds = model.playFirstFour(beat.text)
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds + 0.2) {
                guard token == playToken, scene.current == beat else { return }
                heard = true
                log.append((activator.call, beat.text))
            }
        case .silence:
            heard = true
            log.append((activator.call, "…"))
        case .send, .wait:
            break
        }
    }

    private func replay(_ beat: FirstFourBeat) {
        model.playFirstFour(beat.text)
    }

    private func respond(_ response: FirstFourScene.Response) {
        guard let beat = scene.current else { return }
        let verdict = scene.respond(response)
        model.noteFirstFourPractice()
        switch verdict {
        case .advance:
            feedback = nil
            Haptics.tap()
            switch response {
            case .sent(let text): log.append(("You", text.uppercased()))
            case .copied(let text): log.append(("You", "copied \(text.uppercased())"))
            case .waited: log.append(("You", "(waiting)"))
            case .continued: break
            }
            typed = ""
            keyReset += 1
            if scene.isFinished {
                finishRun()
            } else {
                // A short pause before the activator answers, as on the air.
                let delay: Double = scene.current?.kind == .hear || scene.current?.kind == .copy ? 0.6 : 0
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { beginBeat() }
            }
        case .finished:
            break
        default:
            Haptics.error()
            feedback = verdictText(verdict, beat: beat, response: response)
            keyReset += 1
        }
    }

    private func finishRun() {
        let clean = scene.isClean
        let passedNow = progress.recordScene(stage, clean: clean)
        round += 1
        if clean { Haptics.success() }
        result = (clean, passedNow)
    }

    private func verdictText(_ v: FirstFourScene.Verdict, beat: FirstFourBeat, response: FirstFourScene.Response) -> String {
        switch v {
        case .sentWrong:
            if case .sent(let t) = response {
                return "That decoded as “\(t.uppercased())”. Send \(beat.text)."
            }
            return "Send \(beat.text)."
        case .copyWrong:
            return "Not quite. Replay it and listen for the state after 5NN."
        case .yourTurn:
            return "Your turn — send it."
        case .stayQuiet:
            return "Stay quiet: they're working another station. Calling over a contact only makes it harder for everyone. Tap Wait."
        case .notYourTurn:
            return "Listen first."
        case .advance, .finished:
            return ""
        }
    }

    private func cueText(_ beat: FirstFourBeat) -> String {
        switch beat.cue {
        case .cq:
            return "\(activator.call) is calling CQ POTA — asking any station to answer."
        case .callThem:
            return "Your turn: send your call, once. Then stop and listen."
        case .partial:
            return beat.text == "?"
                ? "Just “?”: they heard someone calling, but not who. Send your call again."
                : "“\(beat.text)”: they caught part of a call. It matches yours, so they want you to send it again."
        case .resend:
            return "Send your full call — then wait for them to come back to you."
        case .ack:
            return "Your call, 5NN and their state: they've got you. From here the contact goes on as usual."
        case .noReply:
            return "Nothing came back. That's normal — they may not have heard you, or heard several stations at once."
        case .callAgain:
            return "Call again: send your call once more, then listen."
        case .otherStation:
            return "They came back to another hunter. That contact is now in progress."
        case .stayQuiet:
            return "Don't send while they work the other station. Wait and listen."
        case .qrz:
            return "TU 73 ends that contact, and QRZ asks “who is calling?” Your turn to call again."
        case .theirExchange:
            return "They sent your call, 5NN (the signal report, 599) and their state, twice. Type their state."
        case .yourExchange:
            return "Send your exchange: 5NN, your state, and 73."
        case .signOff:
            return "TU 73 and two dits: you're in the log. That is a complete contact!"
        }
    }
}

// MARK: - The key

/// The on-screen key (straight or paddles, per Settings) and any hardware
/// Vail / BLE-MIDI key, decoded by the same `SendingKeyer` every keying mode
/// uses. Submits by itself once the decoded text is as long as `expected`
/// and the key is idle, as Sending Practice does; Submit and Clear are there
/// too. `resetToken` clears it from outside.
private struct FirstFourKeyPanel: View {
    @EnvironmentObject var model: AppModel
    let expected: String
    let resetToken: Int
    let onSubmit: (String) -> Void
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false

    init(expected: String, resetToken: Int, wpm: Double, toneHz: Double,
         onSubmit: @escaping (String) -> Void) {
        self.expected = expected
        self.resetToken = resetToken
        self.onSubmit = onSubmit
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("YOU SENT").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                Spacer()
                if !sender.midiDeviceNames.isEmpty {
                    Label(sender.midiDeviceNames.joined(separator: ", "), systemImage: "pianokeys")
                        .font(.caption).foregroundStyle(Theme.teal).lineLimit(1)
                }
            }
            Text(sender.decodedText.isEmpty ? "—" : sender.decodedText)
                .font(Theme.copyFont(size: 26, weight: .semibold, monospaced: true, slashedZero: model.settings.slashedZero))
                .foregroundStyle(sender.decodedText.isEmpty ? Theme.textSecondary : .white)
                .lineLimit(1).minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity, minHeight: 36)
            OnScreenKeySwitch(onKey: { down, ms in sender.touchKey(isDown: down, atMs: ms) }) {
                ZStack {
                    RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                        .fill(keyPressed ? Theme.teal : Theme.navyRaised)
                    RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                        .strokeBorder(keyPressed ? Theme.tealBright : Theme.hairline, lineWidth: keyPressed ? 2 : 1)
                    Text("HOLD TO KEY")
                        .font(.system(size: 12, weight: .bold)).tracking(1.5)
                        .foregroundStyle(keyPressed ? Theme.navy : Theme.textSecondary)
                }
                .frame(height: 100)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { _ in
                            if !keyPressed { keyPressed = true; sender.touchKey(isDown: true) }
                        }
                        .onEnded { _ in
                            if keyPressed { keyPressed = false; sender.touchKey(isDown: false) }
                        }
                )
                .accessibilityLabel("Morse key")
                .accessibilityHint("Press and hold to send each dit and dah")
            }
            .frame(height: 100)
            HStack(spacing: 12) {
                Button { sender.clear() } label: {
                    Label("Clear", systemImage: "delete.left").frame(maxWidth: .infinity, minHeight: 40)
                }
                .buttonStyle(.bordered)
                .tint(Theme.textSecondary)
                Button { submit() } label: {
                    Label("Submit", systemImage: "checkmark")
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 40)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .disabled(sender.decodedText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .onAppear { sender.start() }
        .onDisappear { sender.stop() }
        .onChange(of: resetToken) { _ in sender.clear() }
        .onChange(of: sender.decodedText) { _ in maybeAutoSubmit() }
        .onChange(of: sender.isKeying) { _ in maybeAutoSubmit() }
    }

    private func submit() {
        let answer = sender.submit()
        guard !answer.isEmpty else { return }
        onSubmit(answer)
    }

    private func maybeAutoSubmit() {
        guard !sender.isKeying else { return }
        let sent = FirstFour.compact(sender.decodedText)
        guard !sent.isEmpty, sent.count >= FirstFour.compact(expected).count else { return }
        submit()
    }
}
