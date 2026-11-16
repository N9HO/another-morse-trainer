import Foundation

// Brass Knuckles Knock Out: the ring and the title splash, drawn as pixel art
// into an ARGB buffer. Created by Derick (KO6PBZ) for the HamCube's PocketCW
// firmware (Kristen, K5MME); GPL-3.0-or-later, used with permission.
//
// A port of the game's firmware/src/ui/ring.rs. The firmware redraws only the
// rectangles that changed; here a whole frame is composed each time, which is
// cheap at 160 x 120. The art is composed at half size and doubled to
// 320 x 240, as on the device. Pure Swift: no UIKit or SwiftUI, so it can be
// tested and drawn by any UI. Twin of the Kotlin `morsekit/BrassKnucklesRing.kt`
// on Android and desktop; the three must stay in step (same constants, same
// draw order, same integer arithmetic). Colors are 0xAARRGGBB `UInt32`.

public enum BkRing {
    public static let artW = 160
    public static let artH = 120
    public static let screenW = 2 * artW
    public static let screenH = 2 * artH

    /// The ring starts below the scoreboard.
    public static let stageY = 16
    /// The side panels cover the arena above this row, outside `window`.
    public static let panelBottom = 59
    public static let window = (37, 123)

    private static let foeX = 80
    private static let foeY = 110
    private static let meX = 80
    private static let meY = 117
    private static let myShoulderX = 12
    private static let myShoulderY = -32
    private static let torsoY = -63
    private static let neckY = -60
    private static let shoulderX = 23
    private static let shoulderY = -51
    private static let faceY = -15
    private static let kneelDrop = 18
    private static let dodgeX = 32
    private static let ropeY = [46, 50, 54]
    private static let matY = 59
    private static let shadowPercent = 45

    public static let splashH = 96
    public static let mottoY = 194
    public static let mottoH = 28
    public static let promptY = mottoY + mottoH + 2
    public static let mottoMs = 1500
    public static let promptMs = 2100

    /// Rounds an 0xRRGGBB color through RGB565, as the device's panel does.
    public static func rgb(_ hex: Int) -> UInt32 { q((hex >> 16) & 255, (hex >> 8) & 255, hex & 255) }

    private static func q(_ r: Int, _ g: Int, _ b: Int) -> UInt32 {
        let r5 = r >> 3
        let g6 = g >> 2
        let b5 = b >> 3
        let rr = (r5 << 3) | (r5 >> 2)
        let gg = (g6 << 2) | (g6 >> 4)
        let bb = (b5 << 3) | (b5 >> 2)
        return 0xFF00_0000 | UInt32((rr << 16) | (gg << 8) | bb)
    }

    private static func channel(_ v: UInt32, _ shift: Int) -> Int { Int((v >> UInt32(shift)) & 255) }

    /// The color `percent` of the way from `a` to `b`.
    public static func mix(_ a: UInt32, _ b: UInt32, _ percent: Int) -> UInt32 {
        func m(_ x: Int, _ y: Int) -> Int { (x * (100 - percent) + y * percent) / 100 }
        return q(
            m(channel(a, 16), channel(b, 16)),
            m(channel(a, 8), channel(b, 8)),
            m(channel(a, 0), channel(b, 0))
        )
    }

    private static let white = rgb(0xFFFFFF)

    /// A half-open range that is empty, not a crash, when the bounds cross (Kotlin's `until`).
    private static func span(_ a: Int, _ b: Int) -> Range<Int> { a < b ? a..<b : a..<a }

    /// The value of a hex digit in a sprite or font string (0 for anything else).
    private static func hex(_ b: UInt8) -> Int {
        switch b {
        case 48...57: return Int(b) - 48
        case 97...102: return Int(b) - 87
        case 65...70: return Int(b) - 55
        default: return 0
        }
    }

    public struct Stage: Sendable {
        public let name: String
        public let wall: UInt32
        public let wallLow: UInt32
        public let barrier: UInt32
        public let barrierEdge: UInt32
        public let ropes: [UInt32]
        public let ropeShade: [UInt32]
        public let post: UInt32
        public let postDark: UInt32
        public let pad: UInt32
        public let apron: UInt32
        public let apronEdge: UInt32
        public let matBack: UInt32
        public let matFront: UInt32
    }

    private static func rgbs(_ h: [Int]) -> [UInt32] { h.map { rgb($0) } }

