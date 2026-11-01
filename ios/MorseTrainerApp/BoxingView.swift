import SwiftUI

/// Brass Knuckles Knock Out: a boxing game that teaches Morse, created by Derick
/// (KO6PBZ) for Kristen's (K5MME) HamCube PocketCW firmware and used here with
/// permission (GPL-3.0-or-later). The opponent sends a one- or two-character
/// tell; key it back before the punch lands to dodge, then every character you
/// send is a punch. The rules live in `BoxingFight` (MorseKit); this screen is
/// the frame clock, the tell's sound, the key and the drawing (the ring is the
/// game's pixel art from `BkRing`; the scoreboard and panels are native
/// SwiftUI). It runs inside the normal session (`AppModel.start()` for
/// `.brassKnuckles`) the way Asteroids does, so the End button, the mode menu
/// and the session summary all work as everywhere else; each finished fight
/// reaches the session record through `AppModel.noteBoxingFight`.
///
/// Fights read the letter statistics only; they never write per-character
/// stats. Fight state is not saved across a relaunch: only the career
/// (unlocked fighters, bests, the hints switch) is, in UserDefaults under the
/// `amt_boxing.` prefix (Android keeps the same fields in the `amt_boxing`
/// SharedPreferences).
///
/// Twin of `BoxingScreen.kt` on Android and desktop.

// MARK: - Look

private enum BK {
    static let brass = Color(red: 232 / 255, green: 176 / 255, blue: 32 / 255)
    static let red = Color(red: 232 / 255, green: 58 / 255, blue: 58 / 255)
    static let bg = Color(red: 18 / 255, green: 14 / 255, blue: 28 / 255)
    static let panel = Color(red: 14 / 255, green: 12 / 255, blue: 22 / 255)
    static let pale = Color(red: 244 / 255, green: 238 / 255, blue: 224 / 255)
    static let barBack = Color(red: 42 / 255, green: 34 / 255, blue: 56 / 255)
    static let picked = Color(red: 42 / 255, green: 34 / 255, blue: 56 / 255)
    static let rim = Color(red: 74 / 255, green: 60 / 255, blue: 90 / 255)
    static let band = Color(red: 10 / 255, green: 3 / 255, blue: 6 / 255)
    static let green = Color(red: 48 / 255, green: 200 / 255, blue: 96 / 255)
}

/// Pixel buffers to CGImages, and the few images that never change.
@MainActor
private enum BoxingImages {
    private static var portraits: [Int: CGImage] = [:]

    /// An ARGB buffer as an image: no interpolation is applied here; the view asks for none either.
    static func make(_ pixels: [UInt32], w: Int, h: Int) -> CGImage? {
        guard pixels.count == w * h else { return nil }
        let data = pixels.withUnsafeBufferPointer { Data(buffer: $0) }
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info, provider: provider,
                       decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    static func portrait(_ fighter: Int, locked: Bool) -> CGImage? {
        let key = fighter * 2 + (locked ? 1 : 0)
        if let cached = portraits[key] { return cached }
        let pixels = BkRing.portrait(fighter: fighter, face: 0, locked: locked, bg: 0xFF12_0E1C)
        let image = make(pixels, w: BkRing.portraitSize, h: BkRing.portraitSize)
        portraits[key] = image
        return image
    }
}

/// A pixel image scaled up to fit, with no smoothing.
private struct PixelImage: View {
    let image: CGImage?

    var body: some View {
        if let image {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .aspectRatio(contentMode: .fit)
        } else {
            Color.clear
        }
    }
}

// MARK: - The controller

/// The fight's frame clock, key, sound and career. One per `BoxingView`.
@MainActor
final class BoxingController: ObservableObject {
    enum Screen { case splash, title, card, fight, results }

    /// How long the result shows in the ring before the results screen.
    private static let overMs = 3200
    /// The title splash moves on by itself after this long.
    private static let splashMs = 7000

    @Published private(set) var screen = Screen.splash
    /// Bumped each frame so the views redraw.
    @Published private(set) var tick = 0
    @Published var pick = 0
    @Published private(set) var fight: BoxingFight?
    @Published private(set) var career: BoxingCareer
    @Published private(set) var stage = 0
    @Published private(set) var opened = false
    @Published private(set) var hints: Bool

