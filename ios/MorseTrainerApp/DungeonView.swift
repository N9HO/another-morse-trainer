import SwiftUI

/// CW Dungeon (#186, #170): a small roguelike where monsters cast spell words
/// in Morse and the learner keys the counter word before the attack lands.
/// The rules live in `DungeonGame` (MorseKit); this view is the frame clock,
/// the sound, the key and the drawing. It runs inside the normal session
/// (`AppModel.start()` for `.dungeon`), so the End button, the mode menu and
/// the session summary all work as everywhere else, and every keyed character
/// goes to history through `AppModel.noteDungeonOutcomes`.
///
/// Twin of `DungeonScreen.kt` on Android.
struct DungeonView: View {
    @EnvironmentObject var model: AppModel

    // Setup choices persist across launches, like every other mode's.
    @AppStorage("dungeon.difficulty") private var difficultyRaw = InvadersDifficulty.normal.rawValue
    @AppStorage("dungeon.characters") private var characterSetRaw = InvadersCharacterSet.active.rawValue

    private enum Phase { case setup, playing, over }
    @State private var phase: Phase = .setup
    @State private var game: DungeonGame?
    /// A snapshot of the room for drawing; refreshed every frame from `game`.
    @State private var scene = Scene()
    @State private var hud = HUD()
    @State private var lastFrame: Date?
    /// A flash of feedback on the room: the last counter's points, or what hit you.
    @State private var flashText = ""
    @State private var flashUntil = Date.distantPast
    /// What the key panel has decoded so far, so a late attack can still
    /// grade the characters that were keyed.
    @State private var keyedSoFar = ""
    /// Bumped to clear the key panel: on every cast and every resolution.
    @State private var keyReset = 0

    private struct Scene: Equatable {
        var room = 1
        var monsters: [DungeonMonster] = []
        /// The monster casting now, if any.
        var casterId: Int?
        /// True while the spell is still sounding.
        var sending = false
        /// The attack window left, 0…1, once the spell has sounded.
        var windowFraction: Double?
    }

    private struct HUD: Equatable {
        var score = 0, room = 1, lives = 3, maxLives = 3, multiplier = 1
        var bestCombo = 0, accuracy = 0.0, roomsCleared = 0
        var wpm = 0, bestWpm = 0
    }

    private struct Option: Identifiable {
        let id: String
        let label: String
    }

    private var difficulty: InvadersDifficulty { InvadersDifficulty(rawValue: difficultyRaw) ?? .normal }
    private var characterSet: InvadersCharacterSet { InvadersCharacterSet(rawValue: characterSetRaw) ?? .active }

    /// The spell book for the chosen set: what it spells, or the starters.
    private var pool: DungeonPool { DungeonSpells.pool(for: model.invadersCharacters(characterSet)) }

    /// The ramp in words for the setup card.
    private var speedNote: String {
        let target = Int(model.settings.wpm.rounded())
        let start = Int(DungeonGame.rampStart(characterWpm: model.settings.wpm).rounded())
        let spacing = model.settings.farnsworth
            ? " Farnsworth spacing at \(Int(model.settings.effectiveWpm.rounded())) WPM applies to the spell."
            : ""
        if start >= target { return "Spells are sent at your \(target) WPM character speed." + spacing }
        return "Spells are sent from \(start) WPM, stepping up \(Int(DungeonGame.rampStep)) WPM every "
            + "\(DungeonGame.hitsPerRampStep) counters to your \(target) WPM character speed. "
            + "A hit taken steps it back." + spacing
    }

    var body: some View {
        Group {
            switch phase {
            case .setup:   setupCard
            case .playing: playfield
            case .over:    gameOverCard
            }
        }
        .onDisappear { model.stopDungeon() }
    }

    // MARK: - Setup

