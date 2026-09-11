import SwiftUI

/// Morse Defender (#188): assets — cities and ships, each with a callsign —
/// line the bottom of the field; attackers come down from the top, each
/// sending the callsign of the asset it is heading for. The learner copies
/// the callsign and routes the defence by tapping that asset, or by typing
/// the callsign. The rules live in `DefenderGame` (MorseKit); this view is
/// the frame clock, the sound, the input, and the drawing. It runs inside the
/// normal session (`AppModel.start()` for `.defender`), so the End button,
/// the mode menu and the session summary all work as everywhere else, and
/// the tally goes to history through `AppModel.noteDefenderRoute`.
///
/// Twin of `DefenderScreen.kt` on Android.
struct DefenderView: View {
    @EnvironmentObject var model: AppModel

    // Setup choices persist across launches, like every other mode's.
    @AppStorage("defender.input") private var inputRaw = DefenderInput.tap.rawValue
    @AppStorage("defender.difficulty") private var difficultyRaw = InvadersDifficulty.normal.rawValue
    @AppStorage("defender.callsigns") private var callsignsRaw = InvadersCharacterSet.active.rawValue

    private enum Phase { case setup, playing, over }
    @State private var phase: Phase = .setup
    @State private var game: DefenderGame?
    /// Snapshots of the field for drawing; refreshed every frame from `game`.
    @State private var attackers: [DefenderAttacker] = []
    @State private var assets: [DefenderAsset] = []
    @State private var hud = HUD()
    @State private var lastFrame: Date?
    /// When each attacker's callsign finished sounding, keyed by attacker id,
    /// so a hit's time-to-recognize runs from the end of the tone.
    @State private var toneEnd: [Int: Date] = [:]
    /// The callsign typed so far (typed input).
    @State private var typed = ""
    /// A flash of feedback on the field: the last hit's points, or "miss".
    @State private var flashText = ""
    @State private var flashUntil = Date.distantPast
    /// The asset last struck, for a brief burst where it stood.
    @State private var struckId: Int?
    @State private var struckUntil = Date.distantPast

    private struct HUD: Equatable {
        var score = 0, wave = 1, live = 0, total = 0, combo = 0, multiplier = 1
        var bestCombo = 0, accuracy = 0.0
        var wpm = 0, bestWpm = 0
    }

    private struct Option: Identifiable {
        let id: String
        let label: String
    }

    private var input: DefenderInput { DefenderInput(rawValue: inputRaw) ?? .tap }
    private var difficulty: InvadersDifficulty { InvadersDifficulty(rawValue: difficultyRaw) ?? .normal }
    private var callsigns: InvadersCharacterSet { InvadersCharacterSet(rawValue: callsignsRaw) ?? .active }

    /// The learner's characters, for the synthetic callsigns of `.active`.
    private var characters: [Character] { model.invadersCharacters(.active) }

    private static func callsignLabel(_ set: InvadersCharacterSet) -> String {
        switch set {
        case .active: return "From my characters"
        case .full:   return "Real callsigns"
        }
    }

    private var callsignNote: String {
        switch callsigns {
        case .active:
            let alphabet = DefenderGame.callsignAlphabet(from: characters)
            return "Four-character call-like groups spelled only from your active set: "
                + alphabet.map(String.init).joined(separator: " ")
        case .full:
            return "Real US-style callsigns such as K1AB, AB1C, K1ABC and AB1CD."
        }
    }

    private let columns = DefenderGame.defaultColumns

    /// A 9×7 attacker, drawn from this bitmap so there is no image asset;
    /// `#` is a lit pixel. The same table is in `DefenderScreen.kt`.
    private static let attackerSprite: [String] = [
        "....#....",
        "...###...",
        "..#####..",
        ".##.#.##.",
        "#########",
        "..#...#..",
        ".#.....#.",
    ]
    /// A 9×6 city block for a standing asset.
    private static let citySprite: [String] = [
        "....#....",
        "..#.#.#..",
        "..#####..",
        ".#######.",
        "#########",
        "#########",
    ]
    private static let spritePixel: CGFloat = 3

