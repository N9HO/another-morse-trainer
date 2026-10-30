package app.anothermorsetrainer.morsekit

// Brass Knuckles Knock Out: the ring and the title splash, drawn as pixel art
// into an ARGB buffer. Created by Derick (KO6PBZ) for the HamCube's PocketCW
// firmware (Kristen, K5MME); GPL-3.0-or-later, used with permission.
//
// A port of the game's firmware/src/ui/ring.rs. The firmware redraws only the
// rectangles that changed; here a whole frame is composed each time, which is
// cheap at 160 x 120. The art is composed at half size and doubled to
// 320 x 240, as on the device. Pure Kotlin: no Android types, so it can be
// tested and drawn by any UI. Twin of desktop/.../morsekit/BrassKnucklesRing.kt;
// the two must stay in step.

object BkRing {
    const val ART_W = 160
    const val ART_H = 120
    const val SCREEN_W = 2 * ART_W
    const val SCREEN_H = 2 * ART_H

    /** The ring starts below the scoreboard. */
    const val STAGE_Y = 16
    /** The side panels cover the arena above this row, outside [WINDOW]. */
    const val PANEL_BOTTOM = 59
    val WINDOW = 37 to 123

    private const val FOE_X = 80
    private const val FOE_Y = 110
    private const val ME_X = 80
    private const val ME_Y = 117
    private const val MY_SHOULDER_X = 12
    private const val MY_SHOULDER_Y = -32
    private const val TORSO_Y = -63
    private const val NECK_Y = -60
    private const val SHOULDER_X = 23
    private const val SHOULDER_Y = -51
    private const val FACE_Y = -15
    private const val KNEEL_DROP = 18
    private const val DODGE_X = 32
    private val ROPE_Y = intArrayOf(46, 50, 54)
    private const val MAT_Y = 59
    private const val SHADOW_PERCENT = 45

    const val SPLASH_H = 96
    const val MOTTO_Y = 194
    const val MOTTO_H = 28
    const val PROMPT_Y = MOTTO_Y + MOTTO_H + 2
    const val MOTTO_MS = 1500
    const val PROMPT_MS = 2100

    /** Rounds an 0xRRGGBB color through RGB565, as the device's panel does. */
    fun rgb(hex: Int): Int = q(hex shr 16 and 255, hex shr 8 and 255, hex and 255)

    private fun q(r: Int, g: Int, b: Int): Int {
        val r5 = r shr 3
        val g6 = g shr 2
        val b5 = b shr 3
        return 0xFF000000.toInt() or ((r5 shl 3 or (r5 shr 2)) shl 16) or ((g6 shl 2 or (g6 shr 4)) shl 8) or
            (b5 shl 3 or (b5 shr 2))
    }

    /** The color [percent] of the way from [a] to [b]. */
    fun mix(a: Int, b: Int, percent: Int): Int {
        fun m(x: Int, y: Int) = (x * (100 - percent) + y * percent) / 100
        return q(
            m(a shr 16 and 255, b shr 16 and 255),
            m(a shr 8 and 255, b shr 8 and 255),
            m(a and 255, b and 255),
        )
    }

    private val WHITE = rgb(0xFFFFFF)

    class Stage(
        val name: String,
        val wall: Int, val wallLow: Int, val barrier: Int, val barrierEdge: Int,
        val ropes: IntArray, val ropeShade: IntArray,
        val post: Int, val postDark: Int, val pad: Int, val apron: Int, val apronEdge: Int,
        val matBack: Int, val matFront: Int,
    )

    private fun rgbs(vararg h: Int) = IntArray(h.size) { rgb(h[it]) }