    /// The stages, in the order of the stage tiles and palettes.
    public static let stages: [Stage] = [
        Stage(name: "THE ARENA", wall: rgb(0x1A1426), wallLow: rgb(0x2A2038), barrier: rgb(0x0E0A14), barrierEdge: rgb(0x4A3C5A),
              ropes: rgbs([0xD83A3A, 0xECECEC, 0x3A5AD8]), ropeShade: rgbs([0x8A1E1E, 0x9A9AA4, 0x22348A]),
              post: rgb(0x9A9AA8), postDark: rgb(0x4A4A58), pad: rgb(0xE8B020), apron: rgb(0x2E3E78),
              apronEdge: rgb(0xE8E8F0), matBack: rgb(0x4C6CA2), matFront: rgb(0x86A4D2)),
        Stage(name: "THE HAM SHACK", wall: rgb(0x2E1C10), wallLow: rgb(0x3D2616), barrier: rgb(0x1E120A), barrierEdge: rgb(0x9A6A3A),
              ropes: rgbs([0xF0C020, 0x202020, 0xF0C020]), ropeShade: rgbs([0x9A7810, 0x080808, 0x9A7810]),
              post: rgb(0xB08850), postDark: rgb(0x5A3818), pad: rgb(0xD83A3A), apron: rgb(0x5A3818),
              apronEdge: rgb(0xE8D8B0), matBack: rgb(0x8A6A40), matFront: rgb(0xD2B482)),
        Stage(name: "FIELD DAY", wall: rgb(0x2E6A24), wallLow: rgb(0x3E8A30), barrier: rgb(0x6A5030), barrierEdge: rgb(0xD8B860),
              ropes: rgbs([0xF07A20, 0xFFFFFF, 0x2E6AD8]), ropeShade: rgbs([0x904A10, 0xA0A0A8, 0x1A3E80]),
              post: rgb(0xD0D0D8), postDark: rgb(0x6A6A74), pad: rgb(0x3E8A30), apron: rgb(0x2E6AD8),
              apronEdge: rgb(0xFFFFFF), matBack: rgb(0xA8ACB4), matFront: rgb(0xF0F0F0)),
        Stage(name: "ROOFTOP DX", wall: rgb(0x14141E), wallLow: rgb(0x22222E), barrier: rgb(0x0A0A10), barrierEdge: rgb(0x6A6A7A),
              ropes: rgbs([0xFF2EA0, 0x30F0FF, 0xA0FF30]), ropeShade: rgbs([0x901860, 0x188890, 0x589018]),
              post: rgb(0x6A6A7A), postDark: rgb(0x34343E), pad: rgb(0xFF2EA0), apron: rgb(0x101018),
              apronEdge: rgb(0x30F0FF), matBack: rgb(0x26263A), matFront: rgb(0x4A4A66)),
    ]

    // --- Layers -------------------------------------------------------------------------------

    struct Layer {
        let sprite: BkSprite
        let x: Int
        let y: Int
        let palette: [UInt32]
        var flip = false
        var tint: UInt32?
        var scale = 1

        init(_ sprite: BkSprite, _ x: Int, _ y: Int, _ palette: [UInt32],
             flip: Bool = false, tint: UInt32? = nil, scale: Int = 1) {
            self.sprite = sprite
            self.x = x
            self.y = y
            self.palette = palette
            self.flip = flip
            self.tint = tint
            self.scale = scale
        }

        func flipped(_ f: Bool) -> Layer { Layer(sprite, x, y, palette, flip: f, tint: tint, scale: scale) }
        func flashing(_ f: Bool) -> Layer { Layer(sprite, x, y, palette, flip: flip, tint: f ? BkRing.white : nil, scale: scale) }
        func scaled(_ k: Int) -> Layer { Layer(sprite, x, y, palette, flip: flip, tint: tint, scale: k) }

        /// Draws into `buf`, `w` pixels wide, each sprite pixel as `scale` art pixels.
        func draw(_ buf: inout [UInt32], w: Int, h: Int) {
            let s = sprite
            let k = scale
            let sw = s.w * k
            let sh = s.h * k
            let ox = s.ox * k
            let oy = s.oy * k
            let left = flip ? x + ox + 1 - sw : x - ox
            let top = y - oy
            let pix = Array(s.pixels.utf8)
            for py in BkRing.span(max(top, 0), min(top + sh, h)) {
                let srow = (py - top) / k * s.w
                for px in BkRing.span(max(left, 0), min(left + sw, w)) {
                    let col = (flip ? left + sw - 1 - px : px - left) / k
                    let v = BkRing.hex(pix[srow + col])
                    if v != 0 { buf[py * w + px] = tint ?? palette[v] }
                }
            }
        }
    }

    // --- Block letters ------------------------------------------------------------------------

