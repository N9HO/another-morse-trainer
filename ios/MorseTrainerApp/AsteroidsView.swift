import SwiftUI

/// CW Asteroids (#189, part of #170): labelled asteroids drift in toward the
/// ship at the centre and the learner destroys each one by sending its label
/// (see it, send it) or by tapping the one whose label was just sent (hear
/// it, tap it). The rules live in `AsteroidsGame` (MorseKit); this view is
/// the frame clock, the sound, the input and the drawing. It runs inside the
/// normal session (`AppModel.start()` for `.asteroids`) the way Invaders
/// does, so the End button, the mode menu and the session summary all work
/// as everywhere else, and the tally goes to history through
/// `AppModel.noteAsteroidsHit` and friends.
///
/// Twin of `AsteroidsScreen.kt` on Android.
struct AsteroidsView: View {
    @EnvironmentObject var model: AppModel

    // Setup choices persist across launches, like every other mode's.
    @AppStorage("asteroids.input") private var inputRaw = AsteroidsInput.send.rawValue
    @AppStorage("asteroids.difficulty") private var difficultyRaw = InvadersDifficulty.normal.rawValue
    @AppStorage("asteroids.characters") private var characterSetRaw = InvadersCharacterSet.active.rawValue

    private enum Phase { case setup, playing, over }
    @State private var phase: Phase = .setup
    @State private var game: AsteroidsGame?
    /// A snapshot of the field for drawing; refreshed every frame from `game`.
    @State private var field: [Asteroid] = []
    @State private var elapsed = 0.0
    @State private var hud = HUD()
    @State private var lastFrame: Date?
    /// Copy mode: when the armed label finished sounding, so a hit's
    /// time-to-recognize runs from the end of the tone.
    @State private var cueEnd: Date?
    /// Send mode: the characters sent so far toward a label.
    @State private var sendBuffer = ""
    @State private var flashText = ""
    @State private var flashUntil = Date.distantPast

    private struct HUD: Equatable {
        var score = 0, wave = 1, lives = 3, combo = 0, multiplier = 1
        var bestCombo = 0, accuracy = 0.0
        var wpm = 0, bestWpm = 0
    }

    private struct Option: Identifiable {
        let id: String
        let label: String
    }

    private var input: AsteroidsInput { AsteroidsInput(rawValue: inputRaw) ?? .send }
    private var difficulty: InvadersDifficulty { InvadersDifficulty(rawValue: difficultyRaw) ?? .normal }
    private var characterSet: InvadersCharacterSet { InvadersCharacterSet(rawValue: characterSetRaw) ?? .active }

    /// The game's character set, letters first then digits, the way the
    /// recognition chart lists it.
    private var pool: [Character] {
        model.invadersCharacters(characterSet)
            .map(String.init)
            .sorted(by: SessionRecord.characterOrder)
            .compactMap(\.first)
    }

    /// The ramp in words for the setup card. Send mode sends nothing, so it
    /// names the decoder speed instead.
    private var speedNote: String {
        let target = Int(model.settings.wpm.rounded())
        let start = Int(AsteroidsGame.rampStart(characterWpm: model.settings.wpm).rounded())
        if input == .send { return "Decoded at your \(target) WPM character speed." }
        if start >= target { return "Sent at your \(target) WPM character speed." }
        return "Sent from \(start) WPM, stepping up \(Int(AsteroidsGame.rampStep)) WPM every "
            + "\(AsteroidsGame.hitsPerRampStep) hits to your \(target) WPM character speed. "
            + "An asteroid that reaches the ship steps it back."
    }

    var body: some View {
        Group {
            switch phase {
            case .setup:   setupCard
            case .playing: playfield
            case .over:    gameOverCard
            }
        }
        .onDisappear { model.stopAsteroids() }
    }

    // MARK: - Setup