    private static func spritePath(_ sprite: [String], centre: CGPoint, pixel: CGFloat = spritePixel) -> Path {
        let cols = sprite[0].count
        let origin = CGPoint(x: centre.x - CGFloat(cols) * pixel / 2,
                             y: centre.y - CGFloat(sprite.count) * pixel / 2)
        var path = Path()
        for (r, row) in sprite.enumerated() {
            for (c, cell) in row.enumerated() where cell == "#" {
                path.addRect(CGRect(x: origin.x + CGFloat(c) * pixel,
                                    y: origin.y + CGFloat(r) * pixel,
                                    width: pixel, height: pixel))
            }
        }
        return path
    }

    /// The ramp in words for the setup card: where a game opens and where it
    /// climbs to, with Farnsworth spacing honoured when it is on.
    private var speedNote: String {
        let target = Int(model.settings.wpm.rounded())
        let start = Int(DefenderGame.rampStart(characterWpm: model.settings.wpm).rounded())
        let farnsworth = model.settings.farnsworth
            ? " Farnsworth spacing at \(Int(model.settings.effectiveWpm.rounded())) WPM effective applies."
            : ""
        if start >= target { return "Callsigns sent at your \(target) WPM character speed." + farnsworth }
        return "Callsigns sent from \(start) WPM, stepping up \(Int(DefenderGame.rampStep)) WPM every "
            + "\(DefenderGame.hitsPerRampStep) hits to your \(target) WPM character speed. "
            + "A lost asset steps it back." + farnsworth
    }

    var body: some View {
        Group {
            switch phase {
            case .setup:   setupCard
            case .playing: playfield
            case .over:    gameOverCard
            }
        }
        .onDisappear { model.stopDefender() }
    }

    // MARK: - Setup

