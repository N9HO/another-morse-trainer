import SwiftUI

/// **CW Operating Procedure** (#294, #295, docs/operating-procedure-design.md):
/// on-air etiquette for hunting POTA activators, one rule at a time. Eight
/// lessons (concept, demo, quick scenarios — the eighth is #294's zero beat,
/// RIT and XIT lesson with its pileup demo and drill) and "What should you
/// do?", a scenario mode over them. The rules are MorseKit's
/// `OperatingProcedure`; this screen only sounds, shows and records them.
/// Twin of the Kotlin `OperatingProcedureScreen` on Android and desktop.
///
/// Teaching material, not a session: no session record, no leaderboard. A
/// graded answer counts as practice for the day.
struct OperatingProcedureView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.wideLayout) private var wide

    private enum Screen: Hashable {
        case home, lesson(OpLesson), scenarios
    }

    @State private var progress = OperatingProcedureStore.load()
    @State private var screen: Screen = .home
    @State private var editingStation = false
    @State private var callField = ""
    @State private var stateField = ""
    @State private var confirmingReset = false
    @State private var showingFirstFour = false
    @State private var firstFourDone = FirstFourStore.load().isComplete

    private var call: String { OperatingProcedure.normalizeCall(model.settings.qso.myCall) }
    private var state: String { OperatingProcedure.normalizeState(model.settings.qso.myState) }
    private var stationReady: Bool {
        !OperatingProcedure.prefillCall(saved: call).isEmpty
            && OperatingProcedure.isValidCall(call) && OperatingProcedure.isValidState(state)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if !stationReady || editingStation {
                        stationCard
                    } else {
                        switch screen {
                        case .home:
                            home
                        case .lesson(let lesson):
                            OpLessonScreen(lesson: lesson, call: call, state: state, progress: $progress,
                                           onNext: { open(progress.nextLesson.map(Screen.lesson) ?? .home) })
                                .id(lesson)
                        case .scenarios:
                            OpScenarioMode(call: call, state: state, onOpenLesson: { open(.lesson($0)) })
                        }
                    }
                }
                .padding()
                .readableWidth(wide ? Theme.wideContentMaxWidth : Theme.contentMaxWidth)
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if screen != .home && stationReady && !editingStation {
                        Button {
                            open(.home)
                        } label: {
                            Label("Lessons", systemImage: "chevron.left")
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear {
                callField = OperatingProcedure.prefillCall(saved: model.settings.qso.myCall)
                stateField = state
            }
            .onDisappear {
                model.stopOperating()
                model.releaseAudioIfIdle()   // no band noise on the menu after (#331)
            }
            .onChange(of: progress) { OperatingProcedureStore.save($0) }
            // A newer copy from the account (another device got further):
            // show it rather than save this screen's older one over it.
            .onReceive(NotificationCenter.default.publisher(for: SyncCoordinator.stateApplied)) { _ in
                progress = OperatingProcedureStore.load()
            }
            .sheet(isPresented: $showingFirstFour, onDismiss: {
                firstFourDone = FirstFourStore.load().isComplete
            }) {
                FirstFourView().environmentObject(model).pageSizedSheet()
            }
            .alert("Start Operating Procedure over?", isPresented: $confirmingReset) {
                Button("Start over", role: .destructive) {
                    progress = OperatingProcedureProgress()
                    OperatingProcedureStore.reset()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Every lesson goes back to not passed. Your callsign and state stay as they are.")
            }
        }
    }

    private var title: String {
        switch screen {
        case .home: return "Operating Procedure"
        case .lesson(let l): return OpCopy.title(l)
        case .scenarios: return "What should you do?"
        }
    }

    private func open(_ s: Screen) {
        model.stopOperating()
        screen = s
    }

    // MARK: - Your station

    private var stationCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("CW Operating Procedure")
                .font(.title3.weight(.semibold))
            Text("The lessons use your own callsign and state, so every example sounds like your next contact.")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 6) {
                Text("Your callsign").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                TextField("W1AW", text: $callField)
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
            if !callField.isEmpty && !OperatingProcedure.isValidCall(callField) {
                Text("A callsign is 3 to 10 letters and digits (and /), with at least one of each.")
                    .font(.caption).foregroundStyle(.orange)
            }
            if !stateField.isEmpty && !OperatingProcedure.isValidState(stateField) {
                Text("Use the 2- or 3-letter abbreviation, like WI or ON.")
                    .font(.caption).foregroundStyle(.orange)
            }
            Text("Saved to Settings › QSO & Pileups, where the rest of the app reads them too.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
            Button {
                Haptics.tap()
                model.saveOperatingStation(call: callField, state: stateField)
                editingStation = false
            } label: {
                Text(editingStation ? "Save" : "Start")
                    .font(.headline)
                    .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            .disabled(!OperatingProcedure.isValidCall(callField) || !OperatingProcedure.isValidState(stateField)
                      || OperatingProcedure.prefillCall(saved: callField).isEmpty)
        }
        .padding(18)
        .brandCard()
        .frame(maxWidth: Theme.contentMaxWidth)
    }

    // MARK: - The lesson list

    private var home: some View {
        VStack(spacing: 16) {
            Text("On-air etiquette for hunting Parks on the Air activators, one rule at a time.")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)

            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(call) · \(state)")
                        .font(Theme.copyFont(size: 22, weight: .semibold, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                    Text("\(progress.passedCount) of \(OpLesson.allCases.count) lessons passed")
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

            firstFourRow

            if let next = progress.nextLesson {
                Button {
                    Haptics.selection()
                    open(.lesson(next))
                } label: {
                    Label(progress.passedCount == 0 ? "Begin: \(OpCopy.title(next))" : "Continue: \(OpCopy.title(next))",
                          systemImage: "play.fill")
                        .font(.headline)
                        .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
            } else {
                Label("Every lesson passed", systemImage: "checkmark.seal.fill")
                    .font(.headline)
                    .foregroundStyle(Theme.tealBright)
            }

            // Two columns on a big iPad window, so eight lessons fit without
            // a long scroll; one on a phone.
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: wide ? 2 : 1), spacing: 10) {
                ForEach(Array(OpLesson.allCases.enumerated()), id: \.element) { i, lesson in
                    lessonRow(number: i + 1, lesson: lesson)
                }
            }

            scenarioModeRow

            Text("Everything plays at your own speed (\(Int(model.settings.wpm)) WPM) and tone — change them in Settings. Any lesson can be opened at any time.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)

            // The credit (maintainer, 2026-10-03): WB0RLJ's advice, linked,
            // and his channel of daily activation recordings.
            VStack(spacing: 6) {
                Text("Procedure after WB0RLJ's “Advice for CW POTA Hunters”, with thanks, and the Parks on the Air CW Guide.")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                Link("Advice for CW POTA Hunters (QRZ.com)", destination: OpCopy.adviceURL)
                    .font(.caption.weight(.semibold))
                Link("Jim Vaughan (WB0RLJ) on YouTube — his daily activations", destination: OpCopy.youTubeURL)
                    .font(.caption.weight(.semibold))
            }
            .tint(Theme.teal)

            if progress.passedCount > 0 || progress.drillPassed || !progress.cleanRuns.isEmpty {
                Button("Start over", role: .destructive) { confirmingReset = true }
                    .font(.subheadline)
            }
        }
    }

    /// First Four is the on-ramp: linked from here, not moved in (design note).
    private var firstFourRow: some View {
        Button {
            Haptics.selection()
            model.stopOperating()
            showingFirstFour = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: firstFourDone ? "checkmark.seal.fill" : "antenna.radiowaves.left.and.right")
                    .foregroundStyle(firstFourDone ? Theme.tealBright : Theme.teal)
                VStack(alignment: .leading, spacing: 1) {
                    Text(firstFourDone ? "First Four ✓" : "New to CW on the air? Start with First Four")
                        .font(.subheadline.weight(.semibold))
                    Text("Your call, your state, ?, 73 — heard and sent")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.teal.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func lessonRow(number: Int, lesson: OpLesson) -> some View {
        let passed = progress.hasPassed(lesson)
        return Button {
            Haptics.selection()
            open(.lesson(lesson))
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
                    Text(OpCopy.title(lesson)).font(.subheadline.weight(.semibold))
                    Text(OpCopy.subtitle(lesson)).font(.caption).foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxHeight: .infinity)
            .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Lesson \(number), \(OpCopy.title(lesson))\(passed ? ", passed" : "")")
    }

    private var scenarioModeRow: some View {
        Button {
            Haptics.selection()
            open(.scenarios)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "questionmark.bubble.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.teal)
                VStack(alignment: .leading, spacing: 2) {
                    Text("What should you do?").font(.subheadline.weight(.semibold))
                    Text("\(OperatingProcedure.scenarioRunLength) quick situations from every lesson")
                        .font(.caption).foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.tealBright.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - A lesson

private struct OpLessonScreen: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.wideLayout) private var wide
    let lesson: OpLesson
    let call: String
    let state: String
    @Binding var progress: OperatingProcedureProgress
    let onNext: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Text(OpCopy.subtitle(lesson)).font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                Spacer()
                if progress.hasPassed(lesson) {
                    Label("Passed", systemImage: "checkmark.seal.fill")
                        .font(.caption.weight(.semibold)).foregroundStyle(Theme.tealBright)
                }
            }
            if wide {
                // Concept and demos beside the practice, so the rule stays in
                // view while you answer. Falls back to one column when the
                // sheet is too narrow for two.
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: 16) {
                        learnColumn.frame(minWidth: 320)
                        practiceColumn.frame(minWidth: 320)
                    }
                    VStack(spacing: 16) { learnColumn; practiceColumn }
                }
            } else {
                learnColumn
                practiceColumn
            }
        }
    }

    private var learnColumn: some View {
        VStack(spacing: 16) {
            OpConceptCard(lesson: lesson, call: call, state: state)
            if lesson != .offset {
                OpDemoCard(demos: OperatingProcedure.demos(lesson, call: call, state: state), lesson: lesson)
            } else {
                OpPileupDemoCard(call: call)
                OpRitDemoCard()
            }
        }
    }

    private var practiceColumn: some View {
        VStack(spacing: 16) {
            if lesson == .offset {
                OpZeroBeatDrill(progress: $progress)
            }
            OpLessonRun(lesson: lesson, call: call, state: state, progress: $progress, onNext: onNext)
        }
    }
}