    private var setupCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(TrainingMode.asteroids.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                optionPicker("How you play", selection: $inputRaw,
                             options: AsteroidsInput.allCases.map { Option(id: $0.rawValue, label: $0.label) })
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
                Text("From wave \(AsteroidsGame.wordWaveStart), larger asteroids carry short words and callsigns spelt from these characters; a word splits into its characters when hit.")
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

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
            if input == .copy {
                HStack {
                    Text("Tap the asteroid you heard")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                    Spacer()
                    Button {
                        replayCue()
                    } label: {
                        Label("Hear it again", systemImage: "speaker.wave.2.fill")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.teal)
                }
                .padding(.horizontal, 4)
            } else {
                AsteroidsKeyPanel(wpm: model.settings.wpm,
                                  toneHz: model.settings.toneFrequency,
                                  slashedZero: model.settings.slashedZero,
                                  buffer: sendBuffer) { ch in
                    send(ch)
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
            if input == .copy {
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

    /// Radius multipliers around an asteroid's outline, so each one is a
    /// lumpy rock rather than a circle; offset by id so they differ.
    private static let lumps: [CGFloat] = [1.0, 0.82, 0.95, 0.72, 1.0, 0.88, 0.76, 1.0, 0.9, 0.8]

    /// Drawn radius, in points, for a label: single characters small,
    /// fragments smaller, words wider for their letters.
    private static func radius(for a: Asteroid) -> CGFloat {
        if a.isFragment { return 15 }
        if a.label.count == 1 { return 19 }
        return 16 + 6 * CGFloat(a.label.count)
    }

    private static func rockPath(centre: CGPoint, radius: CGFloat, id: Int, spin: Double) -> Path {
        var path = Path()
        let n = lumps.count
        for k in 0..<n {
            let theta = spin + Double(k) * 2 * .pi / Double(n)
            let r = radius * lumps[(k + id) % n]
            let p = CGPoint(x: centre.x + r * CGFloat(cos(theta)), y: centre.y + r * CGFloat(sin(theta)))
            if k == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }

    private static func point(of a: Asteroid, in size: CGSize) -> CGPoint {
        CGPoint(x: CGFloat(a.x) * size.width, y: CGFloat(a.y) * size.height)
    }

    private func canvas(size: CGSize) -> some View {
        Canvas { context, size in
            let centre = CGPoint(x: size.width / 2, y: size.height / 2)
            let font = Theme.copyFont(size: 17, weight: .bold, monospaced: true,
                                      slashedZero: model.settings.slashedZero)

            // The ship: a small triangle facing the nearest asteroid, inside
            // a faint shield ring.
            let facing: Double
            if let nearest = field.min(by: { $0.progress > $1.progress }) {
                let p = Self.point(of: nearest, in: size)
                facing = atan2(Double(p.y - centre.y), Double(p.x - centre.x))
            } else {
                facing = -.pi / 2
            }
            var ship = Path()
            let nose = CGPoint(x: centre.x + 14 * CGFloat(cos(facing)), y: centre.y + 14 * CGFloat(sin(facing)))
            let left = CGPoint(x: centre.x + 11 * CGFloat(cos(facing + 2.5)), y: centre.y + 11 * CGFloat(sin(facing + 2.5)))
            let right = CGPoint(x: centre.x + 11 * CGFloat(cos(facing - 2.5)), y: centre.y + 11 * CGFloat(sin(facing - 2.5)))
            ship.move(to: nose); ship.addLine(to: left); ship.addLine(to: centre); ship.addLine(to: right); ship.closeSubpath()
            context.stroke(Path(ellipseIn: CGRect(x: centre.x - 26, y: centre.y - 26, width: 52, height: 52)),
                           with: .color(Theme.teal.opacity(0.35)), lineWidth: 1)
            context.fill(ship, with: .color(Theme.teal))

            for a in field {
                let p = Self.point(of: a, in: size)
                let r = Self.radius(for: a)
                let spin = elapsed * 0.6 * (a.id.isMultiple(of: 2) ? 1 : -1)
                let rock = Self.rockPath(centre: p, radius: r, id: a.id, spin: spin)
                context.fill(rock, with: .color(Theme.navyRaised))
                context.stroke(rock, with: .color(Theme.tealBright), lineWidth: a.isFragment ? 1 : 1.5)
                context.draw(Text(a.label).font(font).foregroundColor(.white), at: p)
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
        .onTapGesture { location in tapped(at: location, in: size) }
        .accessibilityLabel("Play field, \(field.count) asteroids")
    }

    /// Copy mode: a tap on the field. The nearest asteroid within reach is
    /// the one tapped; the ship replays the cue; empty space does nothing.
    private func tapped(at location: CGPoint, in size: CGSize) {
        guard input == .copy, phase == .playing, let game else { return }
        let centre = CGPoint(x: size.width / 2, y: size.height / 2)
        if hypot(location.x - centre.x, location.y - centre.y) <= 30 {
            replayCue()
            return
        }
        var best: (Asteroid, CGFloat)?
        for a in field {
            let p = Self.point(of: a, in: size)
            let d = hypot(location.x - p.x, location.y - p.y)
            if d <= Self.radius(for: a) + 14, best == nil || d < best!.1 { best = (a, d) }
        }
        guard let (target, _) = best else { return }
        let now = Date()
        let shot = game.tap(target.id)
        resolve(shot, now: now)
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
            if input == .copy {
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
        let config = AsteroidsGame.Config(characters: pool, words: model.asteroidsWords(), input: input,
                                          difficulty: difficulty, characterWpm: model.settings.wpm)
        let g = AsteroidsGame(config: config)
        game = g
        field = []
        elapsed = 0
        cueEnd = nil
        sendBuffer = ""
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
        // empty onto the ship in one step.
        let dt = min(0.1, now.timeIntervalSince(last))
        for event in game.advance(by: dt) {
            switch event {
            case .spawned:
                break
            case .cued(let a):
                cue(a, now: now, wpm: game.currentWpm)
            case .struck(let a):
                model.noteAsteroidsStrike(label: a.label)
                Haptics.error()
                flash("\(a.label) hit the ship", for: 1.0)
            case .gameOver:
                model.stopAsteroids()
                phase = .over
            }
        }
        sync(game)
    }

    /// Copy mode: sound the armed label at the ramp's current speed and date
    /// the end of the tone.
    private func cue(_ a: Asteroid, now: Date, wpm: Double) {
        let duration = model.playAsteroid(a.label, wpm: wpm)
        cueEnd = now.addingTimeInterval(duration)
    }

    private func replayCue() {
        guard let game, let armed = game.armed else { return }
        cue(armed, now: Date(), wpm: game.currentWpm)
    }

    /// Send mode: one decoded character from the key.
    private func send(_ character: Character) {
        guard phase == .playing, let game else { return }
        let now = Date()
        let shot = game.send(character)
        resolve(shot, now: now)
    }

    private func resolve(_ shot: AsteroidsShot, now: Date) {
        guard let game else { return }
        switch shot.outcome {
        case .hit:
            if let hit = shot.asteroid {
                let ttr = cueEnd.map { max(0, now.timeIntervalSince($0)) } ?? 0
                model.noteAsteroidsHit(label: hit.label, ttr: ttr)
            }
            cueEnd = nil
            if let next = shot.cued { cue(next, now: now, wpm: game.currentWpm) }
            Haptics.success()
            flash(shot.waveCleared ? "Wave \(game.wave)!" : "+\(shot.points)", for: 0.8)
        case .miss:
            model.noteAsteroidsMiss(expected: shot.expected, chosen: shot.chosen)
            Haptics.error()
            flash("miss", for: 0.6)
        case .partial, .ignored:
            break
        }
        sync(game)
    }

    private func flash(_ text: String, for seconds: TimeInterval) {
        flashText = text
        flashUntil = Date().addingTimeInterval(seconds)
    }

    private func sync(_ g: AsteroidsGame) {
        field = g.asteroids
        elapsed = g.elapsed
        if sendBuffer != g.sendBuffer { sendBuffer = g.sendBuffer }
        syncHUD(g)
    }

    private func syncHUD(_ g: AsteroidsGame) {
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
/// the same decoder). Owns its `SendingKeyer` the way `SendingKeyerView`
/// does, so the decoder is built at the session speed; each finalised
/// character is handed to `onCharacter`, and `buffer` shows what has been
/// sent toward a label so far.
private struct AsteroidsKeyPanel: View {
    let slashedZero: Bool
    let buffer: String
    let onCharacter: (Character) -> Void
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false

    init(wpm: Double, toneHz: Double, slashedZero: Bool, buffer: String,
         onCharacter: @escaping (Character) -> Void) {
        self.slashedZero = slashedZero
        self.buffer = buffer
        self.onCharacter = onCharacter
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("Key an asteroid's label")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(buffer.isEmpty ? "—" : buffer + "_")
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
            .accessibilityHint("Press and hold to key the label on an asteroid")
        }
        .onAppear { sender.start() }
        .onDisappear { sender.stop() }
        // A finished character is sent at once; the decoder finalises it on
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
