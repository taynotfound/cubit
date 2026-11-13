package com.cubit.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Renders the cube with a hand-rolled perspective projection on Canvas and runs the tap/slide rules. */
class GameView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onArrowRemoved(left: Int, total: Int)
        fun onMistake()
        fun onTargetFreed()
        /** Every counted tap, before its outcome callback; ranked runs send these to the server for replay. */
        fun onTap(arrow: Int)
        /** Two fingers tapped without moving: Clean mode's undo, since it has no buttons. */
        fun onTwoFingerTap() {}
        /** A bomb arrow left and took [count] other arrows with it, set off by [bombs] bombs (more than one: a chain). */
        fun onBomb(count: Int, bombs: Int) {}
    }

    var listener: Listener? = null
    var theme: Theme = THEMES[0]
    var tintSides = true
    var sideMarkers = true
    var arrowColor = 0
    var thickness = 5
    /** Taps are ignored once the round is over (win, out of lives, time up). */
    var inputEnabled = true
    /** Slow idle turn, used for the home-screen cube. */
    var autoSpin = false
    /** Clean mode's look: a see-through cube on the colourway's ground, flat arrows that fade with depth. */
    var glass = false
    /** Full-clear goal: the pink arrow only leaves once every other arrow is gone. */
    var pinkLast = false
    /** Accessibility: a white stripe down the pink arrow, so it doesn't rely on colour alone. */
    var markTarget = false

    private lateinit var puzzle: Puzzle
    private lateinit var occ: IntArray
    private lateinit var removed: BooleanArray
    private lateinit var tracks: Array<Track>
    /** Two-way arrows' tracks run backwards too: tail first, for leaving that way and for the second arrowhead. */
    private lateinit var revTracks: Array<Track?>
    var remaining = 0; private set
    val total get() = puzzle.paths.size

    /** [rev]: a two-way arrow leaving tail first, along its reversed track. */
    private class Anim(val arrow: Int, val escape: Boolean, val start: Long, val dist: Float, val rev: Boolean = false)
    private val anims = ArrayList<Anim>()
    private var hintArrow = -1
    /** What each tap removed, in order, for undo: the arrow, then any a bomb took with it. */
    private val history = ArrayList<IntArray>()
    /** Ghost race: another player's run replayed on a private copy of the board; arrows it has cleared draw faintly. */
    var ghostOn = false
    private lateinit var ghostOcc: IntArray
    private lateinit var ghostGone: BooleanArray
    private val ghostHist = ArrayList<IntArray>()
    var ghostLeft = 0; private set
    /** Faces each track touches (bit per face), so glass mode only builds an arrow in the passes that need it. */
    private lateinit var faceMask: IntArray
    private var scanUntil = 0L

    // Camera: rotation matrix (row-major 3×3), zoom and screen-space pan.
    private val rot = FloatArray(9)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val camDist = 6f
    /** Coasting spin in radians per second about the screen axes (tilt, turn). */
    private var spinX = 0f
    private var spinY = 0f

    /** One arrow's full route: its body, the straight run to the face edge (on Nightmare a lap of the cube first), then off into space. */
    private class Track(val x: FloatArray, val y: FloatArray, val z: FloatArray, val t: FloatArray, val segFace: IntArray, val headT: Float, val headFace: Int, val rayCells: IntArray)

    fun load(p: Puzzle) {
        puzzle = p
        val cube = p.cube
        occ = IntArray(cube.size) { -1 }
        p.paths.forEachIndexed { i, path -> path.forEach { occ[it] = i } }
        removed = BooleanArray(p.paths.size)
        remaining = p.paths.size
        anims.clear(); hintArrow = -1; scanUntil = 0L; history.clear()
        ghostOcc = occ.copyOf(); ghostGone = BooleanArray(p.paths.size); ghostHist.clear(); ghostOn = false; ghostLeft = p.paths.size
        tracks = Array(p.paths.size) { buildTrack(p.paths[it], p.dirs[it], p.exit(it)) }
        revTracks = Array(p.paths.size) { if (p.twoWay[it]) buildTrack(p.paths[it].reversedArray(), p.tailDir(it), p.tailExit(it)) else null }
        // The faces an arrow's body lies on (not its way out, which on Nightmare goes all round the cube).
        faceMask = IntArray(tracks.size) { a -> p.paths[a].fold(0) { m, c -> m or (1 shl cube.face[c]) } }
        inputEnabled = true
        resetView()
        // Intro: start half-size and turned away, then coast into the resting pose.
        rotateBy(0f, -1.2f); spinY = 6f // coasts 6/5 = 1.2 rad back to rest
        introStart = SystemClock.uptimeMillis()
    }

    /** Turns the cube so a face with arrows still on it faces you (free ones first), for finding the last few. */
    fun findNext() {
        val left = puzzle.paths.indices.filter { !removed[it] }
        val a = left.firstOrNull { it != puzzle.target && wayOf(it) != Puzzle.STUCK } ?: left.firstOrNull { it != puzzle.target } ?: left.firstOrNull() ?: return
        val f = tracks[a].headFace
        val ax = f / 2; val s = if (f % 2 == 0) 1f else -1f
        // Base rotation with the face normal as the view axis, then the usual three-quarter tilt.
        val n = FloatArray(3).also { it[ax] = s }
        val u = FloatArray(3).also { it[(ax + 1) % 3] = 1f }
        val v = floatArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
        val base = floatArrayOf(u[0], u[1], u[2], v[0], v[1], v[2], n[0], n[1], n[2])
        val ta = 0.5f; val tb = -0.65f
        val ry = floatArrayOf(cos(tb), 0f, sin(tb), 0f, 1f, 0f, -sin(tb), 0f, cos(tb))
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cos(ta), -sin(ta), 0f, sin(ta), cos(ta))
        mul(rx, ry, m); mul(m, base, rot)
        // Recentre too, or a zoomed/panned view could leave the face it just turned to off-screen.
        zoom = 1f; panX = 0f; panY = 0f
        spinX = 0f; spinY = 0f; hintArrow = a
        invalidate()
    }

    /** Skips the spin-in, for drawing a still (the share card). */
    fun settle() { introStart = 0L; resetView() }

    fun resetView() {
        val a = 0.5f; val b = -0.65f
        // rot = Rx(a) * Ry(b)
        val ry = floatArrayOf(cos(b), 0f, sin(b), 0f, 1f, 0f, -sin(b), 0f, cos(b))
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cos(a), -sin(a), 0f, sin(a), cos(a))
        mul(rx, ry, rot)
        zoom = 1f; panX = 0f; panY = 0f; spinX = 0f; spinY = 0f
        invalidate()
    }

    private fun buildTrack(path: IntArray, dir: Int, ray: IntArray): Track {
        val cube = puzzle.cube; val n = cube.n.toFloat()
        val cells = path + ray
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val zs = ArrayList<Float>(); val ts = ArrayList<Float>(); val fs = ArrayList<Int>()
        fun add(x: Int, y: Int, z: Int, t: Float, f: Int) { xs.add(x / n); ys.add(y / n); zs.add(z / n); ts.add(t); fs.add(f) }
        add(cube.px[cells[0]], cube.py[cells[0]], cube.pz[cells[0]], 0f, cube.face[cells[0]])
        for (i in 1 until cells.size) {
            val p = cells[i - 1]; val c = cells[i]; val fc = cube.face[c]
            if (cube.face[p] != fc) add(cube.px[p] + DX[fc], cube.py[p] + DY[fc], cube.pz[p] + DZ[fc], i - 0.5f, cube.face[p])
            add(cube.px[c], cube.py[c], cube.pz[c], i.toFloat(), fc)
        }
        val last = cells.last(); val lt = (cells.size - 1).toFloat(); val fl = cube.face[last]
        add(cube.px[last] + DX[dir], cube.py[last] + DY[dir], cube.pz[last] + DZ[dir], lt + 0.5f, fl)
        val fly = path.size + 8
        add(cube.px[last] + DX[dir] * (1 + 2 * fly), cube.py[last] + DY[dir] * (1 + 2 * fly), cube.pz[last] + DZ[dir] * (1 + 2 * fly), lt + 0.5f + fly, -1)
        return Track(xs.toFloatArray(), ys.toFloatArray(), zs.toFloatArray(), ts.toFloatArray(), fs.toIntArray(),
            (path.size - 1).toFloat(), cube.face[path.last()], ray)
    }

    // ---- rules ----

    /** How [a] can leave right now ([Puzzle.HEAD], [Puzzle.TAIL]) or [Puzzle.STUCK]: locks, two-way arrows, and on a
     *  full clear the pink arrow stays put until it's alone. */
    private fun wayOf(a: Int) =
        if (pinkLast && a == puzzle.target && remaining > 1) Puzzle.STUCK else puzzle.way(a, occ, removed)

    /** How far a stuck arrow nudges before it bounces back: up to whatever blocks its head (a locked one barely moves). */
    private fun bounceAt(a: Int) = tracks[a].rayCells.indexOfFirst { occ[it] >= 0 && occ[it] != a }.coerceAtLeast(0)

    private fun tapArrow(a: Int) {
        if (anims.any { it.arrow == a }) return
        if (a == hintArrow) hintArrow = -1
        listener?.onTap(a)
        val way = wayOf(a)
        val now = SystemClock.uptimeMillis()
        if (way != Puzzle.STUCK) {
            val group = take(a, occ, removed)
            remaining -= group.size; history.add(group)
            if (hintArrow in group) hintArrow = -1
            group.forEach { anims.add(Anim(it, true, now, 0f, rev = it == a && way == Puzzle.TAIL)) }
            pointAt(tracks[a], tracks[a].headT, p1)
            if (a == puzzle.target) {
                burst(p1[0], p1[1], PINK, 46, 1.6f)
                confetti()
            } else if (puzzle.bombs[a]) {
                burst(p1[0], p1[1], BOMB, 40, 1.8f); burst(p1[0], p1[1], Color.WHITE, 14, 1.2f)
                shakeStart = now - 120 // a short jolt, without the red flash of a bump
                listener?.onBomb(group.size - 1, group.count { puzzle.bombs[it] })
            } else burst(p1[0], p1[1], theme.accent, 12, 1f)
            listener?.onArrowRemoved(remaining, total)
            if (a == puzzle.target) listener?.onTargetFreed()
        } else {
            anims.add(Anim(a, false, now, bounceAt(a) + 0.12f))
            val key = puzzle.keys[a]
            if (key >= 0 && !removed[key]) hintArrow = key // a locked arrow points out its key
            shakeStart = now
            listener?.onMistake()
        }
        invalidate()
    }

    /** Highlights a free arrow, preferring one on a face the player can see. */
    fun showHint(): Boolean {
        val free = puzzle.paths.indices.filter { !removed[it] && anims.none { an -> an.arrow == it } && wayOf(it) != Puzzle.STUCK }
        if (free.isEmpty()) return false
        hintArrow = free.firstOrNull { faceVisible(tracks[it].headFace) } ?: free.first()
        invalidate()
        return true
    }

    /** Puts the most recently removed arrow back (and what a bomb took with it). Returns it, or -1 if there's nothing to undo. */
    fun undoLast(): Int {
        val group = history.removeLastOrNull() ?: return -1
        for (b in group) { puzzle.paths[b].forEach { occ[it] = b }; removed[b] = false }
        remaining += group.size
        anims.removeAll { it.arrow in group }
        invalidate()
        return group[0]
    }

    /** Removes arrow [a] from a board, with whatever it blasts if it's a bomb. Returns every arrow it removed, [a] first. */
    private fun take(a: Int, board: IntArray, gone: BooleanArray): IntArray {
        val group = puzzle.cleared(a, gone)
        for (b in group) { puzzle.paths[b].forEach { board[it] = -1 }; gone[b] = true }
        return group
    }

    /** Restores a saved level: applies its recorded taps instantly, without animation, sound or callbacks. */
    fun applyInstant(moves: List<LongArray>) {
        for (m in moves) {
            val a = m[0].toInt()
            if (a == UNDO_TAP) { undoLast(); continue }
            if (a !in tracks.indices || removed[a] || wayOf(a) == Puzzle.STUCK) continue
            val group = take(a, occ, removed)
            remaining -= group.size; history.add(group)
        }
        introStart = 0L
        invalidate()
    }

    /** Plays one of the ghost's moves on its own board. Returns true when that move freed the pink arrow. */
    fun ghostTap(a: Int): Boolean {
        if (a == UNDO_TAP) {
            val group = ghostHist.removeLastOrNull() ?: return false
            for (b in group) { puzzle.paths[b].forEach { ghostOcc[it] = b }; ghostGone[b] = false }
            ghostLeft += group.size
            invalidate(); return false
        }
        if (a !in tracks.indices || ghostGone[a]) return false
        if (pinkLast && a == puzzle.target && ghostLeft > 1) return false
        if (puzzle.way(a, ghostOcc, ghostGone) == Puzzle.STUCK) return false
        val group = take(a, ghostOcc, ghostGone); ghostHist.add(group); ghostLeft -= group.size
        invalidate()
        return a == puzzle.target
    }

    private fun ghosted(a: Int) = ghostOn && ghostGone[a]

    /** The win: a slow turntable spin while the confetti falls. */
    fun celebrate() { autoSpin = true; invalidate() }

    /** Replays drive taps directly, bypassing [inputEnabled]. */
    fun playTap(a: Int) { if (a in tracks.indices && !removed[a]) tapArrow(a) }

    /** Scan powerup: every arrow that could leave right now glows for a few seconds. */
    fun scan() { scanUntil = SystemClock.uptimeMillis() + 3000; invalidate() }

    // ---- projection ----

    private var introStart = 0L
    private val introScale: Float get() {
        val t = ((SystemClock.uptimeMillis() - introStart) / 700f).coerceIn(0f, 1f) - 1f
        return 0.5f + 0.5f * (1f + 2.70158f * t * t * t + 1.70158f * t * t) // ease-out-back
    }
    private val focal get() = 1.55f * min(width, height) * zoom * introScale
    private val tmp = FloatArray(2)

    private fun project(x: Float, y: Float, z: Float, out: FloatArray = tmp): FloatArray {
        val vx = rot[0] * x + rot[1] * y + rot[2] * z
        val vy = rot[3] * x + rot[4] * y + rot[5] * z
        val vz = rot[6] * x + rot[7] * y + rot[8] * z
        val s = focal / (camDist - vz)
        out[0] = width / 2f + panX + vx * s
        out[1] = height / 2f + panY - vy * s
        return out
    }

    /** Face f (plane at distance 1 along its normal) faces the camera when the camera sits beyond that plane. */
    private fun faceVisible(f: Int): Boolean = facing(f) > 0f
    private fun facing(f: Int): Float {
        val sign = if (f % 2 == 0) 1f else -1f
        return camDist * sign * rot[6 + f / 2] - 1f
    }

    // ---- drawing ----

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; strokeJoin = Paint.Join.ROUND }
    private val shape = Path()
    private val p1 = FloatArray(2)
    private val p2 = FloatArray(2)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // The glow fades out before the view's edges, so no box shows on the home screen.
        bgPaint.shader = RadialGradient(w / 2f, h / 2f, minOf(w, h) * 0.5f, mix(theme.bg, if (theme.isLight) Color.WHITE else theme.face, 0.14f), theme.bg, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (glass) canvas.drawColor(theme.bg) else canvas.drawPaint(bgPaint)
        if (!::puzzle.isInitialized || width == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(0, 50)) / 1000f
        lastFrame = now
        val shakeT = now - shakeStart
        canvas.save()
        if (shakeT < 320) {
            val k = 1f - shakeT / 320f
            canvas.translate(sin(shakeT * 0.11f) * dp * 9f * k, 0f)
        }
        if (!glass) drawShadow(canvas)
        stepSpin(dt)
        if (autoSpin && !dragging) {
            // Turntable: spin about the cube's own vertical axis, not the screen's.
            val a = 0.005f
            mul(rot, floatArrayOf(cos(a), 0f, sin(a), 0f, 1f, 0f, -sin(a), 0f, cos(a)), r2); r2.copyInto(rot)
        }

        if (glass) { drawGlass(canvas, now, dt, shakeT); return }
        for (f in 0 until 6) if (faceVisible(f)) drawFace(canvas, f)

        val w = 2f / puzzle.cube.n * (0.06f + thickness * 0.018f)
        baseLift = w * 0.55f
        val ink = if (arrowColor == 0) theme.ink else ARROW_COLORS[arrowColor]
        val busy = HashSet<Int>()
        anims.forEach { busy.add(it.arrow) }
        val scanning = now < scanUntil
        for (a in tracks.indices) {
            if (removed[a] || a in busy) continue
            val tr = tracks[a]
            if (a == puzzle.target) continue
            if (a == hintArrow || scanning && wayOf(a) != Puzzle.STUCK) {
                val pulse = 0.5f + 0.5f * sin(now / 140f)
                drawSnake(canvas, tr, 0f, tr.headT, w * (2.4f + pulse), withAlpha(theme.accent, 80), lift = 0f, shadow = false)
                drawSnake(canvas, tr, 0f, tr.headT, w, theme.accent)
            } else if (ghosted(a)) drawSnake(canvas, tr, 0f, tr.headT, w, withAlpha(ink, 80), shadow = false)
            else drawSnake(canvas, tr, 0f, tr.headT, w, ink)
            if (!ghosted(a)) drawMarks(canvas, a, w, ink, baseLift)
        }
        val tg = puzzle.target
        if (!removed[tg] && tg !in busy) {
            val beat = 0.5f + 0.5f * sin(now / 260f)
            drawSnake(canvas, tracks[tg], 0f, tracks[tg].headT, w * (3.2f + 1.2f * beat), withAlpha(PINK, (30 + 40 * beat).toInt()), lift = 0f, shadow = false)
            drawSnake(canvas, tracks[tg], 0f, tracks[tg].headT, w * 2.3f, theme.bg)
            drawSnake(canvas, tracks[tg], 0f, tracks[tg].headT, w * 1.45f, PINK, lift = baseLift * 1.05f, shadow = false)
            if (markTarget) drawSnake(canvas, tracks[tg], 0f, tracks[tg].headT - 0.6f, w * 0.4f, Color.WHITE, lift = baseLift * 1.1f, shadow = false)
        }

        drawAnims(canvas, now, w)
        finishFrame(canvas, now, dt, shakeT, scanning)
    }

    private fun drawAnims(canvas: Canvas, now: Long, w: Float) {
        val it = anims.iterator()
        while (it.hasNext()) {
            val an = it.next(); val tr = (if (an.rev) revTracks[an.arrow] else null) ?: tracks[an.arrow]
            val e = (now - an.start) / 1000f
            if (an.escape) {
                val off = 7f * e + 40f * e * e
                val end = tr.t.last()
                if (off > end - tr.headT - 1f) { it.remove(); continue }
                val beyond = (off + tr.headT - (tr.rayCells.size + tr.headT + 0.5f)).coerceAtLeast(0f)
                val alpha = (255 * (1f - beyond / 10f)).toInt().coerceIn(0, 255)
                val c = if (an.arrow == puzzle.target) targetColor else if (glass) (if (arrowColor == 0) theme.ink else ARROW_COLORS[arrowColor]) else theme.accent
                drawSnake(canvas, tr, off, off + tr.headT, if (an.arrow == puzzle.target && !glass) w * 1.5f else w, withAlpha(c, alpha), if (glass) 0f else baseLift, !glass)
            } else {
                val dur = 0.22f + an.dist * 0.05f
                if (e > dur) { it.remove(); continue }
                val off = an.dist * sin(Math.PI * e / dur).toFloat()
                drawSnake(canvas, tr, off, off + tr.headT, w, Color.rgb(235, 40, 60), if (glass) 0f else baseLift, !glass)
            }
        }
    }

    private fun finishFrame(canvas: Canvas, now: Long, dt: Float, shakeT: Long, scanning: Boolean) {
        canvas.restore()
        if (shakeT < 260) canvas.drawColor(withAlpha(Color.rgb(255, 40, 60), (70 * (1f - shakeT / 260f)).toInt()))
        drawParticles(canvas, dt)

        val pulsing = inputEnabled && !removed[puzzle.target]
        if (pulsing || particles.isNotEmpty() || shakeT < 320 || now - introStart < 700 || autoSpin || anims.isNotEmpty() || hintArrow >= 0 || scanning || spinX != 0f || spinY != 0f) postInvalidateOnAnimation()
    }

    private val targetColor get() = PINK

    /** Restricts [buildRibbon] to one face (glass mode draws face by face, back to front, each with its own fade). */
    private var onlyFace = -1

    private fun drawGlass(canvas: Canvas, now: Long, dt: Float, shakeT: Long) {
        val w = 2f / puzzle.cube.n * (0.06f + thickness * 0.018f) // same weight as the other modes
        val busy = HashSet<Int>(); anims.forEach { busy.add(it.arrow) }
        val ink = if (arrowColor == 0) theme.ink else ARROW_COLORS[arrowColor]
        val ground = theme.bg
        // Far faces first so near ones paint over them; facing() runs from about -(camDist+1) (away) to camDist-1 (toward us).
        val order = (0 until 6).sortedBy { facing(it) }
        for (f in order) {
            val fc = facing(f)
            val depth = if (fc > 0) 0.45f + 0.55f * (fc / (camDist - 1f)).coerceIn(0f, 1f) else 0.10f + 0.12f * (1f + fc / (camDist + 1f)).coerceIn(0f, 1f)
            drawGlassEdges(canvas, f)
            onlyFace = f
            val col = mix(ground, ink, depth)
            for (a in tracks.indices) {
                if (removed[a] || a in busy || faceMask[a] and (1 shl f) == 0) continue
                val tr = tracks[a]
                val c0 = if (a == puzzle.target) mix(ground, targetColor, (depth * 1.4f).coerceAtMost(1f)) else col
                val c = if (ghosted(a)) mix(ground, c0, 0.35f) else c0
                drawSnake(canvas, tr, 0f, tr.headT, w, c, lift = 0f, shadow = false)
                if (markTarget && a == puzzle.target && fc > 0) drawSnake(canvas, tr, 0f, tr.headT - 0.6f, w * 0.35f, ground, lift = 0f, shadow = false)
                drawMarks(canvas, a, w, c, 0f, ground, depth)
            }
        }
        onlyFace = -1
        drawAnims(canvas, now, w)
        finishFrame(canvas, now, dt, shakeT, false)
    }

    private fun drawGlassEdges(canvas: Canvas, f: Int) {
        val a = f / 2; val u = (a + 1) % 3; val v = (a + 2) % 3
        val s = if (f % 2 == 0) 1f else -1f
        val c = FloatArray(3)
        shape.reset()
        arrayOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).forEachIndexed { i, (cu, cv) ->
            c[a] = s; c[u] = cu; c[v] = cv
            project(c[0], c[1], c[2])
            if (i == 0) shape.moveTo(tmp[0], tmp[1]) else shape.lineTo(tmp[0], tmp[1])
        }
        shape.close()
        edge.strokeWidth = maxOf(1f, focal / camDist * 0.004f)
        edge.color = withAlpha(theme.text, 34)
        canvas.drawPath(shape, edge)
    }

    // ---- eye candy ----

    private val dp = ctx.resources.displayMetrics.density
    private val bgPaint = Paint()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastFrame = 0L
    private var shakeStart = 0L

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float,
                           val color: Int, val size: Float, val confetti: Boolean, var spin: Float)
    private val particles = ArrayList<Particle>()
    private val rnd = kotlin.random.Random

    private fun burst(x: Float, y: Float, color: Int, count: Int, power: Float) {
        repeat(count) {
            val ang = rnd.nextFloat() * 6.2832f
            val sp = (120f + rnd.nextFloat() * 380f) * power * dp / 2.5f
            particles.add(Particle(x, y, cos(ang) * sp, sin(ang) * sp, 0f, 0.45f + rnd.nextFloat() * 0.45f,
                if (rnd.nextFloat() < 0.3f) Color.WHITE else color, (2f + rnd.nextFloat() * 3f) * dp, false, 0f))
        }
    }

    private fun confetti() {
        val colors = intArrayOf(PINK, theme.accent, Color.WHITE, LINE_COLORS[0], LINE_COLORS[1])
        repeat(110) {
            particles.add(Particle(rnd.nextFloat() * width, -rnd.nextFloat() * height * 0.4f,
                (rnd.nextFloat() - 0.5f) * 160f * dp / 2.5f, (80f + rnd.nextFloat() * 200f) * dp / 2.5f, 0f, 2.2f + rnd.nextFloat(),
                colors[it % colors.size], (4f + rnd.nextFloat() * 4f) * dp, true, rnd.nextFloat() * 6f))
        }
    }

    private fun drawParticles(canvas: Canvas, dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            if (p.life >= p.max) { it.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt
            if (p.confetti) { p.vy += 260f * dp / 2.5f * dt; p.spin += dt * 7f }
            else { p.vx *= 0.9f; p.vy *= 0.9f }
            val fade = 1f - p.life / p.max
            fill.color = withAlpha(p.color, (255 * fade.coerceAtMost(1f)).toInt())
            if (p.confetti) {
                canvas.save(); canvas.translate(p.x, p.y); canvas.rotate(p.spin * 57f)
                canvas.drawRect(-p.size / 2, -p.size / 4 * (1.2f + sin(p.spin * 2f)), p.size / 2, p.size / 4 * (1.2f + sin(p.spin * 2f)), fill)
                canvas.restore()
            } else canvas.drawCircle(p.x, p.y, p.size * (0.4f + 0.6f * fade), fill)
        }
    }

    /** Soft contact shadow under the cube. */
    private fun drawShadow(canvas: Canvas) {
        val r = focal / camDist * 1.35f
        val cx = width / 2f + panX; val cy = height / 2f + panY + r * 1.08f
        shadowPaint.shader = RadialGradient(cx, cy, r, withAlpha(Color.rgb(20, 30, 60), 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.save(); canvas.scale(1f, 0.22f, cx, cy)
        canvas.drawCircle(cx, cy, r, shadowPaint)
        canvas.restore()
    }

    private fun drawFace(canvas: Canvas, f: Int) {
        val a = f / 2; val u = (a + 1) % 3; val v = (a + 2) % 3
        val s = if (f % 2 == 0) 1f else -1f
        val c = FloatArray(3)
        shape.reset()
        val corners = arrayOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
        corners.forEachIndexed { i, (cu, cv) ->
            c[a] = s; c[u] = cu; c[v] = cv
            project(c[0], c[1], c[2])
            if (i == 0) shape.moveTo(tmp[0], tmp[1]) else shape.lineTo(tmp[0], tmp[1])
        }
        shape.close()
        val base = if (tintSides) theme.tints[f] else theme.face
        // Fixed light from upper-left-front, so each side gets its own clear shade.
        val sg = if (f % 2 == 0) 1f else -1f
        val light = (sg * (-0.35f * rot[a] + 0.75f * rot[3 + a] + 0.56f * rot[6 + a])).coerceIn(0f, 1f)
        fill.color = mix(base, if (theme.isLight) Color.rgb(90, 105, 135) else Color.BLACK, (1f - light) * if (theme.isLight) 0.30f else 0.38f)
        canvas.drawPath(shape, fill)
        edge.strokeWidth = focal / camDist * 0.022f
        edge.color = withAlpha(Color.WHITE, (40 + 110 * light).toInt())
        canvas.drawPath(shape, edge)
        edge.strokeWidth = focal / camDist * 0.008f
        edge.color = withAlpha(theme.ink, 50)
        canvas.drawPath(shape, edge)

        if (sideMarkers) {
            fill.color = withAlpha(theme.ink, 26)
            for ((pu, pv) in PIPS[PIP_COUNT[f] - 1]) {
                shape.reset()
                for (k in 0 until 16) {
                    val ang = k * Math.PI.toFloat() / 8f
                    c[a] = s; c[u] = pu + 0.13f * cos(ang); c[v] = pv + 0.13f * sin(ang)
                    project(c[0], c[1], c[2])
                    if (k == 0) shape.moveTo(tmp[0], tmp[1]) else shape.lineTo(tmp[0], tmp[1])
                }
                shape.close()
                canvas.drawPath(shape, fill)
            }
        }
    }

    /** World-space point at track position [t]; returns the segment index it falls in. */
    private fun pointAt3(tr: Track, t: Float, out: FloatArray): Int {
        val ts = tr.t
        var i = 1
        while (i < ts.size - 1 && ts[i] < t) i++
        val k = ((t - ts[i - 1]) / (ts[i] - ts[i - 1]))
        out[0] = lerp(tr.x[i - 1], tr.x[i], k); out[1] = lerp(tr.y[i - 1], tr.y[i], k); out[2] = lerp(tr.z[i - 1], tr.z[i], k)
        return i
    }

    private fun pointAt(tr: Track, t: Float, out: FloatArray) {
        pointAt3(tr, t, w3a); project(w3a[0], w3a[1], w3a[2], out)
    }

    private fun segFace(tr: Track, i: Int) = tr.segFace[i].let { if (it < 0) tr.headFace else it }
    private fun segVisible(tr: Track, i: Int) = faceVisible(segFace(tr, i))

    private var baseLift = 0f
    private val w3a = FloatArray(3); private val w3b = FloatArray(3)
    private val poly = FloatArray(40)
    private val shadowPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Appends a world-space polygon (xyz triples) to [shape], projected, always wound the same way on screen
     *  so overlapping pieces of one arrow never cancel under the nonzero fill rule. */
    private fun addPoly(pts: FloatArray, n: Int) {
        var area = 0f
        for (k in 0 until n) {
            project(pts[k * 3], pts[k * 3 + 1], pts[k * 3 + 2], p1); poly[k * 2] = p1[0]; poly[k * 2 + 1] = p1[1]
        }
        for (k in 0 until n) { val j = (k + 1) % n; area += poly[k * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[k * 2 + 1] }
        for (m in 0 until n) {
            val k = if (area >= 0) m else n - 1 - m
            if (m == 0) shape.moveTo(poly[k * 2], poly[k * 2 + 1]) else shape.lineTo(poly[k * 2], poly[k * 2 + 1])
        }
        shape.close()
    }

    private val q = FloatArray(30)

    /** Round joint lying in the face plane spanned by (px,py,pz) and (ux,uy,uz). */
    private fun addDisc(cx: Float, cy: Float, cz: Float, px: Float, py: Float, pz: Float, ux: Float, uy: Float, uz: Float) {
        for (k in 0 until 10) {
            val a = k * 0.6283f; val c = cos(a); val sn = sin(a)
            q[k * 3] = cx + px * c + ux * sn; q[k * 3 + 1] = cy + py * c + uy * sn; q[k * 3 + 2] = cz + pz * c + uz * sn
        }
        addPoly(q, 10)
    }

    /** Builds the arrow between [t0, t1] as strips in each face's plane, lifted [lift] off the surface. */
    private fun buildRibbon(tr: Track, t0: Float, t1: Float, hw: Float, lift: Float, surfaceOnly: Boolean, withHead: Boolean) {
        val ts = tr.t
        var prevFace = -2
        for (i in 1 until ts.size) {
            if (ts[i] <= t0 || ts[i - 1] >= t1) continue
            val raw = tr.segFace[i]
            if (surfaceOnly && raw < 0) continue
            val f = segFace(tr, i)
            if (if (onlyFace >= 0) f != onlyFace else !faceVisible(f)) { prevFace = -2; continue }
            val ta = maxOf(t0, ts[i - 1]); val tb = min(t1, ts[i])
            pointAt3(tr, ta, w3a); pointAt3(tr, tb, w3b)
            val nx = DX[f].toFloat(); val ny = DY[f].toFloat(); val nz = DZ[f].toFloat()
            var ux = w3b[0] - w3a[0]; var uy = w3b[1] - w3a[1]; var uz = w3b[2] - w3a[2]
            val len = sqrt(ux * ux + uy * uy + uz * uz)
            if (len < 1e-5f) continue
            ux /= len; uy /= len; uz /= len
            // perp = n × u, scaled to half-width; lift pushes the strip off the face along its normal
            val px = (ny * uz - nz * uy) * hw; val py = (nz * ux - nx * uz) * hw; val pz = (nx * uy - ny * ux) * hw
            val lx = nx * lift; val ly = ny * lift; val lz = nz * lift
            val ax = w3a[0] + lx; val ay = w3a[1] + ly; val az = w3a[2] + lz
            val bx = w3b[0] + lx; val by = w3b[1] + ly; val bz = w3b[2] + lz
            if (prevFace >= 0 && prevFace != f && raw >= 0) {
                // Crossing a cube edge: bridge the two lifted strips so the arrow wraps over the corner.
                val ox = DX[prevFace] * lift; val oy = DY[prevFace] * lift; val oz = DZ[prevFace] * lift
                val ex = w3a[0]; val ey = w3a[1]; val ez = w3a[2]
                q[0] = ex + px + ox; q[1] = ey + py + oy; q[2] = ez + pz + oz
                q[3] = ex + px + lx; q[4] = ey + py + ly; q[5] = ez + pz + lz
                q[6] = ex - px + lx; q[7] = ey - py + ly; q[8] = ez - pz + lz
                q[9] = ex - px + ox; q[10] = ey - py + oy; q[11] = ez - pz + oz
                addPoly(q, 4)
            }
            q[0] = ax + px; q[1] = ay + py; q[2] = az + pz
            q[3] = bx + px; q[4] = by + py; q[5] = bz + pz
            q[6] = bx - px; q[7] = by - py; q[8] = bz - pz
            q[9] = ax - px; q[10] = ay - py; q[11] = az - pz
            addPoly(q, 4)
            val ex = ux * hw; val ey = uy * hw; val ez = uz * hw
            if (prevFace == -2) addDisc(ax, ay, az, px, py, pz, ex, ey, ez) // rounded tail / restart after a hidden face
            if (!(withHead && tb >= t1)) addDisc(bx, by, bz, px, py, pz, ex, ey, ez)
            prevFace = f
        }
        if (!withHead) return
        // Head: ahead of t1 the track is straight on one face (or flying straight off it), so the tip is safe. A Nightmare
        // lap crosses edges with the head too; there the tip skews for a frame as it wraps, which reads fine.
        val hi = pointAt3(tr, t1 + 0.4f, w3b)
        if (surfaceOnly && tr.segFace[hi] < 0) return
        val f = segFace(tr, hi)
        if (if (onlyFace >= 0) f != onlyFace else !faceVisible(f)) return
        pointAt3(tr, t1 - 0.05f, w3a)
        val nx = DX[f].toFloat(); val ny = DY[f].toFloat(); val nz = DZ[f].toFloat()
        var ux = w3b[0] - w3a[0]; var uy = w3b[1] - w3a[1]; var uz = w3b[2] - w3a[2]
        val len = sqrt(ux * ux + uy * uy + uz * uz); if (len < 1e-5f) return
        ux /= len; uy /= len; uz /= len
        val hh = hw * 3.3f
        val px = (ny * uz - nz * uy) * hh; val py = (nz * ux - nx * uz) * hh; val pz = (nx * uy - ny * ux) * hh
        val lx = nx * lift; val ly = ny * lift; val lz = nz * lift
        q[0] = w3b[0] + lx; q[1] = w3b[1] + ly; q[2] = w3b[2] + lz
        q[3] = w3a[0] + px + lx; q[4] = w3a[1] + py + ly; q[5] = w3a[2] + pz + lz
        q[6] = w3a[0] - px + lx; q[7] = w3a[1] - py + ly; q[8] = w3a[2] - pz + lz
        addPoly(q, 3)
    }

    /**
     * Draws an arrow (body between [t0, t1], head at t1) as a raised strip: a soft shadow on the face, then the strip
     * itself lifted off it. Both are real geometry in the face plane, so they foreshorten and shift with rotation.
     */
    /**
     * What makes a special arrow readable by shape as well as colour: a bomb's solid orange core, a locked arrow's
     * dashed core (gone once its key has left), and a second arrowhead on a two-way arrow's tail. [ground] and [depth]
     * fade them like the rest of the arrow in Clean's glass mode.
     */
    private fun drawMarks(canvas: Canvas, a: Int, w: Float, body: Int, lift: Float, ground: Int = 0, depth: Float = 1f) {
        val tr = tracks[a]; val core = lift * 1.1f
        fun col(c: Int) = if (depth < 1f) mix(ground, c, depth) else c
        if (puzzle.bombs[a]) drawSnake(canvas, tr, 0f, tr.headT - 0.6f, w * 0.45f, col(BOMB), lift = core, shadow = false)
        val key = puzzle.keys[a]
        if (key >= 0 && !removed[key]) {
            var t = 0.1f
            while (t < tr.headT - 0.5f) { drawSnake(canvas, tr, t, minOf(t + 0.28f, tr.headT - 0.5f), w * 0.42f, col(LOCK), lift = core, shadow = false, head = false); t += 0.55f }
        }
        revTracks[a]?.let { rv -> drawSnake(canvas, rv, rv.headT - 0.3f, rv.headT, w, body, lift = lift, shadow = false) }
    }

    private fun drawSnake(canvas: Canvas, tr: Track, t0: Float, t1: Float, w: Float, color: Int, lift: Float = baseLift, shadow: Boolean = true, head: Boolean = true) {
        val hw = w / 2f
        if (shadow) {
            shape.reset(); shape.fillType = Path.FillType.WINDING
            buildRibbon(tr, t0, t1, hw * 1.08f, 0f, surfaceOnly = true, withHead = head)
            shadowPaint2.color = withAlpha(Color.BLACK, ((if (theme.isLight || Color.luminance(theme.face) > 0.5f) 60 else 90) * Color.alpha(color) / 255))
            canvas.drawPath(shape, shadowPaint2)
        }
        shape.reset(); shape.fillType = Path.FillType.WINDING
        buildRibbon(tr, t0, t1, hw, lift, surfaceOnly = false, withHead = head)
        this.head.color = color
        canvas.drawPath(shape, this.head)
    }

    // ---- input ----

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var multi = false
    private var dragging = false

    private var lastMoveTime = 0L
    /** The touch landed on a spinning cube: it only stops the spin, it never taps an arrow. */
    private var caught = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(0.6f, 6f)
            val k = nz / zoom
            // Zoom about the pinch point so the spot under your fingers stays put.
            panX = d.focusX + (width / 2f + panX - d.focusX) * k - width / 2f
            panY = d.focusY + (height / 2f + panY - d.focusY) * k - height / 2f
            zoom = nz
            clampPan()
            invalidate()
            return true
        }
    }).apply {
        // Quick scale (double-tap then drag) zooms when you tap and then start turning the cube; pinch only.
        isQuickScaleEnabled = false
    }

    private fun clampPan() {
        val lim = min(width, height) * 0.6f * zoom
        panX = panX.coerceIn(-lim, lim); panY = panY.coerceIn(-lim, lim)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        val cx = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
        val cy = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = cx; lastY = cy
                caught = abs(spinX) + abs(spinY) > 0.6f
                downTime = e.eventTime; lastMoveTime = e.eventTime; moved = false; multi = false; dragging = true; spinX = 0f; spinY = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                multi = true; lastX = cx; lastY = cy; spinX = 0f; spinY = 0f
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    // Re-anchor on the fingers that stay down so the view doesn't jump.
                    val up = e.actionIndex
                    val rest = (0 until e.pointerCount).filter { it != up }
                    lastX = rest.map { e.getX(it) }.average().toFloat(); lastY = rest.map { e.getY(it) }.average().toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = cx - lastX; val dy = cy - lastY
                lastX = cx; lastY = cy
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                if (!moved && !multi) return true
                if (e.pointerCount >= 2) { panX += dx; panY += dy; clampPan() }
                else if (!multi) {
                    // One finger turns the cube. The finger left over after a pinch does nothing, so lifting
                    // one finger of a pinch doesn't jerk the cube round.
                    val k = TURN_GAIN / grabRadius
                    val ax = dy * k; val ay = dx * k
                    rotateBy(ax, ay)
                    // Track the drag speed (rad/s), smoothed over a few events, for the coast after release.
                    val ms = (e.eventTime - lastMoveTime).coerceIn(4L, 50L) / 1000f
                    spinX = lerp(spinX, ax / ms, 0.5f); spinY = lerp(spinY, ay / ms, 0.5f)
                }
                lastMoveTime = e.eventTime
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                if (!moved && !multi && e.eventTime - downTime < 450) { spinX = 0f; spinY = 0f; if (!caught) tap(e.x, e.y) }
                else if (multi && !moved && e.eventTime - downTime < 350 && inputEnabled) { spinX = 0f; spinY = 0f; listener?.onTwoFingerTap() }
                // No coast after a pinch, or when the finger paused before lifting.
                else if (multi || e.eventTime - lastMoveTime > 60 || abs(spinX) + abs(spinY) < 0.15f) { spinX = 0f; spinY = 0f }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; spinX = 0f; spinY = 0f }
        }
        return true
    }

    /** On-screen radius of the cube's near surface, in pixels: a drag this long turns the cube one radian at gain 1,
     *  which is the cube staying under the finger. Following the screen size and zoom keeps the feel the same on a
     *  small phone, a tablet, and zoomed right in (where a fixed angle per pixel threw the cube round). */
    private val grabRadius get() = 1.55f * min(width, height) * zoom / (camDist - 1f)

    private fun stepSpin(dt: Float) {
        if (spinX == 0f && spinY == 0f) return
        if (dragging) return // coast only after the finger lifts
        // Time-based so the coast feels the same at any frame or touch rate.
        // Integrates v·e^(-5t) exactly, so a spin of v always travels v/5 radians in total.
        val decay = exp(-5f * dt.coerceAtMost(1f / 30f))
        val k = (1f - decay) / 5f
        rotateBy(spinX * k, spinY * k)
        spinX *= decay; spinY *= decay
        if (abs(spinX) + abs(spinY) < 0.03f) { spinX = 0f; spinY = 0f }
    }

    private fun tap(x: Float, y: Float) {
        if (!inputEnabled || !::puzzle.isInitialized) return
        val cube = puzzle.cube; val n = cube.n.toFloat()
        var best = -1; var bestD = Float.MAX_VALUE
        for (c in 0 until cube.size) {
            if (occ[c] < 0 || !faceVisible(cube.face[c])) continue
            project(cube.px[c] / n, cube.py[c] / n, cube.pz[c] / n)
            val d = hypot(tmp[0] - x, tmp[1] - y)
            if (d < bestD) { bestD = d; best = c }
        }
        val cellPx = focal / camDist * 2f / cube.n
        if (best >= 0 && bestD < cellPx * 0.9f + 12f) tapArrow(occ[best])
    }

    private val m = FloatArray(9)
    private val r2 = FloatArray(9)
    private var rotations = 0

    /** Spins about the screen axes: ax tilts (drag up/down), ay turns (drag left/right). */
    private fun rotateBy(ax: Float, ay: Float) {
        val ry = floatArrayOf(cos(ay), 0f, sin(ay), 0f, 1f, 0f, -sin(ay), 0f, cos(ay))
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cos(ax), -sin(ax), 0f, sin(ax), cos(ax))
        mul(rx, ry, m); mul(m, rot, r2); r2.copyInto(rot)
        if (++rotations % 60 == 0) orthonormalize(rot)
    }
}

