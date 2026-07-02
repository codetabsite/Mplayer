package com.tdev.mplayr.ui

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
import com.tdev.mplayr.db.AchievementEntity
import com.tdev.mplayr.db.FavoriteEntity
import com.tdev.mplayr.service.PlayerService
import android.content.SharedPreferences
import kotlinx.coroutines.launch

class NowPlayingActivity : AppCompatActivity(), PlayerService.Listener {

    private var svc: PlayerService? = null
    private var bound = false
    private var isFullscreenMode = false
    private var prefs: SharedPreferences? = null
    private var isRetroTheme = false
    private var retroClickCount = 0

    private lateinit var cdView:       CdView
    private lateinit var ivBgBlur:     ImageView
    private lateinit var tvTitle:      TextView
    private lateinit var tvArtist:     TextView
    private lateinit var tvPos:        TextView
    private lateinit var tvDur:        TextView
    private lateinit var seek:         SeekBar
    private lateinit var btnPlay:      ImageButton
    private lateinit var btnFav:       ImageButton
    private lateinit var btnSleep:     ImageButton
    private lateinit var btnEq:        ImageButton
    private lateinit var tvSpeed:      TextView
    private lateinit var btnAB:        TextView
    private lateinit var btnCrossfade: TextView
    private lateinit var vuContainer:  View
    private lateinit var rootLayout:   View
    private lateinit var toolbarLayout:   View
    private lateinit var controlsLayout:  View
    private lateinit var btnFullscreen:   ImageButton
    private lateinit var btnNote:         TextView
    private lateinit var btnLyrics:       TextView
    private lateinit var waveformSeek:    WaveformSeekBar

    private val db      by lazy { AppDatabase.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    // CD animasyonu — ValueAnimator ile rotationDeg'i sürekli artırıyoruz.
    // ObjectAnimator.ofFloat(view, "rotation") donuyor çünkü her pause/resume'da
    // rotation sıfırlanabilir. Bunun yerine kendi sayacımızı tutuyoruz.
    private var cdAnimator: ValueAnimator? = null
    private var currentRotation = 0f

    private var vuAnimator: ValueAnimator? = null
    private var vuBars: List<View> = emptyList()

    private var abState = 0
    private var abAMs   = -1

    private val ticker = object : Runnable {
        override fun run() {
            val s = svc
            if (s != null && s.isPlaying) {
                seek.progress = s.position
                tvPos.text    = fmt(s.position)
                if (s.duration > 0) waveformSeek.setProgress(s.position.toFloat() / s.duration)
            }
            handler.postDelayed(this, 500)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val service = (b as PlayerService.LocalBinder).get()
            svc = service
            service.listener = this@NowPlayingActivity
            bound = true
            service.current?.let { updateSong(it) }
            updatePlayBtn()
            updateSpeedLabel()
            if (service.isPlaying) startCdSpin() else pauseCdSpin()
        }
        override fun onServiceDisconnected(n: ComponentName) {
            svc = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        cdView         = findViewById(R.id.cdView)
        ivBgBlur       = findViewById(R.id.ivBgBlur)
        tvTitle        = findViewById(R.id.tvTitle)
        tvArtist       = findViewById(R.id.tvArtist)
        tvPos          = findViewById(R.id.tvPos)
        tvDur          = findViewById(R.id.tvDur)
        seek           = findViewById(R.id.seek)
        btnPlay        = findViewById(R.id.btnPlay)
        btnFav         = findViewById(R.id.btnFav)
        btnSleep       = findViewById(R.id.btnSleep)
        btnEq          = findViewById(R.id.btnEq)
        tvSpeed        = findViewById(R.id.tvSpeed)
        btnAB          = findViewById(R.id.btnAB)
        btnCrossfade   = findViewById(R.id.btnCrossfade)
        vuContainer    = findViewById(R.id.vuContainer)
        rootLayout     = findViewById(R.id.rootLayout)
        toolbarLayout  = findViewById(R.id.toolbarLayout)
        controlsLayout = findViewById(R.id.controlsLayout)
        btnFullscreen  = findViewById(R.id.btnFullscreen)
        btnNote        = findViewById(R.id.btnNote)
        btnLyrics      = findViewById(R.id.btnLyrics)
        waveformSeek   = findViewById(R.id.waveformSeek)
        waveformSeek.onSeek = { ratio ->
            val dur = svc?.duration ?: 0
            if (dur > 0) svc?.seekTo((dur * ratio).toInt())
        }

        vuBars = listOf(
            findViewById(R.id.vuBar1), findViewById(R.id.vuBar2),
            findViewById(R.id.vuBar3), findViewById(R.id.vuBar4),
            findViewById(R.id.vuBar5)
        )

        prefs = getSharedPreferences("mplayr_ui", MODE_PRIVATE)
        isRetroTheme = prefs?.getBoolean("retro_theme", false) ?: false
        if (isRetroTheme) applyRetroColors(true)
        setupListeners()
        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)
    }

    private fun setupListeners() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { svc?.prev() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { svc?.next() }
        btnPlay.setOnClickListener   { svc?.togglePlay() }
        btnFav.setOnClickListener    { toggleFavorite() }
        btnSleep.setOnClickListener  { showSleepDialog() }
        btnEq.setOnClickListener     { showEqDialog() }
        tvSpeed.setOnClickListener   { showSpeedDialog() }
        btnCrossfade.setOnClickListener { showCrossfadeDialog() }

        btnAB.setOnClickListener {
            val s = svc ?: return@setOnClickListener
            when (abState) {
                0 -> { abAMs = s.position; abState = 1; btnAB.text = "A-B  [A]"; btnAB.alpha = 1f }
                1 -> {
                    val bMs = s.position
                    if (bMs > abAMs) {
                        s.setABLoop(abAMs, bMs); abState = 2; btnAB.text = "A-B  ●"
                        promptSaveABLoop(abAMs, bMs) // [7] isimlendirilebilir kayıt
                    }
                }
                2 -> { s.clearABLoop(); abAMs = -1; abState = 0; btnAB.text = "A-B"; btnAB.alpha = 0.5f }
            }
        }

        // [7] Uzun basış: kayıtlı A-B döngülerini listele
        btnAB.setOnLongClickListener {
            showSavedABLoops()
            true
        }

        btnFullscreen.setOnClickListener { toggleFullscreen() }

        // [24] Şarkılara Özel Not Defteri
        btnNote.setOnClickListener { showNoteDialog() }
        // [22] Yerel LRC Söz Editörü'nü ayrı ekranda aç
        btnLyrics.setOnClickListener {
            val song = svc?.current ?: return@setOnClickListener
            startActivity(Intent(this, LyricsEditorActivity::class.java).apply {
                putExtra("songId", song.id)
                putExtra("songTitle", song.title)
                putExtra("songUri", song.uri.toString())
            })
        }

        cdView.setOnLongClickListener {
            retroClickCount++
            if (retroClickCount >= 5) { retroClickCount = 0; toggleRetroTheme() }
            true
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) svc?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    // CD animasyonu: ValueAnimator kendi iç sayacını tutar,
    // pause() çağrılınca currentRotation'ı kaydediyoruz, resume'da kaldığı yerden devam eder.
    private fun startCdSpin() {
        if (cdAnimator?.isRunning == true) return
        cdAnimator?.cancel()
        cdAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration     = 4000
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val delta = (anim.animatedValue as Float)
                cdView.rotationDeg = (currentRotation + delta) % 360f
            }
            start()
        }
    }

