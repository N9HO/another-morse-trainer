import SwiftUI

/// CW Galaga (#187): enemies swoop in along curved paths and settle into a
/// formation at the top, then dive at the player one at a time. The learner
/// shoots each one by naming the character it carries — typing it after
/// hearing it (copy) or keying it after seeing it (send) — and consecutive
/// hits build a combo multiplier. The rules live in `GalagaGame` (MorseKit)
/// and the flight paths in `GalagaPath`; this view is the frame clock, the
/// sound, the input, and the drawing. It runs inside the normal session
/// (`AppModel.start()` for `.galaga`), so the End button, the mode menu and
/// the session summary all work as everywhere else, and the tally goes to
/// history through `AppModel.noteGalagaShot`.
///
/// Twin of `GalagaScreen.kt` on Android.
struct GalagaView: View {
    @EnvironmentObject var model: AppModel

    // Setup choices persist across launches, like every other mode's. The
    // choices themselves are the arcade games' shared enums (Invaders.swift).
    @AppStorage("galaga.input") private var inputRaw = InvadersInput.icr.rawValue
    @AppStorage("galaga.difficulty") private var difficultyRaw = InvadersDifficulty.normal.rawValue
    @AppStorage("galaga.characters") private var characterSetRaw = InvadersCharacterSet.active.rawValue

    private enum Phase { case setup, playing, over }
    @State private var phase: Phase = .setup
    @State private var game: GalagaGame?
    /// A snapshot of the field for drawing; refreshed every frame from `game`.
    @State private var field: [GalagaEnemy] = []
    @State private var columns = 4
    @State private var hud = HUD()
    @State private var lastFrame: Date?
    /// When each enemy's Morse finished sounding (copy mode), keyed by enemy
    /// id, so a hit's time-to-recognize runs from the end of the tone.
    @State private var toneEnd: [Int: Date] = [:]
    /// A flash of feedback on the field: the last hit's points, or "miss".
    @State private var flashText = ""
    @State private var flashUntil = Date.distantPast

    private struct HUD: Equatable {
        var score = 0, wave = 1, lives = 3, combo = 0, multiplier = 1
        var bestCombo = 0, accuracy = 0.0
        /// The ramp: the speed enemies are sent at now, and the highest reached.
        var wpm = 0, bestWpm = 0
    }

    private struct Option: Identifiable {
        let id: String
        let label: String
    }

    private var input: InvadersInput { InvadersInput(rawValue: inputRaw) ?? .icr }
    private var difficulty: InvadersDifficulty { InvadersDifficulty(rawValue: difficultyRaw) ?? .normal }
    private var characterSet: InvadersCharacterSet { InvadersCharacterSet(rawValue: characterSetRaw) ?? .active }

    /// The game's pool, letters first then digits, so the setup card lists it
    /// the way the recognition chart does. (The keyboard lays it out QWERTY.)
    private var pool: [Character] {
        model.galagaCharacters(characterSet)
            .map(String.init)
            .sorted(by: SessionRecord.characterOrder)
            .compactMap(\.first)
    }

    /// The input choice in this game's words: what an enemy does when it
    /// enters, and what shoots it.
    private static func inputBlurb(_ input: InvadersInput) -> String {
        switch input {
        case .icr:
            return "Enemies fly in unmarked. Each one is sent in Morse as it enters and again as it dives (tap it to hear it again); type the character you heard to shoot the most dangerous one carrying it."
        case .keying:
            return "Each enemy shows its character. Key it on the on-screen key or a hardware key; the decoded character shoots the most dangerous one carrying it."
        }
    }

    /// An 11×8 "butterfly" enemy, drawn from this bitmap so there is no image
    /// asset; `#` is a lit pixel. The same table is in `GalagaScreen.kt`.
    private static let sprite: [String] = [
        "#.........#",
        "#..#...#..#",
        "##.#####.##",
        ".#########.",
        "..##.#.##..",
        ".###.#.###.",
        "#.##...##.#",
        "#..#...#..#",
    ]
    /// Points per sprite pixel: 33×24 pt, the Invaders sprite's size.
    private static let spritePixel: CGFloat = 3

    /// The sprite as one path centred on `centre`.
    private static func spritePath(centre: CGPoint) -> Path {
        let cols = sprite[0].count
        let origin = CGPoint(x: centre.x - CGFloat(cols) * spritePixel / 2,
                             y: centre.y - CGFloat(sprite.count) * spritePixel / 2)
        var path = Path()
        for (r, row) in sprite.enumerated() {
            for (c, cell) in row.enumerated() where cell == "#" {
                path.addRect(CGRect(x: origin.x + CGFloat(c) * spritePixel,
                                    y: origin.y + CGFloat(r) * spritePixel,
                                    width: spritePixel, height: spritePixel))
            }
        }
        return path
    }