    /** The stages, in the order of the stage tiles and palettes. */
    val STAGES: List<Stage> = listOf(
        Stage("THE ARENA", rgb(0x1A1426), rgb(0x2A2038), rgb(0x0E0A14), rgb(0x4A3C5A),
            rgbs(0xD83A3A, 0xECECEC, 0x3A5AD8), rgbs(0x8A1E1E, 0x9A9AA4, 0x22348A),
            rgb(0x9A9AA8), rgb(0x4A4A58), rgb(0xE8B020), rgb(0x2E3E78), rgb(0xE8E8F0), rgb(0x4C6CA2), rgb(0x86A4D2)),
        Stage("THE HAM SHACK", rgb(0x2E1C10), rgb(0x3D2616), rgb(0x1E120A), rgb(0x9A6A3A),
            rgbs(0xF0C020, 0x202020, 0xF0C020), rgbs(0x9A7810, 0x080808, 0x9A7810),
            rgb(0xB08850), rgb(0x5A3818), rgb(0xD83A3A), rgb(0x5A3818), rgb(0xE8D8B0), rgb(0x8A6A40), rgb(0xD2B482)),
        Stage("FIELD DAY", rgb(0x2E6A24), rgb(0x3E8A30), rgb(0x6A5030), rgb(0xD8B860),
            rgbs(0xF07A20, 0xFFFFFF, 0x2E6AD8), rgbs(0x904A10, 0xA0A0A8, 0x1A3E80),
            rgb(0xD0D0D8), rgb(0x6A6A74), rgb(0x3E8A30), rgb(0x2E6AD8), rgb(0xFFFFFF), rgb(0xA8ACB4), rgb(0xF0F0F0)),
        Stage("ROOFTOP DX", rgb(0x14141E), rgb(0x22222E), rgb(0x0A0A10), rgb(0x6A6A7A),
            rgbs(0xFF2EA0, 0x30F0FF, 0xA0FF30), rgbs(0x901860, 0x188890, 0x589018),
            rgb(0x6A6A7A), rgb(0x34343E), rgb(0xFF2EA0), rgb(0x101018), rgb(0x30F0FF), rgb(0x26263A), rgb(0x4A4A66)),
    )

    // --- Layers -------------------------------------------------------------------------------

    class Layer(
        val sprite: BkSprite,
        val x: Int,
        val y: Int,
        val palette: IntArray,
        val flip: Boolean = false,
        val tint: Int? = null,
        val scale: Int = 1,
    ) {
        fun flipped(f: Boolean) = Layer(sprite, x, y, palette, f, tint, scale)
        fun flashing(f: Boolean) = Layer(sprite, x, y, palette, flip, if (f) WHITE else null, scale)
        fun scaled(k: Int) = Layer(sprite, x, y, palette, flip, tint, k)

        /** Draws into [buf], [w] pixels wide, each sprite pixel as [k] art pixels. */
        fun draw(buf: IntArray, w: Int, h: Int) {
            val s = sprite
            val k = scale
            val sw = s.w * k
            val sh = s.h * k
            val ox = s.ox * k
            val oy = s.oy * k
            val left = if (flip) x + ox + 1 - sw else x - ox
            val top = y - oy
            for (py in maxOf(top, 0) until minOf(top + sh, h)) {
                val srow = (py - top) / k * s.w
                for (px in maxOf(left, 0) until minOf(left + sw, w)) {
                    val col = (if (flip) left + sw - 1 - px else px - left) / k
                    val v = Character.digit(s.pixels[srow + col], 16)
                    if (v != 0) buf[py * w + px] = tint ?: palette[v]
                }
            }
        }
    }

    // --- Block letters ------------------------------------------------------------------------

