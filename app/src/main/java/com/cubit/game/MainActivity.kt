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
    /** Weekly challenge round: its week number. */
    private var weekNo: Int? = null
    /** Puzzle pack round: the pack and the level index in it. */
    private var packRun: Pair<Pack, Int>? = null
    /** Fewest taps that can win this cube (everything blocking the pink one, plus the pink one; or every arrow). */
    private var par = 0
    // Ghost race: someone's recorded run, replayed by the game clock.
    private var ghost: List<LongArray> = emptyList()
    private var ghostAt = 0
    private var ghostName = ""
    private var ghostDoneMs = -1L
    private var ghostLabel: TextView? = null
    private var coach: TextView? = null
    /** A friend's shared run: this round is played on its cube, rules and goal, against its ghost. */
    private var challenge: Challenge? = null
    /** Endless run: cubes cleared so far, or -1 when not in one. Lives carry from cube to cube via [carryLives]. */
    private var endless = -1
    private var endlessSeed = 0L
    private var carryLives = -1
    /** Seconds bought in the endless shop, added to the next cube's clock. */
    private var carryBonus = 0
    /** Daily endless: everyone gets the same cubes today. */
    private var endlessDay: Int? = null
    /** The server's id for this endless run, when it's ranked. */
    private var endlessId: String? = null
    /** This round's biggest blast and longest bomb chain, for the badges. */
    private var maxBlast = 0
    private var maxChain = 0
    /** Clears in quick succession with no bump between: the combo shown over the cube, and this round's best. */
    private var combo = 0
    private var bestCombo = 0
    private var lastClearAt = 0L
    /** A past daily cube played from the archive: free-roam rules, no streak, marked solved in the calendar. */
    private var archiveDay: Int? = null
    /** The ghost is your own best run on this cube. */
    private var ownGhost = false
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    private class Hud(val level: FlapView, val timer: FlapView, val lamps: LampsView?, val cleared: FlapView, val hints: TextView, val sheet: FrameLayout)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.load(this)
        prefs = Prefs(this)
        Sfx.init(this); Sfx.enabled = prefs.sound
        Haptics.init(this); Haptics.enabled = prefs.haptics
        Music.init(this); Music.enabled = prefs.music; Music.level = prefs.musicVolume / 100f; Music.menu()
        root = FrameLayout(this)
        setContentView(root)
        buildShell()
        // Opening screen on a cold start (not on rotation or when Android brings the activity back).
        ReminderReceiver.schedule(this, prefs.streakReminder)
        if (startDemo(intent)) return
        if (savedInstanceState == null) showIntro { if (!prefs.onboarded) showSetup() else handleLink(intent) } else if (!prefs.onboarded) showSetup()
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); if (!startDemo(intent)) handleLink(intent) }

    // ---------- demo autoplay (debug builds only) ----------

    private var demoTaps = 0
    private var demoMinTaps = 0
    private var demoPace = 1f
    /** Set by [startDemo]: every round that starts plays itself. */
    private var demoOn = false

    /**
     * Records promo footage: `adb shell am start -n de.taymaerz.cubit/com.cubit.game.MainActivity --es demo <scene>`
     * with scene "level:12", "free:<n>:<maxLen>:<seed>", "endless" or "home", and optional --es mode NORMAL|CLEAN|…,
     * --ez full true, --ei taps <before pink>, --ef pace <tap speed>. The app plays itself with a slow camera drift and
     * a ring on each tap. Rankings are switched off so demo runs never reach the board. Release builds ignore it.
     */
    private fun startDemo(i: android.content.Intent?): Boolean {
        val scene = i?.getStringExtra("demo") ?: return false
        if (!BuildConfig.DEBUG) return false
        i.removeExtra("demo")
        prefs.onboarded = true; prefs.tutorialDone = true; prefs.postOnline = false; prefs.bombSeen = true
        if (prefs.name == "Player") prefs.name = "Cubit"
        i.getStringExtra("mode")?.let { m -> Difficulty.entries.firstOrNull { it.name == m }?.let { prefs.difficulty = it } }
        prefs.fullClear = i.getBooleanExtra("full", false)
        demoMinTaps = i.getIntExtra("taps", 30); demoPace = i.getFloatExtra("pace", 1f); demoTaps = 0; demoOn = true
        closeSheet(); root.findViewWithTag<View>("welcome")?.let { root.removeView(it) }
        if (gameLayer != null) leaveGame()
        val f = scene.split(":")
        when (f[0]) {
            "level" -> { prefs.level = f.getOrNull(1)?.toIntOrNull() ?: prefs.level; startCampaign() }
            "free" -> startFree(LevelSpec(f[1].toInt(), f[2].toInt(), f[3].toLong()))
            "endless" -> startEndless()
            else -> { showTab(Tab.PLAY); return true }
        }
        return true
    }

    private val demoStep: Runnable = object : Runnable {
        override fun run() {
            val g = game ?: return
            if (!running) {
                // Endless: take the next cube after the win sheet has been seen.
                // The win is counted 1.7 s after the pink arrow leaves, so look again once the sheet is up.
                if (endless >= 0) handler.postDelayed({ if (endless > 0 && !running) { carryLives = livesLeft; demoTaps = 0; startRound(endlessSpec(endlessSeed, endless), endlessLabel(endless)) } }, 4300)
                return
            }
            g.showTaps = true
            if (g.turning) { handler.postDelayed(this, 100); return }
            val a = g.demoPick(pinkOk = demoTaps >= demoMinTaps)
            // Only ever tap what the viewer can see: an arrow out of view gets the cube turned to it first.
            if (a >= 0 && !g.demoSees(a)) { g.demoLookAt(a); handler.postDelayed(this, 800); return }
            if (a >= 0) { android.util.Log.i("CubitDemo", if (g.isBomb(a)) "bomb" else "tap"); g.demoTap(a); demoTaps++ }
            // Between taps the camera drifts; the gaps vary like a person's.
            val gap = ((380 + Random.nextInt(420)) / demoPace).toLong()
            var t = 0L
            while (t < gap) { handler.postDelayed({ if (running) g.demoTurn(0f, 0.003f) }, t); t += 16 }
            handler.postDelayed(this, gap)
        }
    }

    /** cubit://daily/<day>, week/<week>, challenge/<run>, pack/<pack> and friend/<code>, from shared links and the streak reminder. */
    private fun handleLink(i: android.content.Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "cubit" || gameLayer != null || !prefs.onboarded) return
        val arg = u.lastPathSegment ?: return
        i.data = null // handle once
        val n = arg.toIntOrNull()
        when (u.host) {
            "daily" -> if (n == null) return else if (n == utcDay()) startDaily() else startFree(dailySpec(n), "Daily " + dailyDate(n))
            "week" -> if (n == null) return else if (n == utcWeek()) startWeekly() else startFree(weekSpec(n), "Week of " + dailyDate(weekStart(n)))
            "challenge" -> Links.parseChallenge(arg)?.let { startChallenge(it) } ?: toast("That challenge link is broken")
            "pack" -> {
                val pk = Links.parsePack(arg) ?: run { toast("That pack link is broken"); return }
                when {
                    prefs.allPacks().any { it.name == pk.name && it.levels == pk.levels } -> {}
                    prefs.allPacks().any { it.name == pk.name } -> { toast("You already have a pack called ${pk.name}"); return }
                    else -> prefs.sharedPacks = prefs.sharedPacks + arg
                }
                toast("${pk.name} is in More → Puzzle packs"); moreSheet()
            }
            "friend" -> Links.cleanCode(arg)?.let { addFriend(it) }
        }
    }

    private fun startChallenge(c: Challenge) {
        plainRound()
        freeRoam = true; dailyDay = null; weekNo = null; packRun = null; tutorial = false; challenge = c
        startRound(c.spec, "${c.name}'s challenge")
    }

    private fun addFriend(code: String) {
        if (code == prefs.friendCode) { toast("That's your own code"); return }
        prefs.friends = prefs.friends + code
        if (!prefs.postOnline) toast("Friend added. Turn on global rankings to see their runs.") else toast("Friend added: Rankings → Friends")
        ranksMode = 5; showTab(Tab.RANKS)
    }

    /** Hands a link to the system share sheet. */
    private fun shareText(text: String, title: String) = startActivity(android.content.Intent.createChooser(
        android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }, title))

    private fun sharePack(pk: Pack) =
        shareText("A Cubit puzzle pack for you: ${pk.name}, ${pk.levels.size} cubes. ${Links.WEB}?pack=${Links.packPayload(pk.name, pk.levels)}", "Share ${pk.name}")

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
    override fun onPause() { super.onPause(); pauseClock(); handler.removeCallbacks(tick); Music.pause(); if (running && !tutorial && game?.let { it.remaining < it.total } == true) saveRun() } // nothing to keep before the first arrow goes
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

    /** Tabs in a strip. More than six don't fit a phone side by side, so then the strip scrolls sideways instead. */
    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val scrolls = options.size > 6
        val r = row().apply { background = rect(th.panel); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val items = options.map { tv(it, if (options.size > 5) 13f else 15f, Fonts.label, caps = true, track = 0.04f).apply { gravity = Gravity.CENTER; minHeight = dp(44) } }
        fun paint(sel: Int) = items.forEachIndexed { i, t ->
            t.background = if (i == sel) rect(th.accent, 4) else null
            t.setTextColor(if (i == sel) th.onAccent else th.muted)
        }
        items.forEachIndexed { i, t ->
            t.setOnClickListener { Sfx.click(); Haptics.tick(); paint(i); onSelect(i) }
            t.contentDescription = options[i]
            if (scrolls) { t.setPadding(dp(14), 0, dp(14), 0); r.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
            else r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        paint(selected)
        if (!scrolls) return r
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(r)
            post { items.getOrNull(selected)?.let { smoothScrollTo((it.left - dp(40)).coerceAtLeast(0), 0) } } // keep the open tab in view
        }
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
        val y = if (::content.isInitialized) scrollOf(content) else 0
        root.removeAllViews()
        root.setBackgroundColor(th.bg)
        systemBars(th.bg, th.bg, th.isLight)

        shell = column()
        content = FrameLayout(this)
        shell.add(content, 0, MATCH_PARENT, 0, 1f)
        root.addView(shell, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        showTab(tab, y)
    }

    private fun systemBars(status: Int, nav: Int, light: Boolean) {
        window.statusBarColor = status
        window.navigationBarColor = nav
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }

    private fun scrollOf(box: FrameLayout) = (box.getChildAt(0) as? ScrollView)?.scrollY ?: 0

    /** Redrawing the same tab (a setting that rebuilds the page) keeps its scroll place; a new tab starts at the top. */
    private fun showTab(t: Tab, y: Int = if (t == tab) scrollOf(content) else 0) {
        tab = t
        content.removeAllViews()
        val page = when (t) { Tab.PLAY -> playTab(); Tab.RANKS -> ranksTab(); Tab.SETTINGS -> settingsTab() }
        content.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (y > 0) { page.post { page.scrollTo(0, y) }; return }
        if (motionEnabled()) { page.alpha = 0f; page.translationY = dp(10).toFloat(); page.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start() }
    }

    /** Side gap for sheets: 12dp on a phone, wider on a tablet or in landscape so they stay a readable width. */
    private fun side() = maxOf(dp(12), (resources.displayMetrics.widthPixels - dp(620)) / 2)

    /** Rotating (or resizing on a tablet) rebuilds the menus for the new width; a game in progress just re-lays itself out. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (gameLayer == null) buildShell()
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val gap = maxOf(dp(20), (resources.displayMetrics.widthPixels - dp(640)) / 2)
        val col = column().apply { setPadding(gap, dp(20), gap, dp(24)) }
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
            rules.text = listOfNotNull("$n×$n", "${pz.paths.size} arrows", "par ${if (prefs.fullClear) pz.paths.size else mustClear(pz) + 1}", if (prefs.fullClear) "full clear" else null,
                if (d == Difficulty.CLEAN) "just the cube" else if (d.lives == 0) "no lives" else "${d.lives} ${if (d.lives == 1) "life" else "lives"}",
                if (t > 0) "%d:%02d".format(t / 60, t % 60) else if (d != Difficulty.CLEAN) "no timer" else null).joinToString("  ·  ")
        }
        describe()
        handler.postDelayed({ sign.text = "LEVEL %03d".format(level % 1000) }, 200)
        // Big cubes take a moment to build: do it off the main thread, then hand the cube to the preview.
        bg.execute {
            val pz = PuzzleCache.get(specForLevel(level).copy(lap = prefs.difficulty == Difficulty.NIGHTMARE))
            handler.post { puzzle = pz; demo.load(pz); demo.inputEnabled = false; demo.autoSpin = true; describe() }
        }

        homeNotices(col)
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
            // Nightmare's preview is a different (lap) cube: rebuild the screen rather than relabel it.
            if (prefs.lastMode == Difficulty.NIGHTMARE) { showTab(Tab.PLAY); return@setOnClickListener }
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

    /** A slim card on the home screen; [onClose] dismisses it for good. */
    private fun notice(col: LinearLayout, head: String, text: String, action: String?, onAction: () -> Unit, onClose: () -> Unit) {
        val r = row().apply { background = rect(th.panel, stroke = th.hairline); setPadding(dp(16), dp(10), dp(8), dp(10)) }
        val t = column()
        t.add(label(head, th.accent)); t.add(tv(text, 15f, Fonts.body), 4)
        r.add(t, 0, 0, WRAP_CONTENT, 1f)
        if (action != null) r.add(tv(action, 16f, Fonts.label, th.accent, caps = true, track = 0.05f).apply {
            setPadding(dp(10), dp(12), dp(6), dp(12)); contentDescription = action; pressable(onAction)
        }, 0, WRAP_CONTENT)
        val x = plainIcon(Glyph.CLOSE, "Dismiss", th.muted) { onClose(); r.visibility = View.GONE }
        r.add(x, 0, dp(44), dp(44))
        col.add(r, 10)
    }

    /** At most one quiet card on the home screen: a new version, or how yesterday's daily cube went. */
    private fun homeNotices(col: LinearLayout) {
        val today = utcDay()
        val latest = prefs.latestVersion
        if (BuildConfig.UPDATE_CHECK && latest != null && Net.newer(latest, appVersion) && latest != prefs.dismissedVersion) {
            notice(col, "Update", "Cubit $latest is out.", "Get it", {
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/taynotfound/cubit/releases/latest/download/Cubit.apk"))) }
            }) { prefs.dismissedVersion = latest }
            return
        }
        if (BuildConfig.UPDATE_CHECK && prefs.autoUpdate && prefs.updateCheckedDay != today) Net.latestVersion { v ->
            if (v != null) { prefs.latestVersion = v; prefs.updateCheckedDay = today }
            if (v != null && Net.newer(v, appVersion) && tab == Tab.PLAY && gameLayer == null) showTab(Tab.PLAY)
        }
        // Yesterday's daily: once, the morning after you played it.
        if (prefs.postOnline && prefs.dailyDone == today - 1 && prefs.summarySeen != today) {
            val holder = column(); col.add(holder)
            Net.daily(today - 1) { board ->
                if (board == null || holder.parent == null) return@daily
                val me = board.indexOfFirst { it.name == prefs.name }
                val text = if (me >= 0) "You finished #${me + 1} of ${board.size} on yesterday's cube." else "${board.size} players solved yesterday's cube."
                notice(holder, "Yesterday", "$text Today's is waiting.", "Play", { prefs.summarySeen = today; startDaily() }) { prefs.summarySeen = today }
            }
        }
    }

    // ---------- sheets ----------

    /** A bottom sheet over everything; tap outside or press back to close. Returns its content column. */
    private fun openSheet(): LinearLayout {
        closeSheet()
        val scrim = FrameLayout(this).apply { tag = "sheet"; setBackgroundColor(withAlpha(Color.BLACK, 150)); isClickable = true; setOnClickListener { closeSheet() } }
        val c = column().apply { background = rect(th.panel, 10); setPadding(dp(22), dp(24), dp(22), dp(22)); isClickable = true }
        scrim.addView(ScrollView(this).apply { addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(side(), dp(40), side(), dp(12)) })
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
        } + (if (prefs.dailyBest > 0) " Your best: ${prefs.dailyBest} on ${dailyDate(prefs.dailyBestDay)}." else "") +
            (if (prefs.streakFreezes > 0) " ${prefs.streakFreezes} streak freeze${if (prefs.streakFreezes == 1) "" else "s"} in hand." else ""), th.muted), 6)
        c.add(primary("Daily cube ${dailyDate(day)}", if (streak > 0) Glyph.FLAME else Glyph.DICE) { closeSheet(); startDaily() }, 12)
        c.add(secondary("Past daily cubes", glyph = Glyph.REPLAY) { closeSheet(); archiveSheet(0) }, 10)

        c.add(title("Endless"), 26)
        c.add(body("Cube after cube, each a little bigger. Your lives carry over, so every bump counts." +
            if (prefs.endlessBest > 0) " Your best: ${prefs.endlessBest} cube${if (prefs.endlessBest == 1) "" else "s"}." else "", th.muted), 6)
        c.add(secondary("Start an endless run", th.accent, Glyph.BOLT) { closeSheet(); startEndless() }, 12)
        val todayBest = if (prefs.dailyEndlessDay == day) prefs.dailyEndlessBest else 0
        c.add(secondary("Today's endless" + if (todayBest > 0) " · best $todayBest" else "", glyph = Glyph.DICE) { closeSheet(); startEndless(daily = true) }, 10)
        c.add(body("Today's is the same run of cubes for everyone, with its own board in Rankings.", th.muted), 6)

        val week = utcWeek()
        c.add(title("Weekly challenge"), 26)
        c.add(body("One big 16×16 cube for the whole week, with its own board." + if (prefs.weeklyDone == week) " You've done this week's." else
            " Finishing it earns a streak freeze, which covers a missed daily cube (hold up to $MAX_FREEZES). Resets on Monday.", th.muted), 6)
        c.add(secondary("Week of ${dailyDate(weekStart(week))}", if (prefs.weeklyDone == week) th.muted else th.accent, Glyph.RANKS) { closeSheet(); startWeekly() }, 12)

        c.add(title("Puzzle packs"), 26)
        c.add(body("Ten picked cubes each, easiest first, with the pink arrow buried deep.", th.muted), 6)
        prefs.allPacks().forEach { pk ->
            val done = prefs.packSolved(pk).coerceAtMost(pk.levels.size)
            val r = row().apply { background = rect(th.tile, stroke = th.hairline); setPadding(dp(14), dp(10), dp(4), dp(10)); contentDescription = "${pk.name}, $done of ${pk.levels.size} done" }
            val t = column(); t.add(title(pk.name)); t.add(tv(pk.blurb, 14f, Fonts.body, th.muted), 4)
            r.add(t, 0, 0, WRAP_CONTENT, 1f)
            r.add(tv(if (done >= pk.levels.size) "DONE" else "$done/${pk.levels.size}", 20f, Fonts.display, if (done >= pk.levels.size) th.accent else th.text).apply { setPadding(dp(10), 0, dp(6), 0) }, 0, WRAP_CONTENT)
            if (pk !in PACKS) {
                r.add(plainIcon(Glyph.SHARE, "Share ${pk.name} as a link", th.muted) { sharePack(pk) }, 0, dp(44), dp(44))
                r.add(plainIcon(Glyph.CLOSE, "Remove ${pk.name}", th.muted) {
                    confirm("Remove ${pk.name}?", "Its cubes and progress go. You can open its link again to get it back.", "Keep it", "Remove") {
                        if (pk.name == MY_PACK) prefs.myPack = emptyList() else prefs.sharedPacks = prefs.sharedPacks.filter { Links.parsePack(it)?.name != pk.name }
                        prefs.setPackSolved(pk, 0); closeSheet(); moreSheet()
                    }
                }, 0, dp(44), dp(44))
            } else r.space(dp(10))
            r.pressable { closeSheet(); startPack(pk, if (done >= pk.levels.size) 0 else done) }
            c.add(r, 8)
        }
        if (prefs.myPack.isEmpty()) c.add(body("Save any cube you've won into My pack from the win sheet, then share the pack as a link.", th.muted), 8)

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
        c.add(secondary("Stats", glyph = Glyph.RANKS) { statsSheet() }, 16)
        c.add(secondary("See badges", glyph = Glyph.BADGE) { closeSheet(); ranksMode = 4; showTab(Tab.RANKS) }, 8)
        c.add(secondary("Edit name and settings", glyph = Glyph.SETTINGS) { closeSheet(); showTab(Tab.SETTINGS) }, 8)
    }


    /**
     * Every daily cube since the first one, a month at a time. Past ones play with free-roam rules (60% XP, no streak)
     * and get a ring once solved; days solved on the day are filled in, as in Stats.
     */
    private fun archiveSheet(monthsBack: Int) {
        val c = openSheet()
        val today = utcDay()
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = today * 86_400_000L; add(java.util.Calendar.MONTH, -monthsBack); set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val first = (cal.timeInMillis / 86_400_000L).toInt()
        val days = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val head = row()
        head.add(display(java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.ENGLISH).apply { timeZone = cal.timeZone }.format(cal.time), 30f), 0, 0, WRAP_CONTENT, 1f)
        if (first > FIRST_DAILY) head.add(plainIcon(Glyph.ARROW, "Earlier month") { closeSheet(); archiveSheet(monthsBack + 1) }.apply { rotation = 180f }, 0, dp(48), dp(48))
        if (monthsBack > 0) head.add(plainIcon(Glyph.ARROW, "Later month") { closeSheet(); archiveSheet(monthsBack - 1) }, 0, dp(48), dp(48))
        c.add(head)
        c.add(body("Tap a day to play its cube. Past ones don't touch your streak.", th.muted), 6)
        val solved = prefs.dailyDays; val late = prefs.archiveDays
        val names = row()
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { names.add(label(it).apply { gravity = Gravity.CENTER }, 0, 0, WRAP_CONTENT, 1f) }
        c.add(names, 14)
        val lead = (first + 3) % 7 // day 0 was a Thursday; Monday is column 0
        var r = row()
        repeat(lead) { r.space(0, 1f) }
        for (d in first until first + days) {
            val open = d in FIRST_DAILY..today
            val cell = tv("${d - first + 1}", 16f, Fonts.bodyMedium, if (d in solved) th.bg else if (open) th.text else th.muted).apply {
                gravity = Gravity.CENTER
                background = when { d in solved -> rect(th.accent, 3); d in late -> rect(th.tile, 3, stroke = th.accent); open -> rect(th.tile, 3, stroke = if (d == today) th.text else 0); else -> null }
                contentDescription = "${dailyDate(d)}: " + when { d in solved -> "solved on the day"; d in late -> "solved later"; open -> "not solved, tap to play"; else -> "not available" }
                if (open) pressable { closeSheet(); if (d == today) startDaily() else startArchive(d) }
            }
            r.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            if ((lead + d - first + 1) % 7 == 0) { c.add(r); r = row() }
        }
        if (r.childCount > 0) { repeat(7 - r.childCount) { r.space(0, 1f) }; c.add(r) }
        val inMonth = (first until first + days).count { it in solved || it in late }
        val missed = (maxOf(first, FIRST_DAILY)..minOf(first + days - 1, today - 1)).count { it !in solved && it !in late }
        c.add(body("$inMonth solved this month" + (if (missed > 0) " · $missed missed, still open" else "") + " · filled: on the day · ring: later", th.muted), 10)
    }

    /** Wins, losses and times per mode, and a five-week calendar of daily solves. All from this phone. */
    private fun statsSheet() {
        val c = openSheet()
        c.add(display("Stats", 32f))
        c.add(label("Daily cube · last five weeks"), 18)
        val today = utcDay(); val solved = prefs.dailyDays
        val head = row()
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { head.add(label(it).apply { gravity = Gravity.CENTER }, 0, 0, WRAP_CONTENT, 1f) }
        c.add(head, 8)
        for (w in utcWeek(today) - 4..utcWeek(today)) {
            val r = row()
            for (d in weekStart(w) until weekStart(w) + 7) {
                val on = d in solved
                val cell = View(this).apply {
                    background = when { d > today -> null; on -> rect(th.accent, 3); else -> rect(th.tile, 3, stroke = if (d == today) th.accent else 0) }
                    contentDescription = "${dailyDate(d)}: ${if (on) "solved" else if (d > today) "not yet" else "not solved"}"
                }
                r.addView(cell, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            c.add(r)
        }
        val streak = prefs.liveStreak(today)
        c.add(body("${solved.size} daily cube${if (solved.size == 1) "" else "s"} solved · $streak-day streak · ${prefs.streakFreezes} streak freeze${if (prefs.streakFreezes == 1) "" else "s"}" +
            if (prefs.dailyBest > 0) " · best ${prefs.dailyBest} on ${dailyDate(prefs.dailyBestDay)}" else "", th.muted), 8)
        c.add(label("By mode"), 22)
        val played = Difficulty.entries.map { it to prefs.stats(it) }.filter { (_, s) -> s[0] + s[1] > 0 }
        if (played.isEmpty()) c.add(body("Finish a level and your record shows up here.", th.muted), 8)
        played.forEach { (d, s) ->
            val r = row().apply { minimumHeight = dp(56) }
            val t = column()
            t.add(title(d.label))
            val avg = if (s[0] > 0) s[3] / s[0] else 0
            t.add(tv(listOfNotNull("${s[0]} won · ${s[1]} lost",
                s[4].takeIf { it > 0 }?.let { "best %d:%02d".format(it / 60, it % 60) }, if (s[0] > 0) "avg %d:%02d".format(avg / 60, avg % 60) else null,
                if (s[0] > 0) "%.1f bumps a win".format(s[2].toFloat() / s[0]) else null).joinToString(" · "), 14f, Fonts.body, th.muted), 2)
            r.add(t, 0, 0, WRAP_CONTENT, 1f)
            r.add(tv(if (s[0] + s[1] > 0) "${100 * s[0] / (s[0] + s[1])}%" else "", 22f, Fonts.display), 0, WRAP_CONTENT)
            c.add(r, 4)
        }
    }

    // ---------- Rankings ----------

    private var ranksMode = 0 // 0 players, 1 best runs, 2 today's daily cube, 3 weekly, 4 badges, 5 friends, 6 endless

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
        var weekBoard: List<ScoreEntry>? = null
        var endlessAll: List<EndlessEntry>? = null
        var endlessToday: List<EndlessEntry>? = null
        var boardStatus = "" // the Players and Best subtitle: board size, offline note or error
        var friendRows: List<Pair<GlobalPlayer, ScoreEntry?>>? = null
        var codeError: String? = null
        lateinit var render: () -> Unit

        fun loadFriends() {
            if (!prefs.postOnline) return
            val codes = prefs.friends + listOfNotNull(prefs.friendCode)
            if (codes.isEmpty()) { friendRows = emptyList(); return }
            Net.friends(codes) { f -> friendRows = f; if (tab == Tab.RANKS && ranksMode == 5) render() }
        }

        /** Friends: your code to share, a box to add theirs, and everyone's level and best run side by side. */
        fun renderFriends() {
            if (!prefs.postOnline) { status.text = "Friends need global rankings. Turn them on to swap codes."; return }
            val mine = prefs.friendCode
            status.text = if (mine == null) codeError?.let { "Couldn't get your friend code ($it). Try again later." } ?: "Getting your friend code…" else "Your friend code: ${mine.chunked(4).joinToString("-")}"
            if (mine != null) list.add(secondary("Share your code", glyph = Glyph.SHARE) {
                shareText("Add me on Cubit: my friend code is ${mine.chunked(4).joinToString("-")}. ${Links.WEB}?f=$mine", "Share your friend code")
            })
            val addRow = row()
            val box = field("", "Friend's code, like AB12-CD34")
            addRow.add(box, 0, 0, WRAP_CONTENT, 1f)
            addRow.add(secondary("Add") {
                val c = Links.cleanCode(box.text.toString())
                if (c == null) toast("A friend code is 8 letters and digits, like AB12-CD34")
                else if (c == mine) toast("That's your own code")
                else { prefs.friends = prefs.friends + c; friendRows = null; render(); loadFriends() }
            }, 0, WRAP_CONTENT).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
            list.add(addRow, 10)
            val rows = friendRows
            if (rows == null) { list.add(body("Loading your friends…", th.muted), 14); return }
            if (prefs.friends.isEmpty()) { list.add(body("No friends yet. Share your code, or add theirs above.", th.muted), 14); return }
            val gone = prefs.friends - rows.map { it.first.code }.toSet()
            rows.sortedByDescending { it.first.xp }.forEachIndexed { i, (pl, best) ->
                val me = pl.code == mine
                val r = row().apply { minimumHeight = dp(60) }
                val rank = FlapView(this, 2, 20f, if (me) th.accent else th.tile, if (me) th.onAccent else th.text, true).apply { haptics = false; setInstant("%02d".format(i + 1)) }
                r.add(rank, 0, WRAP_CONTENT)
                val mid = column().apply { setPadding(dp(14), 0, dp(8), 0) }
                mid.add(tv(if (me) "${pl.name} (you)" else pl.name, 17f, Fonts.bodyMedium))
                mid.add(tv("Player LV ${pl.level} · " + (best?.let { "best ${it.score} · ${it.label}" } ?: "no runs yet"), 13f, Fonts.body, th.muted), 2)
                r.add(mid, 0, 0, WRAP_CONTENT, 1f)
                r.add(tv("${pl.xp} XP", 20f, Fonts.display), 0, WRAP_CONTENT)
                if (!me) r.add(plainIcon(Glyph.CLOSE, "Remove ${pl.name}", th.muted) {
                    confirm("Remove ${pl.name}?", "You can add them again with their code.", "Keep", "Remove") {
                        closeSheet(); prefs.friends = prefs.friends - pl.code; friendRows = friendRows?.filter { it.first.code != pl.code }; render()
                    }
                }, 0, dp(44), dp(44))
                best?.let { e -> r.contentDescription = "${pl.name}, best run ${e.score} points. Show run details"; r.pressable { showRunDetails(e, i + 1) } }
                list.add(r, if (i == 0) 14 else 6)
            }
            if (gone.isNotEmpty()) list.add(body("Not found: ${gone.joinToString { it.chunked(4).joinToString("-") }}. Check the code with your friend.", th.muted), 10)
        }

        fun renderBoard() {
            val online = prefs.postOnline && board != null
            val today = utcDay()
            val runs = when (ranksMode) {
                1 -> if (online) board!!.scores else prefs.scores()
                2 -> if (prefs.postOnline) dailyBoard ?: emptyList() else prefs.scores().filter { it.label.startsWith("Daily " + dailyDate(today)) }
                3 -> if (prefs.postOnline) weekBoard ?: emptyList() else prefs.scores().filter { it.label.startsWith("Week of " + dailyDate(weekStart(utcWeek()))) }
                else -> emptyList()
            }
            // Every tab sets its own subtitle, so switching tabs never leaves another tab's line behind.
            if (ranksMode < 2) status.text = boardStatus
            if (ranksMode == 2) status.text = "Daily cube ${dailyDate(today)} · same cube for everyone" + if (prefs.postOnline) "" else " · this device"
            if (ranksMode == 3) status.text = "Weekly challenge · week of ${dailyDate(weekStart(utcWeek()))}" + if (prefs.postOnline) "" else " · this device"
            val rows: List<Triple<String, String, String>> = if (ranksMode == 0) {
                if (online) board!!.players.map { Triple(it.name, "Player LV ${it.level}" + if (it.cleanRuns > 0) " · ${it.cleanRuns} clean run${if (it.cleanRuns == 1) "" else "s"}" else "", "${it.xp} XP") }
                else listOf(Triple(prefs.name, "Player LV ${playerLevel(prefs.xp).first} · this device", "${prefs.xp} XP"))
            } else {
                runs.map { Triple(it.name, "${it.label} · ${modeName(it.difficulty)}", "${it.score}") }
            }
            if (rows.isEmpty()) {
                list.add(body(if (ranksMode == 2) "Nobody has solved today's cube yet. Be first: More → Daily cube." else if (ranksMode == 3) "Nobody has finished this week's challenge yet. More → Weekly challenge." else "No runs on the board yet. Clear a level to be first.", th.muted), 8)
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

        /** Endless: today's shared run of cubes, then everyone's best endless run. */
        fun renderEndless() {
            if (!prefs.postOnline) {
                status.text = "Endless boards need global rankings. Your best: ${prefs.endlessBest} cube${if (prefs.endlessBest == 1) "" else "s"}."
                return
            }
            status.text = "Cubes cleared in one run · each cube checked by the server"
            fun section(title: String, rows: List<EndlessEntry>?, empty: String) {
                list.add(label(title), if (list.childCount == 0) 0 else 22)
                if (rows == null) { list.add(body("Loading…", th.muted), 8); return }
                if (rows.isEmpty()) { list.add(body(empty, th.muted), 8); return }
                rows.take(50).forEachIndexed { i, e ->
                    val r = row().apply { minimumHeight = dp(56) }
                    r.add(FlapView(this, 2, 20f, if (i < 3) th.accent else th.tile, if (i < 3) th.onAccent else th.text, true).apply { haptics = false; setInstant("%02d".format((i + 1).coerceAtMost(99))) }, 0, WRAP_CONTENT)
                    val mid = column().apply { setPadding(dp(14), 0, dp(8), 0) }
                    mid.add(tv(e.name, 17f, Fonts.bodyMedium)); mid.add(tv("Player LV ${e.level}", 13f, Fonts.body, th.muted), 2)
                    r.add(mid, 0, 0, WRAP_CONTENT, 1f)
                    r.add(tv("${e.cubes}", 22f, Fonts.display), 0, WRAP_CONTENT)
                    r.contentDescription = "${i + 1}: ${e.name}, ${e.cubes} cubes"
                    list.add(r, 6)
                }
            }
            section("Today's endless", endlessToday, "Nobody has played today's endless run yet. More → Today's endless.")
            section("Best endless runs", endlessAll, "No endless runs yet. More → Start an endless run.")
        }

        render = {
            list.removeAllViews()
            if (ranksMode == 4) renderBadges(list, status) else if (ranksMode == 5) renderFriends() else if (ranksMode == 6) renderEndless() else renderBoard()
        }

        fun load() {
            action.removeAllViews()
            if (!prefs.postOnline) {
                boardStatus = "Global rankings are off. You're seeing this device only."
                action.addView(secondary("Turn on global rankings", glyph = Glyph.RANKS) { prefs.postOnline = true; showTab(Tab.RANKS) })
                render(); return
            }
            boardStatus = "Loading the board…"; status.text = boardStatus
            Net.daily(utcDay()) { d -> dailyBoard = d; if (tab == Tab.RANKS && ranksMode == 2) render() }
            Net.weekly(utcWeek()) { w -> weekBoard = w; if (tab == Tab.RANKS && ranksMode == 3) render() }
            Net.endless(null) { e -> endlessAll = e ?: emptyList(); if (tab == Tab.RANKS && ranksMode == 6) render() }
            Net.endless(utcDay()) { e -> endlessToday = e ?: emptyList(); if (tab == Tab.RANKS && ranksMode == 6) render() }
            if (prefs.friendCode == null) Net.me(prefs) { c -> codeError = if (c == null) Net.lastError else null; if (c != null) loadFriends(); if (tab == Tab.RANKS && ranksMode == 5) render() }
            loadFriends()
            Net.fetch { b ->
                if (tab != Tab.RANKS) return@fetch
                board = b
                if (b == null && Net.noNetwork) {
                    boardStatus = "Cubit can't reach the internet. If you're online, the app's network access is probably off: on GrapheneOS, allow the Network permission for Cubit. Showing this device for now."
                    action.removeAllViews()
                    val btns = column()
                    btns.add(secondary("Open app permissions", glyph = Glyph.SETTINGS) {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
                    })
                    btns.add(secondary("Retry", glyph = Glyph.REPLAY) { load() }, 8)
                    action.addView(btns)
                } else if (b == null) {
                    boardStatus = "Can't reach the board (${Net.lastError}). Showing this device."
                    action.removeAllViews(); action.addView(secondary("Retry", glyph = Glyph.REPLAY) { load() })
                } else boardStatus = if (b.total > b.players.size) "Global board · top ${b.players.size} of ${b.total} players" else "Global board · ${b.total} players"
                render()
            }
        }

        col.add(segmented(listOf("Players", "Best", "Daily", "Week", "Badges", "Friends", "Endless"), ranksMode) { ranksMode = it; render() }, 16)
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
        profile.add(switchRow("Global rankings", "Post your name, level and scores to the shared Cubit board. Off keeps everything on this phone.", prefs.postOnline) {
            prefs.postOnline = it
            if (!it && prefs.publicProfile) { prefs.publicProfile = false; Net.setProfile(prefs, false); showTab(Tab.SETTINGS) } // no board, no public page
        }, 4)
        profile.add(switchRow("Public profile", "A page anyone with your link can see: name, level, badges, streak and best runs. There's no account or sign-in: it hangs off your friend code, and switching it off takes it down.", prefs.publicProfile) { on ->
            prefs.publicProfile = on
            if (on) prefs.postOnline = true
            Net.setProfile(prefs, on) { res -> if (res == null) toast("Couldn't reach the board (${Net.lastError}). Try the switch again later.") }
            showTab(Tab.SETTINGS)
        }, 4)
        if (prefs.publicProfile) profile.add(secondary("Share my profile", glyph = Glyph.SHARE) {
            fun share(code: String) = shareText("My Cubit profile: ${Links.PROFILE}?c=$code", "Share your profile")
            prefs.friendCode?.let { share(it) } ?: Net.me(prefs) { c -> if (c != null) share(c) else toast("Couldn't reach the board (${Net.lastError})") }
        }, 10)

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
            val lock = THEME_UNLOCKS[i]?.takeIf { it.name !in prefs.achievements && i != prefs.themeIndex }
            if (lock != null) {
                plate.alpha = 0.35f
                item.contentDescription = "${t.name} colourway, locked. Earn the ${lock.title} badge."
                item.pressable { toast("Earn the \"${lock.title}\" badge to unlock ${t.name}: ${lock.blurb}") }
            } else item.pressable { prefs.themeIndex = i; buildShell() }
            ways.add(item, 0, 0, WRAP_CONTENT, 1f)
        }
        repeat((4 - THEMES.size % 4) % 4) { ways.space(0, 1f) } // keep the last row's plates the same width
        look.add(ways, 12)
        look.add(tv("Faded colourways open as you earn badges.", 13f, Fonts.body, th.muted), 8)

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
        val contrast = THEMES.indexOfFirst { it.name == "Contrast" }
        access.add(switchRow("High contrast", "The Contrast colourway: black and white, with line colours that stay distinct for colour-blind players.", prefs.themeIndex == contrast) {
            // The stripe marks the target without relying on pink, which matters most for colour-blind players.
            prefs.themeIndex = if (it) contrast else 0; if (it) prefs.markTarget = true; openSections.add("Accessibility"); buildShell()
        })
        access.add(body("Arrow weight in Look goes up to 14 for small screens. Cubit also follows the system's Remove animations setting.", th.muted), 6)

        val feel = section(col, "Sound and touch", Glyph.HINT)
        feel.add(switchRow("Sound effects", "Swooshes, bumps, flap boards and the fanfare when the pink arrow gets out.", prefs.sound) { prefs.sound = it; Sfx.enabled = it }, 4)
        feel.add(switchRow("Music", "A quiet loop under the menus and a slightly busier one while you play.", prefs.music) { prefs.music = it; Music.enabled = it })
        val volFlap = flap(3, 18f).apply { setInstant("%3d".format(prefs.musicVolume)) }
        val volRow = row(); volRow.add(label("Music volume"), 0, 0, WRAP_CONTENT, 1f); volRow.add(volFlap, 0, WRAP_CONTENT)
        feel.add(volRow, 12)
        feel.add(slider(0, 100, prefs.musicVolume) { prefs.musicVolume = it; Music.level = it / 100f; volFlap.text = "%3d".format(it) }.apply { contentDescription = "Music volume" }, 4)
        feel.add(switchRow("Streak reminder", "One notification around 7 pm if your daily streak would end tonight. Nothing else.", prefs.streakReminder) { on ->
            prefs.streakReminder = on
            if (on && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            ReminderReceiver.schedule(this, on)
        })
        feel.add(switchRow("Vibration", "Taps, slides, bumps, powerups and the win, felt as well as heard.", prefs.haptics) { prefs.haptics = it; Haptics.enabled = it })

        val play = section(col, "Powerups", Glyph.SHIELD)
        play.add(body("One of each per level. Each only shows up where it helps: no Freeze without a timer.", th.muted), 8)
        powerupSwitches(play)

        val progress = section(col, "Progress", Glyph.REPLAY)
        progress.add(body("No ads, ever. Rankings only talk to the Cubit board when you turn them on.", th.muted), 8)
        progress.add(secondary("Run setup again", glyph = Glyph.SETTINGS) { showSetup() }, 14)
        progress.add(secondary("Copy a backup code", glyph = Glyph.SHARE) {
            val code = prefs.backup()
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Cubit backup", code))
            toast("Backup code copied. Keep it private: it also moves your place on the board.")
        }, 10)
        progress.add(secondary("Restore from a backup code", glyph = Glyph.REPLAY) { restoreSheet() }, 10)
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
        if (BuildConfig.UPDATE_CHECK) {
            checkButton()
            about.add(switchRow("Check automatically", "Once a day, ask GitHub for a newer version and show a small card on the Play screen.", prefs.autoUpdate) { prefs.autoUpdate = it }, 4)
        } else updateBox.add(body("Updates come through the app store you installed Cubit from.", th.muted))
        about.add(secondary("Website", glyph = Glyph.ARROW) { open("https://taynotfound.github.io/cubit/") }, 10)
        about.add(secondary("Cubit on F-Droid", glyph = Glyph.ARROW) { open("https://f-droid.org/en/packages/de.taymaerz.cubit/") }, 8)
        about.add(secondary("Made by Tay März", glyph = Glyph.ARROW) { open("https://taymaerz.de") }, 8)
        about.add(secondary("Source code and licences", glyph = Glyph.ARROW) { open("https://github.com/taynotfound/cubit") }, 8)
        about.add(secondary("Changelog", glyph = Glyph.ARROW) { open("https://github.com/taynotfound/cubit/blob/main/CHANGELOG.md") }, 8)
        about.add(label("Support Cubit"), 22)
        about.add(body("Cubit is free with no ads. If you enjoy it, you can chip in. It keeps the leaderboard server running.", th.muted), 6)
        val support = row()
        support.add(secondary("Ko-fi", glyph = Glyph.FLAME) { open("https://ko-fi.com/taygotkofi") }, 0, 0, WRAP_CONTENT, 1f)
        support.add(secondary("Sponsors", glyph = Glyph.BADGE) { open("https://github.com/sponsors/taynotfound") }, 0, 0, WRAP_CONTENT, 1f)
            .let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
        about.add(support, 10)

        // Always visible, even with every section folded.
        col.add(label("Cubit $appVersion · by Tay März · MIT").apply { gravity = Gravity.CENTER; contentDescription = "Cubit $appVersion by Tay März, taymaerz.de"; pressable { open("https://taymaerz.de") } }, 22)
        return scroll
    }

    /** Paste a backup code from [Prefs.backup] (another phone, or before a reinstall) and replace everything here with it. */
    private fun restoreSheet() {
        val c = openSheet()
        c.add(display("Restore a backup", 30f))
        c.add(body("Everything on this phone (progress, settings and your place on the board) is replaced by the backup.", th.muted), 8)
        val box = field("", "Paste the code (CUBIT1:…)")
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
            ?.takeIf { it.startsWith("CUBIT1:") }?.let { box.setText(it) }
        c.add(box, 16)
        c.add(primary("Restore") {
            if (prefs.restore(box.text.toString())) { toast("Restored"); closeSheet(); recreate() }
            else toast("That code doesn't read. Copy it again, all of it.")
        }, 16)
        c.add(secondary("Cancel") { closeSheet() }, 10)
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
                    // Nightmare isn't offered to newcomers: it's in Mode on the Play tab once they know the game.
                    val modes = Difficulty.entries.filter { it != Difficulty.NIGHTMARE }
                    pageCol.add(segmented(modes.map { it.label }, modes.indexOf(prefs.difficulty).coerceAtLeast(0)) {
                        prefs.difficulty = modes[it]; blurb.text = prefs.difficulty.blurb
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
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(side(), 0, side(), dp(12)) })
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
            e.spec != null && e.moves.isNotEmpty() -> c.add(primary("Watch replay", Glyph.REPLAY) { showReplay(e.spec, e.moves, who, e.label.endsWith("full clear")) }, 18)
            e.run.isNotEmpty() -> c.add(primary("Watch replay", Glyph.REPLAY) {
                Net.replay(e.run) { r -> if (r == null) toast("No replay for this run. Runs keep their taps from this version on.") else showReplay(r.first, r.second, who, r.third) }
            }, 18)
        }
        c.add(secondary("Close") { root.removeView(scrim) }, 10)
        scrim.setOnClickListener { root.removeView(scrim) }
        scrim.addView(ScrollView(this).apply { addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(side(), 0, side(), dp(12)) })
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (motionEnabled()) { c.translationY = dp(80).toFloat(); c.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2.5f)).start() }
        flaps.forEachIndexed { i, (f, txt) -> handler.postDelayed({ f.text = txt }, 200L + i * 160L) }
    }

    private val replayToken = Any()

    /** Plays a run's recorded taps back on its own cube. Long pauses are cut to 1.5 s so replays stay watchable. */
    private fun showReplay(s: LevelSpec, mv: List<LongArray>, title: String, full: Boolean = false) {
        if (!PuzzleCache.has(s)) { whenBuilt(s) { showReplay(s, mv, title, full) }; return } // someone else's big cube: build it off the main thread
        closeReplay()
        val layer = FrameLayout(this).apply { tag = "replay"; setBackgroundColor(th.bg); isClickable = true }
        val gv = GameView(this).apply {
            applyStyle(this); load(PuzzleCache.get(s)); inputEnabled = false; pinkLast = full
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
        bar.add(iconButton(Glyph.REPLAY, "Watch again") { showReplay(s, mv, title, full) }, 0, dp(48), dp(48))
        layer.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Transport: play/pause, speed, and a scrub bar over the taps. Seeking rebuilds the cube up to that tap.
        var at = 0; var paused = false; var speed = 1
        val controls = column().apply { setBackgroundColor(th.panel); setPadding(dp(16), dp(8), dp(16), dp(16)) }
        val scrub = SeekBar(this).apply {
            max = mv.size; minimumHeight = dp(48); contentDescription = "Replay position"
            progressTintList = ColorStateList.valueOf(th.accent); thumbTintList = ColorStateList.valueOf(th.accent)
            progressBackgroundTintList = ColorStateList.valueOf(th.hairline)
        }
        lateinit var step: Runnable
        fun schedule(delay: Long) { handler.removeCallbacksAndMessages(replayToken); if (!paused && at < mv.size) handler.postAtTime(step, replayToken, SystemClock.uptimeMillis() + delay / speed) }
        val pauseBtn = secondary("Pause") {}
        fun paintPause() { (pauseBtn.getChildAt(pauseBtn.childCount - 1) as? TextView)?.text = if (paused || at >= mv.size) "PLAY" else "PAUSE" }
        step = Runnable {
            val a = mv[at][0].toInt()
            if (a >= 0) gv.playTap(a) else if (a == UNDO_TAP) gv.undoLast() else if (a == Powerup.SCAN.code) gv.scan()
            at++; scrub.progress = at
            if (at < mv.size) schedule((mv[at][1] - mv[at - 1][1]).coerceIn(0L, 1500L)) else paintPause() // long pauses cut to 1.5 s
        }
        pauseBtn.setOnClickListener {
            Sfx.click()
            if (at >= mv.size) { showReplay(s, mv, title, full); return@setOnClickListener } // finished: play from the top
            paused = !paused; paintPause(); schedule(300)
        }
        val speedBtn = secondary("1×") {}
        speedBtn.setOnClickListener { Sfx.click(); speed = if (speed == 1) 2 else 1; (speedBtn.getChildAt(speedBtn.childCount - 1) as? TextView)?.text = "$speed×"; schedule(300) }
        scrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                handler.removeCallbacksAndMessages(replayToken)
                gv.load(PuzzleCache.get(s)); gv.inputEnabled = false; gv.applyInstant(mv.take(p)); gv.settle()
                at = p; paintPause()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { schedule(400) }
        })
        controls.add(scrub)
        val btns = row()
        btns.add(pauseBtn, 0, 0, WRAP_CONTENT, 1f)
        btns.add(speedBtn, 0, dp(96)).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
        controls.add(btns, 4)
        layer.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (mv.isNotEmpty()) schedule(900 + mv[0][1].coerceIn(0L, 1500L))
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
            val link = dailyDay?.let { " Same cube for you: https://taynotfound.github.io/cubit/open.html?daily=$it" }
                ?: weekNo?.let { " Same cube for you: https://taynotfound.github.io/cubit/open.html?week=$it" }
                ?: " Race my ghost on the same cube: ${Links.WEB}?c=${Links.challengePayload(Challenge(spec, roundFull, diff, prefs.name, moves))}"
            putExtra(android.content.Intent.EXTRA_TEXT, "${if (roundFull) "I cleared a whole cube" else "I freed the pink arrow"} in Cubit: $score points.$link")
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

    /** Leaves endless and the archive behind: every way into a round but those two starts here. */
    private fun plainRound() { endless = -1; archiveDay = null; endlessDay = null; endlessId = null }

    private fun endlessLabel(k: Int) = (if (endlessDay != null) "Daily endless " else "Endless ") + (k + 1)

    /** A new endless run. Ranked ones get their seed (and id) from the server, which then counts the cubes; offline
     *  ones pick their own. A daily one has the day's seed either way, so it's the same cubes for everyone. */
    private fun startEndless(daily: Boolean = false) {
        freeRoam = true; dailyDay = null; weekNo = null; packRun = null; tutorial = false; challenge = null; archiveDay = null
        endless = 0; carryLives = -1; carryBonus = 0; endlessId = null
        endlessDay = if (daily) utcDay() else null
        endlessSeed = if (daily) dailyEndlessSeed(utcDay()) else Random.nextLong()
        if (!prefs.postOnline) { startRound(endlessSpec(endlessSeed, 0), endlessLabel(0)); return }
        toast("Starting your endless run…")
        Net.endlessStart(prefs, daily) { r ->
            if (r != null) { endlessId = r.first; endlessSeed = r.second } // the server's seed, so it can check every cube
            if (endless == 0 && gameLayer == null) startRound(endlessSpec(endlessSeed, 0), endlessLabel(0))
        }
    }

    /** Between endless cubes: pick one thing to take into the next. */
    private fun endlessShop() {
        val o = hud?.sheet ?: return
        o.removeAllViews()
        val c = column().apply { background = rect(th.panel, 10); setPadding(dp(22), dp(24), dp(22), dp(22)) }
        c.add(display("Pick one for cube ${endless + 1}", 30f))
        c.add(body("It lasts for the next cube only, except the hint, which you keep.", th.muted), 8)
        fun next() { startRound(endlessSpec(endlessSeed, endless), endlessLabel(endless)) }
        if (diff.lives > 0) c.add(primary("+1 life (${livesLeft + 1} in all)", Glyph.LIFE) { carryLives = livesLeft + 1; next() }, 18)
        if (diff.secPerArrow > 0) c.add(secondary("+15 seconds on the clock", glyph = Glyph.FREEZE) { carryLives = livesLeft; carryBonus = 15; next() }, 10)
        c.add(secondary("+1 hint (you have ${prefs.hints})", glyph = Glyph.HINT) { carryLives = livesLeft; prefs.hints = prefs.hints + 1; next() }, 10)
        o.addView(ScrollView(this).apply { addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(side(), 0, side(), dp(12)) })
        o.visibility = View.VISIBLE
    }
    private fun startArchive(d: Int) {
        freeRoam = true; dailyDay = null; weekNo = null; packRun = null; tutorial = false; challenge = null; endless = -1; archiveDay = d
        startRound(dailySpec(d), "Daily ${dailyDate(d)}")
    }

    private fun startCampaign() { plainRound(); freeRoam = false; dailyDay = null; weekNo = null; packRun = null; tutorial = false; challenge = null; startRound(specForLevel(prefs.level), "Level ${prefs.level}") }
    private fun startFree(s: LevelSpec, label: String = "Free ${s.n}×${s.n}") { plainRound(); freeRoam = true; dailyDay = null; weekNo = null; packRun = null; tutorial = false; challenge = null; startRound(s, label) }
    private fun startDaily() { plainRound(); val d = utcDay(); freeRoam = false; dailyDay = d; weekNo = null; packRun = null; tutorial = false; challenge = null; startRound(dailySpec(d), "Daily " + dailyDate(d)) }
    private fun startWeekly() { plainRound(); val w = utcWeek(); freeRoam = false; dailyDay = null; weekNo = w; packRun = null; tutorial = false; challenge = null; startRound(weekSpec(w), "Week of " + dailyDate(weekStart(w))) }
    private fun startPack(p: Pack, i: Int) { plainRound(); freeRoam = true; dailyDay = null; weekNo = null; packRun = p to i; tutorial = false; challenge = null; startRound(p.levels[i], "${p.name} ${i + 1}/${p.levels.size}") }

    /** A tiny 3×3 cube with a coach line that reacts to what you do. Zen rules, nothing ranked or saved. */
    private fun startTutorial() { plainRound(); freeRoam = true; dailyDay = null; weekNo = null; packRun = null; tutorial = true; challenge = null; startRound(LevelSpec(3, 3, 4242), "How to play") }

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
            .put("n", spec.n).put("maxLen", spec.maxLen).put("seed", spec.seed).put("special", spec.special).put("endless", endless).put("eseed", endlessSeed).put("eday", endlessDay ?: -1).put("eid", endlessId ?: "").put("archive", archiveDay ?: -1)
            .put("label", baseLabel).put("free", freeRoam).put("daily", dailyDay ?: -1).put("week", weekNo ?: -1).put("diff", diff.ordinal).put("full", roundFull)
            .put("elapsed", elapsedMillis()).put("mistakes", mistakes).put("lives", livesLeft).put("limit", timeLimit)
            .put("shield", shield).put("used", org.json.JSONArray(usedPowerups.map { it.name })).put("run", runId ?: "")
            .put("left", g.remaining).put("level", prefs.level)
            .apply { packRun?.let { (pk, i) -> put("pack", pk.name).put("packIdx", i) }; challenge?.let { put("challenge", Links.challengePayload(it)) } }
            .put("moves", org.json.JSONArray().apply { moves.forEach { put(org.json.JSONArray().put(it[0]).put(it[1])) } })
        prefs.savedRun = o.toString()
    }

    private fun resumeRun(o: org.json.JSONObject) {
        freeRoam = o.optBoolean("free"); dailyDay = o.optInt("daily", -1).takeIf { it >= 0 }; weekNo = o.optInt("week", -1).takeIf { it >= 0 }; tutorial = false
        challenge = o.optString("challenge").takeIf { it.isNotEmpty() }?.let { Links.parseChallenge(java.net.URLDecoder.decode(it, "UTF-8")) }
        packRun = prefs.allPacks().firstOrNull { it.name == o.optString("pack") }?.let { pk -> o.optInt("packIdx", -1).takeIf { it in pk.levels.indices }?.let { pk to it } }
        endless = o.optInt("endless", -1); endlessSeed = o.optLong("eseed"); endlessDay = o.optInt("eday", -1).takeIf { it >= 0 }; endlessId = o.optString("eid").ifEmpty { null }; archiveDay = o.optInt("archive", -1).takeIf { it >= 0 }
        startRound(LevelSpec(o.getInt("n"), o.getInt("maxLen"), o.getLong("seed"), special = o.optBoolean("special")), o.optString("label"), o)
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

    private fun startRound(base: LevelSpec, label: String, resume: org.json.JSONObject? = null) {
        val mode = if (tutorial) Difficulty.ZEN else resume?.let { Difficulty.entries.getOrNull(it.optInt("diff")) } ?: challenge?.mode ?: prefs.difficulty
        // Nightmare plays the same level on a lap cube; every other mode on the normal one, whatever spec came in.
        val s = base.copy(lap = mode == Difficulty.NIGHTMARE)
        if (!PuzzleCache.has(s)) { whenBuilt(s) { startRound(s, label, resume) }; return }
        stopRound()
        gameLayer?.let { root.removeView(it) }
        shell.visibility = View.GONE
        if (resume == null && !tutorial) prefs.savedRun = null // a new level replaces any saved one
        diff = mode
        roundFull = if (tutorial) false else resume?.optBoolean("full") ?: challenge?.full ?: prefs.fullClear
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
        lvlBox.add(label(if (endless >= 0) "Endless" else if (tutorial) "How to play" else if (challenge != null) "Challenge" else if (dailyDay != null) "Daily" else if (freeRoam) "Free roam" else "Level")); lvlBox.add(levelFlap, 4, WRAP_CONTENT)
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
        if (diff != Difficulty.NIGHTMARE) tray.add(hintBtn, 0, WRAP_CONTENT, dp(48)) // Nightmare: no hints, no powerups
        powerupBtns.clear(); usedPowerups.clear(); shield = false
        Powerup.entries.filter { prefs.powerupOn(it) && it.appliesTo(diff) }.forEach { pu ->
            val b = iconButton(pu.glyph, "Use ${pu.label}: ${pu.blurb}") { usePowerup(pu); tray.visibility = View.GONE }
            (b.getChildAt(0) as IconView).color = th.accent
            tray.add(b, 0, dp(48), dp(48)).let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
            powerupBtns[pu] = b
        }
        ui.add(tray)
        if (tray.childCount == 0) boost.visibility = View.GONE

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
        bottom.add(iconButton(Glyph.RECENTER, "Reset camera, or find the last arrows") { game?.let { if (it.remaining <= 6 && diff != Difficulty.NIGHTMARE) it.findNext() else it.resetView() } }, 0, dp(48), dp(48))
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
        livesLeft = if (carryLives > 0) carryLives else diff.lives; mistakes = 0; carryLives = -1
        val bonus = carryBonus; carryBonus = 0; maxBlast = 0; maxChain = 0; combo = 0; bestCombo = 0
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // no dimming mid-level
        lamps?.total = maxOf(diff.lives, livesLeft)
        moves.clear(); runId = null
        val serial = ++roundSerial
        val eid = endlessId
        if (prefs.postOnline && !tutorial && resume == null && (endless < 0 || eid != null))
            Net.startRun(prefs, s, if (freeRoam || weekNo != null) null else prefs.level, dailyDay, diff.label, roundFull, weekNo,
                eid?.let { EndlessCube(it, if (diff.lives > 0) livesLeft else 0, bonus) }) { id -> if (serial == roundSerial) runId = id }
        timeLimit = diff.timeLimitSec(puzzle.paths.size) + (if (diff.secPerArrow > 0) bonus else 0)
        par = if (roundFull) puzzle.paths.size else mustClear(puzzle) + 1
        // Ghost race on the daily and weekly cubes: race the leader's recorded run, by the same game clock.
        ghost = emptyList(); ghostAt = 0; ghostDoneMs = -1; ghostLabel = null
        val ch = challenge
        // Your own best run on this exact cube and goal (kept with local scores), raced when nothing else is.
        val pastDay = archiveDay // a past daily from the archive races that day's leader too
        val mine = if (ch != null || tutorial || dailyDay != null || weekNo != null || pastDay != null) null
            else prefs.scores().filter { it.spec == s && it.moves.isNotEmpty() && it.label.endsWith("full clear") == roundFull }.maxByOrNull { it.score }
        ownGhost = mine != null
        if (ch != null || mine != null || (prefs.postOnline && !roundFull && (dailyDay != null || weekNo != null || pastDay != null))) {
            val g = row().apply { visibility = View.GONE }
            val gl = label("", th.muted)
            g.add(gl, 0, WRAP_CONTENT)
            lampsRow.space(0, 1f); lampsRow.add(g, 0, WRAP_CONTENT)
            ghostLabel = gl
            val board: (List<ScoreEntry>?) -> Unit = { b ->
                // Race someone on the same cube: Nightmare runs are on the lap cube, every other mode on the normal one.
                val lead = b?.firstOrNull { it.run.isNotEmpty() && it.name != prefs.name && (it.difficulty == Difficulty.NIGHTMARE.label) == s.lap }
                if (lead != null && serial == roundSerial) Net.replay(lead.run) { r ->
                    if (r == null || serial != roundSerial || r.first != s) return@replay
                    ghost = r.second; ghostName = lead.name; gv.ghostOn = true; g.visibility = View.VISIBLE; updateHud()
                }
            }
            // A shared challenge brings its own ghost; the daily and weekly ones race the board's leader.
            if (ch != null) { ghost = ch.moves; ghostName = ch.name; gv.ghostOn = true; g.visibility = View.VISIBLE }
            else if (mine != null) { ghost = mine.moves; ghostName = "Your best"; gv.ghostOn = true; g.visibility = View.VISIBLE }
            else if (weekNo != null) Net.weekly(weekNo!!, board) else Net.daily(dailyDay ?: pastDay!!, board)
        }
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
        if (demoOn) handler.postDelayed(demoStep, 1600) // the demo starts tapping once the cube has spun in
        if (!prefs.bombSeen && (puzzle.bombs.any { it } || puzzle.keys.any { it >= 0 } || puzzle.twoWay.any { it })) {
            prefs.bombSeen = true
            toast("New arrows: an orange core is a bomb (it takes the arrows touching it along), a dashed core is locked until its key leaves, and two heads means it can leave either way.")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            advanceGhost()
            updateHud()
            if (timeLimit > 0 && elapsedSec() >= timeLimit) lose("Time's up") else handler.postDelayed(this, 250)
        }
    }

    private fun advanceGhost() {
        val g = game ?: return
        val now = elapsedMillis()
        while (ghostAt < ghost.size && ghost[ghostAt][1] <= now) {
            val m = ghost[ghostAt++]
            if (g.ghostTap(m[0].toInt()) && ghostDoneMs < 0) ghostDoneMs = m[1]
        }
        ghostLabel?.text = when {
            ghost.isEmpty() -> ""
            ghostDoneMs >= 0 -> "$ghostName freed it at %d:%02d".format(ghostDoneMs / 60000, ghostDoneMs / 1000 % 60)
            else -> if (ownGhost) "Ghost: your best" else "Ghost: $ghostName"
        }
    }

    private fun elapsedMillis() = elapsedMs + (if (running) SystemClock.uptimeMillis() - resumedAt else 0)
    private fun elapsedSec() = elapsedMillis() / 1000f
    private fun pauseClock() { if (running) { elapsedMs += SystemClock.uptimeMillis() - resumedAt; resumedAt = SystemClock.uptimeMillis() } }
    private fun stopRound() {
        pauseClock(); running = false; handler.removeCallbacks(tick); handler.removeCallbacks(demoStep); game = null; hud = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

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
        if (!running || diff == Difficulty.NIGHTMARE) return
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
        val now = SystemClock.uptimeMillis()
        combo = if (now - lastClearAt < 2500) combo + 1 else 1
        lastClearAt = now; bestCombo = maxOf(bestCombo, combo)
        if (combo >= 3 && diff != Difficulty.CLEAN && !tutorial && running) showCombo(combo)
        if (tutorial) coachSay(if (total - left >= 3) "Now find the pink arrow. Clear its way out and tap it to win." else "That's it. Keep clearing. Drag to turn the cube, pinch to zoom.")
    }

    /** "×5" floating up over the cube; every fifth one is bigger and gets a tick you can feel. */
    private fun showCombo(n: Int) {
        val layer = gameLayer ?: return
        val big = n % 5 == 0
        val t = display("×$n", if (big) 64f else 44f, if (big) PINK else th.accent).apply {
            gravity = Gravity.CENTER; contentDescription = "Combo $n"; setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), withAlpha(Color.BLACK, 140))
        }
        layer.addView(t, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = (layer.height * 0.22f).toInt() })
        if (big) Haptics.double()
        if (!motionEnabled()) { handler.postDelayed({ layer.removeView(t) }, 700); return }
        t.scaleX = 0.6f; t.scaleY = 0.6f
        t.animate().scaleX(1f).scaleY(1f).translationY(-dp(40).toFloat()).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).withEndAction {
            t.animate().alpha(0f).translationY(-dp(70).toFloat()).setDuration(420).withEndAction { layer.removeView(t) }.start()
        }.start()
    }

    override fun onTap(arrow: Int) { if (running) moves.add(longArrayOf(arrow.toLong(), elapsedMillis())) }

    override fun onTwoFingerTap() { if (diff.lives == 0) undoTap() }

    // A heavy thud you can feel, and the chime rather than the bump sound: a blast is good news, not a mistake.
    override fun onBomb(count: Int, bombs: Int) {
        Sfx.hint(); Haptics.bump()
        maxBlast = maxOf(maxBlast, count); maxChain = maxOf(maxChain, bombs)
        if (bombs > 1) toast("Chain reaction · $bombs bombs, $count arrows")
    }

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
        if (demoOn) android.util.Log.i("CubitDemo", "win") // promo audio is laid on from these
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
        combo = 0
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
        if (prefs.postOnline && prefs.publicProfile) handler.postDelayed({ Net.setProfile(prefs, true) }, 3000) // after this run's badges and streak land
        if (endless >= 0) {
            endless++
            val d = endlessDay
            if (d == null) { if (endless > prefs.endlessBest) prefs.endlessBest = endless }
            else if (prefs.dailyEndlessDay != d || endless > prefs.dailyEndlessBest) { prefs.dailyEndlessDay = d; prefs.dailyEndlessBest = endless }
        }
        archiveDay?.let { prefs.archiveDays = prefs.archiveDays + it }
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
        // Hints are earned by playing well: a perfect run on a mode with lives, and each day's first daily solve
        // (replaying an already solved daily cube would otherwise farm unlimited hints).
        val firstDaily = dailyDay != null && prefs.dailyDone != dailyDay
        val bonusHints = (if (perfect && diff.lives > 0) 1 else 0) + (if (firstDaily) 1 else 0)
        prefs.hints = prefs.hints + bonusHints
        prefs.solved = prefs.solved + 1
        var freezesUsed = 0
        dailyDay?.let { d ->
            val missed = d - 1 - prefs.dailyDone // whole days skipped since the last daily solve
            when {
                prefs.dailyDone == d -> {}
                missed == 0 -> prefs.dailyStreak = prefs.dailyStreak + 1
                missed in 1..prefs.streakFreezes -> { freezesUsed = missed; prefs.streakFreezes = prefs.streakFreezes - missed; prefs.dailyStreak = prefs.dailyStreak + 1 }
                else -> prefs.dailyStreak = 1
            }
            prefs.dailyDone = d
            prefs.dailyDays = prefs.dailyDays + d
        }
        val dailyBest = dailyDay != null && score > prefs.dailyBest
        if (dailyBest) { prefs.dailyBest = score; prefs.dailyBestDay = dailyDay!! }
        if (dailyDay == null && weekNo == null && !freeRoam) prefs.level = prefs.level + 1
        // The first finish of each week's challenge earns a streak freeze.
        val freezeEarned = weekNo != null && prefs.weeklyDone != weekNo && prefs.streakFreezes < MAX_FREEZES
        if (freezeEarned) prefs.streakFreezes = prefs.streakFreezes + 1
        weekNo?.let { prefs.weeklyDone = it }
        prefs.recordRun(diff, true, mistakes, elapsedSec().toInt())
        packRun?.let { (pk, i) -> if (prefs.packSolved(pk) <= i) prefs.setPackSolved(pk, i + 1) }
        val taps = moves.count { it[0] >= 0 }
        val atPar = taps <= par
        if (atPar) prefs.parWins = prefs.parWins + 1
        prefs.totalArrows = prefs.totalArrows + cleared
        prefs.noPowerupStreak = if (usedPowerups.isEmpty()) prefs.noPowerupStreak + 1 else 0
        if (diff == Difficulty.CLEAN) prefs.cleanSolved = prefs.cleanSolved + 1
        val ms = elapsedMillis()
        val facts = RunFacts(diff, perfect, cleared, ms, spec.n, dailyDay != null, usedPowerups.isNotEmpty(), atPar, weekNo != null, maxBlast, maxChain, maxOf(endless, 0))
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
            "Taps / par" to (flap(7, 26f, right = true) to "%d/%d".format(taps.coerceAtMost(999), par.coerceAtMost(999))),
            "Global rank" to (rankFlap to if (prefs.postOnline) "----" else "OFF"),
        )
        val extra = buildList {
            add(if (perfect) "Perfect run · +25% XP" else "$mistakes mistake${if (mistakes == 1) "" else "s"}")
            if (after > before) add("Level up · you're now LV $after · +${after - before} hint")
            if (bonusHints > 0) add("+$bonusHints hint for ${if (firstDaily && perfect && diff.lives > 0) "a perfect daily" else if (firstDaily) "the daily cube" else "a perfect run"}")
            if (endless > 0) add((if (endlessDay != null) "Daily endless" else "Endless") + " · $endless cube${if (endless == 1) "" else "s"} cleared · your best is ${if (endlessDay != null) prefs.dailyEndlessBest else prefs.endlessBest}" +
                if (diff.lives > 0) " · $livesLeft ${if (livesLeft == 1) "life" else "lives"} carry over" else "")
            if (archiveDay != null) add("Marked solved in the daily archive")
            if (atPar) add("On par · nobody can do it in fewer taps")
            if (bestCombo >= 5) add("Best combo ×$bestCombo")
            if (dailyBest) add("Your best daily score yet")
            if (freezesUsed > 0) add("A streak freeze covered $freezesUsed missed day${if (freezesUsed == 1) "" else "s"} · ${prefs.dailyStreak}-day streak")
            if (freezeEarned) add("Streak freeze earned · it covers one missed daily cube (you hold ${prefs.streakFreezes})")
            unlocked.forEach { a ->
                add("Badge earned · ${a.title}: ${a.blurb}")
                THEME_UNLOCKS.entries.firstOrNull { it.value == a }?.let { add("Unlocked the ${THEMES[it.key].name} colourway in Settings") }
            }
            if (ghost.isNotEmpty()) add(when {
                ownGhost -> if (ghostDoneMs in 1 until ms) "Your best run was faster" else "Faster than your best run"
                ghostDoneMs in 1 until ms -> "$ghostName was faster this time"
                else -> "You beat $ghostName's run"
            })
        }
        if (unlocked.isNotEmpty()) handler.postDelayed({ Sfx.hint(); Haptics.double() }, 1400)
        val shareLine = "${roundLabel} · ${modeName(diff.label)}"
        val pk = packRun
        val nextText = when {
            endless > 0 -> "Next cube"
            pk != null -> if (pk.second + 1 < pk.first.levels.size) "Next in ${pk.first.name}" else "Play level ${prefs.level}"
            freeRoam -> "Another cube"
            dailyDay != null || weekNo != null -> "Play level ${prefs.level}"
            else -> "Next level"
        }
        showSheet(if (endless > 0) "Cube $endless cleared" else if (roundFull) "Cube cleared" else "Pink arrow freed", rows, extra, nextText,
            onShare = { shareCard(shareLine, score, t, cleared, perfect) },
            extra = ("Save cube to $MY_PACK" to { prefs.myPack = prefs.myPack.filter { it != spec.copy(lap = false) } + spec.copy(lap = false); toast("Saved to $MY_PACK in More → Puzzle packs") })
                .takeIf { dailyDay == null && weekNo == null && packRun?.first?.name != MY_PACK }) {
            when {
                endless > 0 -> endlessShop()
                pk != null && pk.second + 1 < pk.first.levels.size -> startPack(pk.first, pk.second + 1)
                pk == null && freeRoam -> startFree(LevelSpec(spec.n, spec.maxLen, Random.nextLong()))
                else -> startCampaign()
            }
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
        if (!tutorial) prefs.recordRun(diff, false, mistakes, elapsedSec().toInt())
        if (endless >= 0) {
            val n = endless
            val top = if (endlessDay != null) prefs.dailyEndlessBest else prefs.endlessBest
            val best = n > 0 && n >= top
            showSheet("Endless over", listOf("Cubes" to (flap(3, 26f, right = true) to "%d".format(n.coerceAtMost(999)))),
                listOf("$reason on cube ${n + 1}.", if (best) "Your best endless run yet" else "Your best: $top cube${if (top == 1) "" else "s"}"),
                if (endlessDay != null) "Try today's again" else "New endless run") { startEndless(daily = endlessDay != null) }
            return
        }
        showSheet(reason, rows, listOf((if (roundFull) "The cube isn't clear yet." else "The pink arrow is still stuck.") + " Easier modes give more lives and time."), "Try again") { startRound(spec, baseLabel) }
    }

    private fun showSheet(title: String, rows: List<Pair<String, Pair<FlapView, String>>>, notes: List<String>, primaryText: String, onShare: (() -> Unit)? = null,
                          extra: Pair<String, () -> Unit>? = null, onPrimary: () -> Unit) {
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
        extra?.let { (text, act) -> lateinit var b: View; b = secondary(text, glyph = Glyph.BADGE) { act(); b.alpha = 0.4f; b.isEnabled = false }; c.add(b, 10) }
        o.addView(ScrollView(this).apply { addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(side(), 0, side(), dp(12)) })
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