    private(set) var splashAt: UInt32
    private(set) var seed: UInt32
    private var overAt: UInt32?
    private var recorded = false
    private var tellEndsAt: UInt32?
    private var keying = BoxingKeying()
    private weak var model: AppModel?
    private let defaults = UserDefaults.standard

    init() {
        let d = UserDefaults.standard
        let c = Self.loadCareer(d)
        career = c
        hints = c.hints
        let now = Self.clock()
        splashAt = now
        seed = now ^ 0x5BD1_E995
    }

    func attach(_ model: AppModel) { self.model = model }

    /// Milliseconds on the wrapping tick clock: wall-clock milliseconds truncated to 32 bits, the same
    /// domain the keys report their edges in, so the keying judge and the rules agree on "now".
    static func clock() -> UInt32 { UInt32(truncatingIfNeeded: Int64(Date().timeIntervalSince1970 * 1000)) }

    /// Milliseconds from `since` to now (never negative).
    static func age(_ since: UInt32) -> Int { max(0, Int(Int32(bitPattern: clock() &- since))) }

    var animating: Bool { screen == .splash || screen == .fight }

    var marks: String { keying.marks }

    private func ditMs() -> Int {
        let wpm = max(model?.settings.wpm ?? 20, 1)
        return Int(max((1200.0 / wpm).rounded(), 20))
    }

    // MARK: Screens

    func goSplash() {
        splashAt = Self.clock()
        seed = splashAt ^ 0x5BD1_E995
        screen = .splash
    }

    func goTitle() { screen = .title }

    func setHints(_ on: Bool) {
        hints = on
        career.hints = on
        saveCareer()
    }

    func beginFight(_ index: Int) {
        let now = Self.clock()
        let call = model?.settings.qso.myCall ?? "W1AW"
        fight = BoxingFight(fighter: index, seed: now ^ 0x2545_F491, hints: hints, myDitMs: ditMs(), callsign: call)
        keying.reset()
        stage = Int((now &* 31 &+ UInt32(truncatingIfNeeded: index)) % UInt32(BkRing.stages.count))
        recorded = false
        opened = false
        tellEndsAt = nil
        overAt = nil
        screen = .card
    }

    func ringBell() {
        let now = Self.clock()
        fight?.start(now: now)
        screen = .fight
    }

    func nextRound() { fight?.start(now: Self.clock()) }

    func pauseFight() {
        if screen == .fight { fight?.pause(now: Self.clock()) }
    }

    func resumeFight() {
        keying.reset()
        fight?.resume(now: Self.clock())
    }

    func quitFight() {
        model?.stopBoxing()
        tellEndsAt = nil
        fight = nil
        screen = .title
    }

    /// The screen is going away: silence the tell and pause a fight in progress.
    func leave() {
        model?.stopBoxing()
        tellEndsAt = nil
        pauseFight()
    }

    func backToCard() { screen = .title }

    func nextFighter() {
        guard let f = fight else { return }
        pick = min(f.fighter + 1, BoxingRules.fighters - 1)
        beginFight(pick)
    }

    // MARK: The key

    /// A key edge, from the on-screen key, the paddles or a hardware key. `atMs` is when it happened.
    func keyEdge(down: Bool, atMs: UInt32? = nil) {
        guard screen == .fight, let f = fight, !f.paused else { return }
        let t = atMs ?? Self.clock()
        if let j = keying.edge(down: down, atMs: t, ditMs: ditMs()) { judge(j, t) }
    }

    private func judge(_ j: BoxingJudged, _ now: UInt32) {
        if let c = j.char {
            fight?.key(c, quality: j.quality, now: now)
        } else {
            fight?.keyUnknown(now: now)
        }
    }

    // MARK: The frame clock

    /// Runs the rules, the tell, the keying judge and the splash. Called once a frame.
    func step() {
        let now = Self.clock()
        switch screen {
        case .splash:
            if Self.age(splashAt) > Self.splashMs { screen = .title }
        case .fight:
            stepFight(now)
        default:
            break
        }
        tick &+= 1
    }