    private val BLOCK: Map<Char, IntArray> = mapOf(
        'A' to intArrayOf(14, 17, 17, 31, 17, 17, 17), 'B' to intArrayOf(30, 17, 17, 30, 17, 17, 30),
        'C' to intArrayOf(14, 17, 16, 16, 16, 17, 14), 'D' to intArrayOf(30, 17, 17, 17, 17, 17, 30),
        'E' to intArrayOf(31, 16, 16, 30, 16, 16, 31), 'F' to intArrayOf(31, 16, 16, 30, 16, 16, 16),
        'G' to intArrayOf(14, 17, 16, 23, 17, 17, 15), 'H' to intArrayOf(17, 17, 17, 31, 17, 17, 17),
        'I' to intArrayOf(14, 4, 4, 4, 4, 4, 14), 'J' to intArrayOf(7, 2, 2, 2, 2, 18, 12),
        'K' to intArrayOf(17, 18, 20, 24, 20, 18, 17), 'L' to intArrayOf(16, 16, 16, 16, 16, 16, 31),
        'M' to intArrayOf(17, 27, 21, 21, 17, 17, 17), 'N' to intArrayOf(17, 17, 25, 21, 19, 17, 17),
        'O' to intArrayOf(14, 17, 17, 17, 17, 17, 14), 'P' to intArrayOf(30, 17, 17, 30, 16, 16, 16),
        'Q' to intArrayOf(14, 17, 17, 17, 21, 18, 13), 'R' to intArrayOf(30, 17, 17, 30, 20, 18, 17),
        'S' to intArrayOf(15, 16, 16, 14, 1, 1, 30), 'T' to intArrayOf(31, 4, 4, 4, 4, 4, 4),
        'U' to intArrayOf(17, 17, 17, 17, 17, 17, 14), 'V' to intArrayOf(17, 17, 17, 17, 17, 10, 4),
        'W' to intArrayOf(17, 17, 17, 21, 21, 21, 10), 'X' to intArrayOf(17, 17, 10, 4, 10, 17, 17),
        'Y' to intArrayOf(17, 17, 10, 4, 4, 4, 4), 'Z' to intArrayOf(31, 1, 2, 4, 8, 16, 31),
        '0' to intArrayOf(14, 17, 19, 21, 25, 17, 14), '1' to intArrayOf(4, 12, 4, 4, 4, 4, 14),
        '2' to intArrayOf(14, 17, 1, 2, 4, 8, 31), '3' to intArrayOf(31, 2, 4, 2, 1, 17, 14),
        '4' to intArrayOf(2, 6, 10, 18, 31, 2, 2), '5' to intArrayOf(31, 16, 30, 1, 1, 17, 14),
        '6' to intArrayOf(6, 8, 16, 30, 17, 17, 14), '7' to intArrayOf(31, 1, 2, 4, 8, 8, 8),
        '8' to intArrayOf(14, 17, 17, 14, 17, 17, 14), '9' to intArrayOf(14, 17, 17, 15, 1, 2, 12),
        '/' to intArrayOf(1, 1, 2, 4, 8, 16, 16), '-' to intArrayOf(0, 0, 0, 31, 0, 0, 0),
        '.' to intArrayOf(0, 0, 0, 0, 0, 12, 12), '!' to intArrayOf(4, 4, 4, 4, 4, 0, 4),
        '+' to intArrayOf(0, 4, 4, 31, 4, 4, 0), '?' to intArrayOf(14, 17, 1, 2, 4, 0, 4),
        ':' to intArrayOf(0, 12, 12, 0, 12, 12, 0), '\'' to intArrayOf(4, 4, 8, 0, 0, 0, 0),
        ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
    )
    private val SOLID = intArrayOf(31, 31, 31, 31, 31, 31, 31)

    /** Big block letters across the ring: [px] art pixels per block, outlined in [edge]. */
    class Banner(val text: String, val color: Int, val y: Int, val px: Int, val edge: Int) {
        private fun width() = text.length * 6 * px - px

        fun draw(buf: IntArray, w: Int, h: Int) {
            val left = (w - width()) / 2
            fun on(x: Int, yy: Int): Boolean {
                val dx = x - left
                val dy = yy - y
                if (dx < 0 || dy < 0 || dy >= 7 * px) return false
                val cell = dx / (6 * px)
                val col = dx % (6 * px) / px
                if (col >= 5 || cell >= text.length) return false
                val g = BLOCK[text[cell].uppercaseChar()] ?: SOLID
                return g[dy / px] and (16 shr col) != 0
            }
            val top = mix(color, WHITE, 45)
            for (yy in maxOf(y - 1, 0) until minOf(y + 7 * px + 3, h)) {
                for (x in maxOf(left - 1, 0) until minOf(left + width() + 3, w)) {
                    if (on(x, yy)) {
                        buf[yy * w + x] = if (yy - y < 3 * px) top else color
                    } else if (on(x - 1, yy) || on(x + 1, yy) || on(x, yy - 1) || on(x, yy + 1) ||
                        on(x - 1, yy - 1) || on(x - 2, yy - 2)
                    ) {
                        buf[yy * w + x] = edge
                    }
                }
            }
        }
    }

    // --- The arena ----------------------------------------------------------------------------

