package com.tdev.mplayr.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.*
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.tdev.mplayr.R
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.FavoriteEntity
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch

class NowPlayingActivity : AppCompatActivity(), PlayerService.Listener {

    private var svc: PlayerService? = null
    private var bound = false
    private var isFullscreenMode = false
    private var isRetroTheme = false
    private var retroClickCount = 0

    private lateinit var ivArt:       ImageView
    private lateinit var ivBgBlur:    ImageView
    private lateinit var tvTitle:     TextView
    private lateinit var tvArtist:    TextView
    private lateinit var tvPos:       TextView
    private lateinit var tvDur:       TextView
    private lateinit var seek:        SeekBar
    private lateinit var btnPlay:     ImageButton
    private lateinit var btnFav:      ImageButton
    private lateinit var btnSleep:    ImageButton
    private lateinit var btnEq:       ImageButton
    private lateinit var tvSpeed:     TextView
    private lateinit var btnAB:       TextView
    private lateinit var btnCrossfade:TextView
    private lateinit var vuBar1:      View
    private lateinit var vuBar2:      View
    private lateinit var vuBar3:      View
    private lateinit var vuBar4:      View
    private lateinit var vuBar5:      View
    private lateinit var vuContainer: View
    private lateinit var rootLayout:  View
    private lateinit var toolbarLayout: View
    private lateinit var controlsLayout: View
    private lateinit var btnFullscreen: ImageButton