    private func stepFight(_ now: UInt32) {
        guard var f = fight else { return }
        if f.stop {
            f.stop = false
            model?.stopBoxing()
            tellEndsAt = nil
        }
        if !f.paused {
            if let end = tellEndsAt, BoxingRules.reached(now, end) {
                tellEndsAt = nil
                f.tellPlayed(now: now)
            }
            f.update(now: now)
            if let j = keying.poll(nowMs: now, ditMs: ditMs()) {
                if let c = j.char {
                    f.key(c, quality: j.quality, now: now)
                } else {
                    f.keyUnknown(now: now)
                }
            }
            if let text = f.takePlay() { playTell(text, f.foe) }
            if f.stop {
                f.stop = false
                model?.stopBoxing()
                tellEndsAt = nil
            }
            f.takeCopies { _, _ in }   // fights read the letter stats, they do not write them
        }
        if case .over(let won, _) = f.phase {
            if overAt == nil { overAt = now }
            finishRecord(f, won: won)
            if let at = overAt, Self.age(at) > Self.overMs {
                overAt = nil
                fight = f
                screen = .results
                return
            }
        }
        fight = f
    }

    private func playTell(_ text: String, _ foe: BoxingFighter) {
        guard let model else { return }
        let base = Int(model.settings.wpm.rounded())
        let farnsworth = model.settings.farnsworth ? Int(model.settings.effectiveWpm.rounded()) : 0
        let (wpm, eff) = foe.speeds(baseWpm: base, farnsworth: farnsworth)
        let seconds = model.playBoxingTell(text, wpm: wpm, effectiveWpm: eff, pitchOffsetHz: foe.pitchOffsetHz)
        // No completion callback from the player: date the end of the tell from its length.
        tellEndsAt = Self.clock() &+ UInt32(max(0, (seconds * 1000).rounded()))
    }

    private func finishRecord(_ f: BoxingFight, won: Bool) {
        if recorded { return }
        recorded = true
        opened = career.finished(fighter: f.fighter, won: won, score: f.score)
        saveCareer()
        model?.noteBoxingFight(tells: f.stats.tells, dodged: f.stats.dodged, score: f.score)
    }

    // MARK: Career

    private static func loadCareer(_ d: UserDefaults) -> BoxingCareer {
        func ints(_ key: String) -> [Int] {
            let parts = (d.string(forKey: key) ?? "").split(separator: ",", omittingEmptySubsequences: false)
            return (0..<BoxingRules.fighters).map { i in i < parts.count ? (Int(parts[i]) ?? 0) : 0 }
        }
        let unlocked = (d.object(forKey: "amt_boxing.unlocked") as? Int) ?? 1
        var c = BoxingCareer(unlocked: min(max(unlocked, 1), BoxingRules.fighters))
        c.hints = (d.object(forKey: "amt_boxing.hints") as? Bool) ?? true
        c.best = ints("amt_boxing.best")
        c.wins = ints("amt_boxing.wins")
        return c
    }

    private func saveCareer() {
        defaults.set(career.unlocked, forKey: "amt_boxing.unlocked")
        defaults.set(career.hints, forKey: "amt_boxing.hints")
        defaults.set(career.best.map(String.init).joined(separator: ","), forKey: "amt_boxing.best")
        defaults.set(career.wins.map(String.init).joined(separator: ","), forKey: "amt_boxing.wins")
    }
}

// MARK: - The screen

struct BoxingView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var ctl = BoxingController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch ctl.screen {
            case .splash:  BoxingSplashView(ctl: ctl)
            case .title:   BoxingTitleView(ctl: ctl)
            case .card:    BoxingCardView(ctl: ctl)
            case .fight:   BoxingFightView(ctl: ctl)
            case .results: BoxingResultsView(ctl: ctl)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BK.bg, in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        // The frame clock: one tick per display frame while something is animating.
        .background(
            TimelineView(.animation(minimumInterval: nil, paused: !ctl.animating)) { context in
                Color.clear.onChange(of: context.date) { _ in ctl.step() }
            }
            .allowsHitTesting(false)
        )
        .onAppear { ctl.attach(model) }
        .onDisappear { ctl.leave() }
        // Leaving the app pauses the fight; the player comes back to the pause card.
        .onChange(of: scenePhase) { phase in
            if phase != .active { ctl.pauseFight() }
        }
    }
}