    private static let block: [Character: [Int]] = [
        "A": [14, 17, 17, 31, 17, 17, 17], "B": [30, 17, 17, 30, 17, 17, 30],
        "C": [14, 17, 16, 16, 16, 17, 14], "D": [30, 17, 17, 17, 17, 17, 30],
        "E": [31, 16, 16, 30, 16, 16, 31], "F": [31, 16, 16, 30, 16, 16, 16],
        "G": [14, 17, 16, 23, 17, 17, 15], "H": [17, 17, 17, 31, 17, 17, 17],
        "I": [14, 4, 4, 4, 4, 4, 14], "J": [7, 2, 2, 2, 2, 18, 12],
        "K": [17, 18, 20, 24, 20, 18, 17], "L": [16, 16, 16, 16, 16, 16, 31],
        "M": [17, 27, 21, 21, 17, 17, 17], "N": [17, 17, 25, 21, 19, 17, 17],
        "O": [14, 17, 17, 17, 17, 17, 14], "P": [30, 17, 17, 30, 16, 16, 16],
        "Q": [14, 17, 17, 17, 21, 18, 13], "R": [30, 17, 17, 30, 20, 18, 17],
        "S": [15, 16, 16, 14, 1, 1, 30], "T": [31, 4, 4, 4, 4, 4, 4],
        "U": [17, 17, 17, 17, 17, 17, 14], "V": [17, 17, 17, 17, 17, 10, 4],
        "W": [17, 17, 17, 21, 21, 21, 10], "X": [17, 17, 10, 4, 10, 17, 17],
        "Y": [17, 17, 10, 4, 4, 4, 4], "Z": [31, 1, 2, 4, 8, 16, 31],
        "0": [14, 17, 19, 21, 25, 17, 14], "1": [4, 12, 4, 4, 4, 4, 14],
        "2": [14, 17, 1, 2, 4, 8, 31], "3": [31, 2, 4, 2, 1, 17, 14],
        "4": [2, 6, 10, 18, 31, 2, 2], "5": [31, 16, 30, 1, 1, 17, 14],
        "6": [6, 8, 16, 30, 17, 17, 14], "7": [31, 1, 2, 4, 8, 8, 8],
        "8": [14, 17, 17, 14, 17, 17, 14], "9": [14, 17, 17, 15, 1, 2, 12],
        "/": [1, 1, 2, 4, 8, 16, 16], "-": [0, 0, 0, 31, 0, 0, 0],
        ".": [0, 0, 0, 0, 0, 12, 12], "!": [4, 4, 4, 4, 4, 0, 4],
        "+": [0, 4, 4, 31, 4, 4, 0], "?": [14, 17, 1, 2, 4, 0, 4],
        ":": [0, 12, 12, 0, 12, 12, 0], "'": [4, 4, 8, 0, 0, 0, 0],
        " ": [0, 0, 0, 0, 0, 0, 0],
    ]
    private static let solid = [31, 31, 31, 31, 31, 31, 31]

    /// Big block letters across the ring: `px` art pixels per block, outlined in `edge`.
    public struct Banner: Sendable {
        public let text: String
        public let color: UInt32
        public let y: Int
        public let px: Int
        public let edge: UInt32

        public init(_ text: String, color: UInt32, y: Int, px: Int, edge: UInt32) {
            self.text = text
            self.color = color
            self.y = y
            self.px = px
            self.edge = edge
        }

        /// The banner's width in art pixels.
        public var width: Int { text.count * 6 * px - px }

        func draw(_ buf: inout [UInt32], w: Int, h: Int) {
            let glyphs: [[Int]] = text.map { c in
                let upper = c.uppercased().first ?? c
                return BkRing.block[upper] ?? BkRing.solid
            }
            let width = self.width
            let left = (w - width) / 2
            let px = self.px
            let y = self.y
            func on(_ x: Int, _ yy: Int) -> Bool {
                let dx = x - left
                let dy = yy - y
                if dx < 0 || dy < 0 || dy >= 7 * px { return false }
                let cell = dx / (6 * px)
                let col = dx % (6 * px) / px
                if col >= 5 || cell >= glyphs.count { return false }
                return glyphs[cell][dy / px] & (16 >> col) != 0
            }
            let top = BkRing.mix(color, BkRing.white, 45)
            for yy in BkRing.span(max(y - 1, 0), min(y + 7 * px + 3, h)) {
                for x in BkRing.span(max(left - 1, 0), min(left + width + 3, w)) {
                    if on(x, yy) {
                        buf[yy * w + x] = (yy - y < 3 * px) ? top : color
                    } else if on(x - 1, yy) || on(x + 1, yy) || on(x, yy - 1) || on(x, yy + 1)
                                || on(x - 1, yy - 1) || on(x - 2, yy - 2) {
                        buf[yy * w + x] = edge
                    }
                }
            }
        }
    }

    // --- The arena ----------------------------------------------------------------------------

    private static func fill(_ buf: inout [UInt32], _ from: Int, _ to: Int, _ value: UInt32) {
        for i in span(from, to) { buf[i] = value }
    }