    private fun arena(buf: IntArray, k: Int, shadowX: Int?) {
        val st = STAGES[k]
        val crowd = BrassKnucklesArt.STAGE_TILE[k]
        val pal = BrassKnucklesArt.STAGE_PALETTES[k]
        val crowdRows = crowd.h
        for (y in 0 until ART_H) {
            val row = y * ART_W
            if (y < STAGE_Y) {
                java.util.Arrays.fill(buf, row, row + ART_W, 0xFF000000.toInt())
                continue
            }
            if (y < STAGE_Y + crowdRows) {
                for (x in 0 until ART_W) {
                    val v = Character.digit(crowd.pixels[(y - STAGE_Y) * crowd.w + x % crowd.w], 16)
                    buf[row + x] = pal[v]
                }
                continue
            }
            if (y < MAT_Y) {
                val i = y - STAGE_Y - crowdRows
                val base = when (i) {
                    0 -> st.barrierEdge
                    1, 2 -> st.barrier
                    else -> mix(st.wall, st.wallLow, (i * 100 / 14).coerceIn(0, 100))
                }
                java.util.Arrays.fill(buf, row, row + ART_W, base)
                if (y >= MAT_Y - 3) {
                    java.util.Arrays.fill(buf, row, row + ART_W, if (y == MAT_Y - 3) st.apronEdge else st.apron)
                }
                for ((n, ry) in ROPE_Y.withIndex()) {
                    if (y == ry || y == ry + 1) {
                        val c = if (y == ry) st.ropes[n] else st.ropeShade[n]
                        for (x in 6 until 154) buf[row + x] = c
                    }
                }
                for (post in intArrayOf(4, 152)) {
                    for (x in post until post + 4) {
                        val pad = ROPE_Y.any { y in (it - 1)..(it + 2) }
                        buf[row + x] = if (pad) st.pad else if (x == post || x == post + 3) st.postDark else st.post
                    }
                }
                continue
            }
            val mat = mix(st.matBack, st.matFront, (y - MAT_Y) * 100 / (ART_H - MAT_Y))
            val dark = mix(mat, rgb(0x101828), SHADOW_PERCENT)
            for (x in 0 until ART_W) {
                var c = mat
                if (shadowX != null) {
                    val dx = x - shadowX
                    val dy = y - FOE_Y
                    if (dx * dx * 16 + dy * dy * 23 * 23 <= 23 * 23 * 16) c = dark
                }
                buf[row + x] = c
            }
        }
    }

    // --- The fight ----------------------------------------------------------------------------

    private fun elapsed(now: Int, since: Int): Int = now - since

    /**
     * The ring for one frame, 160 x 120 ARGB: [fighter] is the opponent's ladder index, [foe] and [me]
     * are the poses and when they began ([BoxingFight.foePose], [BoxingFight.mePose]), [hurting] is
     * low health, and [now] the tick count in ms.
     */
    fun ring(
        fighter: Int,
        foe: Pair<BoxingFoe, Int>,
        me: Pair<BoxingMe, Int>,
        hurting: Boolean,
        now: Int,
        stage: Int,
        banner: Banner? = null,
    ): IntArray {
        val layers = ArrayList<Layer>()
        val shadow = foeLayers(layers, fighter, foe, hurting, now)
        meLayers(layers, me, now)
        val buf = IntArray(ART_W * ART_H)
        arena(buf, stage, shadow)
        for (l in layers) l.draw(buf, ART_W, ART_H)
        banner?.draw(buf, ART_W, ART_H)
        return buf
    }

    private fun toward(s: BoxingSide, n: Int) = if (s == BoxingSide.LEFT) -n else n
    private fun sideIx(s: BoxingSide) = if (s == BoxingSide.LEFT) 0 else 1

