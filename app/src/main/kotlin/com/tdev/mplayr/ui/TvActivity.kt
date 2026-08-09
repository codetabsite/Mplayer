package com.tdev.mplayr.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tdev.mplayr.R
import com.tdev.mplayr.data.MusicLoader
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch

/**
 * Android TV Mode:
 * - Large Now Playing screen with album art, seek bar, controls
 * - D-pad / remote navigation
 * - Tabs: Songs, Albums, Artists, Playlists, Folders
 * - Responsive for TV (large grid/list), tablet, phone
 */
class TvActivity : AppCompatActivity(), PlayerService.Listener {

    private var svc: PlayerService? = null
    private var bound = false
    private var allSongs: List<Song> = emptyList()

    // Now Playing panel
    private lateinit var ivTvArt: ImageView
    private lateinit var tvTvTitle: TextView
    private lateinit var tvTvArtist: TextView
    private lateinit var tvTvPos: TextView
    private lateinit var tvTvDur: TextView
    private lateinit var seekTv: SeekBar
    private lateinit var btnTvPlay: ImageButton
    private lateinit var btnTvPrev: ImageButton
    private lateinit var btnTvNext: ImageButton
    private lateinit var btnTvShuffle: ImageButton
    private lateinit var btnTvRepeat: ImageButton

