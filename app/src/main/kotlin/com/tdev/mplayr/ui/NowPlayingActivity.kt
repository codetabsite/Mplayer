package com.tdev.mplayr.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.tdev.mplayr.R
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.FavoriteEntity
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch

class NowPlayingActivity : AppCompatActivity(), PlayerService.Listener {

    private var svc: PlayerService? = null
    private var bound = false

    private lateinit var ivArt:      ImageView
    private lateinit var tvTitle:    TextView
    private lateinit var tvArtist:   TextView
    private lateinit var tvPos:      TextView
    private lateinit var tvDur:      TextView
    private lateinit var seek:       SeekBar
    private lateinit var btnPlay:    ImageButton
    private lateinit var btnFav:     ImageButton
    private lateinit var btnSleep:   ImageButton
    private lateinit var btnEq:      ImageButton

    private val db by lazy { AppDatabase.get(this) }
    private val handler = Handler(Looper.getMainLooper())
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
        }
        override fun onServiceDisconnected(n: ComponentName) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        ivArt    = findViewById(R.id.ivArt)
        tvTitle  = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvPos    = findViewById(R.id.tvPos)
        tvDur    = findViewById(R.id.tvDur)
        seek     = findViewById(R.id.seek)
        btnPlay  = findViewById(R.id.btnPlay)
        btnFav   = findViewById(R.id.btnFav)
        btnSleep = findViewById(R.id.btnSleep)
        btnEq    = findViewById(R.id.btnEq)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { svc?.prev() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { svc?.next() }
        btnPlay.setOnClickListener { svc?.togglePlay() }

        btnFav.setOnClickListener { toggleFavorite() }
        btnSleep.setOnClickListener { showSleepDialog() }
        btnEq.setOnClickListener { showEqDialog() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (user) svc?.seekTo(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        val i = Intent(this, PlayerService::class.java)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    private fun toggleFavorite() {
        val song = svc?.current ?: return
        lifecycleScope.launch {
            val dao  = db.favoriteDao()
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
        val options = arrayOf("5 dakika", "15 dakika", "30 dakika", "45 dakika", "1 saat", "İptal")
        val mins    = intArrayOf(5, 15, 30, 45, 60, 0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Uyku zamanlayıcı")
            .setItems(options) { _, i ->
                if (mins[i] == 0) svc?.cancelSleepTimer()
                else {
                    svc?.setSleepTimer(mins[i])
                    Toast.makeText(this, "${options[i]} sonra duracak", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showEqDialog() {
        val eq = svc?.equalizer ?: run {
            Toast.makeText(this, "Equalizer kullanılamıyor", Toast.LENGTH_SHORT).show()
            return
        }
        val numBands = eq.numberOfBands.toInt()
        val bandLabels = (0 until numBands).map { i ->
            val hz = eq.getCenterFreq(i.toShort()) / 1000
            if (hz >= 1000) "${hz/1000}kHz" else "${hz}Hz"
        }
        val levels = (0 until numBands).map { i ->
            eq.getBandLevel(i.toShort()).toInt()
        }.toIntArray()
        val minLevel = eq.bandLevelRange[0].toInt()
        val maxLevel = eq.bandLevelRange[1].toInt()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        // Enable toggle
        val enableSwitch = android.widget.Switch(this).apply {
            text = "Equalizer Aktif"
            isChecked = svc?.eqEnabled ?: false
        }
        layout.addView(enableSwitch)

        val seekBars = (0 until numBands).map { i ->
            val label = android.widget.TextView(this).apply { text = bandLabels[i] }
            val sb = SeekBar(this).apply {
                max      = maxLevel - minLevel
                progress = levels[i] - minLevel
            }
            layout.addView(label)
            layout.addView(sb)
            sb
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Equalizer")
            .setView(layout)
            .setPositiveButton("Tamam") { _, _ ->
                svc?.eqEnabled = enableSwitch.isChecked
                seekBars.forEachIndexed { i, sb ->
                    val level = (sb.progress + minLevel).toShort()
                    eq.setBandLevel(i.toShort(), level)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    override fun onSongChanged(song: Song) = runOnUiThread {
        updateSong(song)
        refreshFavBtn(song.id)
    }

    override fun onPlayStateChanged(playing: Boolean) = runOnUiThread { updatePlayBtn() }

    private fun updateSong(song: Song) {
        tvTitle.text  = song.title
        tvArtist.text = song.artist
        tvDur.text    = song.formatDuration()
        seek.max      = song.duration.toInt()
        Glide.with(this).load(song.artUri)
            .placeholder(R.drawable.ic_note).error(R.drawable.ic_note)
            .centerCrop().into(ivArt)
        refreshFavBtn(song.id)
    }

    private fun updatePlayBtn() {
        btnPlay.setImageResource(
            if (svc?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onResume()  { super.onResume();  handler.post(ticker) }
    override fun onPause()   { super.onPause();   handler.removeCallbacks(ticker) }
    override fun onDestroy() { super.onDestroy(); if (bound) unbindService(conn) }
}
