import SwiftUI

/// CW Frogger (#190): the frog crosses three lanes of traffic and three of
/// river a hop at a time, and Morse says what is safe — each lane is cued by
/// sending one of the characters its vehicles or logs carry; the cued vehicle
/// is harmless and every other one fatal, the cued log floats and every other
/// one sinks. The rules live in `FroggerGame` (MorseKit); this view is the
/// frame clock, the sound, the direction pad and the drawing. It runs inside
/// the normal session (`AppModel.start()` for `.frogger`), so the End button,
/// the mode menu and the session summary work as everywhere else, and every
/// decision goes to history through `AppModel.noteFroggerDecision`.
///
/// Twin of `FroggerScreen.kt` on Android.
struct FroggerView: View {
    @EnvironmentObject var model: AppModel

    // Setup choices persist across launches, like every other mode's.
    @AppStorage("frogger.difficulty") private var difficultyRaw = InvadersDifficulty.normal.rawValue
    @AppStorage("frogger.characters") private var characterSetRaw = InvadersCharacterSet.active.rawValue

    private enum Phase { case setup, playing, over }
    @State private var phase: Phase = .setup
    @State private var game: FroggerGame?
    /// A snapshot of the board for drawing; refreshed every frame from `game`.
    @State private var board = Board()
    @State private var hud = HUD()
    @State private var lastFrame: Date?
    /// When each lane's cue finished sounding, by row, so a decision's
    /// time-to-recognize runs from the end of the tone.
    @State private var cueToneEnd: [Int: Date] = [:]
    /// Characters waiting to be sent (objects announcing themselves in the
    /// hidden stage), played one after another behind whatever is sounding.
    @State private var soundQueue: [Character] = []
    @State private var soundBusyUntil = Date.distantPast
    /// A flash of feedback on the board: the last decision's points, or what went wrong.
    @State private var flashText = ""
    @State private var flashUntil = Date.distantPast

    private struct Board: Equatable {
        var objects: [FroggerObject] = []
        var frog = FroggerFrog(row: 0, x: 0.5)
        var visibleRows: Set<Int> = []
        var nextCueRow: Int?
    }

    private struct HUD: Equatable {
        var score = 0, wave = 1, lives = 3, combo = 0, multiplier = 1
        var bestCombo = 0, accuracy = 0.0
        var wpm = 0, bestWpm = 0
        var stage: FroggerLabelStage = .visible
    }

    private struct Option: Identifiable {
        let id: String
        let label: String
    }

    private var difficulty: InvadersDifficulty { InvadersDifficulty(rawValue: difficultyRaw) ?? .normal }
    private var characterSet: InvadersCharacterSet { InvadersCharacterSet(rawValue: characterSetRaw) ?? .active }

    /// The game's pool, letters first then digits, the recognition chart's order.
    private var pool: [Character] {
        model.froggerCharacters(characterSet)
            .map(String.init)
            .sorted(by: SessionRecord.characterOrder)
            .compactMap(\.first)
    }

    private static let vehicleColor = Color(red: 0.96, green: 0.62, blue: 0.24)
    private static let logColor = Color(red: 0.55, green: 0.38, blue: 0.22)
    private static let frogColor = Color(red: 0.42, green: 0.85, blue: 0.40)
    private static let riverColor = Color(red: 0.13, green: 0.32, blue: 0.55)
    private static let roadColor = Color(red: 0.16, green: 0.19, blue: 0.24)
    private static let bankColor = Color(red: 0.17, green: 0.40, blue: 0.30)

    /// The ramp in words for the setup card: where a game opens and where it
    /// climbs to.
    private var speedNote: String {
        let target = Int(model.settings.wpm.rounded())
        let start = Int(FroggerGame.rampStart(characterWpm: model.settings.wpm).rounded())
        if start >= target { return "Cues sent at your \(target) WPM character speed." }
        return "Cues sent from \(start) WPM, stepping up \(Int(FroggerGame.rampStep)) WPM every "
            + "\(FroggerGame.decisionsPerRampStep) safe crossings of a lane to your \(target) WPM "
            + "character speed. Losing a life steps it back."
    }

    var body: some View {
        Group {
            switch phase {
            case .setup:   setupCard
            case .playing: playfield
            case .over:    gameOverCard
            }
        }
        .onDisappear { model.stopFrogger() }
    }

    // MARK: - Setup

    private var setupCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(TrainingMode.frogger.blurb)
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
                Text("Labels show for the first two crossings, hide once a lane is cued from the third, "
                     + "and from the fifth the traffic announces itself in Morse instead.")
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
            GeometryReader { _ in
                TimelineView(.animation) { context in
                    canvas
                        // The frame clock: every tick moves the game on by the
                        // real time elapsed, so a dropped frame costs no distance.
                        .onChange(of: context.date) { now in step(now: now) }
                }
            }
            .frame(maxHeight: .infinity)
            Text(hud.stage == .hidden ? "Tap the board to hear the next lane's cue again; the traffic announces itself"
                 : "Tap the board to hear the next lane's cue again")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            directionPad
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

