package com.cubit.game

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.max
import kotlin.math.min

object Fonts {
    lateinit var display: Typeface   // Barlow Condensed Bold: signage lettering
    lateinit var label: Typeface     // Barlow Condensed SemiBold: labels, buttons
    lateinit var body: Typeface      // Barlow Regular
    lateinit var bodyMedium: Typeface
    fun load(ctx: Context) {
        if (::display.isInitialized) return
        val a = ctx.assets
        display = Typeface.createFromAsset(a, "fonts/BarlowCondensed-Bold.ttf")
        label = Typeface.createFromAsset(a, "fonts/BarlowCondensed-SemiBold.ttf")
        body = Typeface.createFromAsset(a, "fonts/Barlow-Regular.ttf")
        bodyMedium = Typeface.createFromAsset(a, "fonts/Barlow-Medium.ttf")
    }
}

/** Vibration through the Vibrator service, so effects keep their strength regardless of the system "touch feedback" switch. */
object Haptics {
    var enabled = true
    private var vib: android.os.Vibrator? = null

    fun init(ctx: Context) {
        vib = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
    }

    private fun effect(predefined: Int, ms: Long) {
        val v = vib ?: return
        if (!enabled) return
        when {
            Build.VERSION.SDK_INT >= 29 -> v.vibrate(android.os.VibrationEffect.createPredefined(predefined))
            Build.VERSION.SDK_INT >= 26 -> v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else -> @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    private fun pattern(timings: LongArray, amps: IntArray) {
        val v = vib ?: return
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(timings, amps, -1))
        else @Suppress("DEPRECATION") v.vibrate(timings, -1)
    }

    /** Buttons, toggles. */
    fun tick() = effect(android.os.VibrationEffect.EFFECT_TICK, 8)
    /** An arrow slides off. */
    fun click() = effect(android.os.VibrationEffect.EFFECT_CLICK, 14)
    /** A powerup fires. */
    fun double() = effect(android.os.VibrationEffect.EFFECT_DOUBLE_CLICK, 24)
    /** An arrow hits a blocker. */
    fun bump() = effect(android.os.VibrationEffect.EFFECT_HEAVY_CLICK, 40)
    fun win() = pattern(longArrayOf(0, 28, 70, 28, 70, 90), intArrayOf(0, 120, 0, 170, 0, 255))
    fun lose() = pattern(longArrayOf(0, 160, 90, 260), intArrayOf(0, 200, 0, 140))
}

/** System "Remove animations" switches animator scale to 0; honour it everywhere we animate by hand. */
fun motionEnabled() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

/** One stroke icon family on a 24-unit grid, 2-unit round strokes. */
enum class Glyph { CUBE, ROAM, RANKS, SETTINGS, CLOSE, HINT, RECENTER, ARROW, DICE, REPLAY, SHIELD, FREEZE, LIFE, SCAN, UNDO, FLAME, BADGE, SHARE, BOLT, MORE }

class IconView(ctx: Context, var glyph: Glyph, color: Int, private val sizeDp: Int = 24) : View(ctx) {
    var color = color; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) {
        val s = (sizeDp * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 24f
        c.save(); c.translate((width - 24 * s) / 2, (height - 24 * s) / 2); c.scale(s, s)
        p.color = color; p.strokeWidth = 2f; p.style = Paint.Style.STROKE
        path.reset()
        fun poly(vararg xy: Float, close: Boolean = false) {
            path.moveTo(xy[0], xy[1]); var i = 2
            while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
            if (close) path.close()
        }
        when (glyph) {
            Glyph.CUBE -> { poly(12f, 3f, 20f, 7.5f, 20f, 16.5f, 12f, 21f, 4f, 16.5f, 4f, 7.5f, close = true); poly(4f, 7.5f, 12f, 12f, 20f, 7.5f); poly(12f, 12f, 12f, 21f) }
            Glyph.ROAM -> { path.addCircle(12f, 12f, 9f, Path.Direction.CW); poly(12f, 6.5f, 14.5f, 12f, 12f, 17.5f, 9.5f, 12f, close = true) }
            Glyph.RANKS -> { poly(3f, 21f, 3f, 14f, 9f, 14f); poly(9f, 21f, 9f, 8f, 15f, 8f, 15f, 21f); poly(15f, 12f, 21f, 12f, 21f, 21f); poly(2f, 21f, 22f, 21f) }
            Glyph.SETTINGS -> { poly(4f, 7f, 20f, 7f); poly(4f, 17f, 20f, 17f); path.addCircle(9f, 7f, 2.6f, Path.Direction.CW); path.addCircle(15f, 17f, 2.6f, Path.Direction.CW) }
            Glyph.CLOSE -> { poly(6f, 6f, 18f, 18f); poly(18f, 6f, 6f, 18f) }
            Glyph.HINT -> { path.addArc(RectF(6f, 3f, 18f, 15f), 140f, 260f); poly(9.2f, 13.6f, 9.6f, 17f, 14.4f, 17f, 14.8f, 13.6f); poly(10f, 20.5f, 14f, 20.5f) }
            Glyph.RECENTER -> { path.addCircle(12f, 12f, 6.5f, Path.Direction.CW); poly(12f, 2f, 12f, 5f); poly(12f, 19f, 12f, 22f); poly(2f, 12f, 5f, 12f); poly(19f, 12f, 22f, 12f) }
            Glyph.ARROW -> { poly(4f, 12f, 19f, 12f); poly(13f, 6f, 19f, 12f, 13f, 18f) }
            Glyph.DICE -> { path.addRoundRect(RectF(4f, 4f, 20f, 20f), 3f, 3f, Path.Direction.CW) }
            Glyph.REPLAY -> { path.addArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), -60f, 300f); poly(16.5f, 2.5f, 17.3f, 6.2f, 13.6f, 7f) }
            Glyph.SHIELD -> { path.moveTo(12f, 3f); path.lineTo(19f, 6f); path.lineTo(19f, 12f); path.cubicTo(19f, 16.5f, 16f, 19.5f, 12f, 21f); path.cubicTo(8f, 19.5f, 5f, 16.5f, 5f, 12f); path.lineTo(5f, 6f); path.close() }
            Glyph.FREEZE -> { path.addCircle(12f, 13.5f, 7.5f, Path.Direction.CW); poly(12f, 13.5f, 12f, 9.5f); poly(10f, 3f, 14f, 3f); poly(12f, 3f, 12f, 6f) }
            Glyph.LIFE -> { path.addCircle(12f, 12f, 8.5f, Path.Direction.CW); poly(12f, 8f, 12f, 16f); poly(8f, 12f, 16f, 12f) }
            Glyph.UNDO -> { poly(9f, 5f, 4f, 10f, 9f, 15f); path.moveTo(4f, 10f); path.lineTo(14f, 10f); path.cubicTo(18f, 10f, 20f, 12.5f, 20f, 15f); path.cubicTo(20f, 17.5f, 18f, 20f, 14f, 20f); path.lineTo(11f, 20f) }
            Glyph.FLAME -> { path.moveTo(12f, 3f); path.cubicTo(14f, 7f, 18f, 9f, 18f, 14f); path.cubicTo(18f, 18f, 15f, 21f, 12f, 21f); path.cubicTo(9f, 21f, 6f, 18f, 6f, 14f); path.cubicTo(6f, 11f, 8f, 9f, 9f, 7f); path.cubicTo(10f, 9f, 11f, 10f, 12f, 10f); path.cubicTo(12f, 8f, 11f, 5f, 12f, 3f); path.close() }
            Glyph.BADGE -> { path.addCircle(12f, 9f, 6f, Path.Direction.CW); poly(8.5f, 13.5f, 7f, 21f, 12f, 18.5f, 17f, 21f, 15.5f, 13.5f) }
            Glyph.SHARE -> { poly(12f, 15f, 12f, 3f); poly(7.5f, 7.5f, 12f, 3f, 16.5f, 7.5f); poly(5f, 12f, 5f, 20f, 19f, 20f, 19f, 12f) }
            Glyph.BOLT -> poly(13f, 2.5f, 5f, 13.5f, 11.5f, 13.5f, 10.5f, 21.5f, 19f, 10f, 12.5f, 10f, close = true)
            Glyph.MORE -> { poly(4f, 7f, 20f, 7f); poly(4f, 12f, 20f, 12f); poly(4f, 17f, 14f, 17f) }
            Glyph.SCAN -> { path.moveTo(2.5f, 12f); path.quadTo(12f, 3f, 21.5f, 12f); path.quadTo(12f, 21f, 2.5f, 12f); path.close(); path.addCircle(12f, 12f, 3f, Path.Direction.CW) }
        }
        c.drawPath(path, p)
        if (glyph == Glyph.DICE) {
            p.style = Paint.Style.FILL
            for ((x, y) in listOf(8.5f to 8.5f, 15.5f to 15.5f, 12f to 12f, 15.5f to 8.5f, 8.5f to 15.5f)) c.drawCircle(x, y, 1.4f, p)
        }
        c.restore()
    }
}

