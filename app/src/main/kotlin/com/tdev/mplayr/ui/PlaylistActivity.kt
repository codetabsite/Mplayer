package com.tdev.mplayr.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.data.MusicLoader
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.PlaylistEntity
import com.tdev.mplayr.db.PlaylistSongEntity
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlist Activity — create, edit, delete, reorder, import/export (M3U/M3U8/JSON)
 */
class PlaylistActivity : AppCompatActivity() {

    companion object {
        private const val REQ_IMPORT = 401
        private const val REQ_EXPORT = 402
    }

    private var svc: PlayerService? = null
    private var bound = false
    private var allSongs: List<Song> = emptyList()
    private var playlists: MutableList<PlaylistEntity> = mutableListOf()
    private var exportPlaylistId: Long = -1

    private lateinit var rvPlaylists: RecyclerView
    private lateinit var adapter: PlaylistAdapter

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) { svc = (b as PlayerService.LocalBinder).get(); bound = true }
        override fun onServiceDisconnected(n: ComponentName) { svc = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        rvPlaylists = findViewById(R.id.rvPlaylists)
        adapter = PlaylistAdapter(
            playlists,
            onPlay = { pl -> playPlaylist(pl) },
            onEdit = { pl -> editPlaylist(pl) },
            onDelete = { pl -> deletePlaylist(pl) },
            onExport = { pl -> exportPlaylist(pl) },
            onPin = { pl -> togglePin(pl) }
        )
        rvPlaylists.layoutManager = LinearLayoutManager(this)
        rvPlaylists.adapter = adapter

        // Swipe-to-delete
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition; val to = target.adapterPosition
                val tmp = playlists[from]; playlists[from] = playlists[to]; playlists[to] = tmp
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                deletePlaylist(playlists[vh.adapterPosition])
            }
        }).attachToRecyclerView(rvPlaylists)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabNewPlaylist)
            .setOnClickListener { createPlaylistDialog() }
        findViewById<Button>(R.id.btnImportPlaylist).setOnClickListener { importPlaylist() }

        loadSongs()
        loadPlaylists()
        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)
    }

    private fun loadSongs() {
        lifecycleScope.launch {
            allSongs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MusicLoader.loadAll(this@PlaylistActivity)
            }
        }
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            val all = AppDatabase.get(this@PlaylistActivity).playlistDao().getAll()
            playlists.clear()
            playlists.addAll(all)
            runOnUiThread { adapter.notifyDataSetChanged() }
        }
    }

    private fun createPlaylistDialog() {
        val et = EditText(this).apply { hint = "Playlist name" }
        android.app.AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    AppDatabase.get(this@PlaylistActivity).playlistDao()
                        .insert(PlaylistEntity(name = name))
                    loadPlaylists()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editPlaylist(pl: PlaylistEntity) {
        val et = EditText(this).apply { setText(pl.name) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename playlist")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    AppDatabase.get(this@PlaylistActivity).playlistDao().rename(pl.id, name)
                    loadPlaylists()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePlaylist(pl: PlaylistEntity) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete \"${pl.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@PlaylistActivity).playlistDao().delete(pl)
                    loadPlaylists()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun togglePin(pl: PlaylistEntity) {
        lifecycleScope.launch {
            AppDatabase.get(this@PlaylistActivity).playlistDao().setPinned(pl.id, !pl.pinned)
            loadPlaylists()
        }
    }

    private fun playPlaylist(pl: PlaylistEntity) {
        lifecycleScope.launch {
            val ids = AppDatabase.get(this@PlaylistActivity).playlistDao().getSongIds(pl.id).toSet()
            val map = allSongs.associateBy { it.id }
            val songs = ids.mapNotNull { map[it] }
            if (songs.isEmpty()) {
                runOnUiThread { Toast.makeText(this@PlaylistActivity, "Playlist is empty", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            svc?.setQueue(songs, 0)
            runOnUiThread { Toast.makeText(this@PlaylistActivity, "Playing ${pl.name}", Toast.LENGTH_SHORT).show() }
        }
    }

    // ── Export M3U/M3U8/JSON ──────────────────────────────────────────────────
    private fun exportPlaylist(pl: PlaylistEntity) {
        exportPlaylistId = pl.id
        val formats = arrayOf("M3U", "M3U8 (UTF-8)", "JSON")
        android.app.AlertDialog.Builder(this)
            .setTitle("Export format")
            .setItems(formats) { _, i ->
                val ext = when (i) { 0 -> "m3u"; 1 -> "m3u8"; else -> "json" }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "${pl.name}.$ext")
                }
                startActivityForResult(intent, REQ_EXPORT + i * 10)
            }
            .show()
    }

    private fun importPlaylist() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "audio/x-mpegurl", "application/json"))
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_IMPORT -> handleImport(uri)
            REQ_EXPORT, REQ_EXPORT + 10, REQ_EXPORT + 20 -> {
                val fmt = (requestCode - REQ_EXPORT) / 10
                handleExport(uri, fmt)
            }
        }
    }

    private fun handleImport(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
                val name = getFileName(uri).substringBeforeLast('.')
                val songs = parsePlaylistText(text, uri.toString())
                if (songs.isEmpty()) {
                    runOnUiThread { Toast.makeText(this@PlaylistActivity, "No matching songs found", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val db = AppDatabase.get(this@PlaylistActivity)
                val plId = db.playlistDao().insert(PlaylistEntity(name = name.ifBlank { "Imported" }))
                val songMap = allSongs.associateBy { it.filePath.substringAfterLast('/') }
                songs.forEachIndexed { i, filename ->
                    val song = songMap[filename.substringAfterLast('/')] ?: return@forEachIndexed
                    db.playlistDao().addSong(PlaylistSongEntity(plId, song.id, i))
                }
                loadPlaylists()
                runOnUiThread { Toast.makeText(this@PlaylistActivity, "Imported ✓", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@PlaylistActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun handleExport(uri: android.net.Uri, fmt: Int) {
        val plId = exportPlaylistId
        if (plId < 0) return
        lifecycleScope.launch {
            val db = AppDatabase.get(this@PlaylistActivity)
            val pl = db.playlistDao().getAll().firstOrNull { it.id == plId } ?: return@launch
            val ids = db.playlistDao().getSongIds(plId).toSet()
            val songMap = allSongs.associateBy { it.id }
            val songs = ids.mapNotNull { songMap[it] }
            val text = when (fmt) {
                0, 1 -> buildM3u(pl.name, songs)
                else -> buildJson(pl.name, songs)
            }
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                runOnUiThread { Toast.makeText(this@PlaylistActivity, "Exported ✓", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@PlaylistActivity, "Export failed", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun buildM3u(name: String, songs: List<Song>): String {
        val sb = StringBuilder("#EXTM3U\n#PLAYLIST:$name\n")
        songs.forEach { sb.append("#EXTINF:${it.duration / 1000},${it.artist} - ${it.title}\n${it.filePath}\n") }
        return sb.toString()
    }

    private fun buildJson(name: String, songs: List<Song>): String {
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(JSONObject().apply {
                put("title", s.title); put("artist", s.artist)
                put("album", s.album); put("path", s.filePath)
                put("duration", s.duration)
            })
        }
        return JSONObject().apply { put("name", name); put("songs", arr) }.toString(2)
    }

    private fun parsePlaylistText(text: String, uriStr: String): List<String> {
        return when {
            uriStr.endsWith(".json") || text.trimStart().startsWith("{") -> {
                runCatching {
                    val json = JSONObject(text)
                    val arr = json.getJSONArray("songs")
                    (0 until arr.length()).map { arr.getJSONObject(it).optString("path") }
                }.getOrDefault(emptyList())
            }
            else -> {
                text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            }
        }
    }

    private fun getFileName(uri: android.net.Uri): String {
        var name = ""
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) name = c.getString(i)
        }
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}

// ── Playlist RecyclerView Adapter ─────────────────────────────────────────────
class PlaylistAdapter(
    private val items: MutableList<PlaylistEntity>,
    private val onPlay: (PlaylistEntity) -> Unit,
    private val onEdit: (PlaylistEntity) -> Unit,
    private val onDelete: (PlaylistEntity) -> Unit,
    private val onExport: (PlaylistEntity) -> Unit,
    private val onPin: (PlaylistEntity) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val tvName = TextView(row.context).apply {
            textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnMenu = ImageButton(row.context).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            background = null
        }
        init {
            row.setPadding(20, 18, 12, 18)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(tvName); row.addView(btnMenu)
        }
    }

    override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): VH {
        val row = LinearLayout(p.context).apply {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        return VH(row)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val pl = items[pos]
        h.tvName.text = "${if (pl.pinned) "📌 " else ""}${pl.name}"
        h.row.setOnClickListener { onPlay(pl) }
        h.btnMenu.setOnClickListener {
            val opts = arrayOf("Edit name", "Delete", "Export", if (pl.pinned) "Unpin" else "Pin")
            android.app.AlertDialog.Builder(h.row.context)
                .setItems(opts) { _, i ->
                    when (i) { 0 -> onEdit(pl); 1 -> onDelete(pl); 2 -> onExport(pl); 3 -> onPin(pl) }
                }.show()
        }
    }

    override fun getItemCount() = items.size
}