    private static func arena(_ buf: inout [UInt32], _ k: Int, _ shadowX: Int?) {
        let st = stages[k]
        let crowd = BrassKnucklesArt.stage_tile[k]
        let crowdPix = Array(crowd.pixels.utf8)
        let pal = BrassKnucklesArt.stage_palettes[k]
        let crowdRows = crowd.h
        for y in 0..<artH {
            let row = y * artW
            if y < stageY {
                fill(&buf, row, row + artW, 0xFF00_0000)
                continue
            }
            if y < stageY + crowdRows {
                for x in 0..<artW {
                    let v = hex(crowdPix[(y - stageY) * crowd.w + x % crowd.w])
                    buf[row + x] = pal[v]
                }
                continue
            }
            if y < matY {
                let i = y - stageY - crowdRows
                let base: UInt32
                switch i {
                case 0: base = st.barrierEdge
                case 1, 2: base = st.barrier
                default: base = mix(st.wall, st.wallLow, min(max(i * 100 / 14, 0), 100))
                }
                fill(&buf, row, row + artW, base)
                if y >= matY - 3 {
                    fill(&buf, row, row + artW, y == matY - 3 ? st.apronEdge : st.apron)
                }
                for (n, ry) in ropeY.enumerated() {
                    if y == ry || y == ry + 1 {
                        let c = y == ry ? st.ropes[n] : st.ropeShade[n]
                        for x in 6..<154 { buf[row + x] = c }
                    }
                }
                for post in [4, 152] {
                    for x in post..<(post + 4) {
                        let pad = ropeY.contains { y >= $0 - 1 && y <= $0 + 2 }
                        buf[row + x] = pad ? st.pad : ((x == post || x == post + 3) ? st.postDark : st.post)
                    }
                }
                continue
            }
            let mat = mix(st.matBack, st.matFront, (y - matY) * 100 / (artH - matY))
            let dark = mix(mat, rgb(0x101828), shadowPercent)
            for x in 0..<artW {
                var c = mat
                if let shadowX {
                    let dx = x - shadowX
                    let dy = y - foeY
                    if dx * dx * 16 + dy * dy * 23 * 23 <= 23 * 23 * 16 { c = dark }
                }
                buf[row + x] = c
            }
        }
    }

    // --- The fight ----------------------------------------------------------------------------

    /// Milliseconds from `since` to `now` on the wrapping tick clock, never negative.
    private static func elapsed(_ now: UInt32, _ since: UInt32) -> Int {
        max(0, Int(Int32(bitPattern: now &- since)))
    }

    /// The ring for one frame, 160 x 120 ARGB: `fighter` is the opponent's ladder index, `foe` and `me`
    /// are the poses and when they began (`BoxingFight.foePose`, `BoxingFight.mePose`), `hurting` is
    /// low health, and `now` the tick count in ms.
    public static func ring(
        fighter: Int,
        foe: (BoxingFoe, UInt32),
        me: (BoxingMe, UInt32),
        hurting: Bool,
        now: UInt32,
        stage: Int,
        banner: Banner? = nil
    ) -> [UInt32] {
        var layers: [Layer] = []
        let shadow = foeLayers(&layers, fighter, foe, hurting, now)
        meLayers(&layers, me, now)
        var buf = [UInt32](repeating: 0, count: artW * artH)
        arena(&buf, stage, shadow)
        for l in layers { l.draw(&buf, w: artW, h: artH) }
        banner?.draw(&buf, w: artW, h: artH)
        return buf
    }

    private static func toward(_ s: BoxingSide, _ n: Int) -> Int { s == .left ? -n : n }
    private static func sideIx(_ s: BoxingSide) -> Int { s == .left ? 0 : 1 }

