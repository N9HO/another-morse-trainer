import SwiftUI

/// Morse Invaders (#170): characters descend the play field in columns and the
/// learner shoots each one by naming it — typing it after hearing it (ICR) or
/// keying it after seeing it. The rules live in `InvadersGame` (MorseKit); this
/// view is the frame clock, the sound, the input, and the drawing. It runs
/// inside the normal session (`AppModel.start()` for `.invaders`), so the End
/// button, the mode menu and the session summary all work as everywhere else,
/// and the tally goes to history through `AppModel.noteInvadersShot`.
///
/// Twin of `InvadersScreen.kt` on Android.
struct InvadersView: View {
    @EnvironmentObject var model: AppModel

    // Setup choices persist across launches, like every other mode's.
    @AppStorage("invaders.input") private var inputRaw = InvadersInput.icr.rawValue
    @AppStorage("invaders.difficulty") private var difficultyRaw = InvadersDifficulty.normal.rawValue
    @AppStorage("invaders.characters") private var characterSetRaw = InvadersCharacterSet.active.rawValue

    private enum Phase { case setup, playing, over }
    @State private var phase: Phase = .setup
    @State private var game: InvadersGame?
    /// A snapshot of the field for drawing; refreshed every frame from `game`.
    @State private var field: [Invader] = []
    @State private var hud = HUD()
    @State private var lastFrame: Date?
    /// When each invader's Morse finished sounding (ICR), keyed by invader id,
    /// so a hit's time-to-recognize runs from the end of the tone.
    @State private var toneEnd: [Int: Date] = [:]
    /// A flash of feedback on the field: the last hit's points, or "miss".
    @State private var flashText = ""
    @State private var flashUntil = Date.distantPast

    private struct HUD: Equatable {
        var score = 0, wave = 1, lives = 3, combo = 0, multiplier = 1
        var bestCombo = 0, accuracy = 0.0
    }

    private struct Option: Identifiable {
        let id: String
        let label: String
    }

    private var input: InvadersInput { InvadersInput(rawValue: inputRaw) ?? .icr }
    private var difficulty: InvadersDifficulty { InvadersDifficulty(rawValue: difficultyRaw) ?? .normal }
    private var characterSet: InvadersCharacterSet { InvadersCharacterSet(rawValue: characterSetRaw) ?? .active }

    /// The key row: the game's pool, letters first then digits, so the row
    /// reads the way the recognition chart does.
    private var pool: [Character] {
        model.invadersCharacters(characterSet)
            .map(String.init)
            .sorted(by: SessionRecord.characterOrder)
            .compactMap(\.first)
    }

    private let columns = 5

    var body: some View {
        Group {
            switch phase {
            case .setup:   setupCard
            case .playing: playfield
            case .over:    gameOverCard
            }
        }
        .onDisappear { model.stopInvaders() }
    }

    // MARK: - Setup