private struct BoxingSplashView: View {
    @ObservedObject var ctl: BoxingController

    var body: some View {
        let t = BoxingController.age(ctl.splashAt)
        let pixels = BkRing.splash(t: t, seed: ctl.seed)
        let image = BoxingImages.make(pixels, w: BkRing.screenW, h: BkRing.screenH)
        VStack(spacing: 4) {
            PixelImage(image: image)
                .frame(maxWidth: 640)
                .accessibilityHidden(true)
            Text("Sponsored by The Brass Knuckle Gang")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(BK.brass)
            Spacer().frame(height: 4)
            Text("Created by Derick, KO6PBZ")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(BK.pale)
            Text("Built on the HamCube PocketCW firmware by Kristen, K5MME")
                .font(.system(size: 12))
                .foregroundStyle(BK.pale.opacity(0.8))
                .multilineTextAlignment(.center)
            if t >= BkRing.promptMs {
                Spacer().frame(height: 8)
                Text("Tap to start")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BK.brass)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BK.band, in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { ctl.goTitle() }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Brass Knuckles Knock Out. Sponsored by The Brass Knuckle Gang. Created by Derick, KO6PBZ. Built on the HamCube PocketCW firmware by Kristen, K5MME.")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Double tap to start")
    }
}

private struct BoxingPortrait: View {
    let fighter: Int
    let locked: Bool
    let size: CGFloat

    var body: some View {
        PixelImage(image: BoxingImages.portrait(fighter, locked: locked))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

private struct BoxingTitleView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var ctl: BoxingController

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("Brass Knuckles")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(BK.brass)
                Text("Your opponent sends a character or two, the tell. Key it back before the punch lands to dodge, then every character you send is a punch: dits jab, dahs hook, a mix throws a combo.")
                    .font(.system(size: 13))
                    .foregroundStyle(BK.pale)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer().frame(height: 4)
                ForEach(0..<BoxingRules.fighters, id: \.self) { i in
                    fighterRow(i)
                }
                Toggle(isOn: Binding(get: { ctl.hints }, set: { ctl.setHints($0) })) {
                    Text("Show the tell on screen (early fighters)")
                        .foregroundStyle(BK.pale)
                }
                .tint(Theme.teal)
                .padding(.top, 4)
                Button {
                    Haptics.tap()
                    ctl.beginFight(ctl.pick)
                } label: {
                    Text("Fight")
                        .font(.headline)
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                Button {
                    model.endSession()
                } label: {
                    Text("End session")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
            }
            .padding(16)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
    }

    private func fighterRow(_ i: Int) -> some View {
        let locked = i >= ctl.career.unlocked
        let foe = BoxingRules.ladder[i]
        let picked = i == ctl.pick
        let lower = foe.from.lowercased()
        let from = String(lower.prefix(1)).uppercased() + String(lower.dropFirst())
        let subtitle = locked ? "Beat the fighter before to unlock" : from
        let best = ctl.career.bestScore(i)
        return Button {
            ctl.pick = i
        } label: {
            HStack(spacing: 10) {
                BoxingPortrait(fighter: i, locked: locked, size: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(locked ? "???" : foe.name)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(BK.pale)
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(BK.pale.opacity(0.7))
                }
                Spacer()
                if !locked && best > 0 {
                    Text("\(best)")
                        .font(.system(size: 16, weight: .bold, design: .monospaced))
                        .foregroundStyle(BK.brass)
                }
            }
            .padding(8)
            .frame(maxWidth: .infinity)
            .background(picked ? BK.picked : BK.panel, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(picked ? BK.brass : BK.rim, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(locked)
        .accessibilityLabel(locked ? "Locked fighter" : foe.name)
        .accessibilityAddTraits(picked ? [.isButton, .isSelected] : .isButton)
    }
}

private struct BoxingCardView: View {
    @ObservedObject var ctl: BoxingController

    var body: some View {
        if let f = ctl.fight {
            let foe = f.foe
            let fresh = foe.newChars(index: f.fighter)
            ScrollView {
                VStack(spacing: 4) {
                    BoxingPortrait(fighter: f.fighter, locked: false, size: 160)
                    Text(foe.name)
                        .font(.system(size: 24, weight: .bold))
                        .foregroundStyle(BK.brass)
                    Text(foe.from)
                        .font(.system(size: 13))
                        .foregroundStyle(BK.pale)
                    Text(foe.blurb)
                        .font(.system(size: 12))
                        .foregroundStyle(BK.pale.opacity(0.8))
                        .multilineTextAlignment(.center)
                    Spacer().frame(height: 16)
                    Text("Tells use: \(String(foe.set))")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(BK.pale)
                        .multilineTextAlignment(.center)
                    if !fresh.isEmpty {
                        Text("New characters: \(String(fresh))")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(BK.brass)
                            .multilineTextAlignment(.center)
                    }
                    Spacer().frame(height: 20)
                    Button {
                        Haptics.tap()
                        ctl.ringBell()
                    } label: {
                        Text("Ring the bell")
                            .font(.headline)
                            .foregroundStyle(Theme.navy)
                            .padding(.horizontal, 24)
                            .frame(minHeight: 50)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.teal)
                    Button("Back") { ctl.backToCard() }
                        .padding(.top, 4)
                }
                .padding(20)
                .frame(maxWidth: .infinity)
            }
        }
    }
}

private struct BoxingBar: View {
    let fraction: Double
    let color: Color

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3).fill(BK.barBack)
                RoundedRectangle(cornerRadius: 3).fill(color)
                    .frame(width: geo.size.width * CGFloat(min(max(fraction, 0), 1)))
            }
        }
        .frame(height: 10)
    }
}