    private var setupCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(TrainingMode.defender.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                optionPicker("How you route", selection: $inputRaw,
                             options: DefenderInput.allCases.map { Option(id: $0.rawValue, label: $0.label) })
                Text(input.blurb)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                optionPicker("Callsigns", selection: $callsignsRaw,
                             options: InvadersCharacterSet.allCases.map { Option(id: $0.rawValue, label: Self.callsignLabel($0)) })
                Text(callsignNote)
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
            if input == .tap {
                Text("Tap an attacker to hear it again · tap the asset it named")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
            } else {
                HStack {
                    Text("Tap an attacker to hear it again")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                    Spacer()
                    Text(typed.isEmpty ? "—" : typed)
                        .font(Theme.copyFont(size: 22, weight: .semibold, monospaced: true,
                                             slashedZero: model.settings.slashedZero))
                        .foregroundStyle(.white)
                        .accessibilityLabel("Typed so far: \(typed.isEmpty ? "nothing" : typed)")
                }
                keyboard
            }
        }
    }

    private var hudBar: some View {
        HStack {
            stat("Score", "\(hud.score)")
            Spacer()
            stat("Wave", "\(hud.wave)")
            Spacer()
            stat("Assets", "\(hud.live)/\(hud.total)")
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

    /// The asset row's height at the bottom of the field: sprite plus label.
    private static let assetRowHeight: CGFloat = 54

    private func canvas(size: CGSize) -> some View {
        Canvas { context, size in
            let rowTop = size.height - Self.assetRowHeight
            var line = Path()
            line.move(to: CGPoint(x: 0, y: rowTop))
            line.addLine(to: CGPoint(x: size.width, y: rowTop))
            context.stroke(line, with: .color(Theme.teal.opacity(0.4)), lineWidth: 1)

            // Assets along the bottom, evenly spaced; rubble stays in place.
            let callFont = Theme.copyFont(size: 13, weight: .bold, monospaced: true,
                                          slashedZero: model.settings.slashedZero)
            let slot = size.width / CGFloat(max(1, assets.count))
            for (i, asset) in assets.enumerated() {
                let x = (CGFloat(i) + 0.5) * slot
                let spriteCentre = CGPoint(x: x, y: rowTop + 16)
                if asset.isAlive {
                    context.fill(Self.spritePath(Self.citySprite, centre: spriteCentre), with: .color(Theme.tealBright))
                    context.draw(Text(asset.callsign).font(callFont).foregroundColor(.white),
                                 at: CGPoint(x: x, y: rowTop + 40))
                } else {
                    var rubble = Path()
                    rubble.addRect(CGRect(x: x - 12, y: rowTop + 22, width: 24, height: 6))
                    context.fill(rubble, with: .color(Theme.textSecondary.opacity(0.4)))
                    context.draw(Text(asset.callsign).font(callFont).foregroundColor(Theme.textSecondary.opacity(0.5)),
                                 at: CGPoint(x: x, y: rowTop + 40))
                }
                if asset.id == struckId, struckUntil > Date() {
                    var burst = Path()
                    burst.addEllipse(in: CGRect(x: x - 16, y: rowTop, width: 32, height: 32))
                    context.stroke(burst, with: .color(.red), lineWidth: 2)
                }
            }

            // Attackers descend their columns; nothing on screen says where
            // each is heading — only the callsign it sent does.
            let colWidth = size.width / CGFloat(columns)
            let top: CGFloat = 18
            for a in attackers {
                let x = (CGFloat(a.column) + 0.5) * colWidth
                let y = top + CGFloat(a.progress) * (rowTop - top - 14)
                context.fill(Self.spritePath(Self.attackerSprite, centre: CGPoint(x: x, y: y)), with: .color(.orange))
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
        .onTapGesture { location in tapped(at: location, size: size) }
        .accessibilityLabel("Play field, \(attackers.count) attackers, \(hud.live) of \(hud.total) assets standing")
    }

    /// A tap in the asset row routes the defence to that asset (tap input);
    /// a tap on the field replays the nearest attacker's callsign.
    private func tapped(at location: CGPoint, size: CGSize) {
        guard phase == .playing, let game else { return }
        let rowTop = size.height - Self.assetRowHeight
        if location.y >= rowTop {
            guard input == .tap, !assets.isEmpty else { return }
            let slot = size.width / CGFloat(assets.count)
            let index = min(assets.count - 1, max(0, Int(location.x / max(1, slot))))
            route(to: assets[index])
        } else {
            let column = Int(location.x / max(1, size.width) * CGFloat(columns))
            guard let nearest = game.attackers.min(by: { abs($0.column - column) < abs($1.column - column) }) else { return }
            model.playDefenderCallsign(nearest.callsign, wpm: game.currentWpm)
        }
    }

    /// Typed input: the QWERTY keyboard from Invaders (#178) laid out for the
    /// callsign alphabet, plus a backspace. A key outside the alphabet stays
    /// in place, dimmed and dead; a hardware keyboard presses the live ones.
    private var keyboard: some View {
        let pool = game?.keyboardPool ?? []
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
                                typedKey(Character(key))
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
                            .accessibilityLabel("Type \(key)")
                            .accessibilityHidden(!enabled)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                Button {
                    if !typed.isEmpty { typed.removeLast() }
                } label: {
                    Label("Backspace", systemImage: "delete.left")
                        .labelStyle(.iconOnly)
                        .frame(width: keyWidth * 2 + spacing, height: keyHeight)
                        .foregroundStyle(.white)
                        .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                }
                .keyboardShortcut(.delete, modifiers: [])
                .accessibilityLabel("Backspace")
            }
        }
        .frame(height: CGFloat(rows.count + 1) * keyHeight + CGFloat(rows.count) * spacing)
    }

    // MARK: - Game over

    private var gameOverCard: some View {
        VStack(spacing: 16) {
            Text("All assets lost")
                .font(.title2.bold())
            HStack(spacing: 24) {
                stat("Score", "\(hud.score)")
                stat("Wave", "\(hud.wave)")
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
        let config = DefenderGame.Config(characters: characters, callsigns: callsigns, difficulty: difficulty,
                                         columns: columns, characterWpm: model.settings.wpm)
        let g = DefenderGame(config: config)
        game = g
        attackers = []
        assets = g.assets
        toneEnd = [:]
        typed = ""
        flashUntil = .distantPast
        struckId = nil
        lastFrame = nil
        syncHUD(g)
        phase = .playing
    }

    private func step(now: Date) {
        guard phase == .playing, let game else { return }
        defer { lastFrame = now }
        guard let last = lastFrame else { return }
        // Cap a long gap (the app was backgrounded) so the field does not
        // empty onto the assets in one step.
        let dt = min(0.1, now.timeIntervalSince(last))
        for event in game.advance(by: dt) {
            switch event {
            case .launched(let a):
                // At the ramp's current speed, which the hits and strikes in
                // this same step may just have moved.
                let duration = model.playDefenderCallsign(a.callsign, wpm: game.currentWpm)
                toneEnd[a.id] = now.addingTimeInterval(duration)
            case .struck(let a, let asset):
                toneEnd[a.id] = nil
                model.noteDefenderStrike(callsign: a.callsign, wpm: game.currentWpm)
                Haptics.error()
                struckId = asset.id
                struckUntil = now.addingTimeInterval(0.8)
                flash("\(asset.callsign) lost", for: 1.0)
            case .gameOver:
                model.stopDefender()
                phase = .over
            }
        }
        attackers = game.attackers
        assets = game.assets
        syncHUD(game)
    }

    /// Tap input: the defence goes to `asset`.
    private func route(to asset: DefenderAsset) {
        guard phase == .playing, let game else { return }
        let nearest = game.nearestArrival
        let result = game.route(to: asset.id)
        settle(result, chosen: asset.callsign, nearest: nearest)
    }

    /// Typed input: a key extends the copy; the defence routes itself the
    /// moment the copy matches a standing asset, and a copy that has run past
    /// the longest callsign without matching is a wasted shot.
    private func typedKey(_ ch: Character) {
        guard phase == .playing, let game else { return }
        typed.append(Character(String(ch).uppercased()))
        let matches = game.assets.contains { $0.isAlive && $0.callsign == typed }
        guard matches || typed.count >= max(1, game.longestLiveCallsign) else { return }
        let nearest = game.nearestArrival
        let attempt = typed
        typed = ""
        let result = game.route(callsign: attempt)
        settle(result, chosen: attempt, nearest: nearest)
    }

    private func settle(_ result: DefenderRoute, chosen: String, nearest: DefenderAttacker?) {
        guard let game else { return }
        let now = Date()
        if let hit = result.attacker {
            let ttr = toneEnd[hit.id].map { max(0, now.timeIntervalSince($0)) } ?? 0
            toneEnd[hit.id] = nil
            model.noteDefenderRoute(target: hit.callsign, chosen: hit.callsign, ttr: ttr, wpm: game.currentWpm)
            Haptics.success()
            if let fresh = result.reinforced {
                flash("Wave \(game.wave)! \(fresh.callsign) joins", for: 1.2)
            } else {
                flash(result.waveCleared ? "Wave \(game.wave)!" : "+\(result.points)", for: 0.8)
            }
        } else {
            // A wrong route: confused with whatever was nearest arrival.
            if let nearest {
                model.noteDefenderRoute(target: nearest.callsign, chosen: chosen, ttr: 0, wpm: game.currentWpm)
            }
            Haptics.error()
            flash("miss", for: 0.6)
        }
        attackers = game.attackers
        assets = game.assets
        syncHUD(game)
    }

    private func flash(_ text: String, for seconds: TimeInterval) {
        flashText = text
        flashUntil = Date().addingTimeInterval(seconds)
    }

    private func syncHUD(_ g: DefenderGame) {
        let next = HUD(score: g.score, wave: g.wave, live: g.liveAssets, total: g.assets.count, combo: g.combo,
                       multiplier: g.multiplier, bestCombo: g.bestCombo, accuracy: g.accuracy,
                       wpm: Int(g.currentWpm.rounded()), bestWpm: Int(g.bestWpm.rounded()))
        if next != hud {
            hud = next
            model.noteGameScore(g.score)   // per-mode personal best
        }
    }
}