    private var canvas: some View {
        Canvas { context, size in
            let rowHeight = size.height / CGFloat(FroggerGame.rows)
            let font = Theme.copyFont(size: min(20, rowHeight * 0.5), weight: .bold, monospaced: true,
                                      slashedZero: model.settings.slashedZero)
            func top(of row: Int) -> CGFloat { size.height - CGFloat(row + 1) * rowHeight }

            // The ground: banks, asphalt with lane lines, the median, water.
            for row in 0..<FroggerGame.rows {
                let rect = CGRect(x: 0, y: top(of: row), width: size.width, height: rowHeight)
                let fill: Color
                switch FroggerGame.lane(at: row)?.kind {
                case .road?:  fill = Self.roadColor
                case .river?: fill = Self.riverColor
                case nil:     fill = Self.bankColor
                }
                context.fill(Path(rect), with: .color(fill))
                if FroggerGame.lane(at: row)?.kind == .road, row < FroggerGame.medianRow - 1 {
                    var dash = Path()
                    var x: CGFloat = 0
                    while x < size.width {
                        dash.move(to: CGPoint(x: x, y: top(of: row)))
                        dash.addLine(to: CGPoint(x: min(size.width, x + 14), y: top(of: row)))
                        x += 28
                    }
                    context.stroke(dash, with: .color(.white.opacity(0.35)), lineWidth: 1)
                }
                if let cueRow = board.nextCueRow, cueRow == row {
                    // The lane the cue is for, so the eye knows where the ear is aimed.
                    context.stroke(Path(rect.insetBy(dx: 1, dy: 1)), with: .color(Theme.tealBright.opacity(0.7)), lineWidth: 2)
                }
            }

            // Vehicles and logs, drawn twice where they straddle an edge.
            for object in board.objects {
                let w = CGFloat(object.width) * size.width
                let h = rowHeight * (object.kind == .road ? 0.58 : 0.5)
                let y = top(of: object.row) + (rowHeight - h) / 2
                let colour = object.kind == .road ? Self.vehicleColor : Self.logColor
                for offset in [-1.0, 0.0, 1.0] {
                    let cx = CGFloat(object.x + offset) * size.width
                    if cx + w / 2 < 0 || cx - w / 2 > size.width { continue }
                    let rect = CGRect(x: cx - w / 2, y: y, width: w, height: h)
                    context.fill(Path(roundedRect: rect, cornerRadius: object.kind == .road ? 5 : h / 2), with: .color(colour))
                    if board.visibleRows.contains(object.row) {
                        context.draw(Text(String(object.character)).font(font).foregroundColor(Theme.navy),
                                     at: CGPoint(x: cx, y: y + h / 2))
                    }
                }
            }

            // The frog.
            let frogSize = min(rowHeight * 0.62, size.width * CGFloat(FroggerGame.hop) * 0.8)
            let fx = CGFloat(board.frog.x) * size.width
            let fy = top(of: board.frog.row) + rowHeight / 2
            let frogRect = CGRect(x: fx - frogSize / 2, y: fy - frogSize / 2, width: frogSize, height: frogSize)
            context.fill(Path(roundedRect: frogRect, cornerRadius: frogSize * 0.35), with: .color(Self.frogColor))
            let eye = frogSize * 0.16
            for dx in [-0.22, 0.22] {
                let e = CGRect(x: fx + CGFloat(dx) * frogSize - eye / 2, y: fy - frogSize * 0.28 - eye / 2, width: eye, height: eye)
                context.fill(Path(ellipseIn: e), with: .color(Theme.navy))
            }

            if flashUntil > Date() {
                context.draw(Text(flashText).font(.headline).foregroundColor(Theme.tealBright),
                             at: CGPoint(x: size.width / 2, y: rowHeight / 2))
            }
        }
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
            .strokeBorder(Theme.hairline, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture { replayCue() }
        .accessibilityLabel("Board: frog on row \(board.frog.row) of \(FroggerGame.rows - 1)")
    }

    /// Hear the next lane's cue again.
    private func replayCue() {
        guard phase == .playing, let game, let cue = game.nextCue, let row = game.nextCueRow else { return }
        playCue(cue, row: row, now: Date())
    }

    /// Up on top, then left, down and right — arrow keys press them too.
    private var directionPad: some View {
        VStack(spacing: 6) {
            padButton(.up, "arrow.up", key: .upArrow)
            HStack(spacing: 6) {
                padButton(.left, "arrow.left", key: .leftArrow)
                padButton(.down, "arrow.down", key: .downArrow)
                padButton(.right, "arrow.right", key: .rightArrow)
            }
        }
    }

    private func padButton(_ direction: FroggerDirection, _ symbol: String, key: KeyEquivalent) -> some View {
        Button {
            hop(direction)
        } label: {
            Image(systemName: symbol)
                .font(.title2.weight(.semibold))
                .frame(width: 84, height: 48)
                .foregroundStyle(.white)
                .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .keyboardShortcut(key, modifiers: [])
        .accessibilityLabel("Hop \(direction.rawValue)")
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
        let config = FroggerGame.Config(characters: pool, difficulty: difficulty,
                                        characterWpm: model.settings.wpm)
        let g = FroggerGame(config: config)
        game = g
        cueToneEnd = [:]
        soundQueue = []
        soundBusyUntil = .distantPast
        flashUntil = .distantPast
        lastFrame = nil
        syncBoard(g)
        syncHUD(g)
        phase = .playing
        // The first lane is cued at birth; send it.
        if let cue = g.nextCue, let row = g.nextCueRow { playCue(cue, row: row, now: Date()) }
    }

    private func step(now: Date) {
        guard phase == .playing, let game else { return }
        defer { lastFrame = now }
        guard let last = lastFrame else { return }
        // Cap a long gap (the app was backgrounded) so the traffic does not
        // sweep the board in one step.
        let dt = min(0.1, now.timeIntervalSince(last))
        handle(game.advance(by: dt), game: game, now: now)
        if now >= soundBusyUntil, !soundQueue.isEmpty {
            let next = soundQueue.removeFirst()
            let duration = model.playFroggerCue(next, wpm: game.currentWpm)
            soundBusyUntil = now.addingTimeInterval(duration + 0.35)
        }
        syncBoard(game)
        syncHUD(game)
    }

    private func hop(_ direction: FroggerDirection) {
        guard phase == .playing, let game else { return }
        let events = game.move(direction)
        guard !events.isEmpty else { return }
        handle(events, game: game, now: Date())
        syncBoard(game)
        syncHUD(game)
    }

    private func handle(_ events: [FroggerEvent], game: FroggerGame, now: Date) {
        for event in events {
            switch event {
            case .hopped:
                break
            case .cue(let row, let character):
                playCue(character, row: row, now: now)
            case .passed(let object, let points), .landed(let object, let points):
                let ttr = cueToneEnd[object.row].map { max(0, now.timeIntervalSince($0)) } ?? 0
                cueToneEnd[object.row] = nil
                model.noteFroggerDecision(target: object.character, chosen: object.character, ttr: ttr)
                Haptics.success()
                flash("+\(points)", for: 0.8)
            case .squashed(let object, let cue):
                model.noteFroggerDecision(target: cue, chosen: object.character, ttr: 0)
                Haptics.error()
                flash("Hit by \(object.character), not \(cue)", for: 1.2)
                cueToneEnd = [:]
            case .sank(let object, let cue):
                model.noteFroggerDecision(target: cue, chosen: object.character, ttr: 0)
                Haptics.error()
                flash("\(object.character) sank, not \(cue)", for: 1.2)
                cueToneEnd = [:]
            case .drowned(let cue):
                model.noteFroggerMiss(target: cue)
                Haptics.error()
                flash("Splash — the log was \(cue)", for: 1.2)
                cueToneEnd = [:]
            case .crossed(let points):
                Haptics.success()
                flash("Across! +\(points) — wave \(game.wave)", for: 1.2)
                cueToneEnd = [:]
            case .entered(let object):
                // From wave 5 the traffic in the lane ahead announces itself.
                if game.labelStage == .hidden, object.row == game.frog.row + 1, soundQueue.count < 3 {
                    soundQueue.append(object.character)
                }
            case .gameOver:
                model.stopFrogger()
                phase = .over
            }
        }
    }

    /// Send a lane's cue now, ahead of anything queued.
    private func playCue(_ character: Character, row: Int, now: Date) {
        soundQueue = []
        let duration = model.playFroggerCue(character, wpm: game?.currentWpm ?? model.settings.wpm)
        soundBusyUntil = now.addingTimeInterval(duration + 0.35)
        cueToneEnd[row] = now.addingTimeInterval(duration)
    }

    private func flash(_ text: String, for seconds: TimeInterval) {
        flashText = text
        flashUntil = Date().addingTimeInterval(seconds)
    }

    private func syncBoard(_ g: FroggerGame) {
        let next = Board(objects: g.objects, frog: g.frog,
                         visibleRows: Set(FroggerGame.lanes.map(\.row).filter { g.isLabelVisible(row: $0) }),
                         nextCueRow: g.nextCueRow)
        if next != board { board = next }
    }

    private func syncHUD(_ g: FroggerGame) {
        let next = HUD(score: g.score, wave: g.wave, lives: g.lives, combo: g.combo,
                       multiplier: g.multiplier, bestCombo: g.bestCombo, accuracy: g.accuracy,
                       wpm: Int(g.currentWpm.rounded()), bestWpm: Int(g.bestWpm.rounded()),
                       stage: g.labelStage)
        if next != hud { hud = next }
    }
}