private func boxingClock(_ ms: UInt32) -> String {
    let s = (Int(ms) + 999) / 1000
    return String(format: "%d:%02d", s / 60, s % 60)
}

private func boxingCalloutText(_ c: BoxingCallout) -> String {
    switch c {
    case .dodge: return "Dodged!"
    case .wrong(let sent): return "You sent \(sent)"
    case .tooSlow: return "Too slow"
    case .clean: return "Clean!"
    case .star: return "Star earned"
    case .uppercut: return "Uppercut!"
    case .blocked: return "Blocked"
    case .missed: return "Missed"
    case .down: return "Down!"
    case .up: return "Back up"
    }
}

private struct BoxingFightView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var ctl: BoxingController

    var body: some View {
        if let f = ctl.fight {
            fightBody(f)
        }
    }

    /// The big block-letter banner over the ring for the phase, if any.
    private func banner(_ f: BoxingFight) -> BkRing.Banner? {
        let edge: UInt32 = 0xFF3A_0A0C
        let gold: UInt32 = 0xFFE8_B020
        let loss: UInt32 = 0xFFE8_3A3A
        switch f.phase {
        case .roundStart:
            return BkRing.Banner("ROUND \(f.round)", color: gold, y: 36, px: 3, edge: edge)
        case .foeDown, .meDown:
            return BkRing.Banner(String(f.count), color: 0xFFFF_FFFF, y: 36, px: 3, edge: edge)
        case .roundEnd:
            return BkRing.Banner("ROUND OVER", color: gold, y: 36, px: 2, edge: edge)
        case .over(let won, let how):
            let text: String
            switch how {
            case .ko: text = won ? "K.O.!" : "K.O."
            case .tko: text = "T.K.O."
            case .decision: text = won ? "YOU WIN" : "YOU LOSE"
            }
            return BkRing.Banner(text, color: won ? gold : loss, y: 36, px: 3, edge: edge)
        default:
            return nil
        }
    }

    private func ringImage(_ f: BoxingFight, _ now: UInt32) -> CGImage? {
        let pixels = BkRing.ring(
            fighter: f.fighter, foe: f.foePose(now: now), me: f.mePose(now: now),
            hurting: f.foeHp * 4 <= f.foe.hp, now: now, stage: ctl.stage, banner: banner(f)
        )
        // The scoreboard strip above the ring is native; show the ring from `stageY` down.
        let rows = Array(pixels[(BkRing.stageY * BkRing.artW)...])
        return BoxingImages.make(rows, w: BkRing.artW, h: BkRing.artH - BkRing.stageY)
    }

    @ViewBuilder
    private func fightBody(_ f: BoxingFight) -> some View {
        let now = BoxingController.clock()
        VStack(spacing: 0) {
            // Scoreboard.
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("YOU").font(.system(size: 11)).foregroundStyle(BK.pale)
                    BoxingBar(fraction: Double(f.myHp) / Double(BoxingRules.playerHp), color: BK.green)
                }
                VStack(spacing: 2) {
                    Text("Round \(f.round) of \(BoxingRules.rounds)")
                        .font(.system(size: 11)).foregroundStyle(BK.brass)
                    Text(boxingClock(f.clockMs(now: now)))
                        .font(.system(size: 16, weight: .bold, design: .monospaced)).foregroundStyle(BK.pale)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(f.foe.name).font(.system(size: 11)).foregroundStyle(BK.pale).lineLimit(1)
                    BoxingBar(fraction: Double(f.foeHp) / Double(f.foe.hp), color: BK.red)
                }
            }
            .padding(8)

            PixelImage(image: ringImage(f, now))
                .frame(maxWidth: 640)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Boxing ring, round \(f.round). You \(f.myHp) health, \(f.foe.name) \(f.foeHp).")

            HStack {
                Text(String(repeating: "★", count: f.stars) + String(repeating: "☆", count: max(BoxingRules.maxStars - f.stars, 0)))
                    .font(.system(size: 18))
                    .foregroundStyle(BK.brass)
                Spacer()
                Text("\(f.score)")
                    .font(.system(size: 16, weight: .bold, design: .monospaced))
                    .foregroundStyle(BK.pale)
                Button("Pause") { ctl.pauseFight() }
                    .padding(.leading, 8)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)

            tellPanel(f, now)

            Spacer(minLength: 0)

            if f.paused {
                VStack(spacing: 8) {
                    Text("Paused").font(.headline).foregroundStyle(BK.pale)
                    HStack(spacing: 12) {
                        Button("Resume") { ctl.resumeFight() }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.teal)
                        Button("Quit fight", role: .destructive) { ctl.quitFight() }
                    }
                }
                .padding(16)
            } else if f.phase == .roundEnd {
                Button {
                    ctl.nextRound()
                } label: {
                    Text("Next round")
                        .font(.headline)
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .padding(16)
            } else {
                BoxingKeyPad(wpm: model.settings.wpm, toneHz: model.settings.toneFrequency) { down, ms in
                    ctl.keyEdge(down: down, atMs: UInt32(truncatingIfNeeded: ms))
                }
                .padding(10)
            }
        }
        .frame(maxWidth: .infinity)
    }

    /// The tell and your keying.
    private func tellPanel(_ f: BoxingFight, _ now: UInt32) -> some View {
        let shown: String
        if f.starring {
            shown = f.word
        } else if f.phase == .meDown {
            shown = "Key \(f.getUp) to get up!"
        } else if f.tellShown {
            shown = f.tell
        } else {
            shown = ""
        }
        let typed = f.starring ? f.typed : f.answer
        let marks = ctl.marks
        return VStack(alignment: .leading, spacing: 2) {
            Text(shown.isEmpty ? (f.telling ? "· · ·" : " ") : shown)
                .font(.system(size: 26, weight: .bold, design: .monospaced))
                .foregroundStyle(BK.brass)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text(typed + (marks.isEmpty ? "" : "  " + marks))
                .font(.system(size: 18, design: .monospaced))
                .foregroundStyle(BK.pale)
                .lineLimit(1)
            if let w = f.window(now: now) {
                BoxingBar(fraction: Double(w.0) / Double(max(w.1, 1)), color: BK.brass)
                    .padding(.top, 4)
            }
            if let callout = f.callout {
                Text(boxingCalloutText(callout.0))
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(BK.red)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BK.panel)
    }
}