    private var setupCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(TrainingMode.dungeon.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                optionPicker("Characters", selection: $characterSetRaw,
                             options: InvadersCharacterSet.allCases.map { Option(id: $0.rawValue, label: $0.label) })

                VStack(alignment: .leading, spacing: 6) {
                    Text("Spell book").font(.subheadline.weight(.semibold))
                    Text("Hear the spell, key the counter. ♥ marks a counter that also heals you.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                    spellBook(pool.spells, columns: 2)
                    if pool.fallback {
                        Text("Your active characters spell fewer than \(DungeonSpells.minPool) pairs, so the starter spells are in use. They need the first ten Koch characters.")
                            .font(.footnote)
                            .foregroundStyle(Theme.teal)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

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
                    Text("Enter the dungeon")
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

    /// The pairs of the book laid out in columns, in book order.
    private func spellBook(_ spells: [DungeonSpell], columns: Int) -> some View {
        let grid = Array(repeating: GridItem(.flexible(), alignment: .leading), count: columns)
        return LazyVGrid(columns: grid, alignment: .leading, spacing: 4) {
            ForEach(spells, id: \.spell) { s in
                HStack(spacing: 4) {
                    Text(s.spell)
                        .foregroundStyle(Theme.textSecondary)
                    Text("→").foregroundStyle(Theme.textSecondary)
                    Text(s.counter).foregroundStyle(.white)
                    if s.heals { Text("♥").foregroundStyle(Color.red) }
                }
                .font(Theme.copyFont(style: .footnote, weight: .semibold, monospaced: true,
                                     slashedZero: model.settings.slashedZero))
            }
        }
    }

    // MARK: - Play

    private var playfield: some View {
        VStack(spacing: 10) {
            hudBar
            GeometryReader { geo in
                TimelineView(.animation) { context in
                    canvas(size: geo.size)
                        // The frame clock: every tick moves the game on by the
                        // real time elapsed, so a dropped frame costs no time.
                        .onChange(of: context.date) { now in step(now: now) }
                }
            }
            .frame(maxHeight: .infinity)
            Text("Tap a monster to hear its spell again")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(pool.spells, id: \.spell) { s in
                        Text("\(s.spell) → \(s.counter)\(s.heals ? " ♥" : "")")
                            .font(Theme.copyFont(style: .caption1, weight: .semibold, monospaced: true,
                                                 slashedZero: model.settings.slashedZero))
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
                .padding(.horizontal, 4)
            }
            .accessibilityLabel("Spell book")
            DungeonKeyPanel(expected: game?.pendingCast?.spell.counter,
                            resetToken: keyReset,
                            wpm: model.settings.wpm,
                            toneHz: model.settings.toneFrequency,
                            slashedZero: model.settings.slashedZero,
                            onText: { keyedSoFar = $0 },
                            onWord: { castWord($0) })
        }
    }

    private var hudBar: some View {
        HStack {
            stat("Score", "\(hud.score)")
            Spacer()
            stat("Room", "\(hud.room)")
            Spacer()
            HStack(spacing: 2) {
                ForEach(0..<max(1, hud.maxLives), id: \.self) { i in
                    Image(systemName: i < hud.lives ? "heart.fill" : "heart")
                        .foregroundStyle(i < hud.lives ? Color.red : Theme.textSecondary)
                }
            }
            .accessibilityLabel("\(hud.lives) lives")
            Spacer()
            stat("Combo", "×\(hud.multiplier)")
            Spacer()
            stat("WPM", "\(hud.wpm)")
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

    private func canvas(size: CGSize) -> some View {
        Canvas { context, size in
            // The floor: a tile grid inside a wall.
            let tile: CGFloat = 28
            var grid = Path()
            var x: CGFloat = tile
            while x < size.width { grid.move(to: CGPoint(x: x, y: 0)); grid.addLine(to: CGPoint(x: x, y: size.height)); x += tile }
            var y: CGFloat = tile
            while y < size.height { grid.move(to: CGPoint(x: 0, y: y)); grid.addLine(to: CGPoint(x: size.width, y: y)); y += tile }
            context.stroke(grid, with: .color(Color.white.opacity(0.04)), lineWidth: 1)
            let wall = Path(roundedRect: CGRect(x: 4, y: 4, width: size.width - 8, height: size.height - 8), cornerRadius: 6)
            context.stroke(wall, with: .color(Theme.teal.opacity(0.35)), lineWidth: 3)

            let nameFont = Theme.copyFont(size: 11, weight: .semibold, monospaced: true,
                                          slashedZero: model.settings.slashedZero)
            let count = max(1, scene.monsters.count)
            let monsterY = size.height * 0.34
            for (i, m) in scene.monsters.enumerated() {
                let cx = size.width * (CGFloat(i) + 0.5) / CGFloat(count)
                let alive = !m.isDown
                let tint = alive ? Theme.tealBright : Theme.textSecondary.opacity(0.3)
                context.fill(DungeonSprites.path(for: m.kind, centre: CGPoint(x: cx, y: monsterY)), with: .color(tint))
                // Hit pips above the sprite.
                let pip: CGFloat = 8
                let pipsWidth = CGFloat(m.maxHits) * pip + CGFloat(m.maxHits - 1) * 3
                for h in 0..<m.maxHits {
                    let rect = CGRect(x: cx - pipsWidth / 2 + CGFloat(h) * (pip + 3), y: monsterY - 34, width: pip, height: pip)
                    if h < m.hits {
                        context.fill(Path(rect), with: .color(Color.red))
                    } else {
                        context.stroke(Path(rect), with: .color(Color.red.opacity(0.4)), lineWidth: 1)
                    }
                }
                context.draw(Text(m.kind.label).font(nameFont).foregroundColor(alive ? .white : Theme.textSecondary),
                             at: CGPoint(x: cx, y: monsterY + 30))
                if alive, m.id == scene.casterId {
                    if scene.sending {
                        context.draw(Text("· · ·").font(.headline).foregroundColor(Theme.tealBright),
                                     at: CGPoint(x: cx, y: monsterY - 48))
                    } else if let f = scene.windowFraction {
                        // The attack window, draining under the caster.
                        let barWidth: CGFloat = 60
                        let bar = CGRect(x: cx - barWidth / 2, y: monsterY + 40, width: barWidth, height: 6)
                        context.fill(Path(roundedRect: bar, cornerRadius: 3), with: .color(Theme.navyRaised))
                        let fill = CGRect(x: bar.minX, y: bar.minY, width: barWidth * CGFloat(f), height: 6)
                        let colour = f > 0.4 ? Theme.teal : Color.red
                        context.fill(Path(roundedRect: fill, cornerRadius: 3), with: .color(colour))
                    }
                }
            }

            // The hero, at the bottom.
            context.fill(DungeonSprites.path(for: nil, centre: CGPoint(x: size.width / 2, y: size.height - 44)),
                         with: .color(Theme.teal))

            if flashUntil > Date() {
                context.draw(Text(flashText).font(.headline).foregroundColor(Theme.tealBright),
                             at: CGPoint(x: size.width / 2, y: size.height / 2 + 24))
            }
        }
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
            .strokeBorder(Theme.hairline, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture { replay() }
        .accessibilityLabel("Room \(scene.room), \(scene.monsters.filter { !$0.isDown }.count) monsters standing")
    }

    /// Hear the pending spell again. Free, but the window keeps running.
    private func replay() {
        guard let game, let cast = game.pendingCast else { return }
        model.playDungeonSpell(cast.spell.spell, timing: cast.timing)
    }

    // MARK: - Game over

    private var gameOverCard: some View {
        VStack(spacing: 16) {
            Text("You fell")
                .font(.title2.bold())
            HStack(spacing: 24) {
                stat("Score", "\(hud.score)")
                stat("Rooms", "\(hud.roomsCleared)")
                stat("Accuracy", "\(Int((hud.accuracy * 100).rounded()))%")
                stat("Best combo", "\(hud.bestCombo)")
            }
            .padding(.vertical, 8)
            Text("Speed reached: \(hud.bestWpm) WPM")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
            Button {
                Haptics.tap()
                startGame()
            } label: {
                Text("Try again")
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
                Text("Leave the dungeon")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .brandCard()
    }

    // MARK: - Game loop

    private func startGame() {
        let config = DungeonGame.Config(
            spells: pool.spells, difficulty: difficulty,
            characterWpm: model.settings.wpm,
            effectiveWpm: model.settings.farnsworth ? model.settings.effectiveWpm : nil)
        let g = DungeonGame(config: config)
        game = g
        flashUntil = .distantPast
        lastFrame = nil
        keyedSoFar = ""
        keyReset += 1
        syncScene(g)
        syncHUD(g)
        phase = .playing
    }

    private func step(now: Date) {
        guard phase == .playing, let game else { return }
        defer { lastFrame = now }
        guard let last = lastFrame else { return }
        // Cap a long gap (the app was backgrounded) so a whole window does
        // not close in one step.
        let dt = min(0.1, now.timeIntervalSince(last))
        for event in game.advance(by: dt) {
            switch event {
            case .cast(let cast):
                keyReset += 1
                keyedSoFar = ""
                model.playDungeonSpell(cast.spell.spell, timing: cast.timing)
            case .attacked(let cast):
                // Late: whatever was keyed is graded against the counter,
                // and the rest of it is missed.
                model.noteDungeonOutcomes(DungeonGame.characterOutcomes(expected: cast.spell.counter, keyed: keyedSoFar))
                keyReset += 1
                keyedSoFar = ""
                Haptics.error()
                flash("\(cast.spell.spell) hit you — \(cast.spell.counter)", for: 1.2)
            case .gameOver:
                model.stopDungeon()
                phase = .over
            }
        }
        syncScene(game)
        syncHUD(game)
    }

    private func castWord(_ word: String) {
        guard phase == .playing, let game, let result = game.cast(word) else { return }
        model.stopDungeon()
        model.noteDungeonOutcomes(DungeonGame.characterOutcomes(expected: result.spell.counter, keyed: result.keyed))
        keyReset += 1
        keyedSoFar = ""
        if result.isCountered {
            Haptics.success()
            if result.roomCleared {
                flash("Room \(game.room - 1) cleared! +\(DungeonGame.roomClearBonus)", for: 1.2)
            } else if result.healed {
                flash("+\(result.points) and a life back", for: 1.0)
            } else {
                flash("+\(result.points)", for: 0.8)
            }
        } else {
            Haptics.error()
            flash(result.isEcho ? "That was the spell — key \(result.spell.counter)"
                                : "\(result.keyed) is not \(result.spell.counter)", for: 1.2)
        }
        if result.gameOver {
            model.stopDungeon()
            phase = .over
        }
        syncScene(game)
        syncHUD(game)
    }

    private func flash(_ text: String, for seconds: TimeInterval) {
        flashText = text
        flashUntil = Date().addingTimeInterval(seconds)
    }

    private func syncScene(_ g: DungeonGame) {
        var fraction: Double?
        if let cast = g.pendingCast, !g.isSending {
            fraction = max(0, min(1, (g.windowRemaining ?? 0) / max(0.001, cast.window)))
        }
        let next = Scene(room: g.room, monsters: g.monsters, casterId: g.pendingCast?.monsterId,
                         sending: g.isSending, windowFraction: fraction)
        if next != scene { scene = next }
    }

    private func syncHUD(_ g: DungeonGame) {
        let next = HUD(score: g.score, room: g.room, lives: g.lives, maxLives: g.maxLives,
                       multiplier: g.multiplier, bestCombo: g.bestCombo, accuracy: g.accuracy,
                       roomsCleared: g.roomsCleared,
                       wpm: Int(g.currentWpm.rounded()), bestWpm: Int(g.bestWpm.rounded()))
        if next != hud { hud = next }
    }
}

/// The monsters and the hero, drawn from bitmaps so there is no image asset;
/// `#` is a lit pixel. The same tables are in `DungeonScreen.kt`.
private enum DungeonSprites {
    static let pixel: CGFloat = 4

    static let slime: [String] = [
        ".........",
        "...###...",
        "..#####..",
        ".#######.",
        ".##.#.##.",
        ".#######.",
        ".#######.",
        "..#####..",
    ]
    static let bat: [String] = [
        "#.......#",
        "##.....##",
        "###...###",
        "#########",
        ".#######.",
        "..##.##..",
        "...#.#...",
        ".........",
    ]
    static let skeleton: [String] = [
        "...###...",
        "..#####..",
        "..#.#.#..",
        "..#####..",
        "...###...",
        ".#.###.#.",
        "..#####..",
        "...#.#...",
        "...#.#...",
    ]
    static let ghost: [String] = [
        "...###...",
        "..#####..",
        ".###.#.#.",
        ".#######.",
        ".#######.",
        ".#######.",
        ".#######.",
        ".#.#.#.#.",
    ]
    static let dragon: [String] = [
        ".....##......",
        "....####.....",
        "...######.#..",
        "..##.####.##.",
        ".############",
        ".###########.",
        "..##.####.#..",
        "...#..#..#...",
        ".........#...",
    ]
    static let hero: [String] = [
        "..###..",
        "..###..",
        ".#####.",
        "#.###.#",
        "#.###.#",
        "..###..",
        "..#.#..",
        "..#.#..",
        ".##.##.",
    ]

    static func bitmap(for kind: DungeonMonsterKind?) -> [String] {
        switch kind {
        case .slime?:    return slime
        case .bat?:      return bat
        case .skeleton?: return skeleton
        case .ghost?:    return ghost
        case .dragon?:   return dragon
        case nil:        return hero
        }
    }

    /// The sprite as one path centred on `centre`.
    static func path(for kind: DungeonMonsterKind?, centre: CGPoint) -> Path {
        let rows = bitmap(for: kind)
        let cols = rows[0].count
        let origin = CGPoint(x: centre.x - CGFloat(cols) * pixel / 2,
                             y: centre.y - CGFloat(rows.count) * pixel / 2)
        var path = Path()
        for (r, row) in rows.enumerated() {
            for (c, cell) in row.enumerated() where cell == "#" {
                path.addRect(CGRect(x: origin.x + CGFloat(c) * pixel,
                                    y: origin.y + CGFloat(r) * pixel,
                                    width: pixel, height: pixel))
            }
        }
        return path
    }
}

/// The key: the on-screen key (a hardware Vail/BLE-MIDI key feeds the same
/// decoder). Owns its `SendingKeyer` the way `SendingKeyerView` does, so the
/// decoder is built at the session speed. Characters are assembled into a
/// word: it is handed to `onWord` the moment it matches `expected`, or when a
/// word gap of silence arrives (the decoder appends a space) — whatever was
/// keyed by then is the answer. `resetToken` clears it from outside.
private struct DungeonKeyPanel: View {
    let expected: String?
    let resetToken: Int
    let slashedZero: Bool
    let onText: (String) -> Void
    let onWord: (String) -> Void
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false

    init(expected: String?, resetToken: Int, wpm: Double, toneHz: Double, slashedZero: Bool,
         onText: @escaping (String) -> Void, onWord: @escaping (String) -> Void) {
        self.expected = expected
        self.resetToken = resetToken
        self.slashedZero = slashedZero
        self.onText = onText
        self.onWord = onWord
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("Key the counter-spell")
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
            .accessibilityHint("Press and hold to key the counter-spell")
        }
        .onAppear { sender.start() }
        .onDisappear { sender.stop() }
        .onChange(of: sender.decodedText) { text in keyed(text) }
        .onChange(of: resetToken) { _ in sender.clear() }
    }

    private func keyed(_ text: String) {
        let word = text.trimmingCharacters(in: .whitespaces)
        onText(word)
        guard let expected, !word.isEmpty else { return }
        // A match casts at once; otherwise the word gap decides.
        if word == expected || text.hasSuffix(" ") {
            sender.clear()
            onWord(word)
        }
    }
}