    /// The player's ship at the bottom: a small chevron.
    private static func shipPath(centre: CGPoint) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: centre.x, y: centre.y - 9))
        path.addLine(to: CGPoint(x: centre.x + 10, y: centre.y + 7))
        path.addLine(to: CGPoint(x: centre.x, y: centre.y + 2))
        path.addLine(to: CGPoint(x: centre.x - 10, y: centre.y + 7))
        path.closeSubpath()
        return path
    }

    /// The ramp in words for the setup card: where a game opens and where it
    /// climbs to. Keying mode sends nothing, so it names the decoder speed.
    private var speedNote: String {
        let target = Int(model.settings.wpm.rounded())
        let start = Int(GalagaGame.rampStart(characterWpm: model.settings.wpm).rounded())
        if input == .keying { return "Decoded at your \(target) WPM character speed." }
        if start >= target { return "Sent at your \(target) WPM character speed." }
        return "Sent from \(start) WPM, stepping up \(Int(GalagaGame.rampStep)) WPM every "
            + "\(GalagaGame.hitsPerRampStep) hits to your \(target) WPM character speed. "
            + "A dive that gets through steps it back."
    }

    var body: some View {
        Group {
            switch phase {
            case .setup:   setupCard
            case .playing: playfield
            case .over:    gameOverCard
            }
        }
        .onDisappear { model.stopGalaga() }
    }

    // MARK: - Setup

    private var setupCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(TrainingMode.galaga.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                optionPicker("How you answer", selection: $inputRaw,
                             options: InvadersInput.allCases.map { Option(id: $0.rawValue, label: $0.label) })
                Text(Self.inputBlurb(input))
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
                Text(speedNote)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

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
                    canvas(size: geo.size)
                        // The frame clock: every tick moves the game on by the
                        // real time elapsed, so a dropped frame costs no distance.
                        .onChange(of: context.date) { now in step(now: now) }
                }
            }
            .frame(maxHeight: .infinity)
            if input == .icr {
                Text("Tap an enemy to hear it again")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                keyboard
            } else {
                GalagaKeyPanel(wpm: model.settings.wpm,
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
            if input == .icr {
                Spacer()
                stat("WPM", "\(hud.wpm)")
            }
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

    /// Where the unit-space paths land on the canvas: the field keeps a
    /// margin so the sprite stays whole at the edges of its swoop, and the
    /// bottom of the dive is the player's line.
    private func canvasPoint(_ p: GalagaPath.Point, in size: CGSize) -> CGPoint {
        let inset: CGFloat = 20
        let top: CGFloat = input == .keying ? 30 : 18
        let bottom = size.height - 36
        return CGPoint(x: inset + CGFloat(p.x) * (size.width - 2 * inset),
                       y: top + CGFloat(p.y) * (bottom - top))
    }

    private func canvas(size: CGSize) -> some View {
        Canvas { context, size in
            let ground = size.height - 18
            var line = Path()
            line.move(to: CGPoint(x: 0, y: ground))
            line.addLine(to: CGPoint(x: size.width, y: ground))
            context.stroke(line, with: .color(Theme.teal.opacity(0.6)), lineWidth: 2)
            context.fill(Self.shipPath(centre: CGPoint(x: size.width / 2, y: ground - 12)),
                         with: .color(Theme.teal))

            let font = Theme.copyFont(size: 22, weight: .bold, monospaced: true,
                                      slashedZero: model.settings.slashedZero)
            for enemy in field {
                let p = canvasPoint(GalagaPath.position(of: enemy, columns: columns), in: size)
                let tint = enemy.state == .diving ? Theme.tealBright : Theme.teal
                // Copy mode keeps the character to the ear; send mode shows
                // it directly above the sprite to key.
                if input == .keying {
                    context.fill(Self.spritePath(centre: CGPoint(x: p.x, y: p.y + 6)), with: .color(tint))
                    context.draw(Text(String(enemy.character)).font(font).foregroundColor(.white),
                                 at: CGPoint(x: p.x, y: p.y - 14))
                } else {
                    context.fill(Self.spritePath(centre: p), with: .color(tint))
                }
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
        .onTapGesture { location in replay(at: location, size: size) }
        .accessibilityLabel("Play field, \(field.count) enemies")
    }

    /// Copy mode: tap near an enemy to hear its character again.
    private func replay(at location: CGPoint, size: CGSize) {
        guard input == .icr, let game else { return }
        let nearest = game.enemies.min { a, b in
            let pa = canvasPoint(GalagaPath.position(of: a, columns: columns), in: size)
            let pb = canvasPoint(GalagaPath.position(of: b, columns: columns), in: size)
            return hypot(pa.x - location.x, pa.y - location.y) < hypot(pb.x - location.x, pb.y - location.y)
        }
        guard let nearest else { return }
        model.playGalaga(nearest.character, wpm: game.currentWpm)
    }

    /// Copy mode: the arcade games' QWERTY keyboard — `InvadersKeyboard.rows`
    /// lays it out: a digit row when the pool has digits, the three letter
    /// rows always, any punctuation below. A key outside the pool stays in
    /// place, dimmed and dead, so the layout never shifts as the Koch set
    /// grows; a hardware keyboard presses the live ones too.
    private var keyboard: some View {
        let live = Set(pool)
        let rows = InvadersKeyboard.rows(for: pool)
        let spacing: CGFloat = 4
        let keyHeight: CGFloat = 40
        return GeometryReader { geo in
            let keyWidth = (geo.size.width - spacing * 9) / 10
            VStack(spacing: spacing) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: spacing) {
                        ForEach(row.map(String.init), id: \.self) { key in
                            let enabled = live.contains(Character(key))
                            Button {
                                shoot(Character(key))
                            } label: {
                                Text(key)
                                    .font(Theme.copyFont(size: 18, weight: .semibold, monospaced: true,
                                                         slashedZero: model.settings.slashedZero))
                                    .frame(width: keyWidth, height: keyHeight)
                                    .foregroundStyle(.white)
                                    .background(Theme.navyRaised,
                                                in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                            }
                            .keyboardShortcut(KeyEquivalent(Character(key.lowercased())), modifiers: [])
                            .disabled(!enabled)
                            .opacity(enabled ? 1 : 0.3)
                            .accessibilityLabel("Shoot \(key)")
                            .accessibilityHidden(!enabled)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .frame(height: CGFloat(rows.count) * keyHeight + CGFloat(max(0, rows.count - 1)) * spacing)
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
            if input == .icr {
                // The speed the ramp reached; nothing is sent in keying mode.
                Text("Speed reached: \(hud.bestWpm) WPM")
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
            }
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
        let config = GalagaGame.Config(characters: pool, difficulty: difficulty,
                                       characterWpm: model.settings.wpm)
        let g = GalagaGame(config: config)
        game = g
        field = []
        columns = g.formation.columns
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
        // Cap a long gap (the app was backgrounded) so a dive does not land
        // in one step.
        let dt = min(0.1, now.timeIntervalSince(last))
        for event in game.advance(by: dt) {
            switch event {
            case .entered(let enemy), .dived(let enemy):
                if input == .icr {
                    // At the ramp's current speed, which the hits and
                    // landings in this same step may just have moved.
                    let duration = model.playGalaga(enemy.character, wpm: game.currentWpm)
                    toneEnd[enemy.id] = now.addingTimeInterval(duration)
                }
            case .landed(let enemy):
                toneEnd[enemy.id] = nil
                model.noteGalagaLanding(target: enemy.character)
                Haptics.error()
                flash("\(enemy.character) got through", for: 1.0)
            case .gameOver:
                model.stopGalaga()
                phase = .over
            }
        }
        field = game.enemies
        columns = game.formation.columns
        syncHUD(game)
    }

    private func shoot(_ character: Character) {
        guard phase == .playing, let game else { return }
        let now = Date()
        let threat = game.mostThreatening
        let shot = game.shoot(character)
        if let hit = shot.enemy {
            let ttr = toneEnd[hit.id].map { max(0, now.timeIntervalSince($0)) } ?? 0
            toneEnd[hit.id] = nil
            model.noteGalagaShot(target: hit.character, chosen: hit.character, ttr: ttr)
            Haptics.success()
            flash(shot.waveCleared ? "Wave \(game.wave)!" : "+\(shot.points)", for: 0.8)
        } else {
            // A wrong key: confused with whatever was the biggest threat.
            if let threat {
                model.noteGalagaShot(target: threat.character,
                                     chosen: Character(String(character).uppercased()), ttr: 0)
            }
            Haptics.error()
            flash("miss", for: 0.6)
        }
        field = game.enemies
        columns = game.formation.columns
        syncHUD(game)
    }

    private func flash(_ text: String, for seconds: TimeInterval) {
        flashText = text
        flashUntil = Date().addingTimeInterval(seconds)
    }

    private func syncHUD(_ g: GalagaGame) {
        let next = HUD(score: g.score, wave: g.wave, lives: g.lives, combo: g.combo,
                       multiplier: g.multiplier, bestCombo: g.bestCombo, accuracy: g.accuracy,
                       wpm: Int(g.currentWpm.rounded()), bestWpm: Int(g.bestWpm.rounded()))
        if next != hud {
            hud = next
            model.noteGameScore(g.score)   // per-mode personal best
        }
    }
}

/// Send mode's input: the on-screen key (a hardware Vail/BLE-MIDI key feeds
/// the same decoder). Owns its `SendingKeyer` the way `SendingKeyerView` does,
/// so the decoder is built at the session speed; each finalised character is
/// handed to `onCharacter` as the shot. The same panel Invaders' keying mode
/// uses, kept with this view so neither game's file depends on the other's.
private struct GalagaKeyPanel: View {
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
                Text("Key the diving enemy")
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
            .accessibilityHint("Press and hold to key the character on the diving enemy")
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