    /** Adds the opponent's layers, back to front; returns the shadow's middle when he stands. */
    private fun foeLayers(out: MutableList<Layer>, fighter: Int, foe: Pair<BoxingFoe, Int>, hurting: Boolean, now: Int): Int? {
        val art = BrassKnucklesArt
        val look = art.LOOKS[fighter]
        val pal = art.FIGHTER_PALETTES[fighter]
        val (pose, since) = foe
        val t = elapsed(now, since)
        val x = FOE_X
        val y = FOE_Y
        if (pose == BoxingFoe.Out) {
            out += Layer(art.EXTRA[1], x, y - 2, pal)
            dizzy(out, x, y - 34, now)
            return null
        }
        val rise = pose == BoxingFoe.Rise && t < 360
        val down = pose == BoxingFoe.Down || rise
        val bob = if ((now / 260) % 4 < 2) 0 else 1
        var body = 0 to bob
        var headd = 0 to bob
        val arms = arrayOf(0 to (0 to bob), 0 to (0 to 1 - bob))
        var face = if (hurting) 3 else 0
        var front = 1
        var flash = false
        val extra = ArrayList<Layer>()
        when (pose) {
            BoxingFoe.Idle, BoxingFoe.Out, BoxingFoe.Down -> {}
            BoxingFoe.Rise -> {
                face = 0
                body = body.first to 0
                headd = headd.first to 0
            }
            is BoxingFoe.Windup -> {
                val s = pose.side
                val shake = if (t > 300 && (t / 90) % 2 == 0) 1 else 0
                body = toward(s, -2) + shake to 0
                headd = toward(s, -3) + shake to -1
                arms[sideIx(s)] = 1 to (toward(s, -2) to -1)
                arms[1 - sideIx(s)] = arms[1 - sideIx(s)].first to (toward(s, -2) to 0)
                front = sideIx(s)
                face = 1
            }
            is BoxingFoe.Punch -> {
                val s = pose.side
                body = toward(s, -2) to 2
                headd = toward(s, -2) to 3
                arms[sideIx(s)] = 2 to (toward(s, -2) to 2)
                arms[1 - sideIx(s)] = arms[1 - sideIx(s)].first to (toward(s, -2) to 2)
                front = sideIx(s)
                face = 1
            }
            is BoxingFoe.Whiff -> {
                val s = pose.side
                val k = minOf(t / 40, 4)
                body = toward(s, -k - 2) to 2
                headd = toward(s, -2 * k - 2) to 3
                arms[sideIx(s)] = 3 to (toward(s, -k - 2) to 2)
                arms[1 - sideIx(s)] = 4 to (toward(s, -k) to 2)
                front = sideIx(s)
                face = 2
            }
            BoxingFoe.Open -> {
                val sway = intArrayOf(0, 1, 2, 1, 0, -1, -2, -1)[(now / 140 % 8)]
                body = sway / 2 to 2
                headd = sway to 3
                arms[0] = 4 to (sway / 2 to 2)
                arms[1] = 4 to (sway / 2 to 2)
                face = 3
                sweat(extra, x + headd.first, y + NECK_Y + headd.second + FACE_Y, now)
            }
            is BoxingFoe.Hurt -> {
                val s = pose.side
                val snap = if (t < 120) 5 else if (t < 240) 3 else 1
                body = toward(s, -1) to 1
                headd = toward(s, -snap) to 2
                arms[0] = 4 to (toward(s, -1) to 1)
                arms[1] = 4 to (toward(s, -1) to 1)
                face = 2
                flash = t < 70
                if (t < 160) {
                    val hx = x + headd.first + toward(s, 9)
                    extra += Layer(art.FX[2], hx, y + NECK_Y + FACE_Y + 2, art.FX_PALETTE)
                }
            }
            BoxingFoe.Block -> {
                body = 0 to 1
                headd = 0 to 3
                arms[0] = 5 to (0 to 1)
                arms[1] = 5 to (0 to 1)
                face = 1
            }
            BoxingFoe.Stunned -> {
                val sway = intArrayOf(0, 1, 1, 0, -1, -1)[(now / 160 % 6)]
                body = 0 to 2
                headd = sway to 3
                arms[0] = 4 to (0 to 2)
                arms[1] = 4 to (0 to 2)
                face = 3
            }
            BoxingFoe.Victory -> {
                val hop = if ((t / 180) % 2 == 0) 0 else -2
                body = 0 to hop
                headd = 0 to hop
                arms[0] = 6 to (0 to hop)
                arms[1] = 6 to (0 to hop)
                face = 4
            }
        }
        if (down) {
            body = 0 to KNEEL_DROP
            headd = 3 to KNEEL_DROP + 3
            arms[0] = 4 to (0 to KNEEL_DROP)
            arms[1] = 4 to (1 to KNEEL_DROP)
            face = 3
        }
        val legs = if (down) look.kneel else look.legs
        out += Layer(art.LEGS[legs], x, y, pal)
        val bx = x + body.first
        val by = y + TORSO_Y + body.second
        out += Layer(art.TORSO[look.torso], bx, by, pal)
        if (look.emblem) out += Layer(art.EXTRA[0], bx, by + 17, pal)
        val hx = x + headd.first
        val hy = y + NECK_Y + headd.second
        out += Layer(art.HEAD[fighter], hx, hy, pal).flashing(flash)
        out += Layer(art.FACE[face], hx, hy, pal)
        out += Layer(art.GEAR[fighter], hx, hy, pal)
        // The arm on the far side is drawn first, the one in front after.
        val armLayers = Array(2) { k ->
            val (a, off) = arms[k]
            val sx = if (k == 0) -SHOULDER_X else SHOULDER_X - 1
            Layer(art.ARM[a], x + sx + off.first, y + SHOULDER_Y + off.second, pal).flipped(k == 1)
        }
        out += armLayers[1 - front]
        out += armLayers[front]
        out += extra
        if (down || pose == BoxingFoe.Stunned) dizzy(out, hx, hy + FACE_Y - 18, now)
        return x
    }