    /// Adds the opponent's layers, back to front; returns the shadow's middle when he stands.
    private static func foeLayers(_ out: inout [Layer], _ fighter: Int, _ foe: (BoxingFoe, UInt32), _ hurting: Bool, _ nowU: UInt32) -> Int? {
        typealias Art = BrassKnucklesArt
        let look = Art.looks[fighter]
        let pal = Art.fighter_palettes[fighter]
        let (pose, since) = foe
        let t = elapsed(nowU, since)
        let now = Int(nowU)
        let x = foeX
        let y = foeY
        if pose == .out {
            out.append(Layer(Art.extra[1], x, y - 2, pal))
            dizzy(&out, x, y - 34, now)
            return nil
        }
        let rise = pose == .rise && t < 360
        let down = pose == .down || rise
        let bob = (now / 260) % 4 < 2 ? 0 : 1
        var body = (x: 0, y: bob)
        var headd = (x: 0, y: bob)
        var arms: [(a: Int, dx: Int, dy: Int)] = [(0, 0, bob), (0, 0, 1 - bob)]
        var face = hurting ? 3 : 0
        var front = 1
        var flash = false
        var extra: [Layer] = []
        switch pose {
        case .idle, .out, .down:
            break
        case .rise:
            face = 0
            body.y = 0
            headd.y = 0
        case .windup(let s):
            let shake = (t > 300 && (t / 90) % 2 == 0) ? 1 : 0
            body = (toward(s, -2) + shake, 0)
            headd = (toward(s, -3) + shake, -1)
            arms[sideIx(s)] = (1, toward(s, -2), -1)
            arms[1 - sideIx(s)] = (arms[1 - sideIx(s)].a, toward(s, -2), 0)
            front = sideIx(s)
            face = 1
        case .punch(let s):
            body = (toward(s, -2), 2)
            headd = (toward(s, -2), 3)
            arms[sideIx(s)] = (2, toward(s, -2), 2)
            arms[1 - sideIx(s)] = (arms[1 - sideIx(s)].a, toward(s, -2), 2)
            front = sideIx(s)
            face = 1
        case .whiff(let s):
            let k = min(t / 40, 4)
            body = (toward(s, -k - 2), 2)
            headd = (toward(s, -2 * k - 2), 3)
            arms[sideIx(s)] = (3, toward(s, -k - 2), 2)
            arms[1 - sideIx(s)] = (4, toward(s, -k), 2)
            front = sideIx(s)
            face = 2
        case .open:
            let sway = [0, 1, 2, 1, 0, -1, -2, -1][(now / 140 % 8)]
            body = (sway / 2, 2)
            headd = (sway, 3)
            arms[0] = (4, sway / 2, 2)
            arms[1] = (4, sway / 2, 2)
            face = 3
            sweat(&extra, x + headd.x, y + neckY + headd.y + faceY, now)
        case .hurt(let s):
            let snap = t < 120 ? 5 : (t < 240 ? 3 : 1)
            body = (toward(s, -1), 1)
            headd = (toward(s, -snap), 2)
            arms[0] = (4, toward(s, -1), 1)
            arms[1] = (4, toward(s, -1), 1)
            face = 2
            flash = t < 70
            if t < 160 {
                let starX = x + headd.x + toward(s, 9)
                extra.append(Layer(Art.fx[2], starX, y + neckY + faceY + 2, Art.fx_palette))
            }
        case .block:
            body = (0, 1)
            headd = (0, 3)
            arms[0] = (5, 0, 1)
            arms[1] = (5, 0, 1)
            face = 1
        case .stunned:
            let sway = [0, 1, 1, 0, -1, -1][(now / 160 % 6)]
            body = (0, 2)
            headd = (sway, 3)
            arms[0] = (4, 0, 2)
            arms[1] = (4, 0, 2)
            face = 3
        case .victory:
            let hop = (t / 180) % 2 == 0 ? 0 : -2
            body = (0, hop)
            headd = (0, hop)
            arms[0] = (6, 0, hop)
            arms[1] = (6, 0, hop)
            face = 4
        }
        if down {
            body = (0, kneelDrop)
            headd = (3, kneelDrop + 3)
            arms[0] = (4, 0, kneelDrop)
            arms[1] = (4, 1, kneelDrop)
            face = 3
        }
        let legs = down ? look.kneel : look.legs
        out.append(Layer(Art.legs[legs], x, y, pal))
        let bx = x + body.x
        let by = y + torsoY + body.y
        out.append(Layer(Art.torso[look.torso], bx, by, pal))
        if look.emblem { out.append(Layer(Art.extra[0], bx, by + 17, pal)) }
        let hx = x + headd.x
        let hy = y + neckY + headd.y
        out.append(Layer(Art.head[fighter], hx, hy, pal).flashing(flash))
        out.append(Layer(Art.face[face], hx, hy, pal))
        out.append(Layer(Art.gear[fighter], hx, hy, pal))
        // The arm on the far side is drawn first, the one in front after.
        var armLayers: [Layer] = []
        for k in 0..<2 {
            let arm = arms[k]
            let sx = k == 0 ? -shoulderX : shoulderX - 1
            armLayers.append(Layer(Art.arm[arm.a], x + sx + arm.dx, y + shoulderY + arm.dy, pal).flipped(k == 1))
        }
        out.append(armLayers[1 - front])
        out.append(armLayers[front])
        out += extra
        if down || pose == .stunned { dizzy(&out, hx, hy + faceY - 18, now) }
        return x
    }

    private static let orbit: [(Int, Int)] = [
        (12, 0), (10, 2), (6, 3), (0, 4), (-6, 3), (-10, 2), (-12, 0), (-10, -2), (-6, -3), (0, -4), (6, -3), (10, -2),
    ]

    private static func dizzy(_ out: inout [Layer], _ x: Int, _ y: Int, _ now: Int) {
        for k in 0..<3 {
            let (dx, dy) = orbit[((now / 90) + k * 4) % orbit.count]
            out.append(Layer(BrassKnucklesArt.fx[0], x + dx, y + dy, BrassKnucklesArt.fx_palette))
        }
    }

    private static func sweat(_ out: inout [Layer], _ x: Int, _ y: Int, _ now: Int) {
        let d = (now % 600) / 60
        out.append(Layer(BrassKnucklesArt.fx[3], x - 12 - d, y - 6 + d / 2, BrassKnucklesArt.sweat_palette))
        out.append(Layer(BrassKnucklesArt.fx[3], x + 12 + d, y - 8 + d / 3, BrassKnucklesArt.sweat_palette))
    }