    private fun pauseCdSpin() {
        currentRotation = cdView.rotationDeg
        cdAnimator?.cancel()
        cdAnimator = null
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
                        val h = 0.2f + rng.nextFloat() * 0.8f
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
            ivBgBlur.setBackgroundColor(swatch.rgb and 0x00FFFFFF or 0xCC000000.toInt())
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
                runOnUiThread { btnFav.setImageResource(R.drawable.ic_fav_off) }
            } else {
                dao.add(FavoriteEntity(song.id))
                runOnUiThread { btnFav.setImageResource(R.drawable.ic_fav_on) }
            }
        }
    }

    private fun refreshFavBtn(songId: Long) {
        lifecycleScope.launch {
            val fav = db.favoriteDao().isFavorite(songId)
            runOnUiThread {
                btnFav.setImageResource(if (fav) R.drawable.ic_fav_on else R.drawable.ic_fav_off)
            }
        }
    }

    private fun toggleFullscreen() {
        isFullscreenMode = !isFullscreenMode
        if (isFullscreenMode) {
            toolbarLayout.visibility  = View.GONE
            controlsLayout.visibility = View.GONE
            vuContainer.visibility    = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
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
        prefs?.edit()?.putBoolean("retro_theme", isRetroTheme)?.apply()
        applyRetroColors(isRetroTheme)
        if (isRetroTheme) {
            Toast.makeText(this, "📼 Retro mod aktif", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { db.achievementDao().unlock(AchievementEntity("retro")) }
        } else {
            Toast.makeText(this, "Retro mod kapalı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyRetroColors(retro: Boolean) {
        if (retro) {
            rootLayout.setBackgroundColor(0xFF1A0A00.toInt())
            tvTitle.setTextColor(0xFFFF8C00.toInt())
            tvArtist.setTextColor(0xFFCC6600.toInt())
            tvPos.setTextColor(0xFFFF8C00.toInt())
            tvDur.setTextColor(0xFFFF8C00.toInt())
            seek.progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
            seek.thumbTintList    = android.content.res.ColorStateList.valueOf(0xFFFF8C00.toInt())
        } else {
            rootLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvArtist.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            tvPos.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            tvDur.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            seek.progressTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
            seek.thumbTintList    = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
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
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { if (it == 1.0f) "Normal (1×)" else "${it}×" }.toTypedArray()
        val sel    = speeds.indexOfFirst { it == (svc?.playbackSpeed ?: 1f) }.coerceAtLeast(2)
        android.app.AlertDialog.Builder(this)
            .setTitle("Çalma hızı")
            .setSingleChoiceItems(labels, sel) { dlg, i -> svc?.playbackSpeed = speeds[i]; updateSpeedLabel(); dlg.dismiss() }
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
                btnCrossfade.text  = if (values[i] == 0) "CF" else "CF ${values[i]}s"
                btnCrossfade.alpha = if (values[i] == 0) 0.5f else 1f
                dlg.dismiss()
            }
            .setNegativeButton("İptal", null).show()
    }

    // [7] İsimlendirilebilir A-B Döngü Kaydedici
    private fun promptSaveABLoop(startMs: Int, endMs: Int) {
        val song = svc?.current ?: return
        val input = EditText(this).apply { hint = "Döngü adı (örn: Nakarat)" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Döngüyü kaydet")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "İsimsiz Döngü" }
                lifecycleScope.launch {
                    db.abLoopDao().insert(
                        com.tdev.mplayr.db.ABLoopEntity(
                            songId = song.id, name = name, startMs = startMs, endMs = endMs
                        )
                    )
                    runOnUiThread { Toast.makeText(this@NowPlayingActivity, "Döngü kaydedildi: $name", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showSavedABLoops() {
        val song = svc?.current ?: return
        lifecycleScope.launch {
            val loops = db.abLoopDao().getForSong(song.id)
            runOnUiThread {
                if (loops.isEmpty()) {
                    Toast.makeText(this@NowPlayingActivity, "Bu şarkı için kayıtlı döngü yok", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = loops.map { "${it.name} (${fmt(it.startMs)}–${fmt(it.endMs)})" }.toTypedArray()
                android.app.AlertDialog.Builder(this@NowPlayingActivity)
                    .setTitle("Kayıtlı Döngüler")
                    .setItems(labels) { _, i ->
                        val loop = loops[i]
                        svc?.setABLoop(loop.startMs, loop.endMs)
                        abState = 2; abAMs = loop.startMs
                        btnAB.text = "A-B  ●"; btnAB.alpha = 1f
                    }
                    .setNegativeButton("Kapat", null)
                    .setNeutralButton("Sil") { _, _ ->
                        // basitlik için ilk döngüyü siler; gelişmiş silme akışı istenirse ayrı liste eklenebilir
                        lifecycleScope.launch { db.abLoopDao().delete(loops.first()) }
                    }
                    .show()
            }
        }
    }

    // [24] Şarkılara Özel Not Defteri Alanı
    private fun showNoteDialog() {
        val song = svc?.current ?: return
        lifecycleScope.launch {
            val existing = db.songNoteDao().get(song.id)
            runOnUiThread {
                val input = EditText(this@NowPlayingActivity).apply {
                    hint = "Bu şarkı hakkında not..."
                    setText(existing?.note ?: "")
                    minLines = 4
                    gravity = android.view.Gravity.TOP
                }
                android.app.AlertDialog.Builder(this@NowPlayingActivity)
                    .setTitle("Not — ${song.title}")
                    .setView(input)
                    .setPositiveButton("Kaydet") { _, _ ->
                        val text = input.text.toString()
                        lifecycleScope.launch {
                            if (text.isBlank()) {
                                db.songNoteDao().delete(song.id)
                            } else {
                                db.songNoteDao().set(
                                    com.tdev.mplayr.db.SongNoteEntity(songId = song.id, note = text)
                                )
                            }
                        }
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
        }
    }

    private fun showEqDialog() {
        val eq = svc?.equalizer ?: run {
            Toast.makeText(this, "Equalizer kullanılamıyor", Toast.LENGTH_SHORT).show(); return
        }
        val numBands   = eq.numberOfBands.toInt()
        val bandLabels = (0 until numBands).map { i ->
            val hz = eq.getCenterFreq(i.toShort()) / 1000
            if (hz >= 1000) "${hz/1000}kHz" else "${hz}Hz"
        }
        val minLevel = eq.bandLevelRange[0].toInt()
        val maxLevel = eq.bandLevelRange[1].toInt()

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,10) }
        val enableSwitch = Switch(this).apply { text = "Equalizer Aktif"; isChecked = svc?.eqEnabled ?: false }
        val bassSwitch   = Switch(this).apply { text = "Bass Boost";      isChecked = svc?.bassBoostEnabled ?: false }
        val bassSeek     = SeekBar(this).apply { max = 1000; progress = svc?.bassBoostStrength?.toInt() ?: 500 }
        layout.addView(enableSwitch)
        layout.addView(bassSwitch)
        layout.addView(TextView(this).apply { text = "Bas Güç" })
        layout.addView(bassSeek)

        val seekBars = (0 until numBands).map { i ->
            layout.addView(TextView(this).apply { text = bandLabels[i] })
            SeekBar(this).apply {
                max      = maxLevel - minLevel
                progress = eq.getBandLevel(i.toShort()).toInt() - minLevel
                layout.addView(this)
            }
        }

        // [8] CdView (Plak) Özelleştirme Seçenekleri
        layout.addView(TextView(this).apply { text = "\nPlak Görünümü"; setPadding(0, 16, 0, 4) })
        val shimmerSwitch = Switch(this).apply { text = "Parıltı efekti"; isChecked = cdView.shimmerEnabled }
        val ringsSwitch = Switch(this).apply { text = "Yansıma halkaları"; isChecked = cdView.reflectionRingsEnabled }
        layout.addView(shimmerSwitch)
        layout.addView(ringsSwitch)

        android.app.AlertDialog.Builder(this).setTitle("Ses Efektleri").setView(layout)
            .setPositiveButton("Tamam") { _, _ ->
                svc?.eqEnabled         = enableSwitch.isChecked
                svc?.bassBoostEnabled  = bassSwitch.isChecked
                svc?.bassBoostStrength = bassSeek.progress.toShort()
                seekBars.forEachIndexed { i, sb ->
                    eq.setBandLevel(i.toShort(), (sb.progress + minLevel).toShort())
                }
                cdView.shimmerEnabled = shimmerSwitch.isChecked
                cdView.reflectionRingsEnabled = ringsSwitch.isChecked
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun updateSpeedLabel() {
        val speed = svc?.playbackSpeed ?: 1f
        tvSpeed.text = if (speed == 1f) "1×" else "${speed}×"
    }

    // --- Listener callbacks ---

    override fun onSongChanged(song: Song) = runOnUiThread {
        updateSong(song)
        refreshFavBtn(song.id)
        abState = 0; abAMs = -1; btnAB.text = "A-B"; btnAB.alpha = 0.5f
    }

    override fun onPlayStateChanged(playing: Boolean) = runOnUiThread {
        updatePlayBtn()
        if (playing) startCdSpin() else pauseCdSpin()
        vuBars.forEach { it.scaleY = if (playing) 0.5f else 0.1f }
    }

    override fun onAchievementUnlocked(id: String, title: String, emoji: String) {
        runOnUiThread { Toast.makeText(this, "$emoji Başarım açıldı: $title", Toast.LENGTH_LONG).show() }
    }

    private fun updateSong(song: Song) {
        tvTitle.text  = song.title
        tvArtist.text = song.artist
        tvDur.text    = song.formatDuration()
        seek.max      = song.duration.toInt()

        // [9] Waveform SeekBar — arka planda amplitüd çıkar, hazır olunca göster
        waveformSeek.visibility = View.GONE
        lifecycleScope.launch {
            val amps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.tdev.mplayr.audio.WaveformExtractor.extract(this@NowPlayingActivity, song.uri, 80)
            }
            if (amps.isNotEmpty() && svc?.current?.id == song.id) {
                waveformSeek.setAmplitudes(amps)
                waveformSeek.visibility = View.VISIBLE
            }
        }

        Glide.with(this).asBitmap().load(song.artUri)
            .placeholder(R.drawable.ic_note).error(R.drawable.ic_note)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, t: Transition<in Bitmap>?) {
                    cdView.setAlbumBitmap(resource)
                    ivBgBlur.setImageBitmap(resource)
                    applyPaletteFromBitmap(resource)
                }
                override fun onLoadCleared(p: Drawable?) { cdView.setAlbumBitmap(null) }
            })
    }

    private fun updatePlayBtn() {
        btnPlay.setImageResource(if (svc?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        startVuMeter()
        if (svc?.isPlaying == true) startCdSpin()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        vuAnimator?.cancel()
        pauseCdSpin()
    }

    override fun onDestroy() {
        super.onDestroy()
        // listener leak'i önle
        svc?.let { if (it.listener == this) it.listener = null }
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}