/// The idea: a few short paragraphs (and, for lesson 1, the signal table;
/// for the offsetting lesson, the rig table).
private struct OpConceptCard: View {
    @EnvironmentObject var model: AppModel
    let lesson: OpLesson
    let call: String
    let state: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("THE IDEA").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            ForEach(Array(OpCopy.concept(lesson, call: call, state: state).enumerated()), id: \.offset) { _, p in
                Text(p)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if lesson == .signals {
                ForEach(OpCopy.signalTable, id: \.0) { row in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(row.0)
                            .font(Theme.copyFont(size: 16, weight: .bold, monospaced: true, slashedZero: model.settings.slashedZero))
                            .frame(width: 64, alignment: .leading)
                        Text(row.1).font(.subheadline).foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            if lesson == .offset {
                Text("On your rig").font(.subheadline.weight(.semibold)).padding(.top, 4)
                ForEach(OpCopy.rigTable, id: \.maker) { row in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.maker).font(.caption.weight(.bold))
                        Text("Hear-shift: \(row.rit) · Transmit-shift: \(row.xit) · Pitch: \(row.pitch) · Zero-beat aid: \(row.aid)")
                            .font(.caption)
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Text("Models and firmware differ — check your manual.")
                    .font(.caption2).foregroundStyle(Theme.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
    }
}

/// Hear it: the lesson's right and wrong examples, each a short transcript.
private struct OpDemoCard: View {
    @EnvironmentObject var model: AppModel
    let demos: [OpDemo]
    let lesson: OpLesson
    @State private var playing: (demo: Int, line: Int)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("HEAR IT").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            ForEach(Array(demos.enumerated()), id: \.offset) { i, demo in
                HStack(alignment: .top, spacing: 10) {
                    Button { play(i) } label: {
                        Image(systemName: playing?.demo == i ? "speaker.wave.2.fill" : "play.circle.fill")
                            .font(.title2)
                            .foregroundStyle(Theme.teal)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Play \(kindLabel(demo.kind)) example")
                    VStack(alignment: .leading, spacing: 3) {
                        if demo.kind != .listen {
                            Text(kindLabel(demo.kind))
                                .font(.caption.weight(.bold))
                                .foregroundStyle(demo.kind == .right ? Theme.tealBright : .orange)
                        }
                        ForEach(Array(demo.lines.enumerated()), id: \.offset) { j, line in
                            HStack(alignment: .firstTextBaseline, spacing: 6) {
                                if demo.kind != .listen {
                                    Text(line.who == .you ? "You" : "Them")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(line.who == .you ? Theme.tealBright : Theme.textSecondary)
                                        .frame(width: 36, alignment: .leading)
                                }
                                Text(OperatingProcedure.display(line.text))
                                    .font(Theme.copyFont(size: 15, weight: .semibold, monospaced: true,
                                                         slashedZero: model.settings.slashedZero))
                                    .foregroundStyle(playing?.demo == i && playing?.line == j ? Theme.tealBright : .white)
                                    .fixedSize(horizontal: false, vertical: true)
                                if demo.kind == .listen, let meaning = OpCopy.signalTable.first(where: { $0.0 == line.text })?.1 {
                                    Text(meaning).font(.caption).foregroundStyle(Theme.textSecondary)
                                }
                            }
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
        .onDisappear { model.stopOperating() }
    }

    private func kindLabel(_ k: OpDemo.Kind) -> String {
        switch k {
        case .listen: return "listen"
        case .right: return "Right"
        case .wrong: return "Wrong"
        }
    }

    private func play(_ i: Int) {
        model.playOperatingLines(demos[i].lines) { line in
            playing = line.map { (i, $0) }
        }
    }
}

// MARK: - The offsetting lesson's demos and drill (#294)

/// The same pileup three times: everyone zero beat, only you offset,
/// everyone offset — as the activator hears it.
private struct OpPileupDemoCard: View {
    @EnvironmentObject var model: AppModel
    let call: String
    @State private var playing: OperatingProcedure.PileupPass?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("THE PILEUP").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            Text("What the activator hears when five hunters answer a CQ — one of them you, \(call).")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(OperatingProcedure.PileupPass.allCases, id: \.self) { pass in
                Button { play(pass) } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: playing == pass ? "speaker.wave.2.fill" : "play.circle.fill")
                            .font(.title2)
                            .foregroundStyle(Theme.teal)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(OpCopy.passTitle(pass)).font(.subheadline.weight(.semibold))
                            Text(OpCopy.passCaption(pass, call: call))
                                .font(.caption)
                                .foregroundStyle(Theme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(Theme.navyRaised.opacity(playing == pass ? 1 : 0.5),
                                in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
        .onDisappear { model.stopOperating() }
    }

    private func play(_ pass: OperatingProcedure.PileupPass) {
        playing = pass
        let voices = OperatingProcedure.pileupVoices(pass, call: call, tone: model.settings.toneFrequency,
                                                     wpm: model.settings.wpm)
        model.playOperatingPileup(voices) {
            if playing == pass { playing = nil }
        }
    }
}

/// RIT moves what you hear, not where you transmit.
private struct OpRitDemoCard: View {
    @EnvironmentObject var model: AppModel
    @State private var rit = 0.0

    private var station: Double { OperatingProcedure.ritDemoStationOffsetHz }
    private var heard: Double {
        OperatingProcedure.heardPitch(tone: model.settings.toneFrequency, station: station, vfo: 0, rit: rit)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("RIT").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            Text("The activator is calling \(Int(station)) Hz above you. Turn the RIT and listen: their pitch moves, but where you'd transmit doesn't.")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Text("RIT \(rit >= 0 ? "+" : "")\(Int(rit)) Hz")
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                    .frame(width: 120, alignment: .leading)
                Slider(value: $rit, in: -OperatingProcedure.ritRangeHz...OperatingProcedure.ritRangeHz,
                       step: OperatingProcedure.ritStepHz) { editing in
                    if !editing { play() }
                }
                .tint(Theme.teal)
                .accessibilityLabel("RIT")
            }
            Text("You hear them at \(Int(OperatingProcedure.audible(heard))) Hz; your sidetone is \(Int(model.settings.toneFrequency)) Hz.")
                .font(.caption).foregroundStyle(Theme.textSecondary)
            Text("You'd still call \(Int(abs(OperatingProcedure.transmitOffset(station: station, vfo: 0, xit: 0)))) Hz from them.")
                .font(.caption.weight(.semibold)).foregroundStyle(.orange)
            HStack {
                Button { play() } label: { Label("Play", systemImage: "play.fill") }
                    .buttonStyle(.bordered)
                Button { rit = 0; play() } label: { Label("RIT off", systemImage: "arrow.uturn.backward") }
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
    }

    private func play() {
        model.playOperatingTone(text: "CQ POTA", pitch: heard)
    }
}

/// The drill: tune until the activator sounds like your sidetone. Three in a
/// row within the tolerance pass it.
private struct OpZeroBeatDrill: View {
    @EnvironmentObject var model: AppModel
    @Binding var progress: OperatingProcedureProgress
    @State private var round = Int.random(in: 0..<OperatingProcedure.drillStarts.count)
    @State private var vfo = 0.0
    @State private var feedback: (String, Bool)?
    @State private var answered = false

    private var start: Double { OperatingProcedure.drillStart(round: round) }
    private var tone: Double { model.settings.toneFrequency }
    private var heard: Double { OperatingProcedure.heardPitch(tone: tone, station: start, vfo: vfo, rit: 0) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("ZERO-BEAT IT").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                Spacer()
                if progress.drillPassed {
                    Label("Drill passed", systemImage: "checkmark.seal.fill")
                        .font(.caption.weight(.semibold)).foregroundStyle(Theme.tealBright)
                }
                streakDots
            }
            Text("The activator is somewhere off your frequency. Tune until they sound just like your sidetone — play them together and the beat slows and stops — then tap Done.")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Button { model.playOperatingTone(text: "VVV", pitch: heard) } label: {
                    Label("Station", systemImage: "antenna.radiowaves.left.and.right").frame(maxWidth: .infinity)
                }
                Button { model.playOperatingTone(text: "VVV", pitch: tone) } label: {
                    Label("Spot", systemImage: "tuningfork").frame(maxWidth: .infinity)
                }
                Button { model.playOperatingTone(text: "TTT", pitch: heard, sidetone: tone) } label: {
                    Label("Together", systemImage: "waveform").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .tint(Theme.teal)
            HStack(spacing: 8) {
                ForEach(OperatingProcedure.knobSteps, id: \.self) { step in
                    Button(step > 0 ? "+\(Int(step))" : "\(Int(step))") {
                        vfo += step
                        answered = false
                        feedback = nil
                        model.playOperatingTone(text: "VVV", pitch: OperatingProcedure.heardPitch(tone: tone, station: start, vfo: vfo, rit: 0))
                    }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                    .accessibilityLabel("Tune \(step > 0 ? "up" : "down") \(Int(abs(step))) hertz")
                }
            }
            HStack {
                Text("Tuning \(vfo >= 0 ? "+" : "")\(Int(vfo)) Hz")
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                Spacer()
                if answered {
                    Button("Next") { nextRound() }
                        .buttonStyle(.borderedProminent).tint(Theme.teal)
                        .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                } else {
                    Button("Done") { grade() }
                        .buttonStyle(.borderedProminent).tint(Theme.teal)
                        .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                }
            }
            if let feedback {
                Text(feedback.0)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(feedback.1 ? Theme.tealBright : .orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
        .onDisappear { model.stopOperating() }
    }

    private var streakDots: some View {
        HStack(spacing: 5) {
            ForEach(0..<OperatingProcedure.zeroBeatStreakToPass, id: \.self) { i in
                Circle()
                    .fill(i < progress.drillStreak ? Theme.tealBright : Theme.navyRaised)
                    .frame(width: 10, height: 10)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(progress.drillStreak) of \(OperatingProcedure.zeroBeatStreakToPass) in a row")
    }

    private func grade() {
        let offset = OperatingProcedure.transmitOffset(station: start, vfo: vfo, xit: 0)
        let right = OperatingProcedure.isZeroBeat(offset)
        model.noteOperatingPractice()
        let passedNow = progress.recordDrill(correct: right)
        answered = true
        if right {
            Haptics.success()
            let note = passedNow ? " Drill passed."
                : progress.drillPassed ? ""
                : " \(progress.drillStreak) of \(OperatingProcedure.zeroBeatStreakToPass) in a row."
            feedback = ("Zero beat — you're \(Int(abs(offset))) Hz from them.\(note)", true)
        } else {
            Haptics.error()
            feedback = ("Not yet: you're \(Int(abs(offset))) Hz \(offset > 0 ? "above" : "below") them. They were at \(start > 0 ? "+" : "")\(Int(start)) Hz.", false)
        }
    }

    private func nextRound() {
        round += 1
        vfo = 0
        answered = false
        feedback = nil
        model.playOperatingTone(text: "VVV", pitch: heard)
    }
}

// MARK: - Scenarios

/// One scenario on screen: the situation, the clip, the shuffled choices,
/// and after an answer the explanation. Used by a lesson's run and by "What
/// should you do?".
private struct OpScenarioCard: View {
    @EnvironmentObject var model: AppModel
    let scenario: OpScenario
    let call: String
    let state: String
    /// Set once answered: the choice picked.
    let picked: OpChoice?
    let onPick: (OpChoice) -> Void
    @State private var order: [OpChoice] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(OpCopy.situation(scenario, call: call))
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if !scenario.clip.isEmpty {
                HStack {
                    Text(OperatingProcedure.display(scenario.clip))
                        .font(Theme.copyFont(size: 22, weight: .bold, monospaced: true, slashedZero: model.settings.slashedZero))
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    Button { model.playOperating(scenario.clip) } label: {
                        Label("Replay", systemImage: "arrow.counterclockwise")
                    }
                    .buttonStyle(.bordered)
                }
            }
            Text(OpCopy.question(scenario)).font(.subheadline.weight(.semibold))
            ForEach(order, id: \.self) { choice in
                Button {
                    guard picked == nil else { return }
                    onPick(choice)
                } label: {
                    HStack {
                        Text(OpCopy.label(choice))
                            .font(.subheadline.weight(.semibold))
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                        if picked != nil, scenario.accepts(choice) {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.tealBright)
                        } else if picked == choice {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.orange)
                        }
                    }
                    .foregroundStyle(.white)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(background(choice), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(picked != nil)
            }
            if let picked {
                let right = scenario.accepts(picked)
                VStack(alignment: .leading, spacing: 4) {
                    Text(right ? "Right" : "Not this time")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(right ? Theme.tealBright : .orange)
                    Text(OpCopy.explanation(scenario, call: call, state: state))
                        .font(.subheadline)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear(perform: setUp)
        .onChange(of: scenario.id) { _ in setUp() }
    }

    private func background(_ choice: OpChoice) -> Color {
        guard let picked else { return Theme.navyRaised }
        if scenario.accepts(choice) { return Theme.teal.opacity(0.35) }
        if choice == picked { return Color.orange.opacity(0.3) }
        return Theme.navyRaised.opacity(0.6)
    }

    private func setUp() {
        order = scenario.choices.shuffled()
        if !scenario.clip.isEmpty { model.playOperating(scenario.clip) }
    }
}

/// A lesson's scenarios, once through. Clean passes it.
private struct OpLessonRun: View {
    @EnvironmentObject var model: AppModel
    let lesson: OpLesson
    let call: String
    let state: String
    @Binding var progress: OperatingProcedureProgress
    let onNext: () -> Void

    @State private var run = OpScenarioRun(scenarios: [])
    @State private var picked: OpChoice?
    @State private var started = false
    @State private var result: (clean: Bool, passedNow: Bool)?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("TRY IT").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                Spacer()
                if started && result == nil {
                    Text("\(min(run.index + 1, run.scenarios.count)) of \(run.scenarios.count)")
                        .font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                }
            }
            if let result {
                resultView(result)
            } else if !started {
                Text("\(OperatingProcedure.scenarios(lesson, call: call, state: state).count) quick situations. Get them all right in one go to pass\(lesson == .offset ? " (with the drill)" : "").")
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                startButton("Start")
            } else if let scenario = displayed {
                OpScenarioCard(scenario: scenario, call: call, state: state, picked: picked) { choice in
                    picked = choice
                    model.noteOperatingPractice()
                    if scenario.accepts(choice) { Haptics.success() } else { Haptics.error() }
                }
                if picked != nil {
                    Button { advance() } label: {
                        Text(run.index + 1 >= run.scenarios.count ? "Finish" : "Next")
                            .font(.headline)
                            .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.teal)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
    }

    /// The scenario being shown: the run's current one, held on screen while
    /// its answer is displayed.
    private var displayed: OpScenario? { run.current }

    private func startButton(_ title: String) -> some View {
        Button {
            run = OpScenarioRun(scenarios: OperatingProcedure.scenarios(lesson, call: call, state: state))
            picked = nil
            result = nil
            started = true
        } label: {
            Text(title)
                .font(.headline)
                .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(Theme.teal)
    }

    private func advance() {
        guard let p = picked else { return }
        run.answer(choice: p)
        picked = nil
        if run.isFinished {
            let clean = run.isClean
            let passedNow = progress.recordRun(lesson, clean: clean)
            result = (clean, passedNow)
            if clean { Haptics.success() }
        }
    }

    @ViewBuilder
    private func resultView(_ r: (clean: Bool, passedNow: Bool)) -> some View {
        if r.clean {
            Label(progress.hasPassed(lesson) ? (r.passedNow ? "Lesson passed" : "Clean run") : "Clean run — now pass the drill",
                  systemImage: "checkmark.seal.fill")
                .font(.headline)
                .foregroundStyle(Theme.tealBright)
        } else {
            Text("Finished with \(run.mistakes) wrong. Only a clean run passes — go again.")
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
        }
        if progress.hasPassed(lesson) {
            Button {
                Haptics.selection()
                onNext()
            } label: {
                Text(progress.nextLesson.map { "Next: \(OpCopy.title($0))" } ?? "Back to the lessons")
                    .font(.headline)
                    .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
        }
        Button(r.clean ? "Run it again" : "Go again") {
            run = OpScenarioRun(scenarios: OperatingProcedure.scenarios(lesson, call: call, state: state))
            picked = nil
            result = nil
        }
        .buttonStyle(.bordered)
    }
}

/// "What should you do?": ten action scenarios from every lesson, shuffled.
private struct OpScenarioMode: View {
    @EnvironmentObject var model: AppModel
    let call: String
    let state: String
    let onOpenLesson: (OpLesson) -> Void

    @State private var deck: [OpScenario] = []
    @State private var index = 0
    @State private var picked: OpChoice?
    @State private var right = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("A clip plays: choose what you'd do. Each answer links to the lesson that teaches it.")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if index < deck.count {
                let scenario = deck[index]
                HStack {
                    Text("\(index + 1) of \(deck.count)").font(.caption.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                    Spacer()
                    Text("\(right) right").font(.caption.weight(.semibold)).foregroundStyle(Theme.tealBright)
                }
                VStack(alignment: .leading, spacing: 12) {
                    OpScenarioCard(scenario: scenario, call: call, state: state, picked: picked) { choice in
                        picked = choice
                        model.noteOperatingPractice()
                        if scenario.accepts(choice) { right += 1; Haptics.success() } else { Haptics.error() }
                    }
                    if picked != nil {
                        Button {
                            onOpenLesson(scenario.lesson)
                        } label: {
                            Label("Lesson: \(OpCopy.title(scenario.lesson))", systemImage: "book")
                        }
                        .buttonStyle(.bordered)
                        Button {
                            picked = nil
                            index += 1
                        } label: {
                            Text(index + 1 >= deck.count ? "See how you did" : "Next")
                                .font(.headline)
                                .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.teal)
                    }
                }
                .padding(14)
                .brandCard()
            } else if !deck.isEmpty {
                VStack(spacing: 12) {
                    Text("\(right) of \(deck.count)")
                        .font(.largeTitle.weight(.bold))
                        .foregroundStyle(Theme.tealBright)
                    Text(right == deck.count ? "Every one right. You're ready for the pileup."
                                             : "Each answer named its lesson — worth a look for the ones you missed.")
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                    Button { deal() } label: {
                        Text("Go again")
                            .font(.headline)
                            .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.teal)
                }
                .frame(maxWidth: .infinity)
                .padding(14)
                .brandCard()
            }
        }
        .frame(maxWidth: Theme.contentMaxWidth)
        .onAppear { if deck.isEmpty { deal() } }
        .onDisappear { model.stopOperating() }
    }

    private func deal() {
        deck = Array(OperatingProcedure.actionPool(call: call, state: state).shuffled()
            .prefix(OperatingProcedure.scenarioRunLength))
        index = 0
        right = 0
        picked = nil
    }
}

// MARK: - The words

/// The section's words. The MorseKit rules pin ids and keys; the words are
/// this platform's own, kept in step with Android's and desktop's strings.
enum OpCopy {
    // swiftlint:disable force_unwrapping
    static let adviceURL = URL(string: "https://www.qrz.com/db/WB0RLJ#Advice")!
    static let youTubeURL = URL(string: "https://www.youtube.com/@WB0RLJ")!
    // swiftlint:enable force_unwrapping

    /// The home card's subtitle, for the progress so far.
    static func homeSubtitle(_ p: OperatingProcedureProgress) -> String {
        if p.isComplete { return "Every lesson passed · pileups, partials, zero beat" }
        if p.passedCount > 0 { return "\(p.passedCount) of \(OpLesson.allCases.count) lessons · the next step after First Four" }
        return "The next step after First Four · pileups, partials, zero beat"
    }
    static func title(_ l: OpLesson) -> String {
        switch l {
        case .signals: return "Signals"
        case .when: return "When to call"
        case .once: return "Send your call once"
        case .partial: return "Partial calls"
        case .me: return "Is it me?"
        case .exchange: return "The exchange"
        case .mistake: return "Mistakes"
        case .offset: return "Offsetting"
        }
    }

    static func subtitle(_ l: OpLesson) -> String {
        switch l {
        case .signals: return "?, AGN?, AS, BK, SRI, QRZ?, dit-dit"
        case .when: return "Right after the dit-dit"
        case .once: return "No DE, no K, no repeats"
        case .partial: return "Answer only if it's in your call"
        case .me: return "Who did they come back to?"
        case .exchange: return "Report, state, 73, dit-dit — then stop"
        case .mistake: return "Send an error, then send it again"
        case .offset: return "Zero beat, RIT, XIT and the pileup"
        }
    }

    static let signalTable: [(String, String)] = [
        ("?", "Say again — they didn't get it all"),
        ("AGN?", "Send that again"),
        ("<AS>", "Wait — stand by (di-dah-di-di-dit, run together)"),
        ("BK", "Back to you"),
        ("SRI", "Sorry"),
        ("QRZ?", "Who is calling me?"),
        ("E E", "Dit-dit: the friendly sign-off. This contact is done."),
    ]

    struct Rig { let maker, rit, xit, pitch, aid: String }
    static let rigTable: [Rig] = [
        Rig(maker: "Icom", rit: "RIT", xit: "∂TX (delta TX)", pitch: "CW PITCH", aid: "AUTOTUNE"),
        Rig(maker: "Yaesu", rit: "CLAR (RX)", xit: "CLAR (TX)", pitch: "CW PITCH", aid: "ZIN/SPOT"),
        Rig(maker: "Kenwood", rit: "RIT", xit: "XIT", pitch: "CW pitch", aid: "CW T. (auto tune)"),
        Rig(maker: "Elecraft", rit: "RIT", xit: "XIT", pitch: "PITCH", aid: "SPOT, auto-spot"),
        Rig(maker: "FlexRadio", rit: "RIT", xit: "XIT", pitch: "Pitch (and the pitch line on the panadapter)",
            aid: "none built in: click-tune to the line"),
    ]

    static func concept(_ l: OpLesson, call: String, state: String) -> [String] {
        let near = OperatingProcedure.nearMiss(call: call)
        switch l {
        case .signals:
            return ["Activators keep it short. These are the signals you'll hear most while hunting, and what each one asks of you."]
        case .when:
            return [
                "An activator ends each contact with 73 or TU and two quick dits (E E). That dit-dit is your moment: call right away.",
                "Don't wait for a CQ. In a pileup many activators never send one between contacts — the dit-dit is the invitation.",
                "While a contact is going on — the activator is sending someone else's call, a report, or <AS> — stay quiet. Calling over it only slows everyone down.",
            ]
        case .once:
            return [
                "The activator knows their own call, and you're on their frequency, so they already know who you're calling.",
                "Send just your call, once. No DE, no K, no activator's call, no repeats. Then stop and listen.",
                "Repeating your call doesn't get you through faster: it covers up the next round, and the activator may have been answering you.",
            ]
        case .partial:
            return [
                "When the activator sends part of a call and a ?, they're asking that station to send again.",
                "Answer only if those characters are in your call — at the start, the middle or the end. If they aren't, it's someone else: stay quiet.",
                "When it is yours, send your full call, once — never just the missing part. A bare ? means they heard someone and caught nothing: anyone who called can send again.",
            ]
        case .me:
            return [
                "After you call, listen to who the activator comes back to. Your call: it's you — send your exchange.",
                "Another call: that contact is in progress. Stay quiet until it ends with the dit-dit.",
                "A call one letter from yours, like \(near) for \(call), sent without a question mark: they're working \(near). Stay silent.",
                "The same call with a question mark (\(near)?): they're not sure what they heard. Send your call once, then listen for whether they come back with yours.",
            ]
        case .exchange:
            return [
                "When they come back with your call, a report and their state, reply with your report, your state and 73: \(OperatingProcedure.reply(state: state)). That's the form First Four teaches.",
                "WB0RLJ's order works too: report, state and BK (\(OperatingProcedure.replyBK(state: state))), then, after their TU 73 and dit-dit, \(OperatingProcedure.closeBK). Either form is right here.",
                "5NN is 599, the report nearly every POTA contact carries.",
                "They finish with TU 73 and the dit-dit. Send E E (or 73 E E) back — then stop. The next QRZ? is for someone else.",
            ]
        case .mistake:
            return [
                "Everyone fumbles a character. When you do, send an error, then send the word again, correctly, from the start: your whole call, not just the letter you fixed. Don't go quiet and hope; the activator may log what you sent.",
                "An error isn't one fixed signal. It can be a quick run of dits, five to eight of them, or someone slapping the key: fast or slow, run together or ragged. Don't copy its shape. Recognise it, forget what came just before it, and copy what follows.",
                "Here an error is shown as \(OperatingProcedure.errorDisplay). Play the examples: no two sound alike.",
            ]
        case .offset:
            return [
                "Pitch. On CW, the tone you hear is how far the signal is from where your receiver is tuned, plus your rig's CW pitch setting. Your sidetone is set to that same pitch. So a station that sounds exactly like your sidetone is on the frequency you'd transmit on.",
                "Zero beat. Being on exactly the same frequency is called zero beat. Play two tones a few hertz apart and you hear a slow beat; at the same frequency the beat stops. Zero beat is how you get onto a station's frequency.",
                "Offset. In a pileup, everyone zero beat sounds like one long tone and nobody gets copied. Call a little off — 20 to 100 Hz, the Parks on the Air CW Guide says — and you're on your own pitch in the activator's ears, still well inside their filter.",
                "RIT, XIT and pitch. RIT moves what you hear and leaves where you transmit alone. XIT moves where you transmit and leaves what you hear alone. Pitch sets the tone you hear CW at, and your sidetone with it. A forgotten RIT is the classic way to call off frequency without meaning to.",
            ]
        }
    }

    static func passTitle(_ p: OperatingProcedure.PileupPass) -> String {
        switch p {
        case .zeroBeat: return "1 · Everyone zero beat"
        case .youOffset: return "2 · Only you offset (+\(Int(OperatingProcedure.demoYourOffsetHz)) Hz)"
        case .allOffset: return "3 · Everyone offset"
        }
    }

    static func passCaption(_ p: OperatingProcedure.PileupPass, call: String) -> String {
        switch p {
        case .zeroBeat: return "Five callers on one tone. Can you pick out \(call)?"
        case .youOffset: return "The same pileup with you \(Int(OperatingProcedure.demoYourOffsetHz)) Hz up. Your call stands out."
        case .allOffset: return "Everyone on their own pitch. Now the activator can pick out each call."
        }
    }

    static func situation(_ s: OpScenario, call: String) -> String {
        let act = OperatingProcedure.activator(for: call).call
        switch s.id {
        case "signals.as", "signals.qrz", "signals.ee", "signals.bk", "signals.agn":
            return "The activator sends:"
        case "when.dits", "when.inProgress", "when.as", "when.sriQrz":
            return "You're waiting to call \(act). You hear:"
        case "once.cq", "once.qrz", "once.dits":
            return "Time to call \(act). You hear:"
        case "exchange.agn":
            return "You've sent your exchange. The activator sends:"
        case "exchange.stop":
            return "You've sent E E back: the contact is done. Then you hear:"
        case "mistake.call":
            return "You meant to send your call, but keyed \(s.detail)."
        case "mistake.last":
            return "You keyed \(s.detail) — only the last character is wrong."
        case "mistake.state":
            return "In your reply you keyed \(s.detail)."
        case "offset.pileup":
            return "A big pileup: everyone is calling exactly on the activator's frequency."
        case "offset.rit":
            return "You want to hear the activator at your sidetone pitch without moving where you transmit."
        case "offset.xit":
            return "You want to transmit 60 Hz off the activator without changing how they sound to you."
        case "offset.tune":
            return "You need to retune your antenna tuner before you call."
        default:
            return "You've called \(act). You hear:"
        }
    }

    static func question(_ s: OpScenario) -> String {
        switch s.lesson {
        case .signals: return "What does it mean?"
        case .mistake: return "What next?"
        case .offset:
            switch s.id {
            case "offset.pileup": return "Where do you call?"
            case "offset.tune": return "Where do you tune?"
            default: return "Which control?"
            }
        default: return "What do you do?"
        }
    }

    static func label(_ c: OpChoice) -> String {
        switch c {
        case .send(let t): return "Send \(OperatingProcedure.display(t))"
        case .silent: return "Stay silent and listen"
        case .option(let k): return option(k)
        }
    }

    static func option(_ key: String) -> String {
        switch key {
        case "wait": return "Wait — stand by"
        case "goAhead": return "Go ahead"
        case "goodbye": return "Goodbye — the contact is done"
        case "whoIsCalling": return "Who is calling me?"
        case "sayAgain": return "Say again"
        case "sorry": return "Sorry"
        case "error": return "I made a mistake"
        case "backToYou": return "Back to you"
        case "offsetSmall": return "About 50 Hz off their frequency"
        case "zeroBeat": return "Exactly zero beat"
        case "twoKUp": return "2 kHz up"
        case "rit": return "RIT"
        case "xit": return "XIT"
        case "pitch": return "CW pitch"
        case "tuneAway": return "1 kHz or more away, at low power"
        case "tuneOnQuick": return "On their frequency, quickly"
        case "tuneOnLow": return "On their frequency, at low power"
        default: return key
        }
    }

    static func explanation(_ s: OpScenario, call: String, state: String) -> String {
        let other = OperatingProcedure.otherHunter(for: call)
        switch s.id {
        case "signals.as": return "<AS> means wait. The activator will be back — don't call until they are."
        case "signals.qrz": return "QRZ? asks “who is calling me?” — an invitation to send your call."
        case "signals.ee": return "Two quick dits are the friendly sign-off: that contact is done."
        case "signals.bk": return "BK means back to you — it's your turn to send."
        case "signals.agn": return "AGN? asks the station they're working to send it again."
        case "when.dits": return "That dit-dit ended their contact with \(other). Call now — don't wait for a CQ."
        case "when.inProgress": return "They're sending \(other) a report: that contact is in progress. Stay quiet."
        case "when.as": return "<AS> means wait. Calling now just adds noise; wait for them to come back."
        case "when.sriQrz": return "Sorry, who's calling? That's an open invitation — send your call now."
        case "once.cq": return "Just your call, once. They know their own call, and DE and K add nothing."
        case "once.qrz": return "QRZ? gets your call, once. Repeats cover up the next round."
        case "once.dits": return "Their contact is over: send your call, once — no need for theirs."
        case "partial.prefix": return "\(s.clip) matches the start of your call. Send your full call, once."
        case "partial.notMine": return "\(s.clip) isn't in your call — they want someone else. Stay quiet."
        case "partial.suffix": return "\(s.clip) matches the end of your call. Send your full call, once."
        case "partial.fullCall": return "They're missing one character, but send your whole call — a lone character means nothing on its own."
        case "me.other": return "They came back to \(other). Stay quiet until that contact ends."
        case "me.mine": return "That's you, with a report and their state. Send your exchange: \(OperatingProcedure.reply(state: state)), or \(OperatingProcedure.replyBK(state: state)) in WB0RLJ's order."
        case "me.close": return "No question mark: they're working \(s.detail), not you. Stay silent."
        case "me.closeAsked": return "\(s.detail)? is a question about a call close to yours. Send your call once, then listen for whether they come back with yours."
        case "exchange.reply": return "Report, state, 73 — or report, state, BK in WB0RLJ's order. Short and complete either way; save the ragchew for another time."
        case "exchange.agn": return "AGN? asks for your exchange again: send it again."
        case "exchange.dits": return "Send the dits back (E E, or 73 E E). That's the friendly close — and then you're done."
        case "exchange.stop": return "You're in the log. The next QRZ? is for someone else — stop and let them in."
        case "mistake.call": return "Send an error, then your whole call, correctly. SRI isn't used for this."
        case "mistake.last": return "After the error, send the whole call again — never just the fixed character."
        case "mistake.state": return "An error, then resend from the word with the mistake: your state and 73."
        case "mistake.hear": return "That burst of dits — however many, however fast — was an error: forget \(s.detail) and copy what follows. They have you, so send your exchange."
        case "offset.pileup": return "A small offset, 20–100 Hz, puts you on your own pitch and still inside their filter. 2 kHz up is off their frequency entirely."
        case "offset.rit": return "RIT shifts what you hear without moving your transmit frequency."
        case "offset.xit": return "XIT shifts where you transmit without moving what you hear."
        case "offset.tune": return "Tune up 1 kHz or more away, at the lowest power that works — never on a busy frequency."
        default: return ""
        }
    }
}