    private static func meLayers(_ out: inout [Layer], _ me: (BoxingMe, UInt32), _ nowU: UInt32) {
        typealias Art = BrassKnucklesArt
        let guardArm = 0, jab = 1, hook = 2, upper = 3, raise = 4, low = 5, tuck = 6
        let (pose, since) = me
        let t = elapsed(nowU, since)
        let now = Int(nowU)
        let bob = (now / 300) % 2 == 0 ? 0 : 1
        var x = meX
        var y = meY
        var arms: [(a: Int, dx: Int, dy: Int)] = [(guardArm, 0, bob), (guardArm, 0, bob)]
        var fx: Layer?
        func ease(_ ms: Int, _ n: Int) -> Int { n * min(t, ms) / ms }
        switch pose {
        case .idle:
            break
        case .dodge(let s):
            x += toward(s, ease(90, dodgeX))
            y += 2
        case .jab(let s):
            if t < 170 {
                arms[sideIx(s)] = (jab, 0, 0)
                arms[1 - sideIx(s)] = (tuck, 0, 0)
                y -= 1
            }
        case .blocked(let s):
            arms[sideIx(s)] = (jab, 0, t < 120 ? 0 : 3)
            arms[1 - sideIx(s)] = (tuck, 0, 0)
            y += t < 120 ? 0 : 2
        case .hook(let s):
            if t < 240 {
                arms[sideIx(s)] = (hook, s == .left ? -4 : 4, -2)
                arms[1 - sideIx(s)] = (tuck, 0, 0)
                x += toward(s, -2)
            }
        case .combo(let punches):
            let k = t / 150
            if k < punches {
                let s: BoxingSide = k % 2 == 0 ? .left : .right
                let reach = k == 0 ? 0 : (s == .left ? -4 : 4)
                arms[sideIx(s)] = (k == 0 ? jab : hook, reach, k == 0 ? 0 : -2)
                arms[1 - sideIx(s)] = (tuck, 0, 0)
                x += toward(s, k == 0 ? 1 : -2)
            }
        case .uppercut:
            if t < 160 {
                y += 3
                arms[0] = (guardArm, 0, 3)
                arms[1] = (guardArm, 0, 4)
            } else if t < 560 {
                y -= 2
                arms[0] = (tuck, 0, 0)
                arms[1] = (upper, 0, 0)
                if t >= 200 && t < 380 { fx = Layer(Art.fx[2], foeX + 4, foeY + neckY - 4, Art.fx_palette) }
            }
        case .miss:
            arms[0] = (tuck, 0, 0)
            arms[1] = (hook, 3, 0)
            x += 4
        case .hurt:
            y += 3
            if t < 240 { x += (t / 60) % 2 == 0 ? -2 : 2 }
            arms[0] = (low, 0, 0)
            arms[1] = (low, 0, 0)
            if t < 150 { fx = Layer(Art.fx[2], x, y - 46, Art.fx_palette) }
        case .down:
            y += ease(300, 30)
            arms[0] = (low, 0, 0)
            arms[1] = (low, 0, 0)
        case .rise:
            y += 30 - ease(600, 30)
            if t < 400 {
                arms[0] = (low, 0, 0)
                arms[1] = (low, 0, 0)
            }
        case .victory:
            y -= (t / 200) % 2 == 0 ? 0 : 2
            arms[0] = (raise, 0, 0)
            arms[1] = (raise, 0, 0)
        }
        // Seen from behind, your arms are in front of you: your head, back and legs cover them.
        let pal = Art.player_palette
        var armLayers: [Layer] = []
        for k in 0..<2 {
            let arm = arms[k]
            let sx = k == 0 ? -myShoulderX : myShoulderX - 1
            armLayers.append(Layer(Art.hero_arm[arm.a], x + sx + arm.dx, y + myShoulderY + arm.dy, pal).flipped(k == 1))
        }
        out.append(armLayers[0])
        out.append(armLayers[1])
        out.append(Layer(Art.hero_body[0], x, y, pal))
        if let fx { out.append(fx) }
    }

    // --- The title splash ---------------------------------------------------------------------

    private static let yellow = rgb(0xFFE030)
    private static let red = rgb(0xD81818)
    private static let green = rgb(0x30FF50)
    public static let band = rgb(0x0A0306)
    private static let starCount = 12
    private static let lines: [(String, Int, Int)] = [("BRASS", 650, 43), ("KNUCKLE", 900, 60), ("GANG", 1150, 77)]

    private static func hash(_ n: UInt32) -> UInt32 {
        var x = (n &* 0x9E37_79B1) ^ 0x5BD1_E995
        x ^= x >> 15
        x = x &* 0x85EB_CA77
        x ^= x >> 13
        x = x &* 0xC2B2_AE3D
        return x ^ (x >> 16)
    }

    private static func umod(_ a: UInt32, _ n: Int) -> Int { Int(a % UInt32(n)) }

    /// True while a title line has just landed: the moment to throw a big burst.
    public static func slam(_ t: Int) -> Bool { lines.contains { t >= $0.1 && t - $0.1 < 90 } }