/// The key: the on-screen key or paddles (as chosen in Settings, #233), and a hardware Vail/BLE-MIDI key
/// through the same `SendingKeyer`. Every edge goes to `onEdge`, which feeds the keying judge; the keyer
/// also plays the sidetone. Owns its `SendingKeyer`, built at the session speed, the way
/// `AsteroidsKeyPanel` does.
private struct BoxingKeyPad: View {
    let onEdge: (Bool, Int64) -> Void
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false

    init(wpm: Double, toneHz: Double, onEdge: @escaping (Bool, Int64) -> Void) {
        self.onEdge = onEdge
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        OnScreenKeySwitch(onKey: { down, ms in sender.touchKey(isDown: down, atMs: ms) }) {
            ZStack {
                RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .fill(keyPressed ? Theme.teal : Theme.navyRaised)
                RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .strokeBorder(keyPressed ? Theme.tealBright : Theme.hairline, lineWidth: keyPressed ? 2 : 1)
                Text("KEY")
                    .font(.system(size: 14, weight: .bold)).tracking(1.5)
                    .foregroundStyle(keyPressed ? Theme.navy : .white)
            }
            .frame(height: 110)
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
            .accessibilityHint("Press and hold to key the tell back, then your punches")
        }
        .frame(height: 110)
        .onAppear {
            sender.onEdge = onEdge
            sender.start()
        }
        .onDisappear {
            sender.onEdge = nil
            sender.stop()
        }
    }
}

