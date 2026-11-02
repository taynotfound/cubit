package com.cubit.game

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.random.Random

class MainActivity : Activity(), GameView.Listener {

    private enum class Tab(val label: String, val glyph: Glyph) {
        PLAY("Play", Glyph.CUBE), RANKS("Rankings", Glyph.RANKS), SETTINGS("Settings", Glyph.SETTINGS)
    }

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var content: FrameLayout
    private var tab = Tab.PLAY
    private val handler = Handler(Looper.getMainLooper())
    private val th get() = prefs.theme

    // Current round
    private var gameLayer: FrameLayout? = null
    private var game: GameView? = null
    private var freeRoam = false
    /** UTC day when the current round is the daily cube. */
    private var dailyDay: Int? = null
    private var spec = specForLevel(1)
    private var roundLabel = ""
    private var diff = Difficulty.NORMAL
    private var livesLeft = 0
    private var mistakes = 0
    private var timeLimit = 0
    private var elapsedMs = 0L
    private var resumedAt = 0L
    private var running = false
    private var hud: Hud? = null
    // Ranked run: id from the server, and every tap as [arrow, game-clock ms] for it to replay.
    private var runId: String? = null
    private var roundSerial = 0
    private val moves = ArrayList<LongArray>()
    // Powerups: one charge each per round.
    private val usedPowerups = HashSet<Powerup>()
    private val powerupBtns = HashMap<Powerup, View>()
    private var shield = false
    /** This round's goal (fixed at start, so changing the setting mid-level doesn't matter). */
    private var roundFull = false
    private var baseLabel = ""
    private var tutorial = false
    private var coach: TextView? = null
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    private class Hud(val level: FlapView, val timer: FlapView, val lamps: LampsView?, val cleared: FlapView, val hints: TextView, val sheet: FrameLayout)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.load(this)
        prefs = Prefs(this)
        Sfx.init(this); Sfx.enabled = prefs.sound
        Haptics.init(this); Haptics.enabled = prefs.haptics
        Music.init(this); Music.enabled = prefs.music; Music.menu()
        root = FrameLayout(this)
        setContentView(root)
        buildShell()
        // Opening screen on a cold start (not on rotation or when Android brings the activity back).
        if (savedInstanceState == null) showIntro { if (!prefs.onboarded) showSetup() } else if (!prefs.onboarded) showSetup()
    }

    /**
     * The opening: a small cube spins in, CUBIT clatters onto the flap board, the line fades up, then it all fades
     * into the home screen. About two seconds; a tap skips it, and "Remove animations" skips it entirely.
     */
    private fun showIntro(then: () -> Unit) {
        if (!motionEnabled()) { then(); return }
        val layer = FrameLayout(this).apply { tag = "intro"; setBackgroundColor(th.bg); isClickable = true }
        val col = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val cube = GameView(this).apply {
            applyStyle(this); markTarget = prefs.markTarget
            load(PuzzleCache.get(LevelSpec(4, 3, 2026))); inputEnabled = false; autoSpin = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        col.add(cube, 0, dp(280), dp(280))
        val sign = FlapView(this, 5, 46f, th.tile, th.text, false).apply { haptics = prefs.haptics; setInstant("     "); contentDescription = "Cubit" }
        col.add(sign, 18, WRAP_CONTENT)
        val line = label("Free the pink arrow", th.muted).apply { alpha = 0f }
        col.add(line, 16, WRAP_CONTENT)
        layer.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER).apply { bottomMargin = dp(40) })
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        var done = false
        val finish = {
            if (!done) {
                done = true
                layer.animate().alpha(0f).setDuration(280).withEndAction { root.removeView(layer); then() }.start()
            }
        }
        handler.postDelayed({ sign.text = "CUBIT" }, 380)
        handler.postDelayed({ line.animate().alpha(1f).translationY(0f).setDuration(320).start() }, 1050)
        line.translationY = dp(6).toFloat()
        handler.postDelayed(finish, 2200)
        layer.setOnClickListener { finish() }
    }

    /**
     * A big level that isn't cached yet is built off the main thread. If that takes more than a blink, a loading board
     * covers the wait; quick builds never show it.
     */
    private fun whenBuilt(s: LevelSpec, go: () -> Unit) {
        if (PuzzleCache.has(s)) { go(); return }
        val layer = FrameLayout(this).apply { tag = "loading"; setBackgroundColor(th.bg); isClickable = true; alpha = 0f }
        val col = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val sign = FlapView(this, 7, 34f, th.tile, th.text, false).apply { haptics = false; setInstant("       ") }
        col.add(sign, 0, WRAP_CONTENT)
        col.add(label("Building a ${s.n}×${s.n} cube"), 16, WRAP_CONTENT)
        layer.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        val show = Runnable {
            root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            layer.animate().alpha(1f).setDuration(160).start()
            sign.text = "LOADING"
        }
        handler.postDelayed(show, 150)
        bg.execute {
            PuzzleCache.get(s)
            handler.post {
                handler.removeCallbacks(show)
                if (layer.parent != null) layer.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(layer) }.start()
                go()
            }
        }
    }

    // The tick stops while we're in the background, or it would count that time and could end the level.
    override fun onPause() { super.onPause(); pauseClock(); handler.removeCallbacks(tick); Music.pause(); if (running && !tutorial) saveRun() }
    override fun onResume() { super.onResume(); if (running) { resumedAt = SystemClock.uptimeMillis(); handler.post(tick) }; Music.resume() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            root.findViewWithTag<View>("intro") != null || root.findViewWithTag<View>("loading") != null -> {}
            root.findViewWithTag<View>("welcome") != null -> {} // setup has to be finished once
            root.findViewWithTag<View>("replay") != null -> closeReplay()
            root.findViewWithTag<View>("details") != null -> root.removeView(root.findViewWithTag("details"))
            root.findViewWithTag<View>("sheet") != null -> closeSheet()
            gameLayer != null -> askLeave()
            tab != Tab.PLAY -> showTab(Tab.PLAY)
            else -> super.onBackPressed()
        }
    }

    // ---------- kit ----------

    private fun dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

    private fun tv(s: String, sp: Float, face: Typeface, color: Int = th.text, caps: Boolean = false, track: Float = 0f) = TextView(this).apply {
        text = if (caps) s.uppercase() else s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = face; setTextColor(color); letterSpacing = track
        includeFontPadding = false
    }
    private fun display(s: String, sp: Float = 40f, color: Int = th.text) = tv(s, sp, Fonts.display, color, caps = true, track = 0.01f)
    private fun headline(s: String) = tv(s, 30f, Fonts.display, caps = true)
    private fun title(s: String) = tv(s, 19f, Fonts.label, caps = true, track = 0.03f)
    private fun body(s: String, color: Int = th.text) = tv(s, 16f, Fonts.body, color).apply { setLineSpacing(0f, 1.3f) }
    private fun label(s: String, color: Int = th.muted) = tv(s, 13f, Fonts.label, color, caps = true, track = 0.08f)

    private fun rect(color: Int, radius: Int = 6, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun View.pressable(onClick: () -> Unit): View {
        isClickable = true; isFocusable = true
        setOnClickListener { Sfx.click(); Haptics.tick(); onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setInterpolator(DecelerateInterpolator(2f)).setDuration(180).start()
            }
            false
        }
        return this
    }

    private fun primary(text: String, glyph: Glyph = Glyph.ARROW, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(16), 0); minimumHeight = dp(60)
        background = rect(th.accent)
        addView(tv(text, 22f, Fonts.display, th.onAccent, caps = true, track = 0.02f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(IconView(this@MainActivity, glyph, th.onAccent, 26))
        contentDescription = text
        pressable(onClick)
    }

    private fun secondary(text: String, color: Int = th.text, glyph: Glyph? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0); minimumHeight = dp(52)
        background = rect(th.panel, stroke = th.hairline)
        if (glyph != null) addView(IconView(this@MainActivity, glyph, color, 20), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        addView(tv(text, 17f, Fonts.label, color, caps = true, track = 0.04f))
        contentDescription = text
        pressable(onClick)
    }

    private fun iconButton(glyph: Glyph, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
        background = rect(th.panel, stroke = th.hairline)
        addView(IconView(this@MainActivity, glyph, th.text), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        contentDescription = desc
        pressable(onClick)
    }

    private fun LinearLayout.add(v: View, top: Int = 0, w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f): View {
        addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) })
        return v
    }
    /** Zero-height filler: a WRAP_CONTENT plain View would stretch its row to full height. */
    private fun LinearLayout.space(w: Int, weight: Float = 0f) = addView(View(this@MainActivity), LinearLayout.LayoutParams(w, 0, weight))
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun panel() = column().apply { background = rect(th.panel); setPadding(dp(16), dp(14), dp(16), dp(16)) }

    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val r = row().apply { background = rect(th.panel); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val items = options.map { tv(it, if (options.size > 5) 13f else 15f, Fonts.label, caps = true, track = 0.04f).apply { gravity = Gravity.CENTER; minHeight = dp(44) } }
        fun paint(sel: Int) = items.forEachIndexed { i, t ->
            t.background = if (i == sel) rect(th.accent, 4) else null
            t.setTextColor(if (i == sel) th.onAccent else th.muted)
        }
        items.forEachIndexed { i, t ->
            t.setOnClickListener { Sfx.click(); Haptics.tick(); paint(i); onSelect(i) }
            t.contentDescription = options[i]
            r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        paint(selected)
        return r
    }

    private fun slider(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max - min; progress = value - min
        progressTintList = ColorStateList.valueOf(th.accent); thumbTintList = ColorStateList.valueOf(th.accent)
        progressBackgroundTintList = ColorStateList.valueOf(th.hairline)
        minimumHeight = dp(48)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange(p + min)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun switchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = value
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(th.accent, th.muted))
            trackTintList = ColorStateList(states, intArrayOf(withAlpha(th.accent, 110), th.hairline))
            setOnCheckedChangeListener { _, b -> onChange(b); Sfx.toggle(); Haptics.tick() }
            contentDescription = title
        }
        val r = row().apply { minimumHeight = dp(64); setPadding(0, dp(10), 0, dp(10)) }
        val texts = column()
        texts.add(tv(title, 17f, Fonts.bodyMedium))
        texts.add(tv(sub, 14f, Fonts.body, th.muted).apply { setLineSpacing(0f, 1.25f) }, 3)
        r.add(texts, 0, 0, WRAP_CONTENT, 1f)
        r.add(sw, 0, WRAP_CONTENT).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(12) }
        r.setOnClickListener { sw.toggle() }
        return r
    }

    private fun field(value: String, hint: String, number: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; setSingleLine()
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        typeface = Fonts.bodyMedium; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(th.text); setHintTextColor(th.muted)
        background = rect(th.tile, stroke = th.hairline)
        setPadding(dp(14), 0, dp(14), 0); minHeight = dp(52)
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = rect(th.accent, 1).apply { setSize(dp(2), dp(20)) }
        highlightColor = withAlpha(th.accent, 90)
    }

    private fun flap(cells: Int, sp: Float, right: Boolean = false) = FlapView(this, cells, sp, th.tile, th.text, right).apply { haptics = prefs.haptics }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- shell ----------

    private fun buildShell() {
        root.removeAllViews()
        root.setBackgroundColor(th.bg)
        systemBars(th.bg, th.bg, th.isLight)

        shell = column()
        content = FrameLayout(this)
        shell.add(content, 0, MATCH_PARENT, 0, 1f)
        root.addView(shell, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        showTab(tab)
    }

    private fun systemBars(status: Int, nav: Int, light: Boolean) {
        window.statusBarColor = status
        window.navigationBarColor = nav
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }

    private fun showTab(t: Tab) {
        tab = t
        content.removeAllViews()
        val page = when (t) { Tab.PLAY -> playTab(); Tab.RANKS -> ranksTab(); Tab.SETTINGS -> settingsTab() }
        content.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (motionEnabled()) { page.alpha = 0f; page.translationY = dp(10).toFloat(); page.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start() }
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val col = column().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        return ScrollView(this).apply { isFillViewport = true; addView(col) } to col
    }

    // ---------- Play ----------

    /** A bare icon with a 48dp touch target, for the quiet corners of the screen. */
    private fun plainIcon(glyph: Glyph, desc: String, color: Int = th.text, onClick: () -> Unit) = FrameLayout(this).apply {
        addView(IconView(this@MainActivity, glyph, color, 26), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        contentDescription = desc
        pressable(onClick)
    }

    /** Page title with a way back to Play. */
    private fun header(name: String): View {
        val r = row()
        val back = plainIcon(Glyph.ARROW, "Back to Play") { showTab(Tab.PLAY) }
        (back.getChildAt(0) as IconView).rotation = 180f
        r.add(back, 0, dp(48), dp(48)).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = -dp(12) }
        r.add(headline(name).apply { setPadding(dp(4), 0, 0, 0) }, 0, WRAP_CONTENT)
        return r
    }

    /** A chip: icon and caps label on a plate; [on] fills it with the accent. */
    private fun chip(glyph: Glyph, text: String, desc: String, onClick: () -> Unit): Pair<LinearLayout, (Boolean) -> Unit> {
        val icon = IconView(this, glyph, th.text, 20)
        val lbl = tv(text, 16f, Fonts.label, th.text, caps = true, track = 0.05f).apply { setPadding(dp(8), 0, 0, 0) }
        val c = row().apply {
            gravity = Gravity.CENTER; minimumHeight = dp(52); setPadding(dp(10), 0, dp(10), 0)
            addView(icon); addView(lbl); contentDescription = desc
            pressable(onClick)
        }
        val paint: (Boolean) -> Unit = { on ->
            c.background = if (on) rect(th.accent) else rect(th.panel, stroke = th.hairline)
            icon.color = if (on) th.onAccent else th.text; lbl.setTextColor(if (on) th.onAccent else th.text)
        }
        paint(false)
        return c to paint
    }

    private fun playTab(): View {
        val col = column().apply { setPadding(dp(20), dp(18), dp(20), dp(20)) }

        // Top: wordmark and player (tap for profile) on the left, rankings and settings as quiet icons on the right.
        val top = row()
        val (lvl, into, need) = playerLevel(prefs.xp)
        val me = column().apply { contentDescription = "Your profile and stats"; pressable { profileSheet() } }
        val mark = row()
        mark.add(IconView(this, Glyph.CUBE, th.accent, 24), 0, WRAP_CONTENT)
        mark.add(display("Cubit", 28f).apply { setPadding(dp(8), 0, 0, 0) }, 0, WRAP_CONTENT)
        me.add(mark, 0, WRAP_CONTENT)
        me.add(label("${prefs.name} · LV $lvl"), 6, WRAP_CONTENT)
        me.add(xpBar(into.toFloat() / need), 6, dp(96), dp(3))
        top.add(me, 0, WRAP_CONTENT)
        top.space(0, 1f)
        top.add(plainIcon(Glyph.RANKS, "Rankings") { showTab(Tab.RANKS) }, 0, dp(48), dp(48))
        top.add(plainIcon(Glyph.SETTINGS, "Settings") { showTab(Tab.SETTINGS) }, 0, dp(48), dp(48))
        col.add(top)

        // The cube is the screen.
        val level = prefs.level
        var puzzle: Puzzle? = null
        val demo = GameView(this).apply {
            applyStyle(this); markTarget = prefs.markTarget
            contentDescription = "Preview of level $level"
        }
        col.add(demo, 0, MATCH_PARENT, 0, 1f)

        // One flap line for the level, one quiet line for the rules.
        val sign = FlapView(this, 9, 30f, th.tile, th.text, false).apply { haptics = prefs.haptics; setInstant("         ") }
        val signRow = row().apply { gravity = Gravity.CENTER }
        signRow.add(sign, 0, WRAP_CONTENT)
        col.add(signRow, 8)
        val rules = tv("", 15f, Fonts.body, th.muted).apply { gravity = Gravity.CENTER }
        col.add(rules, 10)
        fun describe() {
            val pz = puzzle ?: run { rules.text = "Building the cube…"; return }
            val d = prefs.difficulty; val n = pz.cube.n
            val t = d.timeLimitSec(pz.paths.size)
            rules.text = listOfNotNull("$n×$n", "${pz.paths.size} arrows", if (prefs.fullClear) "full clear" else null,
                if (d == Difficulty.CLEAN) "just the cube" else if (d.lives == 0) "no lives" else "${d.lives} ${if (d.lives == 1) "life" else "lives"}",
                if (t > 0) "%d:%02d".format(t / 60, t % 60) else if (d != Difficulty.CLEAN) "no timer" else null).joinToString("  ·  ")
        }
        describe()
        handler.postDelayed({ sign.text = "LEVEL %03d".format(level % 1000) }, 200)
        // Big cubes take a moment to build: do it off the main thread, then hand the cube to the preview.
        bg.execute {
            val pz = PuzzleCache.get(specForLevel(level))
            handler.post { puzzle = pz; demo.load(pz); demo.inputEnabled = false; demo.autoSpin = true; describe() }
        }

        val saved = prefs.savedRun?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        if (saved != null) {
            // A level left mid-way comes first: continue it, or throw it away.
            val r = row().apply { background = rect(th.panel, stroke = th.accent); setPadding(dp(16), dp(10), dp(8), dp(10)) }
            val t = column()
            t.add(label("Saved level", th.accent)); t.add(tv("${saved.optString("label")} · ${saved.optInt("left")} left", 16f, Fonts.bodyMedium), 4)
            r.add(t, 0, 0, WRAP_CONTENT, 1f)
            r.add(plainIcon(Glyph.CLOSE, "Discard the saved level", th.muted) {
                confirm("Discard saved level?", "Its progress is lost.", "Keep it", "Discard") { closeSheet(); prefs.savedRun = null; showTab(Tab.PLAY) }
            }, 0, dp(48), dp(48))
            col.add(r, 14)
            col.add(primary("Continue") { resumeRun(saved) }, 10)
        } else col.add(primary("Play") { if (prefs.tutorialDone) startCampaign() else startTutorial() }, 18)

        // Clean is the one switch that stays on the home screen; Mode and More open sheets.
        val chips = row()
        lateinit var modeChip: View
        val (cleanChip, paintClean) = chip(Glyph.CUBE, "Clean", "Clean mode: just the cube, no lives, no timer") {}
        cleanChip.setOnClickListener {
            Sfx.click(); Haptics.tick()
            val on = prefs.difficulty != Difficulty.CLEAN
            prefs.difficulty = if (on) Difficulty.CLEAN else prefs.lastMode
            paintClean(on); demo.glass = on; demo.invalidate(); describe()
            modeChip.alpha = if (on) 0.5f else 1f
            ((modeChip as LinearLayout).getChildAt(1) as TextView).text = prefs.lastMode.label.uppercase()
        }
        val clean = prefs.difficulty == Difficulty.CLEAN
        paintClean(clean); demo.glass = clean
        modeChip = chip(Glyph.SETTINGS, prefs.lastMode.label, "Mode: lives and timer") { modeSheet() }.first
        if (clean) modeChip.alpha = 0.5f
        val streak = prefs.liveStreak(utcDay())
        val (moreChip, _) = chip(if (streak > 0) Glyph.FLAME else Glyph.MORE, if (streak > 0) "$streak" else "More", "More ways to play: daily cube, free roam, replays") { moreSheet() }
        chips.add(cleanChip, 0, 0, WRAP_CONTENT, 1f)
        chips.add(modeChip, 0, 0, WRAP_CONTENT, 1f).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
        chips.add(moreChip, 0, 0, WRAP_CONTENT, 1f).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
        col.add(chips, 10)
        return col
    }

    private fun xpBar(frac: Float): View {
        val bar = FrameLayout(this).apply { background = rect(th.hairline, 2) }
        val fill = View(this).apply { background = rect(th.accent, 2) }
        bar.addView(fill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        bar.post { fill.layoutParams = FrameLayout.LayoutParams((bar.width * frac.coerceIn(0.03f, 1f)).toInt(), MATCH_PARENT) }
        return bar
    }

    // ---------- sheets ----------

    /** A bottom sheet over everything; tap outside or press back to close. Returns its content column. */
    private fun openSheet(): LinearLayout {
        closeSheet()
        val scrim = FrameLayout(this).apply { tag = "sheet"; setBackgroundColor(withAlpha(Color.BLACK, 150)); isClickable = true; setOnClickListener { closeSheet() } }
        val c = column().apply { background = rect(th.panel, 10); setPadding(dp(22), dp(24), dp(22), dp(22)); isClickable = true }
        scrim.addView(ScrollView(this).apply { addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(12), dp(40), dp(12), dp(12)) })
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (motionEnabled()) { c.translationY = dp(60).toFloat(); c.alpha = 0f; c.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator(2.5f)).start() }
        return c
    }

    /** A yes/no question as a sheet; the safe choice is the big button. */
    private fun confirm(title: String, msg: String, safe: String, risky: String, onRisky: () -> Unit) {
        val c = openSheet()
        c.add(display(title, 30f))
        c.add(body(msg, th.muted), 8)
        c.add(primary(safe) { closeSheet() }, 20)
        c.add(secondary(risky, Color.rgb(255, 107, 94)) { onRisky() }, 10)
    }

    private fun closeSheet() { root.findViewWithTag<View>("sheet")?.let { root.removeView(it) } }

    private fun modeSheet() {
        val c = openSheet()
        c.add(display("Mode", 32f))
        c.add(body("What a win takes, and how many lives and how much time you get. Clean has its own switch on the Play screen.", th.muted), 8)
        c.add(switchRow("Full clear", "Clear every arrow, not just the way out. The pink one has to leave last.", prefs.fullClear) {
            prefs.fullClear = it; handler.postDelayed({ if (tab == Tab.PLAY && gameLayer == null) showTab(Tab.PLAY) }, 200)
        }, 6)
        Difficulty.entries.filter { it != Difficulty.CLEAN }.forEach { d ->
            val on = d == prefs.difficulty
            val r = column().apply {
                background = rect(if (on) withAlpha(th.accent, 40) else th.tile, stroke = if (on) th.accent else th.hairline)
                setPadding(dp(16), dp(12), dp(16), dp(12)); contentDescription = "${d.label}: ${d.blurb}"
                pressable { prefs.difficulty = d; closeSheet(); showTab(Tab.PLAY) } // picking a mode also leaves Clean
            }
            val head = row()
            head.add(title(d.label), 0, 0, WRAP_CONTENT, 1f)
            head.add(label("×%.1f XP".format(d.xpMult), if (on) th.accent else th.muted), 0, WRAP_CONTENT)
            r.add(head); r.add(tv(d.blurb, 14f, Fonts.body, th.muted), 4)
            c.add(r, 10)
        }
    }

    private fun moreSheet() {
        val c = openSheet()
        c.add(display("More ways to play", 30f))
        val day = utcDay(); val streak = prefs.liveStreak(day)
        c.add(secondary("How to play", glyph = Glyph.HINT) { closeSheet(); startTutorial() }, 16)
        c.add(title("Daily cube"), 22)
        c.add(body("One 12×12 cube a day, the same for everyone." + when {
            prefs.dailyDone == day -> " Done today · $streak-day streak."
            streak > 0 -> " Solve it to keep your $streak-day streak."
            else -> ""
        }, th.muted), 6)
        c.add(primary("Daily cube ${dailyDate(day)}", if (streak > 0) Glyph.FLAME else Glyph.DICE) { closeSheet(); startDaily() }, 12)

        c.add(title("Free roam"), 26)
        c.add(body("Any cube you like. Doesn't move your level and earns 60% XP.", th.muted), 6)
        val sizeFlap = flap(5, 22f).apply { setInstant("%02d×%02d".format(prefs.freeSize, prefs.freeSize)) }
        val lenFlap = flap(2, 22f).apply { setInstant("%02d".format(prefs.freeLen)) }
        val r1 = row(); r1.add(label("Cube size"), 0, 0, WRAP_CONTENT, 1f); r1.add(sizeFlap, 0, WRAP_CONTENT); c.add(r1, 12)
        c.add(slider(2, 18, prefs.freeSize) { prefs.freeSize = it; sizeFlap.text = "%02d×%02d".format(it, it) }, 4)
        val r2 = row(); r2.add(label("Max arrow length"), 0, 0, WRAP_CONTENT, 1f); r2.add(lenFlap, 0, WRAP_CONTENT); c.add(r2, 10)
        c.add(slider(2, 20, prefs.freeLen) { prefs.freeLen = it; lenFlap.text = "%02d".format(it) }, 4)
        c.add(secondary("Random cube", glyph = Glyph.DICE) { closeSheet(); startFree(LevelSpec(prefs.freeSize, prefs.freeLen, Random.nextLong())) }, 10)

        c.add(title("Replay a level"), 26)
        val r = row()
        val num = field("", "Level 1 to ${prefs.level}", number = true)
        r.add(num, 0, 0, WRAP_CONTENT, 1f)
        r.add(secondary("Play", glyph = Glyph.REPLAY) {
            val l = num.text.toString().toIntOrNull()
            if (l == null || l !in 1..prefs.level) toast("Pick a level from 1 to ${prefs.level}") else { closeSheet(); startFree(specForLevel(l), "Level $l") }
        }, 0, WRAP_CONTENT).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
        c.add(r, 8)
    }

    private fun profileSheet() {
        val c = openSheet()
        val (lvl, into, need) = playerLevel(prefs.xp)
        c.add(display(prefs.name, 32f))
        c.add(label("Player level $lvl · $into / $need XP to the next"), 6)
        c.add(xpBar(into.toFloat() / need), 10, MATCH_PARENT, dp(4))
        val stats = listOf(
            "Cubes cleared" to "${prefs.solved}", "Arrows cleared" to "${prefs.totalArrows}",
            "Campaign level" to "${prefs.level}", "Daily streak" to "${prefs.liveStreak(utcDay())}",
            "Hints in hand" to "${prefs.hints}", "Badges" to "${prefs.achievements.size} / ${Achievement.entries.size}",
        )
        stats.forEach { (k, v) ->
            val r = row().apply { minimumHeight = dp(44) }
            r.add(title(k).apply { setTextColor(th.muted) }, 0, 0, WRAP_CONTENT, 1f)
            r.add(tv(v, 22f, Fonts.display), 0, WRAP_CONTENT)
            c.add(r, 4)
        }
        c.add(secondary("See badges", glyph = Glyph.BADGE) { closeSheet(); ranksMode = 2; showTab(Tab.RANKS) }, 16)
        c.add(secondary("Edit name and settings", glyph = Glyph.SETTINGS) { closeSheet(); showTab(Tab.SETTINGS) }, 8)
    }


    // ---------- Rankings ----------

    private var ranksMode = 0 // 0 players, 1 best runs, 2 today's daily cube, 3 badges

    private fun renderBadges(list: LinearLayout, status: TextView) {
        val got = prefs.achievements
        status.text = "${got.size} of ${Achievement.entries.size} badges. They live on this phone."
        Achievement.entries.sortedBy { it.name !in got }.forEachIndexed { i, a ->
            val on = a.name in got
            val r = row().apply { minimumHeight = dp(60); alpha = if (on) 1f else 0.55f; contentDescription = "${a.title}: ${a.blurb}" + if (on) ", earned" else ", locked" }
            val plate = FrameLayout(this).apply {
                background = rect(if (on) th.accent else th.tile, 6)
                addView(IconView(this@MainActivity, Glyph.BADGE, if (on) th.onAccent else th.muted, 22), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
            }
            r.add(plate, 0, dp(44), dp(44))
            val mid = column().apply { setPadding(dp(14), 0, 0, 0) }
            mid.add(tv(a.title, 17f, Fonts.bodyMedium))
            mid.add(tv(a.blurb, 13f, Fonts.body, th.muted), 2)
            r.add(mid, 0, 0, WRAP_CONTENT, 1f)
            list.add(r, if (i == 0) 0 else 6)
        }
    }

    private fun ranksTab(): View {
        val (scroll, col) = page()
        col.add(header("Rankings"))
        val status = tv("", 14f, Fonts.body, th.muted).apply { setLineSpacing(0f, 1.25f) }
        val list = column()
        val action = FrameLayout(this)
        var board: GlobalBoard? = null
        var dailyBoard: List<ScoreEntry>? = null

        fun render() {
            list.removeAllViews()
            if (ranksMode == 3) { renderBadges(list, status); return }
            val online = prefs.postOnline && board != null
            val today = utcDay()
            val runs = when (ranksMode) {
                1 -> if (online) board!!.scores else prefs.scores()
                2 -> if (prefs.postOnline) dailyBoard ?: emptyList() else prefs.scores().filter { it.label.startsWith("Daily " + dailyDate(today)) }
                else -> emptyList()
            }
            if (ranksMode == 2) status.text = "Daily cube ${dailyDate(today)} · same cube for everyone" + if (prefs.postOnline) "" else " · this device"
            val rows: List<Triple<String, String, String>> = if (ranksMode == 0) {
                if (online) board!!.players.map { Triple(it.name, "Level ${it.level}" + if (it.cleanRuns > 0) " · ${it.cleanRuns} clean run${if (it.cleanRuns == 1) "" else "s"}" else "", "${it.xp} XP") }
                else listOf(Triple(prefs.name, "Level ${playerLevel(prefs.xp).first} · this device", "${prefs.xp} XP"))
            } else {
                runs.map { Triple(it.name, "${it.label} · ${modeName(it.difficulty)}", "${it.score}") }
            }
            if (rows.isEmpty()) {
                list.add(body(if (ranksMode == 2) "Nobody has solved today's cube yet. Be first: More → Daily cube." else "No runs on the board yet. Clear a level to be first.", th.muted), 8)
                return
            }
            rows.take(50).forEachIndexed { i, (name, sub, value) ->
                val r = row().apply { minimumHeight = dp(60) }
                val rank = FlapView(this, 2, 20f, if (i < 3) th.accent else th.tile, if (i < 3) th.onAccent else th.text, true).apply { haptics = false }
                r.add(rank, 0, WRAP_CONTENT)
                val mid = column().apply { setPadding(dp(14), 0, dp(8), 0) }
                mid.add(tv(name, 17f, Fonts.bodyMedium))
                mid.add(tv(sub, 13f, Fonts.body, th.muted), 2)
                r.add(mid, 0, 0, WRAP_CONTENT, 1f)
                r.add(tv(value, 22f, Fonts.display), 0, WRAP_CONTENT)
                runs.getOrNull(i)?.let { e -> r.contentDescription = "${e.name}, ${e.score} points. Show run details"; r.pressable { showRunDetails(e, i + 1) } }
                list.add(r, if (i == 0) 0 else 6)
                rank.setInstant("  ")
                rank.postDelayed({ rank.text = "%02d".format((i + 1).coerceAtMost(99)) }, 60L * i.coerceAtMost(12))
            }
        }

        fun load() {
            action.removeAllViews()
            if (!prefs.postOnline) {
                status.text = "Global rankings are off. You're seeing this device only."
                action.addView(secondary("Turn on global rankings", glyph = Glyph.RANKS) { prefs.postOnline = true; showTab(Tab.RANKS) })
                render(); return
            }
            status.text = "Loading the board…"
            Net.daily(utcDay()) { d -> dailyBoard = d; if (tab == Tab.RANKS && ranksMode == 2) render() }
            Net.fetch { b ->
                if (tab != Tab.RANKS) return@fetch
                board = b
                if (b == null && Net.noNetwork) {
                    status.text = "Cubit can't reach the internet. If you're online, the app's network access is probably off: on GrapheneOS, allow the Network permission for Cubit. Showing this device for now."
                    action.removeAllViews()
                    val btns = column()
                    btns.add(secondary("Open app permissions", glyph = Glyph.SETTINGS) {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
                    })
                    btns.add(secondary("Retry", glyph = Glyph.REPLAY) { load() }, 8)
                    action.addView(btns)
                } else if (b == null) {
                    status.text = "Can't reach the board (${Net.lastError}). Showing this device."
                    action.removeAllViews(); action.addView(secondary("Retry", glyph = Glyph.REPLAY) { load() })
                } else status.text = "Global board · ${b.players.size} players"
                render()
            }
        }

        col.add(segmented(listOf("Players", "Best", "Today", "Badges"), ranksMode) { ranksMode = it; render() }, 16)
        col.add(status, 12)
        col.add(action, 10)
        col.add(list, 12)
        load()
        return scroll
    }

    // ---------- Settings ----------

    private val openSections = HashSet<String>()

    /** A settings group: a panel whose heading folds it open and shut. Returns the body to fill. */
    private fun section(col: LinearLayout, name: String, glyph: Glyph, first: Boolean = false): LinearLayout {
        val p = panel()
        val h = row().apply { minimumHeight = dp(40) }
        h.add(IconView(this, glyph, th.accent, 22), 0, WRAP_CONTENT)
        h.add(title(name).apply { setPadding(dp(10), 0, 0, 0) }, 0, 0, WRAP_CONTENT, 1f)
        val chevron = IconView(this, Glyph.ARROW, th.muted, 20)
        h.add(chevron, 0, WRAP_CONTENT)
        p.add(h)
        val body = column()
        p.add(body)
        fun show(open: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 90f else 0f
            h.contentDescription = "$name, ${if (open) "open" else "closed"}"
        }
        show(name in openSections)
        h.pressable { val open = name !in openSections; if (open) openSections.add(name) else openSections.remove(name); show(open) }
        col.add(p, if (first) 20 else 12)
        return body
    }

    private fun settingsTab(): View {
        val (scroll, col) = page()
        col.add(header("Settings"))

        val profile = section(col, "Profile", Glyph.RANKS, first = true)
        val name = field(prefs.name, "Your name")
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { prefs.name = s.toString().trim().take(16).ifEmpty { "Player" } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        profile.add(name, 12)
        profile.add(switchRow("Global rankings", "Post your name, level and scores to the shared Cubit board. Off keeps everything on this phone.", prefs.postOnline) { prefs.postOnline = it }, 4)

        val look = section(col, "Look", Glyph.CUBE)
        look.add(label("Colourway"), 14)
        var ways = row()
        THEMES.forEachIndexed { i, t ->
            if (i % 4 == 0 && i > 0) { look.add(ways, 12); ways = row() }
            val item = column().apply { gravity = Gravity.CENTER_HORIZONTAL; contentDescription = "${t.name} colourway" }
            val plate = FrameLayout(this).apply {
                background = rect(t.face, 6).apply { setStroke(dp(3), if (i == prefs.themeIndex) th.accent else mix(t.face, t.bg, 0.5f)) }
                addView(IconView(this@MainActivity, Glyph.ARROW, t.ink, 24), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
            }
            item.addView(plate, LinearLayout.LayoutParams(dp(50), dp(50)))
            item.add(tv(t.name, 12f, Fonts.label, if (i == prefs.themeIndex) th.text else th.muted, caps = true, track = 0.04f).apply { gravity = Gravity.CENTER }, 6, WRAP_CONTENT)
            item.pressable { prefs.themeIndex = i; buildShell() }
            ways.add(item, 0, 0, WRAP_CONTENT, 1f)
        }
        repeat((4 - THEMES.size % 4) % 4) { ways.space(0, 1f) } // keep the last row's plates the same width
        look.add(ways, 12)

        look.add(label("Arrow colour"), 22)
        val ar = row()
        ARROW_COLORS.forEachIndexed { i, ac ->
            val c = if (ac == 0) th.ink else ac
            val sw = FrameLayout(this).apply {
                background = rect(th.face, 6).apply { if (i == prefs.arrowColor) setStroke(dp(3), th.accent) }
                addView(IconView(this@MainActivity, Glyph.ARROW, c, 22), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
                contentDescription = if (ac == 0) "Colourway default" else "Arrow colour ${i + 1}"
                pressable { prefs.arrowColor = i; showTab(Tab.SETTINGS) }
            }
            ar.addView(sw, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        look.add(ar, 10)

        val thick = flap(2, 18f).apply { setInstant("%02d".format(prefs.thickness)) }
        val tr = row(); tr.add(label("Arrow weight"), 0, 0, WRAP_CONTENT, 1f); tr.add(thick, 0, WRAP_CONTENT)
        look.add(tr, 22)
        look.add(slider(1, 14, prefs.thickness) { prefs.thickness = it; thick.text = "%02d".format(it) }, 4)
        look.add(switchRow("Lines per side", "Colour each face like a transit line so you always know which side you're on.", prefs.tintSides) { prefs.tintSides = it }, 4)
        look.add(switchRow("Side dots", "Faint dice dots on each face. Opposite sides add up to seven.", prefs.sideMarkers) { prefs.sideMarkers = it })

        val access = section(col, "Accessibility", Glyph.SCAN)
        access.add(switchRow("Mark the pink arrow", "A stripe down the target, so it stands out without relying on colour.", prefs.markTarget) { prefs.markTarget = it }, 4)
        access.add(switchRow("Left-handed", "Mirror the in-game controls: close and powerups on the right, the camera button on the left.", prefs.leftHanded) { prefs.leftHanded = it })
        access.add(body("Arrow weight in Look goes up to 14 for small screens. Cubit also follows the system's Remove animations setting.", th.muted), 6)

        val feel = section(col, "Sound and touch", Glyph.HINT)
        feel.add(switchRow("Sound effects", "Swooshes, bumps, flap boards and the fanfare when the pink arrow gets out.", prefs.sound) { prefs.sound = it; Sfx.enabled = it }, 4)
        feel.add(switchRow("Music", "A quiet loop under the menus and a slightly busier one while you play.", prefs.music) { prefs.music = it; Music.enabled = it })
        feel.add(switchRow("Vibration", "Taps, slides, bumps, powerups and the win, felt as well as heard.", prefs.haptics) { prefs.haptics = it; Haptics.enabled = it })

        val play = section(col, "Powerups", Glyph.SHIELD)
        play.add(body("One of each per level. Each only shows up where it helps: no Freeze without a timer.", th.muted), 8)
        powerupSwitches(play)

        val progress = section(col, "Progress", Glyph.REPLAY)
        progress.add(body("No ads, ever. Rankings only talk to the Cubit board when you turn them on.", th.muted), 8)
        progress.add(secondary("Run setup again", glyph = Glyph.SETTINGS) { showSetup() }, 14)
        progress.add(secondary("Reset progress", Color.rgb(255, 107, 94)) {
            confirm("Reset progress?", "Your level, XP, hints, badges, streak and local scores will be wiped. Settings and your global board entry stay.", "Cancel", "Reset") {
                closeSheet(); prefs.resetProgress(); toast("Progress reset"); showTab(Tab.SETTINGS)
            }
        }, 10)

        val about = section(col, "About", Glyph.MORE)
        val vr = row().apply { minimumHeight = dp(48) }
        vr.add(title("Version").apply { setTextColor(th.muted) }, 0, 0, WRAP_CONTENT, 1f)
        vr.add(tv(appVersion, 22f, Fonts.display), 0, WRAP_CONTENT)
        about.add(vr, 8)
        val updateBox = column()
        about.add(updateBox, 6)
        fun open(url: String) = runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        fun checkButton() {
            updateBox.removeAllViews()
            updateBox.add(secondary("Check for updates", glyph = Glyph.REPLAY) {
                updateBox.removeAllViews(); updateBox.add(body("Checking GitHub…", th.muted))
                Net.latestVersion { latest ->
                    if (tab != Tab.SETTINGS) return@latestVersion
                    updateBox.removeAllViews()
                    when {
                        latest == null -> { updateBox.add(body("Couldn't reach GitHub. Check your connection, or Cubit's Network permission.", th.muted)); checkAgain(updateBox) { checkButton() } }
                        Net.newer(latest, appVersion) -> {
                            updateBox.add(body("Version $latest is out. It installs over this one and keeps your progress.", th.text))
                            updateBox.add(primary("Download $latest", Glyph.ARROW) { open("https://github.com/taynotfound/cubit/releases/latest/download/Cubit.apk") }, 10)
                            updateBox.add(secondary("What's new") { open("https://github.com/taynotfound/cubit/releases/latest") }, 8)
                        }
                        else -> { updateBox.add(body("You're on the latest version.", th.muted)); checkAgain(updateBox) { checkButton() } }
                    }
                }
            })
        }
        checkButton()
        about.add(secondary("Website", glyph = Glyph.ARROW) { open("https://taynotfound.github.io/cubit/") }, 10)
        about.add(secondary("Source code and licences", glyph = Glyph.ARROW) { open("https://github.com/taynotfound/cubit") }, 8)
        about.add(secondary("Changelog", glyph = Glyph.ARROW) { open("https://github.com/taynotfound/cubit/blob/main/CHANGELOG.md") }, 8)

        // Always visible, even with every section folded.
        col.add(label("Cubit $appVersion · by taynotfound · MIT").apply { gravity = Gravity.CENTER }, 22)
        return scroll
    }

    private fun checkAgain(box: LinearLayout, again: () -> Unit) { box.add(secondary("Check again", glyph = Glyph.REPLAY) { again() }, 10) }

    private val appVersion by lazy { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?" }

    // ---------- first launch ----------

    private fun powerupSwitches(col: LinearLayout) = Powerup.entries.forEach { pu ->
        col.add(switchRow(pu.label, pu.blurb, prefs.powerupOn(pu)) { prefs.setPowerup(pu, it) })
    }

    /** First launch: one sheet that walks through name, mode, feel, powerups and rankings. */
    private fun showSetup() {
        val scrim = FrameLayout(this).apply { tag = "welcome"; setBackgroundColor(withAlpha(Color.BLACK, 170)); isClickable = true }
        val sheet = column().apply { background = rect(th.panel, 10); setPadding(dp(22), dp(24), dp(22), dp(22)) }
        val head = row()
        val sign = flap(7, 30f)
        head.add(sign, 0, WRAP_CONTENT); head.space(0, 1f)
        val stepLbl = label("")
        head.add(stepLbl, 0, WRAP_CONTENT)
        sheet.add(head)
        val pageCol = column()
        sheet.add(pageCol, 18)
        val name = field(if (prefs.name == "Player") "" else prefs.name, "Your name")
        val steps = 5

        fun finish(online: Boolean) {
            prefs.postOnline = online; prefs.onboarded = true
            scrim.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(scrim); showTab(tab) }.start()
        }
        fun show(i: Int) {
            pageCol.removeAllViews()
            stepLbl.text = "${i + 1} / $steps"
            val next = { primary("Next") { show(i + 1) } }
            when (i) {
                0 -> {
                    sign.text = "WELCOME"
                    pageCol.add(display("Free the pink arrow", 32f))
                    pageCol.add(body("Tap an arrow and it slides off the cube along its path, if nothing is in the way. Clear a way out for the pink one.", th.muted), 10)
                    pageCol.add(title("What should we call you?"), 22)
                    pageCol.add(name, 10)
                    pageCol.add(primary("Next") {
                        name.text.toString().trim().take(16).takeIf { it.isNotEmpty() }?.let { prefs.name = it }
                        show(1)
                    }, 20)
                }
                1 -> {
                    sign.text = "MODE"
                    pageCol.add(display("How do you want to play?", 30f))
                    val blurb = tv(prefs.difficulty.blurb, 14f, Fonts.body, th.muted)
                    pageCol.add(segmented(Difficulty.entries.map { it.label }, prefs.difficulty.ordinal) {
                        prefs.difficulty = Difficulty.entries[it]; blurb.text = prefs.difficulty.blurb
                    }, 16)
                    pageCol.add(blurb, 8)
                    pageCol.add(body("Clean shows nothing but the cube. Your clean runs are marked on the board. Change mode any time on the Play tab.", th.muted), 14)
                    pageCol.add(next(), 20)
                }
                2 -> {
                    sign.text = "FEEL"
                    pageCol.add(display("Sound and touch", 30f))
                    pageCol.add(switchRow("Sound effects", "Swooshes, bumps, flap boards and a fanfare.", prefs.sound) { prefs.sound = it; Sfx.enabled = it }, 8)
                    pageCol.add(switchRow("Music", "A quiet ambient loop.", prefs.music) { prefs.music = it; Music.enabled = it })
                    pageCol.add(switchRow("Vibration", "Feel every slide, bump and win.", prefs.haptics) { prefs.haptics = it; Haptics.enabled = it })
                    pageCol.add(switchRow("Lines per side", "Colour each face so you always know which side you're on.", prefs.tintSides) { prefs.tintSides = it })
                    pageCol.add(next(), 16)
                }
                3 -> {
                    sign.text = "POWER"
                    pageCol.add(display("Powerups", 30f))
                    pageCol.add(body("One of each per level. Switch off any you'd rather play without.", th.muted), 8)
                    powerupSwitches(pageCol)
                    pageCol.add(next(), 16)
                }
                else -> {
                    sign.text = "RANKS"
                    pageCol.add(display("Join the global rankings?", 30f))
                    pageCol.add(body("Your name, level and scores go to the shared Cubit board. Nothing else leaves your phone. You can switch this off any time in Settings.", th.muted), 10)
                    pageCol.add(primary("Join rankings", Glyph.RANKS) { finish(true) }, 20)
                    pageCol.add(secondary("Play offline") { finish(false) }, 10)
                }
            }
            if (motionEnabled()) { pageCol.alpha = 0f; pageCol.translationX = dp(16).toFloat(); pageCol.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start() }
        }

        scrim.addView(ScrollView(this).apply { addView(sheet) },
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (motionEnabled()) { sheet.translationY = dp(60).toFloat(); sheet.alpha = 0f; sheet.animate().translationY(0f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator(2.5f)).start() }
        sign.setInstant("       ")
        show(0)
    }

    private fun modeName(difficulty: String) = if (difficulty == Difficulty.CLEAN.label) "Clean mode" else difficulty

    /** A tapped leaderboard run, laid out like the result sheet. */
    private fun showRunDetails(e: ScoreEntry, rank: Int) {
        root.findViewWithTag<View>("details")?.let { root.removeView(it) }
        val scrim = FrameLayout(this).apply { tag = "details"; setBackgroundColor(withAlpha(Color.BLACK, 150)); isClickable = true }
        val c = column().apply { background = rect(th.panel, 10); setPadding(dp(22), dp(24), dp(22), dp(22)); isClickable = true } // taps on the sheet itself don't close it
        val head = row()
        head.add(display(e.name, 32f), 0, 0, WRAP_CONTENT, 1f)
        head.add(FlapView(this, 3, 22f, if (rank <= 3) th.accent else th.tile, if (rank <= 3) th.onAccent else th.text, true).apply { haptics = false; setInstant("#%d".format(rank.coerceAtMost(99))) }, 0, WRAP_CONTENT)
        c.add(head)
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(e.time))
        c.add(label(listOfNotNull(e.label, modeName(e.difficulty), e.playerLevel.takeIf { it > 0 }?.let { "Player LV $it" }).joinToString("  ·  ")), 8)
        c.add(body(date, th.muted), 4)
        val secs = (e.ms / 1000).toInt()
        val rows = buildList {
            add("Score" to (6 to "%d".format(e.score.coerceAtMost(999999))))
            if (e.ms > 0) add("Time" to (5 to "%02d:%02d".format(secs / 60 % 100, secs % 60)))
            if (e.size > 0) add("Cube" to (5 to "%02d×%02d".format(e.size, e.size)))
            if (e.arrows > 0) add("Arrows cleared" to (3 to "%d".format(e.arrows.coerceAtMost(999))))
            if (e.ms > 0) add("Mistakes" to (2 to if (e.mistakes == 0) "0" else "%d".format(e.mistakes.coerceAtMost(99))))
        }
        val flaps = rows.map { (name, v) ->
            val f = flap(v.first, 24f, right = true).apply { setInstant("") }
            val r = row().apply { minimumHeight = dp(48) }
            r.add(title(name).apply { setTextColor(th.muted) }, 0, 0, WRAP_CONTENT, 1f); r.add(f, 0, WRAP_CONTENT)
            c.add(r, 6)
            f to v.second
        }
        if (e.ms > 0) {
            c.add(title("Powerups").apply { setTextColor(th.muted) }, 12)
            c.add(body(if (e.powerups.isEmpty()) "None. Pure skill." else e.powerups.joinToString(" · ")), 4)
            if (e.mistakes == 0) c.add(label("Perfect run", th.accent), 10)
        } else c.add(body("This run was saved before Cubit kept run details.", th.muted), 12)
        val who = "${e.name} · ${e.label}"
        when {
            e.spec != null && e.moves.isNotEmpty() -> c.add(primary("Watch replay", Glyph.REPLAY) { showReplay(e.spec, e.moves, who) }, 18)
            e.run.isNotEmpty() -> c.add(primary("Watch replay", Glyph.REPLAY) {
                Net.replay(e.run) { r -> if (r == null) toast("No replay for this run. Runs keep their taps from this version on.") else showReplay(r.first, r.second, who) }
            }, 18)
        }
        c.add(secondary("Close") { root.removeView(scrim) }, 10)
        scrim.setOnClickListener { root.removeView(scrim) }
        scrim.addView(ScrollView(this).apply { addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (motionEnabled()) { c.translationY = dp(80).toFloat(); c.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2.5f)).start() }
        flaps.forEachIndexed { i, (f, txt) -> handler.postDelayed({ f.text = txt }, 200L + i * 160L) }
    }

    private val replayToken = Any()

    /** Plays a run's recorded taps back on its own cube. Long pauses are cut to 1.5 s so replays stay watchable. */
    private fun showReplay(s: LevelSpec, mv: List<LongArray>, title: String) {
        closeReplay()
        val layer = FrameLayout(this).apply { tag = "replay"; setBackgroundColor(th.bg); isClickable = true }
        val gv = GameView(this).apply {
            applyStyle(this); load(PuzzleCache.get(s)); inputEnabled = false
            listener = object : GameView.Listener {
                override fun onArrowRemoved(left: Int, total: Int) = Sfx.slide()
                override fun onMistake() = Sfx.bump()
                override fun onTargetFreed() = Sfx.win()
                override fun onTap(arrow: Int) {}
            }
        }
        layer.addView(gv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val bar = row().apply { setBackgroundColor(th.panel); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        bar.add(iconButton(Glyph.CLOSE, "Close replay") { closeReplay() }, 0, dp(48), dp(48))
        val tb = column().apply { setPadding(dp(14), 0, dp(8), 0) }
        tb.add(label("Replay")); tb.add(tv(title, 17f, Fonts.bodyMedium), 4)
        bar.add(tb, 0, 0, WRAP_CONTENT, 1f)
        bar.add(iconButton(Glyph.REPLAY, "Watch again") { showReplay(s, mv, title) }, 0, dp(48), dp(48))
        layer.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        var at = 900L; var prev = 0L
        val start = SystemClock.uptimeMillis()
        mv.forEach { m ->
            at += (m[1] - prev).coerceIn(0L, 1500L); prev = m[1]
            val a = m[0].toInt()
            handler.postAtTime({ if (a >= 0) gv.playTap(a) else if (a == Powerup.SCAN.code) gv.scan() }, replayToken, start + at)
        }
    }

    private fun closeReplay() {
        handler.removeCallbacksAndMessages(replayToken)
        root.findViewWithTag<View>("replay")?.let { root.removeView(it) }
    }

    /** A 4:5 card with the cube as it was left and the run's numbers, handed to the system share sheet. */
    private fun shareCard(line: String, score: Int, secs: Int, cleared: Int, perfect: Boolean) {
        // Draw the level as it started, not the emptied cube left after the win.
        val gv = GameView(this).apply { applyStyle(this); glass = diff == Difficulty.CLEAN; markTarget = prefs.markTarget; load(PuzzleCache.get(spec)); inputEnabled = false; settle() }
        gv.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        gv.layout(0, 0, 900, 900)
        val w = 1080; val h = 1350
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(th.bg)
        if (gv.width > 0 && gv.height > 0) {
            val snap = android.graphics.Bitmap.createBitmap(gv.width, gv.height, android.graphics.Bitmap.Config.ARGB_8888)
            gv.draw(android.graphics.Canvas(snap))
            val side = minOf(gv.width, gv.height)
            val sx = (gv.width - side) / 2; val sy = (gv.height - side) / 2
            c.drawBitmap(snap, android.graphics.Rect(sx, sy, sx + side, sy + side), android.graphics.Rect(90, 250, 990, 1150), null)
        }
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun text(s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface, align: android.graphics.Paint.Align = android.graphics.Paint.Align.LEFT) {
            p.textSize = size; p.color = color; p.typeface = face; p.textAlign = align; p.letterSpacing = 0.02f; c.drawText(s.uppercase(), x, y, p)
        }
        p.color = th.accent; c.drawRect(90f, 90f, 130f, 130f, p)
        text("Cubit", 150f, 130f, 64f, th.text, Fonts.display)
        text(line, 90f, 210f, 40f, th.muted, Fonts.label)
        text("%d".format(score), 90f, 1265f, 120f, th.accent, Fonts.display)
        text("points", 90f, 1305f, 32f, th.muted, Fonts.label)
        text("%02d:%02d · %d arrows".format(secs / 60 % 100, secs % 60, cleared), 990f, 1225f, 44f, th.text, Fonts.label, android.graphics.Paint.Align.RIGHT)
        text(if (perfect) "Perfect run" else prefs.name, 990f, 1285f, 40f, if (perfect) PINK else th.muted, Fonts.label, android.graphics.Paint.Align.RIGHT)
        val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
        java.io.File(dir, "cubit-run.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = android.net.Uri.parse("content://${ShareProvider.AUTHORITY}/cubit-run.png")
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TEXT, "${if (roundFull) "I cleared a whole cube" else "I freed the pink arrow"} in Cubit: $score points.")
            clipData = android.content.ClipData.newRawUri("Cubit run", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(send, "Share your run"))
    }

    // ---------- game ----------

    private fun applyStyle(g: GameView) {
        g.theme = th; g.tintSides = prefs.tintSides; g.sideMarkers = prefs.sideMarkers
        g.arrowColor = prefs.arrowColor; g.thickness = prefs.thickness
    }

    private fun startCampaign() { freeRoam = false; dailyDay = null; tutorial = false; startRound(specForLevel(prefs.level), "Level ${prefs.level}") }
    private fun startFree(s: LevelSpec, label: String = "Free ${s.n}×${s.n}") { freeRoam = true; dailyDay = null; tutorial = false; startRound(s, label) }
    private fun startDaily() { val d = utcDay(); freeRoam = false; dailyDay = d; tutorial = false; startRound(dailySpec(d), "Daily " + dailyDate(d)) }

    /** A tiny 3×3 cube with a coach line that reacts to what you do. Zen rules, nothing ranked or saved. */
    private fun startTutorial() { freeRoam = true; dailyDay = null; tutorial = true; startRound(LevelSpec(3, 3, 4242), "How to play") }

    private fun coachSay(text: String) {
        val c = coach ?: return
        if (c.text == text) return
        c.text = text
        if (motionEnabled()) { c.alpha = 0f; c.translationY = -dp(8).toFloat(); c.animate().alpha(1f).translationY(0f).setDuration(220).start() }
    }

    /** Writes the level in progress so it survives leaving, app switches and the app being killed. */
    private fun saveRun() {
        val g = game ?: return
        val o = org.json.JSONObject()
            .put("n", spec.n).put("maxLen", spec.maxLen).put("seed", spec.seed)
            .put("label", baseLabel).put("free", freeRoam).put("daily", dailyDay ?: -1).put("diff", diff.ordinal).put("full", roundFull)
            .put("elapsed", elapsedMillis()).put("mistakes", mistakes).put("lives", livesLeft).put("limit", timeLimit)
            .put("shield", shield).put("used", org.json.JSONArray(usedPowerups.map { it.name })).put("run", runId ?: "")
            .put("left", g.remaining).put("level", prefs.level)
            .put("moves", org.json.JSONArray().apply { moves.forEach { put(org.json.JSONArray().put(it[0]).put(it[1])) } })
        prefs.savedRun = o.toString()
    }

    private fun resumeRun(o: org.json.JSONObject) {
        freeRoam = o.optBoolean("free"); dailyDay = o.optInt("daily", -1).takeIf { it >= 0 }; tutorial = false
        startRound(LevelSpec(o.getInt("n"), o.getInt("maxLen"), o.getLong("seed")), o.optString("label"), o)
    }
    private fun dailyDate(day: Int) = java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(day * 86_400_000L))

    /** The X: leaving mid-level throws the cube away, so ask once there's progress to lose. */
    private fun askLeave() {
        val g = game
        if (!running || g == null || g.remaining == g.total || tutorial) { leaveGame(); return }
        val c = openSheet()
        c.add(display("Leave this level?", 30f))
        c.add(body("Save it and pick up where you left off from the Play screen.", th.muted), 8)
        c.add(primary("Keep playing") { closeSheet() }, 20)
        c.add(secondary("Save and leave", glyph = Glyph.ARROW) { closeSheet(); saveRun(); leaveGame() }, 10)
        c.add(secondary("Quit level", Color.rgb(255, 107, 94)) { closeSheet(); prefs.savedRun = null; leaveGame() }, 10)
    }

    private fun leaveGame() {
        if (tutorial) prefs.tutorialDone = true // seen it; "How to play" in More brings it back
        stopRound()
        gameLayer?.let { root.removeView(it) }; gameLayer = null
        shell.visibility = View.VISIBLE
        systemBars(th.bg, th.bg, th.isLight)
        Music.menu()
        showTab(tab)
    }

    private fun startRound(s: LevelSpec, label: String, resume: org.json.JSONObject? = null) {
        if (!PuzzleCache.has(s)) { whenBuilt(s) { startRound(s, label, resume) }; return }
        stopRound()
        gameLayer?.let { root.removeView(it) }
        shell.visibility = View.GONE
        if (resume == null && !tutorial) prefs.savedRun = null // a new level replaces any saved one
        diff = if (tutorial) Difficulty.ZEN else resume?.let { Difficulty.entries.getOrNull(it.optInt("diff")) } ?: prefs.difficulty
        roundFull = if (tutorial) false else resume?.optBoolean("full") ?: prefs.fullClear
        spec = s; baseLabel = label; roundLabel = if (roundFull) "$label · full clear" else label
        val puzzle = PuzzleCache.get(s)
        val layer = FrameLayout(this)
        gameLayer = layer
        val gv = GameView(this).apply { applyStyle(this); listener = this@MainActivity; load(puzzle); pinkLast = roundFull; markTarget = prefs.markTarget }
        game = gv
        layer.addView(gv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val ui = column()
        // Top: departure-board strip.
        val strip = row().apply { setBackgroundColor(th.panel); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        strip.add(iconButton(Glyph.CLOSE, "Leave level") { askLeave() }, 0, dp(48), dp(48))
        val levelFlap = flap(if (freeRoam || dailyDay != null) 5 else 4, 24f)
        val lvlBox = column().apply { setPadding(dp(14), 0, 0, 0) }
        lvlBox.add(label(if (tutorial) "How to play" else if (dailyDay != null) "Daily" else if (freeRoam) "Free roam" else "Level")); lvlBox.add(levelFlap, 4, WRAP_CONTENT)
        strip.add(lvlBox, 0, WRAP_CONTENT)
        strip.space(0, 1f)
        val timerFlap = flap(5, 24f, right = true).apply { haptics = false; sound = false; maxFlips = 1 }
        val tBox = column().apply { gravity = Gravity.END; setPadding(0, 0, dp(12), 0) }
        tBox.add(label(if (diff.secPerArrow > 0) "Time left" else "Time"), 0, WRAP_CONTENT); tBox.add(timerFlap, 4, WRAP_CONTENT)
        strip.add(tBox, 0, WRAP_CONTENT)
        // Hint and powerups live in a tray behind one button, so the cube gets the screen.
        val tray = row().apply { setPadding(dp(16), dp(10), dp(16), 0); visibility = View.GONE }
        val boost = iconButton(Glyph.BOLT, "Hints and powerups") {
            tray.visibility = if (tray.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        (boost.getChildAt(0) as IconView).color = th.accent
        strip.add(boost, 0, dp(48), dp(48))
        ui.add(strip)

        val lampsRow = row().apply { setPadding(dp(16), dp(12), dp(16), 0) }
        val lamps = if (diff.lives > 0) LampsView(this, PINK, th.hairline).apply { total = diff.lives; on = diff.lives; contentDescription = "Lives" } else null
        if (lamps != null) lampsRow.add(lamps, 0, WRAP_CONTENT) else lampsRow.add(label("Zen · no lives"), 0, WRAP_CONTENT)
        ui.add(lampsRow)
        // Powerups get their own row: five plates next to the lamps don't fit a narrow phone.
        if (diff.lives == 0) {
            // Zen and Clean: take back the last arrow (Clean has no buttons, so it's a two-finger tap there).
            val back = iconButton(Glyph.REPLAY, "Take back the last move") { undoTap() }
            (back.getChildAt(0) as IconView).color = th.accent
            tray.add(back, 0, dp(48), dp(48)).let { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        }
        val hintBtn = row().apply {
            background = rect(th.panel, stroke = th.hairline); gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(12), 0)
            addView(IconView(this@MainActivity, Glyph.HINT, th.accent))
            contentDescription = "Use a hint"
            pressable { useHint(); tray.visibility = View.GONE }
        }
        val hintCount = tv("${prefs.hints}", 20f, Fonts.display).apply { setPadding(dp(6), 0, 0, 0) }
        hintBtn.addView(hintCount)
        tray.add(hintBtn, 0, WRAP_CONTENT, dp(48))
        powerupBtns.clear(); usedPowerups.clear(); shield = false
        Powerup.entries.filter { prefs.powerupOn(it) && it.appliesTo(diff) }.forEach { pu ->
            val b = iconButton(pu.glyph, "Use ${pu.label}: ${pu.blurb}") { usePowerup(pu); tray.visibility = View.GONE }
            (b.getChildAt(0) as IconView).color = th.accent
            tray.add(b, 0, dp(48), dp(48)).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
            powerupBtns[pu] = b
        }
        ui.add(tray)

        ui.space(0, 1f)

        val bottom = row().apply { setPadding(dp(16), dp(10), dp(12), dp(16)) }
        val goal = row()
        goal.add(View(this).apply { background = rect(PINK, 2) }, 0, dp(12), dp(12))
        goal.add(tv(if (roundFull) "Clear all · pink last" else "Free the pink arrow", 17f, Fonts.label, caps = true, track = 0.03f).apply { setPadding(dp(10), 0, 0, 0) }, 0, WRAP_CONTENT)
        bottom.add(goal, 0, 0, WRAP_CONTENT, 1f)
        val clearedFlap = flap(3, 20f, right = true)
        val cBox = column().apply { gravity = Gravity.END; setPadding(0, 0, dp(12), 0) }
        cBox.add(label("Left"), 0, WRAP_CONTENT); cBox.add(clearedFlap, 4, WRAP_CONTENT)
        bottom.add(cBox, 0, WRAP_CONTENT)
        // Few arrows left: the button turns the cube to them instead of just resetting the view.
        bottom.add(iconButton(Glyph.RECENTER, "Reset camera, or find the last arrows") { game?.let { if (it.remaining <= 6) it.findNext() else it.resetView() } }, 0, dp(48), dp(48))
        ui.add(bottom)
        if (tutorial) {
            val c = tv("", 17f, Fonts.bodyMedium).apply {
                background = rect(th.panel, stroke = th.accent); setPadding(dp(16), dp(12), dp(16), dp(12)); setLineSpacing(0f, 1.25f)
            }
            coach = c
            ui.addView(c, 2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(16), dp(12), dp(16), 0) })
            coachSay("Arrows slide forward along their path. Tap one whose way to the edge is clear.")
        } else coach = null
        if (prefs.leftHanded) listOf(strip, lampsRow, tray, bottom).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_RTL }
        if (diff == Difficulty.CLEAN) {
            // Clean mode: a see-through cube and a quiet X. The HUD views still exist and track the round off screen.
            ui.removeAllViews()
            gv.glass = true
            systemBars(th.bg, th.bg, th.isLight)
            val bar = row().apply { setPadding(dp(10), dp(10), dp(10), 0) }
            val x = FrameLayout(this).apply {
                addView(IconView(this@MainActivity, Glyph.CLOSE, th.muted), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
                contentDescription = "Leave level"
                pressable { askLeave() }
            }
            bar.add(x, 0, dp(48), dp(48))
            ui.add(bar)
        } else systemBars(th.bg, th.panel, th.isLight)
        layer.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val sheet = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(withAlpha(Color.BLACK, 150)); isClickable = true }
        layer.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        hud = Hud(levelFlap, timerFlap, lamps, clearedFlap, hintCount, sheet)
        levelFlap.setInstant("     ")
        handler.postDelayed({ levelFlap.text = if (freeRoam || dailyDay != null) "%02d×%02d".format(s.n, s.n) else "%04d".format(prefs.level % 10000) }, 200)
        clearedFlap.setInstant("   ")
        livesLeft = diff.lives; mistakes = 0
        moves.clear(); runId = null
        val serial = ++roundSerial
        if (prefs.postOnline && !tutorial && resume == null) Net.startRun(prefs, s, if (freeRoam) null else prefs.level, dailyDay, diff.label, roundFull) { id -> if (serial == roundSerial) runId = id }
        timeLimit = diff.timeLimitSec(puzzle.paths.size)
        if (resume != null) {
            // Back where we left off: replay the taps silently and restore the counters.
            val mv = resume.optJSONArray("moves") ?: org.json.JSONArray()
            for (i in 0 until mv.length()) mv.getJSONArray(i).let { moves.add(longArrayOf(it.getLong(0), it.getLong(1))) }
            gv.applyInstant(moves)
            mistakes = resume.optInt("mistakes"); livesLeft = resume.optInt("lives", diff.lives)
            timeLimit = resume.optInt("limit", timeLimit); shield = resume.optBoolean("shield")
            runId = resume.optString("run").ifEmpty { null }
            val used = resume.optJSONArray("used") ?: org.json.JSONArray()
            for (i in 0 until used.length()) Powerup.entries.firstOrNull { it.name == used.getString(i) }?.let { pu ->
                usedPowerups.add(pu)
                if (pu == Powerup.LIFE) lamps?.let { it.total = it.total + 1 }
                powerupBtns[pu]?.apply { if (pu == Powerup.SHIELD && shield) background = rect(withAlpha(th.accent, 60), stroke = th.accent) else { alpha = 0.3f; isEnabled = false } }
            }
            prefs.savedRun = null
        }
        elapsedMs = resume?.optLong("elapsed") ?: 0L; resumedAt = SystemClock.uptimeMillis(); running = true
        updateHud()
        Sfx.start(); Music.game()
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            updateHud()
            if (timeLimit > 0 && elapsedSec() >= timeLimit) lose("Time's up") else handler.postDelayed(this, 250)
        }
    }

    private fun elapsedMillis() = elapsedMs + (if (running) SystemClock.uptimeMillis() - resumedAt else 0)
    private fun elapsedSec() = elapsedMillis() / 1000f
    private fun pauseClock() { if (running) { elapsedMs += SystemClock.uptimeMillis() - resumedAt; resumedAt = SystemClock.uptimeMillis() } }
    private fun stopRound() { pauseClock(); running = false; handler.removeCallbacks(tick); game = null; hud = null }

    private fun updateHud() {
        val h = hud ?: return; val g = game ?: return
        val t = elapsedSec().toInt()
        val shown = if (timeLimit > 0) (timeLimit - t).coerceAtLeast(0) else t
        val txt = "%02d:%02d".format((shown / 60).coerceAtMost(99), shown % 60)
        if (h.timer.text != txt) h.timer.text = txt
        h.timer.inkColor = if (timeLimit > 0 && timeLimit - t <= 10) Color.rgb(255, 107, 94) else th.text
        h.lamps?.on = livesLeft.coerceAtLeast(0)
        val cleared = "%03d".format(g.remaining.coerceAtMost(999)) // arrows still on the cube
        if (h.cleared.text != cleared) h.cleared.text = cleared
        h.hints.text = "${prefs.hints}"
    }

    private fun useHint() {
        val g = game ?: return
        if (!running) return
        if (prefs.hints <= 0) { toast("No hints left. You earn one every player level."); return }
        if (g.showHint()) { prefs.hints = prefs.hints - 1; Sfx.hint() }
        updateHud()
    }

    private fun usePowerup(pu: Powerup) {
        if (!running || pu in usedPowerups) return
        if (pu == Powerup.UNDO && mistakes == 0) { toast("Nothing to undo yet. Save it for after a bump."); return }
        usedPowerups.add(pu)
        when (pu) {
            Powerup.SCAN -> game?.scan()
            Powerup.SHIELD -> shield = true
            Powerup.FREEZE -> timeLimit += 15
            Powerup.LIFE -> { livesLeft++; hud?.lamps?.let { it.total = it.total + 1 } }
            Powerup.UNDO -> { mistakes--; if (diff.lives > 0) livesLeft++ }
        }
        if (pu.code < 0) moves.add(longArrayOf(pu.code.toLong(), elapsedMillis())) // the server replays these too
        powerupBtns[pu]?.apply {
            if (pu == Powerup.SHIELD) background = rect(withAlpha(th.accent, 60), stroke = th.accent) // armed until it absorbs a bump
            else { alpha = 0.3f; isEnabled = false }
        }
        Haptics.double(); Sfx.hint()
        updateHud()
    }

    override fun onArrowRemoved(left: Int, total: Int) {
        Sfx.slide(); Haptics.click(); updateHud()
        if (tutorial) coachSay(if (total - left >= 3) "Now find the pink arrow. Clear its way out and tap it to win." else "That's it. Keep clearing. Drag to turn the cube, pinch to zoom.")
    }

    override fun onTap(arrow: Int) { if (running) moves.add(longArrayOf(arrow.toLong(), elapsedMillis())) }

    override fun onTwoFingerTap() { if (diff.lives == 0) undoTap() }

    /** Zen and Clean: put the last removed arrow back. Recorded so ranked replays match. */
    private fun undoTap() {
        val g = game ?: return
        if (!running || diff.lives > 0) return
        if (g.undoLast() < 0) { toast("Nothing to take back"); return }
        moves.add(longArrayOf(UNDO_TAP.toLong(), elapsedMillis()))
        Sfx.toggle(); Haptics.tick(); updateHud()
    }

    override fun onTargetFreed() {
        if (!running) return
        pauseClock(); running = false
        game?.inputEnabled = false
        Sfx.win(); Haptics.win()
        game?.celebrate()
        handler.postDelayed({ win() }, 1700) // let the pink arrow fly and the cube turn before the sheet
    }

    override fun onMistake() {
        if (!running) return
        Sfx.bump(); Haptics.bump()
        if (tutorial) coachSay("That one's blocked: something sits in its path, so it bounces back. Drag to turn the cube and look for a clear one.")
        if (shield) {
            shield = false
            powerupBtns[Powerup.SHIELD]?.apply { background = rect(th.panel, stroke = th.hairline); alpha = 0.3f; isEnabled = false }
            return
        }
        mistakes++
        if (diff.lives > 0) {
            livesLeft--
            updateHud()
            hud?.lamps?.apply { scaleX = 1.4f; scaleY = 1.4f; animate().scaleX(1f).scaleY(1f).setDuration(260).start() }
            if (livesLeft <= 0) lose("Out of lives")
        }
    }

    private fun win() {
        val g = game ?: return
        if (tutorial) {
            prefs.tutorialDone = true
            showSheet("You've got it", emptyList(), listOf(
                "Free the pink arrow to win. Bumps cost lives, and the timer depends on the mode.",
                "The ⚡ button has hints and powerups. When only a few arrows are left, the target button turns the cube to them.",
            ), "Play level ${prefs.level}") { startCampaign() }
            return
        }
        prefs.savedRun = null
        val cleared = g.total - g.remaining
        val perfect = mistakes == 0
        val secsLeft = if (timeLimit > 0) (timeLimit - elapsedSec()).toInt().coerceAtLeast(0) else 0
        val mult = diff.xpMult * (if (freeRoam) 0.6f else 1f)
        val xpGain = ((cleared * 3 + spec.n * 6) * mult * (if (perfect) 1.25f else 1f)).toInt()
        val score = ((cleared * 10 + spec.n * 25) * diff.xpMult + secsLeft * 5 + (if (perfect) 150 else 0)).toInt()

        val before = playerLevel(prefs.xp).first
        prefs.xp = prefs.xp + xpGain
        val after = playerLevel(prefs.xp).first
        if (after > before) prefs.hints = prefs.hints + (after - before)
        // Hints are earned by playing well: a perfect run on a mode with lives, and every daily cube.
        val bonusHints = (if (perfect && diff.lives > 0) 1 else 0) + (if (dailyDay != null) 1 else 0)
        prefs.hints = prefs.hints + bonusHints
        prefs.solved = prefs.solved + 1
        dailyDay?.let { d ->
            if (prefs.dailyDone == d - 1) prefs.dailyStreak = prefs.dailyStreak + 1 else if (prefs.dailyDone != d) prefs.dailyStreak = 1
            prefs.dailyDone = d
        }
        if (dailyDay == null && !freeRoam) prefs.level = prefs.level + 1
        prefs.totalArrows = prefs.totalArrows + cleared
        prefs.noPowerupStreak = if (usedPowerups.isEmpty()) prefs.noPowerupStreak + 1 else 0
        if (diff == Difficulty.CLEAN) prefs.cleanSolved = prefs.cleanSolved + 1
        val ms = elapsedMillis()
        val facts = RunFacts(diff, perfect, cleared, ms, spec.n, dailyDay != null, usedPowerups.isNotEmpty())
        val got = prefs.achievements
        val unlocked = Achievement.entries.filter { it.name !in got && it.check(prefs, facts) }
        prefs.achievements = got + unlocked.map { it.name }
        prefs.addScore(ScoreEntry(prefs.name, score, roundLabel, diff.label, System.currentTimeMillis(),
            cleared, ms, mistakes, spec.n, Powerup.entries.filter { it in usedPowerups }.map { it.label }, after,
            spec = spec, moves = ArrayList(moves)))

        val t = elapsedSec().toInt()
        val rankFlap = flap(4, 26f, right = true)
        val rows = listOf(
            "Score" to (flap(6, 26f, right = true) to "%d".format(score.coerceAtMost(999999))),
            "XP" to (flap(4, 26f, right = true) to "+%d".format(xpGain.coerceAtMost(999))),
            "Time" to (flap(5, 26f, right = true) to "%02d:%02d".format(t / 60 % 100, t % 60)),
            "Global rank" to (rankFlap to if (prefs.postOnline) "----" else "OFF"),
        )
        val extra = buildList {
            add(if (perfect) "Perfect run · +25% XP" else "$mistakes mistake${if (mistakes == 1) "" else "s"}")
            if (after > before) add("Level up · you're now LV $after · +${after - before} hint")
            if (bonusHints > 0) add("+$bonusHints hint for ${if (dailyDay != null && perfect && diff.lives > 0) "a perfect daily" else if (dailyDay != null) "the daily cube" else "a perfect run"}")
            unlocked.forEach { add("Badge earned · ${it.title}: ${it.blurb}") }
        }
        if (unlocked.isNotEmpty()) handler.postDelayed({ Sfx.hint(); Haptics.double() }, 1400)
        val shareLine = "${roundLabel} · ${modeName(diff.label)}"
        showSheet(if (roundFull) "Cube cleared" else "Pink arrow freed", rows, extra, if (freeRoam) "Another cube" else if (dailyDay != null) "Play level ${prefs.level}" else "Next level",
            onShare = { shareCard(shareLine, score, t, cleared, perfect) }) {
            if (freeRoam) startFree(LevelSpec(spec.n, spec.maxLen, Random.nextLong())) else startCampaign()
        }
        val run = runId
        if (prefs.postOnline && run != null) Net.finishRun(prefs, run, ArrayList(moves)) { res ->
            // Land after the sheet's own clatter so the rank is the last row to settle.
            handler.postDelayed({
                rankFlap.text = when {
                    res == null -> "----"
                    res.status == "review" -> "HELD" // looked automated; an admin has to approve it
                    res.rank > 0 -> "#%d".format(res.rank.coerceAtMost(999))
                    else -> "----"
                }
            }, 1200)
        }
    }

    private fun lose(reason: String) {
        pauseClock(); running = false
        Sfx.lose(); Haptics.lose()
        game?.inputEnabled = false
        updateHud()
        val g = game
        val rows = listOf("Cleared" to (flap(3, 26f, right = true) to "%d".format(g?.let { it.total - it.remaining } ?: 0)))
        prefs.savedRun = null
        showSheet(reason, rows, listOf((if (roundFull) "The cube isn't clear yet." else "The pink arrow is still stuck.") + " Easier modes give more lives and time."), "Try again") { startRound(spec, baseLabel) }
    }

    private fun showSheet(title: String, rows: List<Pair<String, Pair<FlapView, String>>>, notes: List<String>, primaryText: String, onShare: (() -> Unit)? = null, onPrimary: () -> Unit) {
        val o = hud?.sheet ?: return
        o.removeAllViews()
        val c = column().apply { background = rect(th.panel, 10); setPadding(dp(22), dp(24), dp(22), dp(22)) }
        c.add(display(title, 34f))
        rows.forEach { (name, fv) ->
            val r = row().apply { minimumHeight = dp(52) }
            r.add(title(name).apply { setTextColor(th.muted) }, 0, 0, WRAP_CONTENT, 1f)
            r.add(fv.first, 0, WRAP_CONTENT)
            c.add(r, 10)
        }
        notes.forEach { c.add(body(it, th.muted), 10) }
        c.add(primary(primaryText) { onPrimary() }, 20)
        val btns = row()
        btns.add(secondary("Back to menu") { leaveGame() }, 0, 0, WRAP_CONTENT, 1f)
        if (onShare != null) btns.add(secondary("Share", glyph = Glyph.SHARE) { onShare() }, 0, WRAP_CONTENT).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
        c.add(btns, 10)
        o.addView(ScrollView(this).apply { addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        o.visibility = View.VISIBLE
        rows.forEach { it.second.first.setInstant("") }
        if (motionEnabled()) {
            o.alpha = 0f; o.animate().alpha(1f).setDuration(180).start()
            c.translationY = dp(80).toFloat(); c.animate().translationY(0f).setDuration(360).setInterpolator(DecelerateInterpolator(2.5f)).start()
        }
        // Each board row clatters in after the previous one.
        rows.forEachIndexed { i, (_, fv) -> handler.postDelayed({ fv.first.text = fv.second }, 300L + i * 220L) }
    }
}