    private val ORBIT = arrayOf(
        12 to 0, 10 to 2, 6 to 3, 0 to 4, -6 to 3, -10 to 2, -12 to 0, -10 to -2, -6 to -3, 0 to -4, 6 to -3, 10 to -2,
    )

    private fun dizzy(out: MutableList<Layer>, x: Int, y: Int, now: Int) {
        for (k in 0 until 3) {
            val (dx, dy) = ORBIT[((now / 90) + k * 4) % ORBIT.size]
            out += Layer(BrassKnucklesArt.FX[0], x + dx, y + dy, BrassKnucklesArt.FX_PALETTE)
        }
    }

    private fun sweat(out: MutableList<Layer>, x: Int, y: Int, now: Int) {
        val d = (now % 600) / 60
        out += Layer(BrassKnucklesArt.FX[3], x - 12 - d, y - 6 + d / 2, BrassKnucklesArt.SWEAT_PALETTE)
        out += Layer(BrassKnucklesArt.FX[3], x + 12 + d, y - 8 + d / 3, BrassKnucklesArt.SWEAT_PALETTE)
    }

    private fun meLayers(out: MutableList<Layer>, me: Pair<BoxingMe, Int>, now: Int) {
        val art = BrassKnucklesArt
        val guard = 0; val jab = 1; val hook = 2; val upper = 3; val raise = 4; val low = 5; val tuck = 6
        val (pose, since) = me
        val t = elapsed(now, since)
        val bob = if ((now / 300) % 2 == 0) 0 else 1
        var x = ME_X
        var y = ME_Y
        val arms = arrayOf(guard to (0 to bob), guard to (0 to bob))
        var fx: Layer? = null
        fun ease(ms: Int, n: Int) = n * minOf(t, ms) / ms
        fun armIx(s: BoxingSide) = sideIx(s)
        when (pose) {
            BoxingMe.Idle -> {}
            is BoxingMe.Dodge -> {
                x += toward(pose.side, ease(90, DODGE_X))
                y += 2
            }
            is BoxingMe.Jab -> if (t < 170) {
                val s = pose.side
                arms[armIx(s)] = jab to (0 to 0)
                arms[1 - armIx(s)] = tuck to (0 to 0)
                y -= 1
            }
            is BoxingMe.Blocked -> {
                val s = pose.side
                arms[armIx(s)] = jab to (0 to (if (t < 120) 0 else 3))
                arms[1 - armIx(s)] = tuck to (0 to 0)
                y += if (t < 120) 0 else 2
            }
            is BoxingMe.Hook -> if (t < 240) {
                val s = pose.side
                arms[armIx(s)] = hook to ((if (s == BoxingSide.LEFT) -4 else 4) to -2)
                arms[1 - armIx(s)] = tuck to (0 to 0)
                x += toward(s, -2)
            }
            is BoxingMe.Combo -> {
                val k = t / 150
                if (k < pose.punches) {
                    val s = if (k % 2 == 0) BoxingSide.LEFT else BoxingSide.RIGHT
                    val reach = if (k == 0) 0 else if (s == BoxingSide.LEFT) -4 else 4
                    arms[armIx(s)] = (if (k == 0) jab else hook) to (reach to (if (k == 0) 0 else -2))
                    arms[1 - armIx(s)] = tuck to (0 to 0)
                    x += toward(s, if (k == 0) 1 else -2)
                }
            }
            BoxingMe.Uppercut -> {
                if (t < 160) {
                    y += 3
                    arms[0] = guard to (0 to 3)
                    arms[1] = guard to (0 to 4)
                } else if (t < 560) {
                    y -= 2
                    arms[0] = tuck to (0 to 0)
                    arms[1] = upper to (0 to 0)
                    if (t in 200 until 380) fx = Layer(art.FX[2], FOE_X + 4, FOE_Y + NECK_Y - 4, art.FX_PALETTE)
                }
            }
            BoxingMe.Miss -> {
                arms[0] = tuck to (0 to 0)
                arms[1] = hook to (3 to 0)
                x += 4
            }
            BoxingMe.Hurt -> {
                y += 3
                if (t < 240) x += if ((t / 60) % 2 == 0) -2 else 2
                arms[0] = low to (0 to 0)
                arms[1] = low to (0 to 0)
                if (t < 150) fx = Layer(art.FX[2], x, y - 46, art.FX_PALETTE)
            }
            BoxingMe.Down -> {
                y += ease(300, 30)
                arms[0] = low to (0 to 0)
                arms[1] = low to (0 to 0)
            }
            BoxingMe.Rise -> {
                y += 30 - ease(600, 30)
                if (t < 400) {
                    arms[0] = low to (0 to 0)
                    arms[1] = low to (0 to 0)
                }
            }
            BoxingMe.Victory -> {
                y -= if ((t / 200) % 2 == 0) 0 else 2
                arms[0] = raise to (0 to 0)
                arms[1] = raise to (0 to 0)
            }
        }
        // Seen from behind, your arms are in front of you: your head, back and legs cover them.
        val pal = art.PLAYER_PALETTE
        val armLayers = Array(2) { k ->
            val (a, off) = arms[k]
            val sx = if (k == 0) -MY_SHOULDER_X else MY_SHOULDER_X - 1
            Layer(art.HERO_ARM[a], x + sx + off.first, y + MY_SHOULDER_Y + off.second, pal).flipped(k == 1)
        }
        out += armLayers[0]
        out += armLayers[1]
        out += Layer(art.HERO_BODY[0], x, y, pal)
        fx?.let { out += it }
    }