/** How far the cube's surface travels for the finger's travel. A little over 1, so a swipe across the cube turns it a
 *  bit more than a quarter turn without it running away from the finger. */
private const val TURN_GAIN = 1.4f
const val PINK = 0xFFFF3D7F.toInt()
/** Bomb arrows' core and blast. */
const val BOMB = 0xFFFF8A1F.toInt()
/** Locked arrows' dashed core. */
const val LOCK = 0xFFE8EEF8.toInt()
/** Move code for "undo the last removal" (Zen and Clean only); the server replays it too. */
const val UNDO_TAP = -6
private val PIP_COUNT = intArrayOf(3, 4, 2, 5, 1, 6) // opposite faces sum to 7, like a die
private val PIPS = arrayOf(
    listOf(0f to 0f),
    listOf(-0.5f to -0.5f, 0.5f to 0.5f),
    listOf(-0.5f to -0.5f, 0f to 0f, 0.5f to 0.5f),
    listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f),
    listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0f to 0f, -0.5f to 0.5f, 0.5f to 0.5f),
    listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0f, 0.5f to 0f, -0.5f to 0.5f, 0.5f to 0.5f),
)

private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
fun mix(a: Int, b: Int, k: Float) = Color.rgb(
    lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), k).toInt(),
    lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), k).toInt(),
    lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), k).toInt(),
)

private fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
    for (r in 0 until 3) for (c in 0 until 3) out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
}

private fun orthonormalize(m: FloatArray) {
    fun norm(o: Int) { val l = sqrt(m[o] * m[o] + m[o + 1] * m[o + 1] + m[o + 2] * m[o + 2]); m[o] /= l; m[o + 1] /= l; m[o + 2] /= l }
    norm(0)
    val d = m[0] * m[3] + m[1] * m[4] + m[2] * m[5]
    m[3] -= d * m[0]; m[4] -= d * m[1]; m[5] -= d * m[2]; norm(3)
    m[6] = m[1] * m[5] - m[2] * m[4]; m[7] = m[2] * m[3] - m[0] * m[5]; m[8] = m[0] * m[4] - m[1] * m[3]
}