    /// The title splash `t` ms in, 320 x 240 ARGB: the logo, BRASS KNUCKLE GANG punched in a line at a time,
    /// impact stars going off all over, and the motto band. `seed` scatters the stars differently each time.
    public static func splash(t: Int, seed: UInt32) -> [UInt32] {
        typealias Art = BrassKnucklesArt
        var buf = [UInt32](repeating: 0, count: artW * splashH)
        for y in 0..<splashH {
            let base = mix(rgb(0x3A0A0C), rgb(0x0E0306), y * 100 / splashH)
            let light = mix(base, rgb(0x6A1A14), 30)
            for x in 0..<artW { buf[y * artW + x] = (x + y) % 16 < 8 ? light : base }
        }
        let cycle = 700
        var stars: [Layer] = []
        for k in 0..<starCount {
            let offset = k * cycle / starCount
            let since = t - offset / 3
            if since < 0 { continue }
            let round = since / cycle
            let at = since % cycle
            let h = hash(seed ^ UInt32(truncatingIfNeeded: round << 8) ^ UInt32(truncatingIfNeeded: k))
            let start = umod(h, 200)
            if at < start || at >= start + 420 { continue }
            let age = at - start
            let scale = [1, 1, 1, 2, 2, 3][umod(h >> 8, 6)]
            let x = umod(h >> 12, 160)
            let y = 4 + umod(h >> 20, splashH - 12)
            let sprite: BkSprite
            let sc: Int
            if age < 90 {
                sprite = Art.fx[2]; sc = scale
            } else if age < 300 {
                sprite = Art.fx[1]; sc = scale
            } else {
                sprite = Art.fx[0]; sc = max(scale, 2) - 1
            }
            stars.append(Layer(sprite, x, y, Art.fx_palette).scaled(sc))
        }
        for l in stars { l.draw(&buf, w: artW, h: splashH) }
        let logoY: Int
        if t < 400 {
            logoY = -24 + 48 * t / 400
        } else if t < 500 {
            logoY = 24 - (t - 400) / 20
        } else if t < 600 {
            logoY = 19 + (t - 500) / 20
        } else {
            logoY = 22
        }
        Layer(Art.logo[0], 80, logoY, Art.logo_palette).draw(&buf, w: artW, h: splashH)
        for (text, at, y0) in lines where t >= at {
            let px = t - at < 90 ? 3 : 2
            Banner(text, color: yellow, y: px == 3 ? y0 - 4 : y0, px: px, edge: red).draw(&buf, w: artW, h: splashH)
        }
        var screen = [UInt32](repeating: band, count: screenW * screenH)
        for y in 0..<(2 * splashH) {
            for x in 0..<screenW { screen[y * screenW + x] = buf[y / 2 * artW + x / 2] }
        }
        if t >= mottoMs { motto(&screen) }
        return screen
    }

    /// The motto, `彙★ 2-Meter CW or Bust!!! ★彙`, at full resolution under the splash.
    private static func motto(_ screen: inout [UInt32]) {
        typealias Art = BrassKnucklesArt
        let text = Array(" 2-Meter CW or Bust!!! ")
        let font = Art.fontSmall
        let hui = Art.glyph[0]
        let star = Art.glyph[1]
        let huiPix = Array(hui.pixels.utf8)
        let starPix = Array(star.pixels.utf8)
        let textW = text.count * font.w
        let width = 2 * (hui.w + 2 + star.w) + textW
        let left = (screenW - width) / 2
        // (x, y, sprite, its pixels)
        let pieces: [(Int, Int, BkSprite, [UInt8])] = [
            (left, 2, hui, huiPix),
            (left + hui.w + 2, 7, star, starPix),
            (left + width - hui.w - 2 - star.w, 7, star, starPix),
            (left + width - hui.w, 2, hui, huiPix),
        ]
        let textX = left + hui.w + 2 + star.w
        let textY = (mottoH - font.h) / 2
        func on(_ s: BkSprite, _ pix: [UInt8], _ x: Int, _ y: Int) -> Bool {
            x >= 0 && x < s.w && y >= 0 && y < s.h && pix[y * s.w + x] != 48
        }
        // The cells of the motto's characters, decoded once.
        let cells: [[UInt8]?] = text.map { cellBytes(font, $0) }
        func ink(_ x: Int, _ y: Int) -> Int {
            let dx = x - textX
            let dy = y - textY
            if dx < 0 || dy < 0 || dx >= textW || dy >= font.h { return 0 }
            guard let cell = cells[dx / font.w] else { return 15 }
            return hex(cell[dy * font.w + dx % font.w])
        }
        let dim = rgb(0x0A5A18)
        for y in 0..<mottoH {
            for x in 0..<screenW {
                var p = (y == 0 || y == mottoH - 1) ? red : band
                for (px, py, s, pix) in pieces {
                    if on(s, pix, x - px, y - py) { p = green } else if on(s, pix, x - px - 1, y - py - 1) { p = dim }
                }
                let shadow = ink(x - 1, y - 1)
                if shadow > 0 { p = mix(p, red, shadow * 100 / 15) }
                let c = ink(x, y)
                if c > 0 { p = mix(p, yellow, c * 100 / 15) }
                screen[(mottoY + y) * screenW + x] = p
            }
        }
    }

    // --- Portraits ----------------------------------------------------------------------------

    /// Side of the square `portrait` buffer.
    public static let portraitSize = 48

    /// A fighter's head on `bg`, in art pixels, `portraitSize` square. A locked fighter shows as a dark silhouette.
    public static func portrait(fighter: Int, face: Int, locked: Bool, bg: UInt32) -> [UInt32] {
        typealias Art = BrassKnucklesArt
        let pal = Art.fighter_palettes[fighter]
        var buf = [UInt32](repeating: bg, count: portraitSize * portraitSize)
        let x = portraitSize / 2
        let y = portraitSize - 3
        Layer(Art.head[fighter], x, y, pal, tint: locked ? rgb(0x2A2434) : nil).draw(&buf, w: portraitSize, h: portraitSize)
        if !locked {
            Layer(Art.face[face], x, y, pal).draw(&buf, w: portraitSize, h: portraitSize)
            Layer(Art.gear[fighter], x, y, pal).draw(&buf, w: portraitSize, h: portraitSize)
        }
        return buf
    }