    // --- The title splash ---------------------------------------------------------------------

    private val YELLOW = rgb(0xFFE030)
    private val RED = rgb(0xD81818)
    private val GREEN = rgb(0x30FF50)
    val BAND = rgb(0x0A0306)
    private const val STARS = 12
    private val LINES = listOf(Triple("BRASS", 650, 43), Triple("KNUCKLE", 900, 60), Triple("GANG", 1150, 77))

    private fun hash(n: Int): Int {
        var x = (n * 0x9E3779B1.toInt()) xor 0x5BD1E995
        x = x xor (x ushr 15)
        x *= 0x85EBCA77.toInt()
        x = x xor (x ushr 13)
        x *= 0xC2B2AE3D.toInt()
        return x xor (x ushr 16)
    }

    private fun umod(a: Int, n: Int): Int = ((a.toLong() and 0xFFFFFFFFL) % n).toInt()

    /** True while a title line has just landed: the moment to throw a big burst. */
    fun slam(t: Int): Boolean = LINES.any { t >= it.second && t - it.second < 90 }

    /**
     * The title splash [t] ms in, 320 x 240 ARGB: the logo, BRASS KNUCKLE GANG punched in a line at a time,
     * impact stars going off all over, and the motto band. [seed] scatters the stars differently each time.
     */
    fun splash(t: Int, seed: Int): IntArray {
        val art = BrassKnucklesArt
        val buf = IntArray(ART_W * SPLASH_H)
        for (y in 0 until SPLASH_H) {
            val base = mix(rgb(0x3A0A0C), rgb(0x0E0306), y * 100 / SPLASH_H)
            val light = mix(base, rgb(0x6A1A14), 30)
            for (x in 0 until ART_W) buf[y * ART_W + x] = if (Math.floorMod(x + y, 16) < 8) light else base
        }
        val cycle = 700
        val stars = ArrayList<Layer>()
        for (k in 0 until STARS) {
            val offset = k * cycle / STARS
            val since = t - offset / 3
            if (since < 0) continue
            val round = since / cycle
            val at = since % cycle
            val h = hash(seed xor (round shl 8) xor k)
            val start = umod(h, 200)
            if (at < start || at >= start + 420) continue
            val age = at - start
            val scale = intArrayOf(1, 1, 1, 2, 2, 3)[umod(h ushr 8, 6)]
            val x = umod(h ushr 12, 160)
            val y = 4 + umod(h ushr 20, SPLASH_H - 12)
            val (sprite, sc) = when {
                age < 90 -> art.FX[2] to scale
                age < 300 -> art.FX[1] to scale
                else -> art.FX[0] to maxOf(scale, 2) - 1
            }
            stars += Layer(sprite, x, y, art.FX_PALETTE).scaled(sc)
        }
        for (l in stars) l.draw(buf, ART_W, SPLASH_H)
        val logoY = when {
            t < 400 -> -24 + 48 * t / 400
            t < 500 -> 24 - (t - 400) / 20
            t < 600 -> 19 + (t - 500) / 20
            else -> 22
        }
        Layer(art.LOGO[0], 80, logoY, art.LOGO_PALETTE).draw(buf, ART_W, SPLASH_H)
        for ((text, at, y0) in LINES) {
            if (t >= at) {
                val px = if (t - at < 90) 3 else 2
                Banner(text, YELLOW, if (px == 3) y0 - 4 else y0, px, RED).draw(buf, ART_W, SPLASH_H)
            }
        }
        val screen = IntArray(SCREEN_W * SCREEN_H) { BAND }
        for (y in 0 until 2 * SPLASH_H) for (x in 0 until SCREEN_W) screen[y * SCREEN_W + x] = buf[y / 2 * ART_W + x / 2]
        if (t >= MOTTO_MS) motto(screen)
        return screen
    }

