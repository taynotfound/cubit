package com.cubit.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import kotlin.random.Random

/** Sound effects (Kenney CC0 packs, see assets/licenses). Variants plus slight pitch drift keep repeats from grating. */
object Sfx {
    var enabled = true
    private var pool: SoundPool? = null
    private val ids = HashMap<String, IntArray>()
    private var lastFlap = 0L

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        fun load(name: String, vararg res: Int) { ids[name] = IntArray(res.size) { p.load(ctx, res[it], 1) } }
        load("slide", R.raw.sfx_slide_1, R.raw.sfx_slide_2, R.raw.sfx_slide_3)
        load("bump", R.raw.sfx_bump_1, R.raw.sfx_bump_2, R.raw.sfx_bump_3)
        // Clacks cut from a real split-flap recording supplied by the user.
        load("flap", R.raw.sfx_flap_1, R.raw.sfx_flap_2, R.raw.sfx_flap_3, R.raw.sfx_flap_4, R.raw.sfx_flap_5)
        load("click", R.raw.sfx_click); load("toggle", R.raw.sfx_toggle); load("hint", R.raw.sfx_hint)
        load("start", R.raw.sfx_start); load("win", R.raw.sfx_win); load("lose", R.raw.sfx_lose)
        pool = p
    }

    private fun play(name: String, vol: Float, drift: Float = 0f) {
        val p = pool ?: return
        if (!enabled) return
        val v = ids[name] ?: return
        p.play(v[Random.nextInt(v.size)], vol, vol, 1, 0, 1f + (Random.nextFloat() * 2 - 1) * drift)
    }

    fun slide() = play("slide", 0.45f, 0.05f)
    fun bump() = play("bump", 0.55f, 0.06f)
    fun click() = play("click", 0.3f)
    fun toggle() = play("toggle", 0.25f)
    fun hint() = play("hint", 0.45f)
    fun start() = play("start", 0.4f)
    fun win() = play("win", 0.6f)
    fun lose() = play("lose", 0.5f)

    /** Split-flap clack; throttled so a whole board flipping reads as a rattle, not a wall of noise. */
    fun flap(vol: Float = 0.11f) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFlap < 70) return
        lastFlap = now
        play("flap", vol, 0.05f)
    }
}

/** Background loops, synthesized by tools/music.py: a slow one for the menus, one with a little pulse for play. */
object Music {
    var enabled = true; set(v) { field = v; if (v) resume() else player?.pause() }
    private var ctx: Context? = null
    private var player: android.media.MediaPlayer? = null
    private var current = 0

    fun init(c: Context) { ctx = c.applicationContext }

    fun menu() = play(R.raw.music_menu, 0.22f)
    fun game() = play(R.raw.music_play, 0.18f)

    private fun play(res: Int, vol: Float) {
        if (res == current && player != null) { resume(); return }
        player?.release(); player = null; current = res
        val c = ctx ?: return
        player = android.media.MediaPlayer.create(c, res)?.apply { isLooping = true; setVolume(vol, vol) }
        resume()
    }

    fun resume() { if (enabled) player?.takeIf { !it.isPlaying }?.start() }
    fun pause() { player?.takeIf { it.isPlaying }?.pause() }
}
