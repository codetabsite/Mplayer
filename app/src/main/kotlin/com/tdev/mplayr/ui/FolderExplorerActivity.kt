package com.tdev.mplayr.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.data.MusicLoader
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.DeletedSongEntity
import com.tdev.mplayr.db.PlaylistSongEntity
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch
import java.io.File

/**
 * Folder Explorer — browse folders, play/shuffle, add to queue/playlist,
 * rename/delete/move files.
 */
class FolderExplorerActivity : AppCompatActivity() {

    private var svc: PlayerService? = null
    private var bound = false
    private var allSongs: List<Song> = emptyList()
    private var currentPath: String = ""

    private lateinit var rvFolders: RecyclerView
    private lateinit var rvSongs: RecyclerView
    private lateinit var tvPath: TextView
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var songAdapter: FolderSongAdapter

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) { svc = (b as PlayerService.LocalBinder).get(); bound = true }
        override fun onServiceDisconnected(n: ComponentName) { svc = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_explorer)

        tvPath = findViewById(R.id.tvFolderPath)
        rvFolders = findViewById(R.id.rvFolders)
        rvSongs = findViewById(R.id.rvFolderSongs)

        folderAdapter = FolderAdapter { folder -> navigateTo(folder) }
        songAdapter = FolderSongAdapter(
            onPlay = { song -> playSong(song) },
            onLongClick = { song -> showSongMenu(song) }
        )

        rvFolders.layoutManager = LinearLayoutManager(this)
        rvFolders.adapter = folderAdapter
        rvSongs.layoutManager = LinearLayoutManager(this)
        rvSongs.adapter = songAdapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (currentPath.isNotEmpty()) {
                val parent = File(currentPath).parent ?: ""
                navigateTo(parent)
            } else finish()
        }

        findViewById<Button>(R.id.btnPlayFolder).setOnClickListener { playFolder() }
        findViewById<Button>(R.id.btnShuffleFolder).setOnClickListener { shuffleFolder() }
        findViewById<Button>(R.id.btnAddFolderToQueue).setOnClickListener { addFolderToQueue() }

        loadSongs()
        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)
    }

    private fun loadSongs() {
        lifecycleScope.launch {
            allSongs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MusicLoader.loadAll(this@FolderExplorerActivity)
            }
            // Start from common root
            val paths = allSongs.mapNotNull { File(it.filePath).parent }.distinct()
            val root = findCommonRoot(paths)
            navigateTo(root)
        }
    }

    private fun findCommonRoot(paths: List<String>): String {
        if (paths.isEmpty()) return "/storage/emulated/0/Music"
        val parts = paths.map { it.split("/") }
        val shortest = parts.minByOrNull { it.size } ?: return "/storage/emulated/0"
        var common = ""
        for (i in shortest.indices) {
            if (parts.all { it.size > i && it[i] == shortest[i] }) {
                common = shortest.take(i + 1).joinToString("/")
            } else break
        }
        return common.ifEmpty { "/storage/emulated/0" }
    }

    private fun navigateTo(path: String) {
        currentPath = path
        tvPath.text = path.ifEmpty { "/" }

        val dir = File(path)
        val subFolders = dir.listFiles()?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        folderAdapter.submit(subFolders)

        val songsInDir = allSongs.filter { File(it.filePath).parent == path }
        songAdapter.submit(songsInDir)
    }

    private fun songsInCurrentFolder(): List<Song> =
        allSongs.filter { File(it.filePath).parent == currentPath }

    private fun playFolder() {
        val songs = songsInCurrentFolder()
        if (songs.isEmpty()) { Toast.makeText(this, "No songs in this folder", Toast.LENGTH_SHORT).show(); return }
        svc?.setQueue(songs, 0)
        Toast.makeText(this, "Playing ${songs.size} songs", Toast.LENGTH_SHORT).show()
    }

    private fun shuffleFolder() {
        val songs = songsInCurrentFolder()
        if (songs.isEmpty()) { Toast.makeText(this, "No songs in this folder", Toast.LENGTH_SHORT).show(); return }
        svc?.setQueueShuffled(songs)
        Toast.makeText(this, "Shuffle: ${songs.size} songs", Toast.LENGTH_SHORT).show()
    }

    private fun addFolderToQueue() {
        val songs = songsInCurrentFolder()
        songs.forEach { svc?.addToQueue(it) }
        Toast.makeText(this, "Added ${songs.size} songs to queue", Toast.LENGTH_SHORT).show()
    }

    private fun playSong(song: Song) {
        val songs = songsInCurrentFolder()
        val idx = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        svc?.setQueue(songs, idx)
    }

    private fun showSongMenu(song: Song) {
        val file = File(song.filePath)
        val opts = arrayOf("Play", "Add to Queue", "Add to Playlist", "Rename", "Delete", "Tag Editor")
        android.app.AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> playSong(song)
                    1 -> { svc?.addToQueue(song); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show() }
                    2 -> addToPlaylistDialog(song)
                    3 -> renameDialog(file, song)
                    4 -> deleteDialog(file, song)
                    5 -> startActivity(Intent(this, TagEditorActivity::class.java).apply {
                        putExtra(TagEditorActivity.EXTRA_SONG_IDS, longArrayOf(song.id))
                    })
                }
            }.show()
    }

    private fun addToPlaylistDialog(song: Song) {
        lifecycleScope.launch {
            val playlists = AppDatabase.get(this@FolderExplorerActivity).playlistDao().getAll()
            runOnUiThread {
                if (playlists.isEmpty()) {
                    Toast.makeText(this@FolderExplorerActivity, "No playlists found. Create one first.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val names = playlists.map { it.name }.toTypedArray()
                android.app.AlertDialog.Builder(this@FolderExplorerActivity)
                    .setTitle("Add to playlist")
                    .setItems(names) { _, i ->
                        val pl = playlists[i]
                        lifecycleScope.launch {
                            val dao = AppDatabase.get(this@FolderExplorerActivity).playlistDao()
                            val count = dao.songCount(pl.id)
                            dao.addSong(com.tdev.mplayr.db.PlaylistSongEntity(pl.id, song.id, count))
                            runOnUiThread { Toast.makeText(this@FolderExplorerActivity, "Added to ${pl.name}", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    .show()
            }
        }
    }

    private fun renameDialog(file: File, song: Song) {
        val et = EditText(this).apply { setText(file.nameWithoutExtension) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isBlank()) return@setPositiveButton
                val newFile = File(file.parent, "$newName.${file.extension}")
                if (file.renameTo(newFile)) {
                    Toast.makeText(this, "Yeniden adlandırıldı", Toast.LENGTH_SHORT).show()
                    loadSongs()
                } else {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDialog(file: File, song: Song) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${song.title}?")
            .setMessage("This will delete the file permanently.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@FolderExplorerActivity).deletedSongDao()
                        .add(DeletedSongEntity(song.id, song.title, song.artist))
                    runOnUiThread {
                        if (file.delete()) {
                            Toast.makeText(this@FolderExplorerActivity, "Silindi", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@FolderExplorerActivity, "Could not delete file", Toast.LENGTH_SHORT).show()
                        }
                        loadSongs()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}

// ── Folder RecyclerView Adapter ───────────────────────────────────────────────
class FolderAdapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<FolderAdapter.VH>() {
    private var items: List<File> = emptyList()
    fun submit(list: List<File>) { items = list; notifyDataSetChanged() }

    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(24, 20, 24, 20)
            textSize = 15f
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_note, 0, 0, 0)
            compoundDrawablePadding = 12
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.tv.text = f.name
        holder.tv.setOnClickListener { onClick(f.absolutePath) }
    }

    override fun getItemCount() = items.size
}

// ── Song list in folder ───────────────────────────────────────────────────────
class FolderSongAdapter(
    private val onPlay: (Song) -> Unit,
    private val onLongClick: (Song) -> Unit
) : RecyclerView.Adapter<FolderSongAdapter.VH>() {
    private var items: List<Song> = emptyList()
    fun submit(list: List<Song>) { items = list; notifyDataSetChanged() }

    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(24, 16, 24, 16); textSize = 14f
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.tv.text = "${s.title}\n${s.artist} · ${s.formatDuration()}"
        holder.tv.setOnClickListener { onPlay(s) }
        holder.tv.setOnLongClickListener { onLongClick(s); true }
    }

    override fun getItemCount() = items.size
}