    /** The motto, `彙★ 2-Meter CW or Bust!!! ★彙`, at full resolution under the splash. */
    private fun motto(screen: IntArray) {
        val art = BrassKnucklesArt
        val text = " 2-Meter CW or Bust!!! "
        val font = art.FONT_SMALL
        val hui = art.GLYPH[0]
        val star = art.GLYPH[1]
        val textW = text.length * font.w
        val width = 2 * (hui.w + 2 + star.w) + textW
        val left = (SCREEN_W - width) / 2
        val pieces = listOf(
            Triple(left, 2, hui),
            Triple(left + hui.w + 2, 7, star),
            Triple(left + width - hui.w - 2 - star.w, 7, star),
            Triple(left + width - hui.w, 2, hui),
        )
        val textX = left + hui.w + 2 + star.w
        val textY = (MOTTO_H - font.h) / 2
        fun on(s: BkSprite, x: Int, y: Int) =
            x in 0 until s.w && y in 0 until s.h && s.pixels[y * s.w + x] != '0'
        fun ink(x: Int, y: Int): Int {
            val dx = x - textX
            val dy = y - textY
            if (dx < 0 || dy < 0 || dx >= textW || dy >= font.h) return 0
            return fontCoverage(font, text[dx / font.w], dx % font.w, dy)
        }
        val dim = rgb(0x0A5A18)
        for (y in 0 until MOTTO_H) {
            for (x in 0 until SCREEN_W) {
                var p = if (y == 0 || y == MOTTO_H - 1) RED else BAND
                for ((px, py, s) in pieces) {
                    if (on(s, x - px, y - py)) p = GREEN else if (on(s, x - px - 1, y - py - 1)) p = dim
                }
                val shadow = ink(x - 1, y - 1)
                if (shadow > 0) p = mix(p, RED, shadow * 100 / 15)
                val c = ink(x, y)
                if (c > 0) p = mix(p, YELLOW, c * 100 / 15)
                screen[(MOTTO_Y + y) * SCREEN_W + x] = p
            }
        }
    }

    // --- Bitmap text --------------------------------------------------------------------------

    /** The 0..15 coverage of [c] at ([x], [y]) in its cell; a lowercase letter missing from the font uses its capital. */
    fun fontCoverage(font: BkFont, c: Char, x: Int, y: Int): Int {
        var i = font.chars.indexOf(c)
        if (i < 0) i = font.chars.indexOf(c.uppercaseChar())
        if (i < 0) return 15
        if (x !in 0 until font.w || y !in 0 until font.h) return 0
        return Character.digit(font.cells[i][y * font.w + x], 16)
    }

    /** Draws [text] into a [w]-wide ARGB buffer with its top left at ([x0], [y0]), blending from what is there to [fg]. */
    fun drawText(buf: IntArray, w: Int, font: BkFont, x0: Int, y0: Int, text: String, fg: Int) {
        val h = buf.size / w
        for ((n, c) in text.withIndex()) {
            for (y in 0 until font.h) for (x in 0 until font.w) {
                val px = x0 + n * font.w + x
                val py = y0 + y
                if (px !in 0 until w || py !in 0 until h) continue
                val cov = fontCoverage(font, c, x, y)
                if (cov > 0) buf[py * w + px] = mix(buf[py * w + px], fg, cov * 100 / 15)
            }
        }
    }

    /** Doubles an art-sized frame to screen size. */
    fun doubled(art: IntArray): IntArray {
        val out = IntArray(SCREEN_W * SCREEN_H)
        for (y in 0 until SCREEN_H) for (x in 0 until SCREEN_W) out[y * SCREEN_W + x] = art[y / 2 * ART_W + x / 2]
        return out
    }
}