    private val db by lazy { AppDatabase.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var vinylAnimator: ObjectAnimator? = null
    private var vuAnimator: ValueAnimator? = null
    private var abState = 0  // 0=off 1=A set 2=AB active
    private var abAMs = -1
    private var vuBars: List<View> = emptyList()

    private val ticker = object : Runnable {
        override fun run() {
            svc?.takeIf { it.isPlaying }?.let {
                seek.progress = it.position
                tvPos.text    = fmt(it.position)
            }
            handler.postDelayed(this, 500)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as PlayerService.LocalBinder).get()
            svc!!.listener = this@NowPlayingActivity
            bound = true
            svc!!.current?.let { updateSong(it) }
            updatePlayBtn()
            updateSpeedLabel()
        }
        override fun onServiceDisconnected(n: ComponentName) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        ivArt         = findViewById(R.id.ivArt)
        ivBgBlur      = findViewById(R.id.ivBgBlur)
        tvTitle       = findViewById(R.id.tvTitle)
        tvArtist      = findViewById(R.id.tvArtist)
        tvPos         = findViewById(R.id.tvPos)
        tvDur         = findViewById(R.id.tvDur)
        seek          = findViewById(R.id.seek)
        btnPlay       = findViewById(R.id.btnPlay)
        btnFav        = findViewById(R.id.btnFav)
        btnSleep      = findViewById(R.id.btnSleep)
        btnEq         = findViewById(R.id.btnEq)
        tvSpeed       = findViewById(R.id.tvSpeed)
        btnAB         = findViewById(R.id.btnAB)
        btnCrossfade  = findViewById(R.id.btnCrossfade)
        vuBar1        = findViewById(R.id.vuBar1)
        vuBar2        = findViewById(R.id.vuBar2)
        vuBar3        = findViewById(R.id.vuBar3)
        vuBar4        = findViewById(R.id.vuBar4)
        vuBar5        = findViewById(R.id.vuBar5)
        vuContainer   = findViewById(R.id.vuContainer)
        rootLayout    = findViewById(R.id.rootLayout)
        toolbarLayout = findViewById(R.id.toolbarLayout)
        controlsLayout = findViewById(R.id.controlsLayout)
        btnFullscreen = findViewById(R.id.btnFullscreen)

        vuBars = listOf(vuBar1, vuBar2, vuBar3, vuBar4, vuBar5)

        setupListeners()

        val i = Intent(this, PlayerService::class.java)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    private fun setupListeners() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { svc?.prev() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { svc?.next() }
        btnPlay.setOnClickListener  { svc?.togglePlay() }
        btnFav.setOnClickListener   { toggleFavorite() }
        btnSleep.setOnClickListener { showSleepDialog() }
        btnEq.setOnClickListener    { showEqDialog() }
        tvSpeed.setOnClickListener  { showSpeedDialog() }
        btnCrossfade.setOnClickListener { showCrossfadeDialog() }

        btnAB.setOnClickListener {
            val svc = svc ?: return@setOnClickListener
            when (abState) {
                0 -> {
                    abAMs = svc.position
                    abState = 1
                    btnAB.text = "A-B  [A]"
                    btnAB.alpha = 1f
                }
                1 -> {
                    val bMs = svc.position
                    if (bMs > abAMs) {
                        svc.setABLoop(abAMs, bMs)
                        abState = 2
                        btnAB.text = "A-B  ●"
                    }
                }
                2 -> {
                    svc.clearABLoop()
                    abAMs = -1
                    abState = 0
                    btnAB.text = "A-B"
                    btnAB.alpha = 0.5f
                }
            }
        }

        btnFullscreen.setOnClickListener { toggleFullscreen() }

        ivArt.setOnLongClickListener {
            retroClickCount++
            if (retroClickCount >= 5) {
                retroClickCount = 0
                toggleRetroTheme()
            }
            true
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (user) svc?.seekTo(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun toggleFullscreen() {
        isFullscreenMode = !isFullscreenMode
        if (isFullscreenMode) {
            toolbarLayout.visibility  = View.GONE
            controlsLayout.visibility = View.GONE
            vuContainer.visibility    = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            ivArt.layoutParams = (ivArt.layoutParams as? android.widget.LinearLayout.LayoutParams)?.also {
                it.weight = 1f
            }
        } else {
            toolbarLayout.visibility  = View.VISIBLE
            controlsLayout.visibility = View.VISIBLE
            vuContainer.visibility    = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        rootLayout.setOnClickListener { if (isFullscreenMode) toggleFullscreen() }
    }

    private fun toggleRetroTheme() {
        isRetroTheme = !isRetroTheme
        if (isRetroTheme) {
            rootLayout.setBackgroundColor(0xFF1A0A00.toInt())
            tvTitle.setTextColor(0xFFFF8C00.toInt())
            tvArtist.setTextColor(0xFFCC6600.toInt())
            tvPos.setTextColor(0xFFFF8C00.toInt())
            tvDur.setTextColor(0xFFFF8C00.toInt())
            seek.progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
            seek.thumbTintList    = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
            Toast.makeText(this, "📼 Retro mod aktif", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                db.achievementDao().unlock(
                    com.tdev.mplayr.db.AchievementEntity("retro")
                )
            }
        } else {
            rootLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvArtist.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            tvPos.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            tvDur.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            seek.progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent))
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent))
        }
    }

    private fun startVinylSpin() {
        vinylAnimator?.cancel()
        vinylAnimator = ObjectAnimator.ofFloat(ivArt, "rotation", 0f, 360f).apply {
            duration     = 3000
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun pauseVinylSpin() {
        vinylAnimator?.pause()
    }

    private fun resumeVinylSpin() {
        vinylAnimator?.resume()
    }

    private fun startVuMeter() {
        vuAnimator?.cancel()
        val rng = java.util.Random()
        vuAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration    = 120
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.REVERSE
            addUpdateListener {
                if (svc?.isPlaying == true) {
                    vuBars.forEach { bar ->
                        val h = (0.2f + rng.nextFloat() * 0.8f)
                        bar.scaleY = h
                        bar.alpha  = 0.6f + h * 0.4f
                    }
                } else {
                    vuBars.forEach { it.scaleY = 0.1f; it.alpha = 0.3f }
                }
            }
            start()
        }
    }

    private fun applyPaletteFromBitmap(bmp: Bitmap) {
        if (isRetroTheme) return
        Palette.from(bmp).generate { palette ->
            val swatch = palette?.darkVibrantSwatch
                ?: palette?.darkMutedSwatch
                ?: palette?.dominantSwatch
                ?: return@generate
            val bg   = swatch.rgb
            val text = swatch.titleTextColor
            ivBgBlur.setBackgroundColor(bg and 0x00FFFFFF or 0xCC000000.toInt())
            seek.progressTintList = android.content.res.ColorStateList.valueOf(swatch.rgb or 0xFF000000.toInt())
            seek.thumbTintList    = android.content.res.ColorStateList.valueOf(swatch.rgb or 0xFF000000.toInt())
        }
    }

    private fun toggleFavorite() {
        val song = svc?.current ?: return
        lifecycleScope.launch {
            val dao = db.favoriteDao()
            if (dao.isFavorite(song.id)) {
                dao.remove(FavoriteEntity(song.id))
                btnFav.setImageResource(R.drawable.ic_fav_off)
            } else {
                dao.add(FavoriteEntity(song.id))
                btnFav.setImageResource(R.drawable.ic_fav_on)
            }
        }
    }

    private fun refreshFavBtn(songId: Long) {
        lifecycleScope.launch {
            val fav = db.favoriteDao().isFavorite(songId)
            btnFav.setImageResource(if (fav) R.drawable.ic_fav_on else R.drawable.ic_fav_off)
        }
    }

    private fun showSleepDialog() {
        val options = arrayOf("5 dakika","15 dakika","30 dakika","45 dakika","1 saat","İptal")
        val mins    = intArrayOf(5, 15, 30, 45, 60, 0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Uyku zamanlayıcı")
            .setItems(options) { _, i ->
                if (mins[i] == 0) svc?.cancelSleepTimer()
                else { svc?.setSleepTimer(mins[i]); Toast.makeText(this, "${options[i]} sonra duracak", Toast.LENGTH_SHORT).show() }
            }.show()
    }

    private fun showSpeedDialog() {
        val speeds  = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels  = speeds.map { if (it == 1.0f) "Normal (1×)" else "${it}×" }.toTypedArray()
        val current = svc?.playbackSpeed ?: 1f
        val sel     = speeds.indexOfFirst { it == current }.coerceAtLeast(2)
        android.app.AlertDialog.Builder(this)
            .setTitle("Çalma hızı")
            .setSingleChoiceItems(labels, sel) { dlg, i ->
                svc?.playbackSpeed = speeds[i]; updateSpeedLabel(); dlg.dismiss()
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun showCrossfadeDialog() {
        val opts   = arrayOf("Kapalı","1 saniye","2 saniye","3 saniye","5 saniye")
        val values = intArrayOf(0, 1, 2, 3, 5)
        val cur    = values.indexOfFirst { it == (svc?.crossfadeSecs ?: 0) }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Crossfade")
            .setSingleChoiceItems(opts, cur) { dlg, i ->
                svc?.crossfadeSecs = values[i]
                btnCrossfade.text = if (values[i] == 0) "CF" else "CF ${values[i]}s"
                btnCrossfade.alpha = if (values[i] == 0) 0.5f else 1f
                dlg.dismiss()
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun showEqDialog() {
        val eq = svc?.equalizer ?: run {
            Toast.makeText(this, "Equalizer kullanılamıyor", Toast.LENGTH_SHORT).show(); return
        }
        val numBands  = eq.numberOfBands.toInt()
        val bandLabels = (0 until numBands).map { i ->
            val hz = eq.getCenterFreq(i.toShort()) / 1000
            if (hz >= 1000) "${hz/1000}kHz" else "${hz}Hz"
        }
        val minLevel = eq.bandLevelRange[0].toInt()
        val maxLevel = eq.bandLevelRange[1].toInt()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val enableSwitch = android.widget.Switch(this).apply {
            text = "Equalizer Aktif"; isChecked = svc?.eqEnabled ?: false
        }
        layout.addView(enableSwitch)
        val bassSwitch = android.widget.Switch(this).apply {
            text = "Bass Boost"; isChecked = svc?.bassBoostEnabled ?: false
        }
        layout.addView(bassSwitch)
        val bassLabel = android.widget.TextView(this).apply { text = "Bas Güç" }
        val bassSeek  = SeekBar(this).apply { max = 1000; progress = svc?.bassBoostStrength?.toInt() ?: 500 }
        layout.addView(bassLabel); layout.addView(bassSeek)

        val seekBars = (0 until numBands).map { i ->
            layout.addView(android.widget.TextView(this).apply { text = bandLabels[i] })
            SeekBar(this).apply {
                max = maxLevel - minLevel
                progress = eq.getBandLevel(i.toShort()).toInt() - minLevel
                layout.addView(this)
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Ses Efektleri").setView(layout)
            .setPositiveButton("Tamam") { _, _ ->
                svc?.eqEnabled        = enableSwitch.isChecked
                svc?.bassBoostEnabled = bassSwitch.isChecked
                svc?.bassBoostStrength = bassSeek.progress.toShort()
                seekBars.forEachIndexed { i, sb ->
                    eq.setBandLevel(i.toShort(), (sb.progress + minLevel).toShort())
                }
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun updateSpeedLabel() {
        val speed = svc?.playbackSpeed ?: 1f
        tvSpeed.text = if (speed == 1f) "1×" else "${speed}×"
    }

    override fun onSongChanged(song: Song) = runOnUiThread {
        updateSong(song)
        refreshFavBtn(song.id)
        abState = 0; abAMs = -1; btnAB.text = "A-B"; btnAB.alpha = 0.5f
    }

    override fun onPlayStateChanged(playing: Boolean) = runOnUiThread {
        updatePlayBtn()
        if (playing) resumeVinylSpin() else pauseVinylSpin()
    }

    override fun onAchievementUnlocked(id: String, title: String, emoji: String) {
        runOnUiThread {
            Toast.makeText(this, "$emoji Başarım açıldı: $title", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSong(song: Song) {
        tvTitle.text  = song.title
        tvArtist.text = song.artist
        tvDur.text    = song.formatDuration()
        seek.max      = song.duration.toInt()

        Glide.with(this).asBitmap().load(song.artUri)
            .placeholder(R.drawable.ic_note).error(R.drawable.ic_note)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, t: Transition<in Bitmap>?) {
                    ivArt.setImageBitmap(resource)
                    ivBgBlur.setImageBitmap(resource)
                    applyPaletteFromBitmap(resource)
                    if (svc?.isPlaying == true) startVinylSpin() else vinylAnimator?.pause()
                }
                override fun onLoadCleared(p: Drawable?) {}
            })
        refreshFavBtn(song.id)
    }

    private fun updatePlayBtn() {
        btnPlay.setImageResource(if (svc?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        startVuMeter()
        if (svc?.isPlaying == true) startVinylSpin()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        vuAnimator?.cancel()
        vinylAnimator?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(conn)
    }
}