    // Content list
    private lateinit var rvTvContent: RecyclerView
    private lateinit var tvAdapter: TvSongAdapter
    private var currentTab = 0

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            svc?.takeIf { it.isPlaying }?.let {
                seekTv.progress = it.position
                tvTvPos.text = fmt(it.position)
            }
            handler.postDelayed(this, 500)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as PlayerService.LocalBinder).get()
            svc?.listener = this@TvActivity
            bound = true
            svc?.current?.let { updateNowPlaying(it) }
            updatePlayBtn()
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)

        ivTvArt    = findViewById(R.id.ivTvArt)
        tvTvTitle  = findViewById(R.id.tvTvTitle)
        tvTvArtist = findViewById(R.id.tvTvArtist)
        tvTvPos    = findViewById(R.id.tvTvPos)
        tvTvDur    = findViewById(R.id.tvTvDur)
        seekTv     = findViewById(R.id.seekTv)
        btnTvPlay  = findViewById(R.id.btnTvPlay)
        btnTvPrev  = findViewById(R.id.btnTvPrev)
        btnTvNext  = findViewById(R.id.btnTvNext)
        btnTvShuffle = findViewById(R.id.btnTvShuffle)
        btnTvRepeat  = findViewById(R.id.btnTvRepeat)
        rvTvContent  = findViewById(R.id.rvTvContent)

        tvAdapter = TvSongAdapter { song -> onSongSelected(song) }
        rvTvContent.adapter = tvAdapter

        // Detect TV vs tablet — use grid for TV
        val isTV = packageManager.hasSystemFeature("android.hardware.type.television") ||
                   packageManager.hasSystemFeature("android.software.leanback")
        rvTvContent.layoutManager = if (isTV) GridLayoutManager(this, 3) else LinearLayoutManager(this)

        setupTabButtons()
        setupControls()

        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)
        loadSongs()
    }

    private fun setupTabButtons() {
        val tabs = listOf("Songs", "Albums", "Artists", "Playlists", "Folders")
        val tabContainer = findViewById<LinearLayout>(R.id.tvTabRow)
        tabs.forEachIndexed { i, label ->
            val btn = Button(this).apply {
                text = label
                isFocusable = true
                setOnClickListener { currentTab = i; applyTab(i) }
            }
            tabContainer?.addView(btn)
        }
    }

    private fun setupControls() {
        btnTvPlay.setOnClickListener { svc?.togglePlay() }
        btnTvPrev.setOnClickListener { svc?.prev() }
        btnTvNext.setOnClickListener { svc?.next() }
        btnTvShuffle.setOnClickListener {
            svc?.let { it.shuffle = !it.shuffle }
            updateShuffleBtn()
        }
        btnTvRepeat.setOnClickListener {
            svc?.let { it.repeat = (it.repeat + 1) % 3 }
            updateRepeatBtn()
        }
        seekTv.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) svc?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Back button
        findViewById<ImageButton>(R.id.btnTvBack)?.setOnClickListener { finish() }

        // 10s skip buttons
        findViewById<Button>(R.id.btnTvSkipBack)?.setOnClickListener {
            svc?.let { it.seekTo((it.position - 10000).coerceAtLeast(0)) }
        }
        findViewById<Button>(R.id.btnTvSkipFwd)?.setOnClickListener {
            svc?.let { it.seekTo((it.position + 10000).coerceAtMost(it.duration)) }
        }
    }

    // D-pad / remote key handling
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                svc?.togglePlay(); true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { svc?.next(); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { svc?.prev(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                svc?.let { it.seekTo((it.position + 10000).coerceAtMost(it.duration)) }; true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                svc?.let { it.seekTo((it.position - 10000).coerceAtLeast(0)) }; true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun loadSongs() {
        lifecycleScope.launch {
            allSongs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MusicLoader.loadAll(this@TvActivity)
            }
            applyTab(0)
        }
    }

    private fun applyTab(tab: Int) {
        when (tab) {
            0 -> tvAdapter.submit(allSongs)
            1 -> { // Albums
                val albums = MusicLoader.groupByAlbum(allSongs)
                val repSongs = albums.values.map { it.first() }
                tvAdapter.submit(repSongs, showAlbum = true)
            }
            2 -> { // Artists
                val artists = MusicLoader.groupByArtist(allSongs)
                val repSongs = artists.values.map { it.first() }
                tvAdapter.submit(repSongs, showArtist = true)
            }
            3 -> { // Playlists
                lifecycleScope.launch {
                    val playlists = AppDatabase.get(this@TvActivity).playlistDao().getAll()
                    val plNames = playlists.map { it.name }
                    runOnUiThread {
                        // Show playlist names as clickable items
                        val fakeSongs = playlists.mapIndexed { idx, pl ->
                            Song(id = pl.id, title = pl.name, artist = "Playlist",
                                 album = "", albumId = 0L, duration = 0L,
                                 uri = android.net.Uri.EMPTY, filePath = "")
                        }
                        tvAdapter.submit(fakeSongs, isPlaylist = true, playlistIds = playlists.map { it.id })
                    }
                }
            }
            4 -> { // Folders
                val folders = allSongs.groupBy { java.io.File(it.filePath).parent ?: "" }
                val repSongs = folders.entries.map { (path, songs) ->
                    songs.first().copy(album = path.substringAfterLast('/'))
                }
                tvAdapter.submit(repSongs, showFolder = true)
            }
        }
    }

    private fun onSongSelected(song: Song) {
        val songs = tvAdapter.getCurrentList()
        val idx = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        svc?.setQueue(songs, idx)
    }

    private fun updateNowPlaying(song: Song) {
        tvTvTitle.text  = song.title
        tvTvArtist.text = song.artist
        tvTvDur.text    = song.formatDuration()
        seekTv.max      = song.duration.toInt()
        Glide.with(this).load(song.artUri)
            .placeholder(R.drawable.ic_note).error(R.drawable.ic_note)
            .centerCrop().into(ivTvArt)
    }

    private fun updatePlayBtn() {
        btnTvPlay.setImageResource(if (svc?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateShuffleBtn() {
        btnTvShuffle.alpha = if (svc?.shuffle == true) 1f else 0.4f
    }

    private fun updateRepeatBtn() {
        when (svc?.repeat ?: 0) {
            0 -> { btnTvRepeat.alpha = 0.4f; btnTvRepeat.setImageResource(R.drawable.ic_repeat) }
            1 -> { btnTvRepeat.alpha = 1f;   btnTvRepeat.setImageResource(R.drawable.ic_repeat) }
            2 -> { btnTvRepeat.alpha = 1f;   btnTvRepeat.setImageResource(R.drawable.ic_repeat_one) }
        }
    }

    override fun onSongChanged(song: Song) = runOnUiThread { updateNowPlaying(song) }
    override fun onPlayStateChanged(playing: Boolean) = runOnUiThread { updatePlayBtn() }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }
    override fun onDestroy() {
        super.onDestroy()
        svc?.let { if (it.listener == this) it.listener = null }
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}

// ── TV Song Adapter ───────────────────────────────────────────────────────────
class TvSongAdapter(private val onClick: (Song) -> Unit) : RecyclerView.Adapter<TvSongAdapter.VH>() {
    private var items: List<Song> = emptyList()
    private var showAlbum = false
    private var showArtist = false
    private var showFolder = false
    private var isPlaylist = false
    private var playlistIds: List<Long> = emptyList()

    fun submit(list: List<Song>, showAlbum: Boolean = false, showArtist: Boolean = false,
               showFolder: Boolean = false, isPlaylist: Boolean = false, playlistIds: List<Long> = emptyList()) {
        items = list; this.showAlbum = showAlbum; this.showArtist = showArtist
        this.showFolder = showFolder; this.isPlaylist = isPlaylist; this.playlistIds = playlistIds
        notifyDataSetChanged()
    }

    fun getCurrentList() = items

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val ivArt = ImageView(root.context).apply {
            val dp = (56 * root.context.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(dp, dp)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val tvTitle = TextView(root.context).apply {
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tvSub = TextView(root.context).apply {
            textSize = 13f; setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.setPadding(16, 14, 16, 14)
            root.isFocusable = true
            root.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val col = LinearLayout(root.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(12, 0, 0, 0)
            }
            col.addView(tvTitle); col.addView(tvSub)
            root.addView(ivArt); root.addView(col)
        }
    }

    override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): VH {
        val root = LinearLayout(p.context).apply {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        return VH(root)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = items[pos]
        com.bumptech.glide.Glide.with(h.root.context).load(s.artUri)
            .placeholder(R.drawable.ic_note).error(R.drawable.ic_note)
            .centerCrop().into(h.ivArt)
        h.tvTitle.text = when {
            showAlbum -> s.album.ifBlank { s.title }
            showArtist -> s.artist
            showFolder -> s.album  // album repurposed as folder name
            isPlaylist -> s.title  // playlist name
            else -> s.title
        }
        h.tvSub.text = when {
            showAlbum -> s.artist
            showArtist -> "Artist"
            isPlaylist -> "Playlist"
            else -> "${s.artist} · ${s.formatDuration()}"
        }
        h.root.setOnClickListener { onClick(s) }
        // TV focus highlight
        h.root.setOnFocusChangeListener { v, focused ->
            v.setBackgroundColor(if (focused) 0x44FFFFFF else 0x00000000)
        }
    }

    override fun getItemCount() = items.size
}