    // --- Bitmap text --------------------------------------------------------------------------

    /// The cell of `c` in `font` as hex-digit bytes; a lowercase letter missing from the font uses its capital; nil when neither is there.
    private static func cellBytes(_ font: BkFont, _ c: Character) -> [UInt8]? {
        var i = font.chars.firstIndex(of: c)
        if i == nil, let up = c.uppercased().first { i = font.chars.firstIndex(of: up) }
        guard let i else { return nil }
        return Array(font.cells[font.chars.distance(from: font.chars.startIndex, to: i)].utf8)
    }

    /// The 0..15 coverage of `c` at (`x`, `y`) in its cell; a lowercase letter missing from the font uses its capital.
    static func fontCoverage(_ font: BkFont, _ c: Character, _ x: Int, _ y: Int) -> Int {
        guard let cell = cellBytes(font, c) else { return 15 }
        if x < 0 || x >= font.w || y < 0 || y >= font.h { return 0 }
        return hex(cell[y * font.w + x])
    }

    /// Draws `text` into a `w`-wide ARGB buffer with its top left at (`x0`, `y0`), blending from what is there to `fg`.
    static func drawText(_ buf: inout [UInt32], w: Int, font: BkFont, x0: Int, y0: Int, text: String, fg: UInt32) {
        let h = buf.count / w
        for (n, c) in text.enumerated() {
            let cell = cellBytes(font, c)
            for y in 0..<font.h {
                for x in 0..<font.w {
                    let px = x0 + n * font.w + x
                    let py = y0 + y
                    if px < 0 || px >= w || py < 0 || py >= h { continue }
                    let cov = cell.map { hex($0[y * font.w + x]) } ?? 15
                    if cov > 0 { buf[py * w + px] = mix(buf[py * w + px], fg, cov * 100 / 15) }
                }
            }
        }
    }

    /// Doubles an art-sized frame to screen size.
    public static func doubled(_ art: [UInt32]) -> [UInt32] {
        var out = [UInt32](repeating: 0, count: screenW * screenH)
        for y in 0..<screenH {
            for x in 0..<screenW { out[y * screenW + x] = art[y / 2 * artW + x / 2] }
        }
        return out
    }

    // --- Self-check ---------------------------------------------------------------------------

    /// Problems found in the art tables, for the logic harness (the tables themselves are internal): every
    /// sprite's pixel string is `w * h` digits, the per-fighter tables line up, palettes have 16 entries and
    /// the fonts have one `w * h` cell per character. Empty when all is well.
    public static func artProblems() -> [String] {
        typealias Art = BrassKnucklesArt
        var bad: [String] = []
        func sprites(_ name: String, _ list: [BkSprite]) {
            for (i, s) in list.enumerated() where s.pixels.utf8.count != s.w * s.h {
                bad.append("\(name)[\(i)] has \(s.pixels.utf8.count) pixels, expected \(s.w * s.h)")
            }
        }
        sprites("torso", Art.torso); sprites("legs", Art.legs); sprites("arm", Art.arm)
        sprites("head", Art.head); sprites("gear", Art.gear); sprites("face", Art.face)
        sprites("extra", Art.extra); sprites("hero_body", Art.hero_body); sprites("hero_arm", Art.hero_arm)
        sprites("fx", Art.fx); sprites("stage_tile", Art.stage_tile); sprites("logo", Art.logo)
        sprites("glyph", Art.glyph)
        func palettes(_ name: String, _ list: [[UInt32]]) {
            for (i, p) in list.enumerated() where p.count != 16 { bad.append("\(name)[\(i)] has \(p.count) colors") }
        }
        palettes("fighter_palettes", Art.fighter_palettes); palettes("stage_palettes", Art.stage_palettes)
        palettes("single", [Art.player_palette, Art.fx_palette, Art.sweat_palette, Art.logo_palette])
        let n = BoxingRules.fighters
        if Art.head.count != n || Art.gear.count != n || Art.fighter_palettes.count != n || Art.looks.count != n {
            bad.append("per-fighter tables are not \(n) long")
        }
        for (i, look) in Art.looks.enumerated() {
            if look.torso >= Art.torso.count || look.legs >= Art.legs.count || look.kneel >= Art.legs.count {
                bad.append("look \(i) points past the torso or legs table")
            }
        }
        if Art.stage_tile.count != stages.count || Art.stage_palettes.count != stages.count {
            bad.append("stage tables do not match the \(stages.count) stages")
        }
        for (name, font) in [("fontSmall", Art.fontSmall), ("fontTiny", Art.fontTiny)] {
            if font.cells.count != font.chars.count { bad.append("\(name) has \(font.cells.count) cells for \(font.chars.count) characters") }
            for (i, cell) in font.cells.enumerated() where cell.utf8.count != font.w * font.h {
                bad.append("\(name) cell \(i) has \(cell.utf8.count) digits, expected \(font.w * font.h)")
            }
        }
        return bad
    }
}
