package com.tdev.mplayr.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.tdev.mplayr.R
import com.tdev.mplayr.data.MusicLoader
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), PlayerService.Listener {

    private var svc: PlayerService? = null
    private var bound = false

    private lateinit var adapter:    SongAdapter
    private lateinit var tvTitle:    TextView
    private lateinit var tvArtist:   TextView
    private lateinit var tvPos:      TextView
    private lateinit var tvDur:      TextView
    private lateinit var seek:       SeekBar
    private lateinit var btnPlay:    ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat:  ImageButton
    private lateinit var ivNowArt:   ImageView
    private lateinit var tabs:       TabLayout

    private var allSongs: List<Song> = emptyList()
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
            svc?.listener = this@MainActivity
            bound = true
            svc?.current?.let { updatePlayer(it) }
            updatePlayBtn()
            updateShuffleBtn()
            updateRepeatBtn()
            restoreLastSession()
        }
        override fun onServiceDisconnected(n: ComponentName) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        observeFavorites()
        checkPermission()
    }

    private fun bindViews() {
        val rv     = findViewById<RecyclerView>(R.id.rv)
        tvTitle    = findViewById(R.id.tvNowTitle)
        tvArtist   = findViewById(R.id.tvNowArtist)
        tvPos      = findViewById(R.id.tvPos)
        tvDur      = findViewById(R.id.tvDur)
        seek       = findViewById(R.id.seek)
        btnPlay    = findViewById(R.id.btnPlay)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat  = findViewById(R.id.btnRepeat)
        ivNowArt   = findViewById(R.id.ivNowArt)
        tabs       = findViewById(R.id.tabs)

        adapter = SongAdapter(
            onClick      = { pos -> onSongClick(pos) },
            onLongClick  = { pos -> showSongContextMenu(pos) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { svc?.prev() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { svc?.next() }
        btnPlay.setOnClickListener    { svc?.togglePlay() }
        btnShuffle.setOnClickListener { toggleShuffle() }
        btnRepeat.setOnClickListener  { cycleRepeat() }

        findViewById<ImageButton>(R.id.btnStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        // Araçlar menüsü: Çöp Kutusu[18], Kara Liste[27], Kopya Temizleyici[19], Mono/Normalize/Sallama ayarları
        findViewById<ImageButton>(R.id.btnTools)?.setOnClickListener { showToolsMenu() }

        findViewById<android.view.View>(R.id.nowPlayingBar).setOnClickListener {
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) svc?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        findViewById<EditText>(R.id.etSearch).addTextChangedListener {
            adapter.filter(it?.toString() ?: "")
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) = applyTab(t.position)
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
    }

    private fun applyTab(pos: Int) {
        when (pos) {
            0 -> adapter.setSongs(allSongs)
            1 -> adapter.setSongs(MusicLoader.groupByArtist(allSongs).values.flatten())
            2 -> adapter.setSongs(MusicLoader.groupByAlbum(allSongs).values.flatten())
            3 -> lifecycleScope.launch {
                val ids  = db.favoriteDao().getAllIds().toSet()
                val favs = allSongs.filter { it.id in ids }
                runOnUiThread { adapter.setSongs(favs) }
            }
            4 -> lifecycleScope.launch {
                val recent    = db.playHistoryDao().getRecent(50)
                val map       = allSongs.associateBy { it.id }
                val songs     = recent.mapNotNull { map[it.songId] }
                runOnUiThread { adapter.setSongs(songs) }
            }
            5 -> loadSmartPlaylists()
        }
    }

    private fun loadSmartPlaylists() {
        lifecycleScope.launch {
            val opts = arrayOf(
                "Son 7 günde en çok dinlenenler",
                "Gece dinlediklerim",
                "Hiç dinlemediklerim",
                "Yarım bırakılanlar",
                "Rastgele keşfet"
            )
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Akıllı Çalma Listeleri")
                    .setItems(opts) { _, i -> applySmartPlaylist(i) }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    private fun applySmartPlaylist(idx: Int) {
        lifecycleScope.launch {
            val dao    = db.playHistoryDao()
            val weekMs = System.currentTimeMillis() - 7 * 86_400_000L
            val songs: List<Song> = when (idx) {
                0 -> {
                    val top = dao.getMostPlayedSince(weekMs, 50)
                    val map = allSongs.associateBy { it.id }
                    top.mapNotNull { map[it.songId] }
                }
                1 -> {
                    val nightIds = dao.getRecent(200)
                        .filter {
                            val h = java.util.Calendar.getInstance().also { c ->
                                c.timeInMillis = it.playedAt
                            }.get(java.util.Calendar.HOUR_OF_DAY)
                            h >= 22 || h <= 4
                        }
                        .map { it.songId }.toSet()
                    allSongs.filter { it.id in nightIds }
                }
                2 -> {
                    val played = dao.getPlayedSongIdsSince(0).toSet()
                    allSongs.filter { it.id !in played }.shuffled().take(30)
                }
                3 -> {
                    val map = allSongs.associateBy { it.id }
                    dao.getRecent(200)
                        .filter { h ->
                            val dur = map[h.songId]?.duration ?: 1L
                            h.listenedMs > 0 && h.listenedMs < dur * 0.5
                        }
                        .mapNotNull { map[it.songId] }
                        .distinctBy { it.id }
                }
                4 -> allSongs.shuffled().take(20)
                else -> allSongs
            }
            runOnUiThread {
                adapter.setSongs(songs)
                Toast.makeText(this@MainActivity, "${songs.size} şarkı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showToolsMenu() {
        val opts = arrayOf(
            "🗑️ Çöp Kutusu",
            "🚫 Klasör Kara Listesi",
            "🧹 Kopya Şarkı Temizleyici",
            "📳 Telefonu Sallayarak Değiştir: ${if (svc?.shakeToSkipEnabled == true) "Açık" else "Kapalı"}",
            "🌙 Hareketsizlikte Uyku Modu",
            "🔊 Ses Normalizasyonu: ${if (svc?.normalizeEnabled == true) "Açık" else "Kapalı"}",
            "🎧 Mono Mod: ${if (svc?.monoEnabled == true) "Açık" else "Kapalı"}",
            "✂️ Sessizlik Kırpma: ${if (svc?.silenceTrimEnabled == true) "Açık" else "Kapalı"}"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Araçlar")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, TrashActivity::class.java))
                    1 -> startActivity(Intent(this, BlacklistActivity::class.java))
                    2 -> startActivity(Intent(this, DuplicateCleanerActivity::class.java))
                    3 -> { svc?.shakeToSkipEnabled = !(svc?.shakeToSkipEnabled ?: false)
                           Toast.makeText(this, getString(R.string.toast_shake_updated), Toast.LENGTH_SHORT).show() }
                    4 -> showSleepMotionDialog()
                    5 -> { svc?.normalizeEnabled = !(svc?.normalizeEnabled ?: false)
                           Toast.makeText(this, getString(R.string.toast_normalize_updated), Toast.LENGTH_SHORT).show() }
                    6 -> { svc?.monoEnabled = !(svc?.monoEnabled ?: false)
                           Toast.makeText(this, getString(R.string.toast_mono_updated), Toast.LENGTH_SHORT).show() }
                    7 -> { svc?.silenceTrimEnabled = !(svc?.silenceTrimEnabled ?: false)
                           Toast.makeText(this, getString(R.string.toast_silence_updated), Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    /** [3] İvmeölçer ile Akıllı Uyku Modu ayar diyaloğu */
    private fun showSleepMotionDialog() {
        val opts = arrayOf("Kapat", "10 dakika hareketsizlik", "15 dakika", "30 dakika")
        val mins = intArrayOf(0, 10, 15, 30)
        android.app.AlertDialog.Builder(this)
            .setTitle("Hareketsizlikte Uyku Modu")
            .setItems(opts) { _, i ->
                if (mins[i] == 0) svc?.stopMotionSleepMode()
                else svc?.startMotionSleepMode(mins[i])
                Toast.makeText(this, opts[i], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSongContextMenu(pos: Int) {
        val song = adapter.get(pos)
        val opts = arrayOf("Sıraya ekle", "Sonraki çal", "Şarkı bilgisi", "Zil sesi yap", "Çöp kutusuna taşı")
        android.app.AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> { svc?.addToQueue(song); Toast.makeText(this, getString(R.string.toast_added_to_queue), Toast.LENGTH_SHORT).show() }
                    1 -> { svc?.playNext(song);   Toast.makeText(this, getString(R.string.toast_play_next), Toast.LENGTH_SHORT).show() }
                    2 -> showSongInfo(song)
                    3 -> startActivity(Intent(this, RingtoneMakerActivity::class.java).apply {
                            putExtra("songUri", song.uri.toString())
                            putExtra("songTitle", song.title)
                            putExtra("durationMs", song.duration.toInt())
                        })
                    4 -> lifecycleScope.launch {
                            db.deletedSongDao().add(
                                com.tdev.mplayr.db.DeletedSongEntity(song.id, song.title, song.artist)
                            )
                            runOnUiThread {
                                allSongs = allSongs.filterNot { it.id == song.id }
                                adapter.setSongs(adapter.getShown().filterNot { it.id == song.id })
                                Toast.makeText(this@MainActivity, getString(R.string.toast_moved_to_trash), Toast.LENGTH_SHORT).show()
                            }
                        }
                }
            }.show()
    }

    private fun showSongInfo(song: Song) {
        val info = """
            Başlık: ${song.title}
            Sanatçı: ${song.artist}
            Albüm: ${song.album}
            Süre: ${song.formatDuration()}
        """.trimIndent()
        android.app.AlertDialog.Builder(this)
            .setTitle("Şarkı Bilgisi")
            .setMessage(info)
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun observeFavorites() {
        lifecycleScope.launch {
            db.favoriteDao().getAllIdsFlow().collect { ids -> adapter.favIds = ids.toSet() }
        }
    }

    private fun restoreLastSession() {
        val svc = svc ?: return
        if (svc.current != null) return
        lifecycleScope.launch {
            val (lastId, lastPos) = svc.getLastSessionInfo()
            if (lastId < 0 || allSongs.isEmpty()) return@launch
            val idx = allSongs.indexOfFirst { it.id == lastId }
            if (idx < 0) return@launch
            runOnUiThread {
                svc.setQueue(allSongs, idx)
                svc.pause()
                handler.postDelayed({ svc.seekTo(lastPos) }, 800)
                adapter.setPlaying(idx)
                Toast.makeText(this@MainActivity, getString(R.string.toast_resuming), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) loadAndBind()
        else ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (req == 1 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadAndBind()
    }

    private fun loadAndBind() {
        thread {
            var songs = MusicLoader.loadAll(this)
            // [27] Kara listedeki klasörleri filtrele, [18] silinenleri gizle
            val blacklist = runBlocking_ { db.blacklistDao().getAll() }
            val deletedIds = runBlocking_ { db.deletedSongDao().let { it.getAll().map { d -> d.songId } } }
            songs = MusicLoader.filterBlacklisted(songs, blacklist)
            songs = MusicLoader.filterDeleted(songs, deletedIds)
            allSongs = songs
            runOnUiThread { adapter.setSongs(songs) }
        }
        val i = Intent(this, PlayerService::class.java)
        startService(i)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    /** thread{} bloğu içinden suspend DAO fonksiyonlarını çağırmak için basit köprü */
    private fun <T> runBlocking_(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    private fun onSongClick(pos: Int) {
        val svc = svc ?: return
        if (svc.shuffle) {
            svc.setQueueShuffled(adapter.getShown(), adapter.get(pos))
            adapter.setPlaying(0)
        } else {
            svc.setQueue(adapter.getShown(), pos)
            adapter.setPlaying(pos)
        }
    }

    private fun toggleShuffle() {
        val s = svc?.let { !it.shuffle } ?: return
        svc?.shuffle = s
        updateShuffleBtn()
    }

    private fun updateShuffleBtn() {
        btnShuffle.alpha = if (svc?.shuffle == true) 1f else 0.35f
    }

    private fun cycleRepeat() {
        val svc = svc ?: return
        svc.repeat = (svc.repeat + 1) % 3; updateRepeatBtn()
    }

    private fun updateRepeatBtn() {
        when (svc?.repeat ?: 0) {
            0 -> { btnRepeat.alpha = 0.35f; btnRepeat.setImageResource(R.drawable.ic_repeat) }
            1 -> { btnRepeat.alpha = 1f;    btnRepeat.setImageResource(R.drawable.ic_repeat) }
            2 -> { btnRepeat.alpha = 1f;    btnRepeat.setImageResource(R.drawable.ic_repeat_one) }
        }
    }

    override fun onSongChanged(song: Song) = runOnUiThread {
        updatePlayer(song)
        val idx = adapter.indexOf(song)
        if (idx >= 0) { adapter.setPlaying(idx); findViewById<RecyclerView>(R.id.rv).scrollToPosition(idx) }
    }

    override fun onPlayStateChanged(playing: Boolean) = runOnUiThread { updatePlayBtn() }

    override fun onAchievementUnlocked(id: String, title: String, emoji: String) {
        runOnUiThread { Toast.makeText(this, "$emoji Başarım: $title", Toast.LENGTH_LONG).show() }
    }

    private fun updatePlayer(song: Song) {
        tvTitle.text  = song.title
        tvArtist.text = song.artist
        tvDur.text    = song.formatDuration()
        seek.max      = song.duration.toInt()
        Glide.with(this).load(song.artUri)
            .placeholder(R.drawable.ic_note).error(R.drawable.ic_note)
            .centerCrop().into(ivNowArt)
    }

    private fun updatePlayBtn() {
        val playing = svc?.isPlaying ?: false
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    override fun onResume()  { super.onResume();  handler.post(ticker) }
    override fun onPause()   { super.onPause();   handler.removeCallbacks(ticker) }
    override fun onDestroy() {
        super.onDestroy()
        svc?.let { if (it.listener == this) it.listener = null }
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}