private struct BoxingResultsView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var ctl: BoxingController

    var body: some View {
        if let f = ctl.fight {
            results(f)
        }
    }

    private func results(_ f: BoxingFight) -> some View {
        var won = false
        if case .over(let w, _) = f.phase { won = w }
        let s = f.stats
        let worst = Array(s.worst().prefix(5))
        let next = BoxingRules.ladder[min(f.fighter + 1, BoxingRules.fighters - 1)]
        return ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text(won ? "You win!" : "You lose")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(BK.brass)
                Text(f.foe.name).foregroundStyle(BK.pale)
                Spacer().frame(height: 12)
                Text("Score \(f.score)")
                    .font(.system(size: 20, design: .monospaced))
                    .foregroundStyle(BK.pale)
                Text("Best \(ctl.career.bestScore(f.fighter))")
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(BK.brass)
                Spacer().frame(height: 8)
                if let a = s.accuracy {
                    Text("Dodged \(a)% of tells (\(s.dodged) of \(s.tells))").foregroundStyle(BK.pale)
                }
                if let c = s.cleanPercent {
                    Text("\(c)% of \(s.punches) punches clean").foregroundStyle(BK.pale)
                }
                Text("Damage dealt \(s.damage), taken \(s.taken)").foregroundStyle(BK.pale)
                Text("Knockdowns \(s.knockdowns), uppercuts \(s.uppercuts)").foregroundStyle(BK.pale)
                if !worst.isEmpty {
                    Spacer().frame(height: 8)
                    Text("Characters to work on")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(BK.brass)
                    Text(worst.map { "\($0.0) \($0.1)/\($0.2)" }.joined(separator: "   "))
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(BK.pale)
                }
                if ctl.opened {
                    Spacer().frame(height: 10)
                    Text("\(next.name) unlocked")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(BK.brass)
                }
                Spacer().frame(height: 20)
                if won && f.fighter + 1 < BoxingRules.fighters {
                    Button {
                        ctl.nextFighter()
                    } label: {
                        Text("Next fighter")
                            .font(.headline)
                            .foregroundStyle(Theme.navy)
                            .frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.teal)
                }
                Button {
                    ctl.beginFight(f.fighter)
                } label: {
                    Text("Fight again")
                        .font(.headline)
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                Button("Back to the fighters") { ctl.goTitle() }
                    .padding(.top, 4)
                Button {
                    // Ends the session: the record is written and the usual summary takes over.
                    model.endSession()
                } label: {
                    Text("End session")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
            }
            .padding(20)
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }
}