    private var setupCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(TrainingMode.invaders.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                optionPicker("How you answer", selection: $inputRaw,
                             options: InvadersInput.allCases.map { Option(id: $0.rawValue, label: $0.label) })
                Text(input.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                optionPicker("Characters", selection: $characterSetRaw,
                             options: InvadersCharacterSet.allCases.map { Option(id: $0.rawValue, label: $0.label) })
                Text(pool.map(String.init).joined(separator: " "))
                    .font(Theme.copyFont(style: .footnote, monospaced: true,
                                         slashedZero: model.settings.slashedZero))
                    .foregroundStyle(Theme.textSecondary)

                optionPicker("Difficulty", selection: $difficultyRaw,
                             options: InvadersDifficulty.allCases.map { Option(id: $0.rawValue, label: $0.label) })
                Text("Sent at your character speed and Farnsworth setting.")
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)

                Button {
                    Haptics.tap()
                    startGame()
                } label: {
                    Text("Start game")
                        .font(.headline)
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
            }
            .padding()
            .brandCard()
        }
    }

    private func optionPicker(_ title: String, selection: Binding<String>,
                              options: [Option]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline.weight(.semibold))
            Picker(title, selection: selection) {
                ForEach(options) { Text($0.label).tag($0.id) }
            }
            .pickerStyle(.segmented)
        }
    }

    // MARK: - Play

    private var playfield: some View {
        VStack(spacing: 12) {
            hudBar
            GeometryReader { geo in
                TimelineView(.animation) { context in
                    canvas(width: geo.size.width)
                        // The frame clock: every tick moves the game on by the
                        // real time elapsed, so a dropped frame costs no distance.
                        .onChange(of: context.date) { now in step(now: now) }
                }
            }
            .frame(maxHeight: .infinity)
            if input == .icr {
                Text("Tap an invader to hear it again")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                keyRow
            } else {
                InvadersKeyPanel(wpm: model.settings.wpm,
                                 toneHz: model.settings.toneFrequency,
                                 slashedZero: model.settings.slashedZero) { ch in
                    shoot(ch)
                }
            }
        }
    }

    private var hudBar: some View {
        HStack {
            stat("Score", "\(hud.score)")
            Spacer()
            stat("Wave", "\(hud.wave)")
            Spacer()
            HStack(spacing: 2) {
                ForEach(0..<3, id: \.self) { i in
                    Image(systemName: i < hud.lives ? "heart.fill" : "heart")
                        .foregroundStyle(i < hud.lives ? Color.red : Theme.textSecondary)
                }
            }
            .accessibilityLabel("\(hud.lives) lives")
            Spacer()
            stat("Combo", "×\(hud.multiplier)")
        }
        .font(.subheadline.monospacedDigit())
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .brandCard()
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(label.uppercased())
                .font(.system(size: 9, weight: .bold)).tracking(1)
                .foregroundStyle(Theme.textSecondary)
            Text(value).font(.headline.monospacedDigit())
        }
    }

    private func canvas(width: CGFloat) -> some View {
        Canvas { context, size in
            let ground = size.height - 18
            var line = Path()
            line.move(to: CGPoint(x: 0, y: ground))
            line.addLine(to: CGPoint(x: size.width, y: ground))
            context.stroke(line, with: .color(Theme.teal.opacity(0.6)), lineWidth: 2)

            let colWidth = size.width / CGFloat(columns)
            let font = Theme.copyFont(size: 22, weight: .bold, monospaced: true,
                                      slashedZero: model.settings.slashedZero)
            for inv in field {
                let x = (CGFloat(inv.column) + 0.5) * colWidth
                let y = 18 + CGFloat(inv.progress) * (ground - 36)
                let rect = CGRect(x: x - 22, y: y - 18, width: 44, height: 36)
                let body = Path(roundedRect: rect, cornerRadius: 10)
                context.fill(body, with: .color(Theme.navyRaised))
                context.stroke(body, with: .color(Theme.tealBright), lineWidth: 1.5)
                // ICR keeps the character to the ear; keying shows it to key.
                let label = input == .keying ? String(inv.character) : "?"
                context.draw(Text(label).font(font).foregroundColor(.white),
                             at: CGPoint(x: x, y: y))
            }
            if flashUntil > Date() {
                context.draw(Text(flashText).font(.headline).foregroundColor(Theme.tealBright),
                             at: CGPoint(x: size.width / 2, y: 14))
            }
        }
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
            .strokeBorder(Theme.hairline, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture { location in replay(at: location, width: width) }
        .accessibilityLabel("Play field, \(field.count) invaders")
    }

    /// ICR: tap a column to hear the nearest invader's character again.
    private func replay(at location: CGPoint, width: CGFloat) {
        guard input == .icr, let game else { return }
        let column = Int(location.x / max(1, width) * CGFloat(columns))
        guard let nearest = game.invaders.min(by: { abs($0.column - column) < abs($1.column - column) }) else { return }
        model.playInvader(nearest.character)
    }

    /// ICR: one button per pool character; a hardware keyboard presses them too.
    private var keyRow: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 44), spacing: 6)], spacing: 6) {
            ForEach(pool.map(String.init), id: \.self) { key in
                Button {
                    shoot(Character(key))
                } label: {
                    Text(key)
                        .font(Theme.copyFont(size: 20, weight: .semibold, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .foregroundStyle(.white)
                        .background(Theme.navyRaised,
                                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .keyboardShortcut(KeyEquivalent(Character(key.lowercased())), modifiers: [])
                .accessibilityLabel("Shoot \(key)")
            }
        }
    }

    // MARK: - Game over

    private var gameOverCard: some View {
        VStack(spacing: 16) {
            Text("Game over")
                .font(.title2.bold())
            HStack(spacing: 24) {
                stat("Score", "\(hud.score)")
                stat("Wave", "\(hud.wave)")
                stat("Accuracy", "\(Int((hud.accuracy * 100).rounded()))%")
                stat("Best combo", "\(hud.bestCombo)")
            }
            .padding(.vertical, 8)
            Button {
                Haptics.tap()
                startGame()
            } label: {
                Text("Play again")
                    .font(.headline)
                    .foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            Button {
                // Ends the session: the record is written and the usual
                // summary (with the per-character chart) takes over.
                model.endSession()
            } label: {
                Text("End game")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .brandCard()
    }

    // MARK: - Game loop

    private func startGame() {
        let config = InvadersGame.Config(characters: pool, difficulty: difficulty, columns: columns)
        let g = InvadersGame(config: config)
        game = g
        field = []
        toneEnd = [:]
        flashUntil = .distantPast
        lastFrame = nil
        syncHUD(g)
        phase = .playing
    }

    private func step(now: Date) {
        guard phase == .playing, let game else { return }
        defer { lastFrame = now }
        guard let last = lastFrame else { return }
        // Cap a long gap (the app was backgrounded) so the field does not
        // empty onto the ground in one step.
        let dt = min(0.1, now.timeIntervalSince(last))
        for event in game.advance(by: dt) {
            switch event {
            case .spawned(let inv):
                if input == .icr {
                    let duration = model.playInvader(inv.character)
                    toneEnd[inv.id] = now.addingTimeInterval(duration)
                }
            case .escaped(let inv):
                toneEnd[inv.id] = nil
                model.noteInvadersEscape(target: inv.character)
                Haptics.error()
                flash("\(inv.character) got through", for: 1.0)
            case .gameOver:
                model.stopInvaders()
                phase = .over
            }
        }
        field = game.invaders
        syncHUD(game)
    }

    private func shoot(_ character: Character) {
        guard phase == .playing, let game else { return }
        let now = Date()
        let lowest = game.lowest
        let shot = game.shoot(character)
        if let hit = shot.invader {
            let ttr = toneEnd[hit.id].map { max(0, now.timeIntervalSince($0)) } ?? 0
            toneEnd[hit.id] = nil
            model.noteInvadersShot(target: hit.character, chosen: hit.character, ttr: ttr)
            Haptics.success()
            flash(shot.waveCleared ? "Wave \(game.wave)!" : "+\(shot.points)", for: 0.8)
        } else {
            // A wrong key: confused with whatever was nearest the ground.
            if let lowest {
                model.noteInvadersShot(target: lowest.character,
                                       chosen: Character(String(character).uppercased()), ttr: 0)
            }
            Haptics.error()
            flash("miss", for: 0.6)
        }
        field = game.invaders
        syncHUD(game)
    }

    private func flash(_ text: String, for seconds: TimeInterval) {
        flashText = text
        flashUntil = Date().addingTimeInterval(seconds)
    }

    private func syncHUD(_ g: InvadersGame) {
        let next = HUD(score: g.score, wave: g.wave, lives: g.lives, combo: g.combo,
                       multiplier: g.multiplier, bestCombo: g.bestCombo, accuracy: g.accuracy)
        if next != hud { hud = next }
    }
}

/// Keying mode's input: the on-screen key (a hardware Vail/BLE-MIDI key feeds
/// the same decoder). Owns its `SendingKeyer` the way `SendingKeyerView` does,
/// so the decoder is built at the session speed; each finalised character is
/// handed to `onCharacter` as the shot.
private struct InvadersKeyPanel: View {
    let slashedZero: Bool
    let onCharacter: (Character) -> Void
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false

    init(wpm: Double, toneHz: Double, slashedZero: Bool, onCharacter: @escaping (Character) -> Void) {
        self.slashedZero = slashedZero
        self.onCharacter = onCharacter
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("Key the lowest invader")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(sender.decodedText.isEmpty ? "—" : sender.decodedText)
                    .font(Theme.copyFont(size: 22, weight: .semibold, monospaced: true, slashedZero: slashedZero))
                    .foregroundStyle(.white)
                if !sender.midiDeviceNames.isEmpty {
                    Image(systemName: "pianokeys").foregroundStyle(Theme.teal)
                        .accessibilityLabel("Hardware key connected")
                }
            }
            ZStack {
                RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .fill(keyPressed ? Theme.teal : Theme.navyRaised)
                RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .strokeBorder(keyPressed ? Theme.tealBright : Theme.hairline, lineWidth: keyPressed ? 2 : 1)
                Text("HOLD TO KEY")
                    .font(.system(size: 12, weight: .bold)).tracking(1.5)
                    .foregroundStyle(keyPressed ? Theme.navy : Theme.textSecondary)
            }
            .frame(height: 90)
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
            .accessibilityHint("Press and hold to key the character on the lowest invader")
        }
        .onAppear { sender.start() }
        .onDisappear { sender.stop() }
        // A finished character shoots at once; the decoder finalises it on
        // the letter gap, so nothing is fired mid-character.
        .onChange(of: sender.decodedText) { _ in keyedCharacter() }
        .onChange(of: sender.isKeying) { _ in keyedCharacter() }
    }

    private func keyedCharacter() {
        guard !sender.isKeying else { return }
        let text = sender.decodedText.trimmingCharacters(in: .whitespaces)
        guard let ch = text.first else { return }
        sender.clear()
        onCharacter(ch)
    }
}