/**
 * Split-flap display, the Cubit signature: each cell clatters through characters to its target like a
 * station departure board.
 */
class FlapView(ctx: Context, private val cells: Int, textSp: Float, var tileColor: Int, var inkColor: Int, private val alignRight: Boolean = false) : View(ctx) {
    /** Most flaps a cell runs through to reach its new character; 1 = a clean single flip (clocks). */
    var maxFlips = 4
    var haptics = true
    var sound = true
    var soundVolume = 0.11f
    private val d = resources.displayMetrics
    private val textPx = textSp * d.scaledDensity
    private val cellW = textPx * 0.64f
    private val cellH = textPx * 1.22f
    private val gap = 2.5f * d.density
    private val cur = CharArray(cells) { ' ' }
    private val target = CharArray(cells) { ' ' }
    private val flipsLeft = IntArray(cells)
    private val flipStart = LongArray(cells)
    private var lastTick = 0L
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.display; textSize = textPx; textAlign = Paint.Align.CENTER }
    private val shade = Paint()
    private val r = RectF()

    var text: String = ""
        set(v) { field = v; setTextInternal(v, motionEnabled()) }

    fun setInstant(v: String) { text = v; setTextInternal(v, false) }

    private fun setTextInternal(v: String, animate: Boolean) {
        val s = v.uppercase().let { if (it.length > cells) it.take(cells) else if (alignRight) it.padStart(cells) else it.padEnd(cells) }
        val now = SystemClock.uptimeMillis()
        for (i in 0 until cells) {
            target[i] = s[i]
            if (!animate) { cur[i] = s[i]; flipsLeft[i] = 0; continue }
            if (cur[i] == s[i] && flipsLeft[i] == 0) continue
            // The run passes through the characters just before the target, so the last flap always lands on it.
            val ti = idx(s[i]); val dist = ((ti - idx(cur[i])) % ABC.length + ABC.length) % ABC.length
            flipsLeft[i] = min(max(dist, 1), maxFlips)
            flipStart[i] = now + i * 70L
        }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension((cells * cellW + (cells - 1) * gap).toInt() + 1, cellH.toInt() + 1)

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        var busy = false
        tile.color = tileColor; ink.color = inkColor
        for (i in 0 until cells) {
            val x = i * (cellW + gap)
            r.set(x, 0f, x + cellW, cellH)
            c.drawRoundRect(r, 3 * d.density, 3 * d.density, tile)
            val mid = cellH / 2f
            if (flipsLeft[i] > 0 && now >= flipStart[i]) {
                var p = (now - flipStart[i]) / FLIP.toFloat()
                while (p >= 1f && flipsLeft[i] > 0) {
                    cur[i] = nextFor(i); flipsLeft[i]--; flipStart[i] += FLIP; p -= 1f
                    if (sound) Sfx.flap(soundVolume)
                    if (haptics && now - lastTick > 70) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); lastTick = now }
                }
                if (flipsLeft[i] > 0) {
                    busy = true
                    val next = nextFor(i)
                    // Static halves: new char on top, old char below; the moving flap covers one of them.
                    half(c, x, next, top = true); half(c, x, cur[i], top = false)
                    c.save()
                    if (p < 0.5f) { c.scale(1f, 1f - 2 * p, x, mid); half(c, x, cur[i], top = true, flap = true) }
                    else { c.scale(1f, 2 * p - 1f, x, mid); half(c, x, next, top = false, flap = true) }
                    c.restore()
                } else glyph(c, x, cur[i])
            } else {
                if (flipsLeft[i] > 0) busy = true
                glyph(c, x, cur[i])
            }
            // The hinge line every split-flap tile has.
            shade.color = withAlpha(0xFF000000.toInt(), 110)
            c.drawRect(x, mid - 0.6f * d.density, x + cellW, mid + 0.6f * d.density, shade)
        }
        if (busy) postInvalidateOnAnimation()
    }

    private fun glyph(c: Canvas, x: Float, ch: Char) {
        val base = cellH / 2f - (ink.descent() + ink.ascent()) / 2f
        c.drawText(ch.toString(), x + cellW / 2f, base, ink)
    }

    private fun half(c: Canvas, x: Float, ch: Char, top: Boolean, flap: Boolean = false) {
        c.save()
        val mid = cellH / 2f
        c.clipRect(x, if (top) 0f else mid, x + cellW, if (top) mid else cellH)
        if (flap) { r.set(x, 0f, x + cellW, cellH); c.drawRoundRect(r, 3 * d.density, 3 * d.density, tile) }
        glyph(c, x, ch)
        if (flap) { shade.color = withAlpha(0xFF000000.toInt(), 50); c.drawRect(x, 0f, x + cellW, cellH, shade) }
        c.restore()
    }

    /** Character the current flap reveals: the target on the last flap, otherwise the one leading up to it. */
    private fun nextFor(i: Int): Char {
        if (flipsLeft[i] <= 1) return target[i]
        val n = ABC.length
        return ABC[((idx(target[i]) - flipsLeft[i] + 1) % n + n) % n]
    }

    private fun idx(ch: Char) = ABC.indexOf(ch).let { if (it < 0) 0 else it }

    companion object {
        const val ABC = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:-#×/+%∞"
        const val FLIP = 95L
    }
}

/** Lives as a row of indicator lamps: lit = pink, spent = dark. */
class LampsView(ctx: Context, private val lit: Int, private val off: Int) : View(ctx) {
    var total = 3; set(v) { field = v; requestLayout(); invalidate() }
    var on = 3; set(v) { field = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(((total * 14 + (total - 1) * 6) * dp).toInt() + 1, (14 * dp).toInt())
    override fun onDraw(c: Canvas) {
        for (i in 0 until total) {
            val x = i * 20 * dp
            r.set(x, 0f, x + 14 * dp, 14 * dp)
            p.color = if (i < on) lit else off
            c.drawRoundRect(r, 3 * dp, 3 * dp, p)
            if (i < on) { p.color = withAlpha(0xFFFFFFFF.toInt(), 90); r.set(x + 3 * dp, 3 * dp, x + 7 * dp, 6 * dp); c.drawRoundRect(r, dp, dp, p) }
        }
    }
}
