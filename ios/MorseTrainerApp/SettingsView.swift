import SwiftUI
import UIKit

// Settings is a root of categories with a search field on top (#236); each
// category pushes a sub-screen of titled sections. The map — which categories
// exist, in what order, which sections each holds, and what search finds — is
// `MorseKit/SettingsCatalog.swift`, pinned to the Android app's by
// `fixtures/settings-catalog.json`. This file only draws the rows.
//
// Adding a setting:
//   1. Put its row in the section it belongs to below (one computed property
//      per section, e.g. `soundSection`). A new section is a new
//      `SettingsSection` case, a property here, and a line in `sectionView`
//      and `isShown`.
//   2. Add one `SettingsSearchEntry` to `SettingsCatalog.entries`, in its
//      section's place. If Android has it too, add it to the fixture.
// Mid-session the sheet is scoped to the running mode (`activeMode`, #66):
// `isShown` hides sections for other modes, and a category with nothing left
// in it drops off the root and out of search.

/// Where the Settings stack can go: a category's sub-screen (scrolled to one
/// section and briefly highlighted when reached from a search result), or the
/// Licenses screen under Help & About.
enum SettingsRoute: Hashable {
    case category(SettingsCategory, focus: SettingsSection?)
    case licenses
}

extension SettingsCategory {
    /// The SF Symbol beside the category on the Settings root.
    var systemImage: String {
        switch self {
        case .sound: return "speaker.wave.2.fill"
        case .speed: return "speedometer"
        case .characters: return "character.book.closed"
        case .practice: return "target"
        case .keys: return "pianokeys"
        case .qso: return "antenna.radiowaves.left.and.right"
        case .reminders: return "bell.fill"
        case .display: return "textformat"
        case .leaderboard: return "trophy.fill"
        case .account: return "person.crop.circle"
        case .about: return "info.circle"
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false
    @State private var copiedDiagnostics = false
    /// The drill-down stack under the root, and the root's search text.
    @State private var path: [SettingsRoute] = []
    @State private var query = ""
    /// The width Settings was given. On a big iPad the sheet is page-sized,
    /// wide enough for the categories to sit in a sidebar beside the one
    /// that is open instead of pushing over it (`splitBody`).
    @State private var width: CGFloat = 0
    /// The sidebar's pick, in the split layout: the open category (and a
    /// search result's section to scroll to), or nil before the first pick.
    @State private var selection: SettingsRoute?
    /// The section a search result landed on, tinted for a moment so the eye
    /// finds it; cleared after the flash.
    @State private var highlighted: SettingsSection?
    /// Settings › Leaderboard & Buddy › Delete my scores: the confirmation,
    /// the in-flight call, and its outcome (nil = not asked yet / success).
    @State private var confirmDeleteScores = false
    @State private var deletingScores = false
    @State private var deleteScoresResult: String?

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
        [.journey, .characters, .words, .cw77, .abbreviations, .qCodes, .prosigns, .confusion]
    /// The choice quizzes that take a typed answer (#232): every one but the Journey.
    private static let answerEntryModes: Set<TrainingMode> =
        [.characters, .words, .cw77, .abbreviations, .qCodes, .prosigns, .confusion]
    /// The pileup surfaces all four QSO sections configure.
    private static let pileupModes: Set<TrainingMode> = [.qso, .contest]
    /// The surfaces a hardware key can drive — every mode `usesKeyingResponse`
    /// answers yes for, since all of them route through `SendingKeyerView` and
    /// its `SendingKeyer`, which wakes the adapter. (The Vail repeater carries
    /// its own copy of this control.)
    private static let hardwareKeyModes: Set<TrainingMode> =
        [.sending, .characters, .words, .cw77, .abbreviations, .qCodes, .prosigns,
         .confusion, .rapidFire, .invaders, .galaga, .dungeon, .asteroids]
    /// Modes with a play → answer → reveal loop the Feedback section controls.
    private static let feedbackModes: Set<TrainingMode> =
        [.journey, .characters, .words, .cw77, .abbreviations, .qCodes, .prosigns,
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

    /// Whether a section belongs on this surface — every gate the flat list
    /// had before #236, one line per section.
    private func isShown(_ section: SettingsSection) -> Bool {
        switch section {
        case .sound, .reminders, .display, .leaderboard, .buddy, .account, .devices, .bugReports, .about: return true
        case .speed: return showsGlobalSpeed
        case .farnsworth: return showsFarnsworth
        case .proficiency: return shown(for: Self.proficiencyModes)
        case .newCharacters: return shown(for: [.characters])
        case .trackStage, .previewStage: return shown(for: Self.stagePinModes)
        case .punctuation: return shown(for: Self.ladderModes)
        // Mid-session the destructive reset stays out of reach — it would
        // yank the engine out from under the running drill. Only from the
        // intro's app-wide entry (Android parity).
        case .reset: return activeMode == nil
        case .learning: return shown(for: Self.choiceQuizModes)
        case .answerEntry: return shown(for: Self.answerEntryModes)
        case .feedback: return shown(for: Self.feedbackModes)
        case .headCopy: return shown(for: [.headCopy])
        case .hardwareKey: return shown(for: Self.hardwareKeyModes)
        case .onScreenKey: return shown(for: Self.hardwareKeyModes)
        // Your call and name are also what CW 77 drills when you include
        // them (#240), so Listen & Learn, Common Words and CW 77 reach them too.
        case .yourStation: return shown(for: Self.pileupModes.union([.listen, .words, .cw77, .cw77Listen]))
        case .pileupRunner, .qsoSignals, .qsoRealism, .qsoCallsigns:
            return shown(for: Self.pileupModes)
        }
    }

    private func visibleSections(in category: SettingsCategory) -> [SettingsSection] {
        SettingsSection.sections(in: category).filter(isShown)
    }

    private var visibleCategories: [SettingsCategory] {
        SettingsCategory.allCases.filter { !visibleSections(in: $0).isEmpty }
    }

    private var searchResults: [SettingsSearchEntry] {
        SettingsCatalog.search(query, in: SettingsCatalog.entries.filter { isShown($0.section) })
    }

    /// Sidebar and detail side by side: a page-sized iPad sheet, a wide Mac
    /// window. Narrower than this (iPhone, Split View, Slide Over) it is the
    /// phone's drill-down stack.
    private var splits: Bool { width >= 700 }

    var body: some View {
        Group {
            if splits { splitBody } else { stackBody }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        // Push every change to a connected adapter as it is made: from the
        // intro there is no drill underneath holding an output, and
        // mid-session the drill's own push is diff-based, so the overlap is
        // harmless. Here at the stack rather than on the Keyer mode row, so a
        // speed or tone changed on another sub-screen still reaches it.
        .onChange(of: adapterKeyerMode) { _ in syncAdapterIfKeyed() }
        .onChange(of: model.settings.wpm) { _ in syncAdapterIfKeyed() }
        .onChange(of: model.settings.toneFrequency) { _ in syncAdapterIfKeyed() }
    }

    /// The phone layout: categories, and each one pushed over them.
    private var stackBody: some View {
        NavigationStack(path: $path) {
            rootList(sidebar: false)
                .searchable(text: $query,
                            placement: .navigationBarDrawer(displayMode: .always),
                            prompt: "Search settings")
                .autocorrectionDisabled()
                .navigationTitle("Settings")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { doneButton }
                .navigationDestination(for: SettingsRoute.self, destination: destination)
        }
    }

    /// The iPad layout: the categories (and search) in a sidebar, the open
    /// one beside them. Same rows, same search, same scroll-to-section.
    private var splitBody: some View {
        NavigationSplitView(columnVisibility: .constant(.all)) {
            rootList(sidebar: true)
                .searchable(text: $query, placement: .sidebar, prompt: "Search settings")
                .autocorrectionDisabled()
                .navigationTitle("Settings")
                .navigationBarTitleDisplayMode(.inline)
                .hidingSidebarToggle()
        } detail: {
            NavigationStack(path: $path) {
                Group {
                    if let selection {
                        destination(selection)
                    } else {
                        Theme.Background()
                            .toolbar { doneButton }
                    }
                }
                // A new pick is a new screen: its focus task runs again.
                .id(selection)
                .navigationDestination(for: SettingsRoute.self, destination: destination)
            }
        }
        .navigationSplitViewStyle(.balanced)
        .onAppear {
            if selection == nil {
                selection = visibleCategories.first.map { .category($0, focus: nil) }
            }
        }
        // Licenses is pushed on the detail's own stack; a new category
        // starts that stack over.
        .onChange(of: selection) { _ in path = [] }
    }

    @ViewBuilder
    private func destination(_ route: SettingsRoute) -> some View {
        switch route {
        case .category(let category, let focus):
            categoryScreen(category, focus: focus)
        case .licenses:
            LicensesView()
        }
    }

    /// Only where the Hardware key section is part of this surface, as when
    /// the change handlers rode on its row.
    private func syncAdapterIfKeyed() {
        if isShown(.hardwareKey) { syncAdapter() }
    }

    @ToolbarContentBuilder
    private var doneButton: some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button("Done") { dismiss() }
        }
    }

    // MARK: - Root and sub-screens

    /// The categories, or search results. In the split layout it is the
    /// sidebar: a selectable `List`, whose links set `selection` rather than
    /// push, left on the system sidebar look so the pick is highlighted.
    @ViewBuilder
    private func rootList(sidebar: Bool) -> some View {
        if sidebar {
            List(selection: $selection) {
                rootRows(rowBackground: nil)
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
        } else {
            Form {
                rootRows(rowBackground: Theme.navyElevated)
            }
            .scrollContentBackground(.hidden)
            .readableWidth()
            .background(Theme.Background())
        }
    }

    @ViewBuilder
    private func rootRows(rowBackground: Color?) -> some View {
        if SettingsCatalog.words(query).isEmpty {
            Section {
                ForEach(visibleCategories) { category in
                    NavigationLink(value: SettingsRoute.category(category, focus: nil)) {
                        Label(category.title, systemImage: category.systemImage)
                    }
                }
            } footer: {
                if activeMode != nil {
                    Text("Showing what applies here — the full settings live on the home screen.")
                }
            }
            .listRowBackground(rowBackground)
        } else {
            let results = searchResults
            if results.isEmpty {
                Section {
                    Text("No settings match “\(query.trimmingCharacters(in: .whitespaces))”.")
                        .foregroundStyle(.secondary)
                }
                .listRowBackground(rowBackground)
            } else {
                Section {
                    ForEach(results) { entry in
                        NavigationLink(value: SettingsRoute.category(entry.category, focus: entry.section)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.title)
                                Text(entry.category.title)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Results")
                }
                .listRowBackground(rowBackground)
            }
        }
    }

    private func categoryScreen(_ category: SettingsCategory, focus: SettingsSection?) -> some View {
        ScrollViewReader { proxy in
            Form {
                ForEach(visibleSections(in: category), id: \.self) { section in
                    sectionView(section)
                }
            }
            .scrollContentBackground(.hidden)
            .readableWidth()
            .background(Theme.Background())
            .navigationTitle(category.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { doneButton }
            .task {
                // A search result: bring its section to the top and tint it
                // for a moment. The short wait lets the push finish first.
                guard let focus, isShown(focus) else { return }
                try? await Task.sleep(nanoseconds: 350_000_000)
                withAnimation { proxy.scrollTo(focus, anchor: .top) }
                highlighted = focus
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                withAnimation(.easeOut(duration: 0.6)) {
                    if highlighted == focus { highlighted = nil }
                }
            }
        }
    }

    /// The card colour of a section's rows: the usual navy, or a teal tint
    /// while a search result is being pointed at.
    private func rowBackground(_ section: SettingsSection) -> Color {
        highlighted == section ? Theme.tealBright.opacity(0.22) : Theme.navyElevated
    }

    @ViewBuilder
    private func sectionView(_ section: SettingsSection) -> some View {
        switch section {
        case .sound: soundSection
        case .speed: speedSection
        case .farnsworth: farnsworthSection
        case .proficiency: proficiencySection
        case .newCharacters: newCharactersSection
        case .trackStage: trackStageSection
        case .punctuation: punctuationSection
        case .previewStage: previewStageSection
        case .reset: resetSection
        case .learning: learningSection
        case .answerEntry: answerEntrySection
        case .feedback: feedbackSection
        case .headCopy: headCopySection
        case .hardwareKey: hardwareKeySection
        case .onScreenKey: onScreenKeySection
        case .yourStation: yourStationSection
        case .pileupRunner: pileupRunnerSection
        case .qsoSignals: qsoSignalsSection
        case .qsoRealism: qsoRealismSection
        case .qsoCallsigns: qsoCallsignsSection
        case .reminders: remindersSection
        case .display: displaySection
        case .leaderboard: leaderboardSection
        case .buddy: buddySection
        case .account: AccountSettingsSection(sync: SyncCoordinator.shared, rowBackground: rowBackground(.account))
        case .devices: AccountDevicesSection(sync: SyncCoordinator.shared, rowBackground: rowBackground(.devices))
        case .bugReports: bugReportsSection
        case .about: aboutSection
        }
    }

    // MARK: - Sound

    private var soundSection: some View {
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
                Text("Adds audible band noise (QRN) under everything so practicing is more like copying off the air; any level also keeps Bluetooth audio awake.")
            } icon: {
                Image(systemName: "waveform")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .listRowBackground(rowBackground(.sound))
    }

    // MARK: - Speed & Timing

    private var speedSection: some View {
        Section("Speed") {
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
        .listRowBackground(rowBackground(.speed))
    }

    private var farnsworthSection: some View {
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
        .listRowBackground(rowBackground(.farnsworth))
    }

    // MARK: - Characters & Lessons

    private var proficiencySection: some View {
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
        .listRowBackground(rowBackground(.proficiency))
    }

    private var newCharactersSection: some View {
        Section {
            Toggle("Introduce new characters", isOn: $model.settings.introduceNewCharacters)
        } header: {
            Text("New characters")
        } footer: {
            Text("Before a character or prosign joins the drill for the first time, show it on its own with its sound and a Replay button.")
        }
        .listRowBackground(rowBackground(.newCharacters))
    }

    // The way back from words to characters (#95). The track grows
    // singles → pairs → triples → words on its own, and until now the only
    // hold on it mid-session was the Developer jump below, which clears the
    // pin and widens the active set. The pin lives on the setup sheet too;
    // here it is one gear-tap from the drill.
    private var trackStageSection: some View {
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
        .listRowBackground(rowBackground(.trackStage))
    }

    private var punctuationSection: some View {
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
            Text("“?” is already part of the base letters & numbers. A mark you turn on joins your Characters drill and the games’ full set straight away; turn it off and it leaves the drill, its stats kept.")
        }
        .listRowBackground(rowBackground(.punctuation))
    }

    private var previewStageSection: some View {
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
        .listRowBackground(rowBackground(.previewStage))
    }

    private var resetSection: some View {
        Section {
            Button(role: .destructive) {
                confirmReset = true
            } label: {
                Label("Reset all progress", systemImage: "trash")
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
        } header: {
            Text("Progress")
        }
        .listRowBackground(rowBackground(.reset))
    }

    // MARK: - Practice & Feedback

    private var learningSection: some View {
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
        .listRowBackground(rowBackground(.learning))
    }

    /// Keyboard-entry answers (#232), in the choice quizzes that take them
    /// (not the Journey).
    private var answerEntrySection: some View {
        Section {
            Picker("Answer entry", selection: $model.settings.answerEntry) {
                ForEach(AnswerEntryMode.allCases, id: \.self) { m in
                    Text(m.title).tag(m)
                }
            }
        } header: {
            Text("Answer entry")
        } footer: {
            Text("Tap picks from the buttons. Progressive starts each level at 4 choices, moves to 6 after 18 of 20 right, then to typing the answer after 18 of 20 more; a new character or stage starts again at 4. Type always has you type. Answers that are a meaning or a prosign always show choices.")
        }
        .listRowBackground(rowBackground(.answerEntry))
    }

    private var feedbackSection: some View {
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
        .listRowBackground(rowBackground(.feedback))
    }

    private var headCopySection: some View {
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
        .listRowBackground(rowBackground(.headCopy))
    }

    // MARK: - Keys & Sending

    private var hardwareKeySection: some View {
        Section {
            // Changes reach a connected adapter through the handlers on the
            // navigation stack in `body` (`syncAdapterIfKeyed`).
            Picker("Keyer mode", selection: $adapterKeyerMode) {
                ForEach(MIDIOutput.KeyerMode.allCases, id: \.rawValue) { mode in
                    Text(mode.displayName).tag(mode.rawValue)
                }
            }
            if (MIDIOutput.KeyerMode(rawValue: adapterKeyerMode) ?? .straightKey).adapterTimesSending {
                Text("The adapter times the sending in this mode, at the speed you're practicing at.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        } header: {
            Text("Hardware key")
        } footer: {
            Text("How the Vail Adapter should read your key. Straight Key is the default; pick an iambic mode for a paddle. This describes your key rather than a drill, so it applies in Sending Practice and on the Vail screen alike.")
        }
        .listRowBackground(rowBackground(.hardwareKey))
    }

    /// The on-screen key (#233): straight key or touch paddles, wherever the
    /// screen offers one — the keyed answers, the keying games and the Vail
    /// screen.
    private var onScreenKeySection: some View {
        Section {
            Picker("On-screen key", selection: $model.settings.onScreenKey) {
                ForEach(OnScreenKeyType.allCases) { kind in
                    Text(kind.label).tag(kind)
                }
            }
            if model.settings.onScreenKey == .paddles {
                Picker("Paddle mode", selection: $model.settings.paddleMode) {
                    ForEach(PaddleKeyer.Mode.allCases) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                Toggle("Dah on the left", isOn: $model.settings.paddleSwap)
            }
        } header: {
            Text("On-screen key")
        } footer: {
            Text(model.settings.onScreenKey == .paddles
                 ? "Two touch paddles, dit and dah, timed at your speed. Hold one to repeat it; hold both to squeeze. Iambic A stops when you let go; Iambic B adds one more alternate element; Ultimatic repeats whichever paddle you pressed last. \"Dah on the left\" swaps them for a left-handed operator."
                 : "One hold-to-key pad: the tone sounds for as long as you hold it. Choose Paddles to key with two touch paddles and a built-in iambic keyer instead.")
        }
        .listRowBackground(rowBackground(.onScreenKey))
    }

    // MARK: - QSO & Pileups

    private var yourStationSection: some View {
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
            HStack {
                Text("Your name")
                Spacer()
                TextField("Optional", text: $model.settings.qso.myName)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
            }
            HStack {
                Text("Your state")
                Spacer()
                TextField("Optional", text: $model.settings.qso.myState)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
            }
        } header: {
            Text("Your Station")
        } footer: {
            Text("Used across the app — sent when you call CQ and work stations in Pileup Runner, drilled in CW 77 when you include them, and taught in First Four, which also uses your state.")
        }
        .listRowBackground(rowBackground(.yourStation))
    }

    private var pileupRunnerSection: some View {
        Section {
            Picker("Mode", selection: $model.settings.qso.mode) {
                ForEach(QSOContestMode.allCases) { Text($0.label).tag($0) }
            }
            if model.settings.qso.mode.isPileup {
                Stepper(value: $model.settings.qso.maxStations, in: 1...8) {
                    Text("Max callers: \(model.settings.qso.maxStations)")
                }
            }
            // Same 60 WPM ceiling as the global character speed, so QRQ
            // practice carries into the QSO simulator (issue #79).
            sliderRow(title: "Min speed", value: $model.settings.qso.minWPM,
                      range: 12...60, step: 1, format: { "\(Int($0)) WPM" })
            sliderRow(title: "Max speed", value: $model.settings.qso.maxWPM,
                      range: 12...60, step: 1, format: { "\(Int($0)) WPM" })
            Toggle("Farnsworth spacing", isOn: $model.settings.qso.farnsworth)
            sliderRow(title: "Tone spread", value: $model.settings.qso.toneSpread,
                      range: 0...500, step: 10,
                      format: { $0 < 10 ? "Zero-beat" : "±\(Int($0)) Hz" })
        } header: {
            Text("Pileup Runner")
        } footer: {
            Text("Max callers thins a pileup at once; the other settings reach callers as they arrive. Tone spread splits callers across the band; zero-beat stacks them all on your pitch.")
        }
        .listRowBackground(rowBackground(.pileupRunner))
    }

    private var qsoSignalsSection: some View {
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
        .listRowBackground(rowBackground(.qsoSignals))
    }

    private var qsoRealismSection: some View {
        Section {
            // Only exchanges that actually carry a signal report (POTA, Basic
            // Contest, Single Caller) can be asked to copy it — the contest
            // sprints send no RST, so the toggle would be a no-op.
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
        .listRowBackground(rowBackground(.qsoRealism))
    }

    private var qsoCallsignsSection: some View {
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
        .listRowBackground(rowBackground(.qsoCallsigns))
    }

    // MARK: - Reminders

    private var remindersSection: some View {
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
        .listRowBackground(rowBackground(.reminders))
    }

    // MARK: - Display

    private var displaySection: some View {
        Section {
            Toggle("Slashed zero", isOn: $model.settings.slashedZero)
        } header: {
            Text("Display")
        } footer: {
            Text("Show the digit 0 with a line through it — the operator's convention for telling 0 from O.")
        }
        .listRowBackground(rowBackground(.display))
    }

    // MARK: - Leaderboard & Buddy

    // Shared leaderboard (docs/high-scores-design.md, step 2). Off by
    // default; nothing leaves the device until this is on and a name is set.
    // The display-name rule is the server's, applied here so the field says
    // what is wrong up front.
    private var leaderboardSection: some View {
        Section {
            Toggle("Share scores to the shared leaderboard", isOn: $model.settings.leaderboard.shareScores)
            HStack {
                Text("Display name")
                Spacer()
                TextField("N0CALL", text: $model.settings.leaderboard.displayName)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.body.monospaced())
                    .onChange(of: model.settings.leaderboard.displayName) { name in
                        // Uppercased as typed, the form the board shows.
                        let up = name.uppercased()
                        if up != name { model.settings.leaderboard.displayName = up }
                    }
            }
            if !model.settings.leaderboard.displayName.isEmpty,
               let problem = LeaderboardDisplayName.problem(with: model.settings.leaderboard.displayName) {
                Text(problem)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            Button(role: .destructive) {
                confirmDeleteScores = true
            } label: {
                HStack {
                    Label("Delete my scores", systemImage: "trash")
                    if deletingScores { Spacer(); ProgressView() }
                }
            }
            .disabled(deletingScores)
            .confirmationDialog("Delete every score this device has posted?",
                                isPresented: $confirmDeleteScores, titleVisibility: .visible) {
                Button("Delete my scores", role: .destructive) {
                    deletingScores = true
                    deleteScoresResult = nil
                    Task {
                        let problem = await model.leaderboardDeleteMyScores()
                        deletingScores = false
                        deleteScoresResult = problem ?? "Your scores were deleted."
                    }
                }
            } message: {
                Text("Removes this device's scores and submissions from the shared board. Your local stats and personal bests stay.")
            }
            if let deleteScoresResult {
                Text(deleteScoresResult)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Leaderboard")
        } footer: {
            Text("Ranked runs — Rapid Fire, Contest, Pileup Runner and the six games — are posted when they end. The board ranks a server-graded copy of the run (speed summed over the items you got right), so a game's board number is not its on-screen score. Names are 2–12 characters: letters, digits, space, / and -. Only a real device can post; the simulator cannot attest.")
        }
        .listRowBackground(rowBackground(.leaderboard))
    }

    // Buddy streak (docs/buddy-streak-design.md, #219, #237): its own view,
    // tinted like the other sections when a search result lands on it.
    @ViewBuilder
    private var buddySection: some View {
        BuddySettingsSection(rowBackground: rowBackground(.buddy))
        // The home screen's buddy line (#253): on by default, so an upgrade
        // changes nothing. Offered before any pairing too, so it can be set
        // first; only IntroView's line reads it — this list and the daily
        // reminder's buddy sentence are unaffected. Android: SettingsScreen's
        // BUDDY branch, same words.
        Section {
            Toggle("Show buddy line on home screen", isOn: $model.settings.buddyOnHome)
        } footer: {
            Text("The line under your streak on the home screen: who has practiced today and your buddy streak. It appears only while you have a buddy. Turning it off hides it there and nowhere else; the list above and the daily reminder still show your buddies.")
        }
        .listRowBackground(rowBackground(.buddy))
    }

    // MARK: - Help & About

    private var bugReportsSection: some View {
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
        .listRowBackground(rowBackground(.bugReports))
    }

    // Support the project (Android parity). Links out to the website's own
    // page rather than straight to a tipping site: App Store guideline 3.1.1
    // treats an in-app link to external tipping as a purchase mechanism, but
    // a link to the project's homepage is not, and the coffee button lives
    // there.
    private var aboutSection: some View {
        Section {
            Link(destination: ProjectLinks.support) {
                Label("Support the project", systemImage: "cup.and.saucer")
            }
            Link(destination: ProjectLinks.discord) {
                Label("Join the Discord", systemImage: "bubble.left.and.bubble.right")
            }
            Link(destination: ProjectLinks.gitHub) {
                Label("Source on GitHub", systemImage: "chevron.left.forwardslash.chevron.right")
            }
            NavigationLink(value: SettingsRoute.licenses) {
                Label("Licenses", systemImage: "doc.text")
            }
        } header: {
            Text("About")
        } footer: {
            Text("Free and open source, with no ads, subscriptions, or tracking. Both apps live in one repository.")
        }
        .listRowBackground(rowBackground(.about))
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
            lines.append("Exam: \(s.examSpeed.label) · \(s.examSpeed.passLabel)")
        case .listen:
            lines.append("Listen: \(s.listenContent.label) · \(s.listenGap.label) · \(s.listenReadback.label)")
        case .cw77, .cw77Listen:
            lines.append("CW 77: \(s.cw77Style.label) · include me \(s.cw77IncludeMe ? "on" : "off")"
                         + (model.learningMode == .cw77Listen
                            ? " · \(s.listenGap.label) · \(s.listenReadback.label)" : ""))
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

/// Outbound links from the About section. The support page is the one
/// place the coffee button lives; see the comment at the section.
private enum ProjectLinks {
    static let support = URL(string: "https://anothermorsetrainer.app/support/")!
    static let discord = URL(string: "https://discord.gg/qgyk3TPUd9")!
    static let gitHub = URL(string: "https://github.com/N9HO/another-morse-trainer")!
    static let license = URL(string: "https://github.com/N9HO/another-morse-trainer/blob/main/LICENSE")!
}

/// The notices the licenses oblige the app to carry (Android parity). The
/// GPL asks an interactive program to show its terms and no-warranty
/// statement, and the vendored decoder's MIT notice must accompany every
/// copy — the bundle ships its `LICENSE` file, and this is where a person
/// can actually read it. Both texts are literals here rather than loaded
/// from the bundle, so a resource rename can't silently drop a notice.
struct LicensesView: View {
    var body: some View {
        List {
            Section {
                Text(LicenseNotices.app)
                Link(destination: ProjectLinks.license) {
                    Label("Read the full license on GitHub", systemImage: "arrow.up.right.square")
                }
            } header: {
                Text("Another Morse Trainer")
            }
            .listRowBackground(Theme.navyElevated)

            Section {
                Text(LicenseNotices.cwDecoderMIT)
                    .font(.system(.footnote, design: .monospaced))
            } header: {
                Text("CW decoder core")
            } footer: {
                Text("The audio decoder is vendored from the Carrier Wave firmware under the MIT license.")
            }
            .listRowBackground(Theme.navyElevated)
        }
        .scrollContentBackground(.hidden)
        .readableWidth()
        .background(Theme.Background())
        .navigationTitle("Licenses")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private enum LicenseNotices {
    static let app = """
    Copyright © 2026 Justin Rogers (N9HO).

    Another Morse Trainer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
    """

    static let cwDecoderMIT = """
    MIT License

    Copyright (c) 2026 Jay Vana

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
    """
}


private extension View {
    /// The split Settings keeps both columns up (the sidebar is the only way
    /// between categories), so its show/hide button would do nothing.
    @ViewBuilder
    func hidingSidebarToggle() -> some View {
        if #available(iOS 17.0, *) {
            self.toolbar(removing: .sidebarToggle)
        } else {
            self
        }
    }
}
