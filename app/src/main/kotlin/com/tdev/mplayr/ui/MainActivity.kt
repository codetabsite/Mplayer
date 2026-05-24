package com.tdev.mplayr.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.data.MusicLoader
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.service.PlayerService
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), PlayerService.Listener {

    private var svc: PlayerService? = null
    private var bound = false

    private lateinit var adapter:   SongAdapter
    private lateinit var tvTitle:   TextView
    private lateinit var tvArtist:  TextView
    private lateinit var tvPos:     TextView
    private lateinit var tvDur:     TextView
    private lateinit var seek:      SeekBar
    private lateinit var btnPlay:   ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat:  ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            svc?.takeIf { it.isPlaying }?.let {
                seek.progress = it.position
                tvPos.text = fmt(it.position)
            }
            handler.postDelayed(this, 500)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as PlayerService.LocalBinder).get()
            svc!!.listener = this@MainActivity
            bound = true
            svc!!.current?.let { updatePlayer(it) }
            updatePlayBtn()
        }
        override fun onServiceDisconnected(n: ComponentName) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        checkPermission()
    }

    private fun bindViews() {
        val rv       = findViewById<RecyclerView>(R.id.rv)
        tvTitle      = findViewById(R.id.tvNowTitle)
        tvArtist     = findViewById(R.id.tvNowArtist)
        tvPos        = findViewById(R.id.tvPos)
        tvDur        = findViewById(R.id.tvDur)
        seek         = findViewById(R.id.seek)
        btnPlay      = findViewById(R.id.btnPlay)
        btnShuffle   = findViewById(R.id.btnShuffle)
        btnRepeat    = findViewById(R.id.btnRepeat)

        adapter = SongAdapter { pos -> onSongClick(pos) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { svc?.prev() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { svc?.next() }
        btnPlay.setOnClickListener { svc?.togglePlay() }
        btnShuffle.setOnClickListener { toggleShuffle() }
        btnRepeat.setOnClickListener { cycleRepeat() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (user) svc?.seekTo(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        findViewById<EditText>(R.id.etSearch).addTextChangedListener {
            adapter.filter(it?.toString() ?: "")
        }
    }

    private fun checkPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadAndBind()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (req == 1 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadAndBind()
    }

    private fun loadAndBind() {
        thread {
            val songs = MusicLoader.loadAll(this)
            runOnUiThread { adapter.setSongs(songs) }
        }
        val i = Intent(this, PlayerService::class.java)
        startService(i)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    private fun onSongClick(pos: Int) {
        svc?.setQueue(adapter.getShown(), pos)
        adapter.setPlaying(pos)
    }

    private fun toggleShuffle() {
        val s = svc?.let { !it.shuffle } ?: return
        svc!!.shuffle = s
        btnShuffle.alpha = if (s) 1f else 0.35f
    }

    private fun cycleRepeat() {
        val svc = svc ?: return
        svc.repeat = (svc.repeat + 1) % 3
        when (svc.repeat) {
            0 -> { btnRepeat.alpha = 0.35f; btnRepeat.setImageResource(R.drawable.ic_repeat) }
            1 -> { btnRepeat.alpha = 1f;    btnRepeat.setImageResource(R.drawable.ic_repeat) }
            2 -> { btnRepeat.alpha = 1f;    btnRepeat.setImageResource(R.drawable.ic_repeat_one) }
        }
    }

    override fun onSongChanged(song: Song) = runOnUiThread {
        updatePlayer(song)
        val idx = adapter.indexOf(song)
        if (idx >= 0) {
            adapter.setPlaying(idx)
            findViewById<RecyclerView>(R.id.rv).scrollToPosition(idx)
        }
    }

    override fun onPlayStateChanged(playing: Boolean) = runOnUiThread { updatePlayBtn() }

    private fun updatePlayer(song: Song) {
        tvTitle.text  = song.title
        tvArtist.text = song.artist
        tvDur.text    = song.formatDuration()
        seek.max      = song.duration.toInt()
    }

    private fun updatePlayBtn() {
        val playing = svc?.isPlaying ?: false
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onResume()  { super.onResume();  handler.post(ticker) }
    override fun onPause()   { super.onPause();   handler.removeCallbacks(ticker) }
    override fun onDestroy() { super.onDestroy(); if (bound) unbindService(conn) }
}
