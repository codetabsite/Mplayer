package com.tdev.mplayr.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.data.MusicLoader
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.CommunityPlaylistEntity
import com.tdev.mplayr.db.PlaylistEntity
import com.tdev.mplayr.db.PlaylistSongEntity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Community / Shared Playlists:
 * - Browse public playlists (metadata only — no audio upload)
 * - Search, Trending, Popular, New tabs
 * - Import by ID/link
 * - Auto-match to local library
 * - Show missing songs
 *
 * Backend is a simple JSON endpoint — works offline by reading cached DB.
 */
class CommunityPlaylistActivity : AppCompatActivity() {

    private companion object {
        // Replace with real backend URL when available; falls back to offline cache
        const val API_BASE = "https://mplayr-community.example.com/api/v1"
        const val DEMO_TRENDING = """{"playlists":[
            {"shareId":"demo1","name":"Chill Vibes 2025","author":"DJ_Chill","likeCount":420,"playCount":8900,
             "songs":[{"title":"Levitating","artist":"Dua Lipa","album":"Future Nostalgia"},
                      {"title":"Blinding Lights","artist":"The Weeknd","album":"After Hours"}]},
            {"shareId":"demo2","name":"Workout Hits","author":"FitBeats","likeCount":310,"playCount":6200,
             "songs":[{"title":"Eye of the Tiger","artist":"Survivor","album":"Eye of the Tiger"},
                      {"title":"Stronger","artist":"Kanye West","album":"Graduation"}]}
        ]}"""
    }

    private var allLocalSongs = listOf<com.tdev.mplayr.data.Song>()
    private var community: MutableList<CommunityPlaylistEntity> = mutableListOf()
    private lateinit var rvCommunity: RecyclerView
    private lateinit var adapter: CommunityAdapter
    private lateinit var etSearch: EditText
    private lateinit var tvStatus: TextView
    private var currentTab = "trending"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_community_playlist)

        rvCommunity = findViewById(R.id.rvCommunity)
        etSearch = findViewById(R.id.etCommunitySearch)
        tvStatus = findViewById(R.id.tvCommunityStatus)

        adapter = CommunityAdapter(community,
            onImport = { pl -> importToLocal(pl) },
            onDetails = { pl -> showDetails(pl) }
        )
        rvCommunity.layoutManager = LinearLayoutManager(this)
        rvCommunity.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCommunitySearch).setOnClickListener {
            val q = etSearch.text.toString().trim()
            if (q.isNotBlank()) searchOnline(q)
        }
        findViewById<Button>(R.id.btnImportById).setOnClickListener { importByIdDialog() }

        // Tab buttons
        listOf("trending", "popular", "new").forEachIndexed { i, tab ->
            val btnId = when (i) { 0 -> R.id.btnTrending; 1 -> R.id.btnPopular; else -> R.id.btnNew }
            findViewById<Button>(btnId)?.setOnClickListener { loadTab(tab) }
        }

        loadLocalSongs()
        loadCachedPlaylists()
        loadTab("trending")
    }

    private fun loadLocalSongs() {
        lifecycleScope.launch {
            allLocalSongs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MusicLoader.loadAll(this@CommunityPlaylistActivity)
            }
        }
    }

    private fun loadCachedPlaylists() {
        lifecycleScope.launch {
            val cached = AppDatabase.get(this@CommunityPlaylistActivity).communityPlaylistDao().getAll()
            if (cached.isNotEmpty()) {
                community.clear()
                community.addAll(cached)
                runOnUiThread { adapter.notifyDataSetChanged() }
            }
        }
    }

    private fun loadTab(tab: String) {
        currentTab = tab
        tvStatus.text = "Loading $tab…"
        lifecycleScope.launch {
            try {
                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    URL("$API_BASE/playlists?tab=$tab").readText()
                }
                displayFromJson(json)
            } catch (e: Exception) {
                // Offline fallback — use demo data for trending, cache for others
                if (tab == "trending") {
                    displayFromJson(DEMO_TRENDING)
                    runOnUiThread { tvStatus.text = "Offline — showing demo data" }
                } else {
                    runOnUiThread { tvStatus.text = "Offline — showing cached playlists" }
                    loadCachedPlaylists()
                }
            }
        }
    }

    private fun searchOnline(query: String) {
        tvStatus.text = "Searching…"
        lifecycleScope.launch {
            try {
                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    URL("$API_BASE/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}").readText()
                }
                displayFromJson(json)
            } catch (e: Exception) {
                // Offline: filter cached
                lifecycleScope.launch {
                    val cached = AppDatabase.get(this@CommunityPlaylistActivity).communityPlaylistDao().getAll()
                    val filtered = cached.filter { it.name.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
                    community.clear()
                    community.addAll(filtered)
                    runOnUiThread { adapter.notifyDataSetChanged(); tvStatus.text = "Offline search: ${filtered.size} results" }
                }
            }
        }
    }

    private fun displayFromJson(json: String) {
        runCatching {
            val arr = JSONObject(json).getJSONArray("playlists")
            val entities = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CommunityPlaylistEntity(
                    shareId = obj.optString("shareId"),
                    name = obj.optString("name"),
                    author = obj.optString("author"),
                    description = obj.optString("description", ""),
                    songMetadataJson = obj.optJSONArray("songs")?.toString() ?: "[]",
                    likeCount = obj.optInt("likeCount"),
                    playCount = obj.optInt("playCount")
                )
            }
            lifecycleScope.launch {
                // Cache to DB
                val dao = AppDatabase.get(this@CommunityPlaylistActivity).communityPlaylistDao()
                entities.forEach { dao.insert(it) }
                community.clear()
                community.addAll(entities)
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    tvStatus.text = "${entities.size} playlists"
                }
            }
        }.onFailure { e ->
            runOnUiThread { tvStatus.text = "Parse error: ${e.message}" }
        }
    }

    private fun importByIdDialog() {
        val et = EditText(this).apply { hint = "Playlist ID or link" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Import by ID")
            .setView(et)
            .setPositiveButton("Import") { _, _ ->
                val id = et.text.toString().trim().substringAfterLast('/')
                if (id.isBlank()) return@setPositiveButton
                fetchById(id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchById(shareId: String) {
        tvStatus.text = "Fetching $shareId…"
        lifecycleScope.launch {
            // Check local cache first
            val cached = AppDatabase.get(this@CommunityPlaylistActivity).communityPlaylistDao().getById(shareId)
            if (cached != null) {
                importToLocal(cached)
                return@launch
            }
            try {
                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    URL("$API_BASE/playlist/$shareId").readText()
                }
                val obj = JSONObject(json)
                val entity = CommunityPlaylistEntity(
                    shareId = obj.optString("shareId", shareId),
                    name = obj.optString("name"),
                    author = obj.optString("author"),
                    songMetadataJson = obj.optJSONArray("songs")?.toString() ?: "[]"
                )
                AppDatabase.get(this@CommunityPlaylistActivity).communityPlaylistDao().insert(entity)
                importToLocal(entity)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@CommunityPlaylistActivity, "Not found or offline", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun importToLocal(pl: CommunityPlaylistEntity) {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@CommunityPlaylistActivity)
            val plId = db.playlistDao().insert(PlaylistEntity(name = pl.name, description = pl.description, shareId = pl.shareId))

            // Match metadata to local songs
            val songMeta = parseSongMeta(pl.songMetadataJson)
            val matched = mutableListOf<com.tdev.mplayr.data.Song>()
            val missing = mutableListOf<String>()

            for ((title, artist) in songMeta) {
                val found = allLocalSongs.firstOrNull { s ->
                    s.title.equals(title, ignoreCase = true) && s.artist.equals(artist, ignoreCase = true)
                } ?: allLocalSongs.firstOrNull { s -> s.title.equals(title, ignoreCase = true) }
                if (found != null) matched.add(found)
                else missing.add("$title – $artist")
            }

            matched.forEachIndexed { i, s ->
                db.playlistDao().addSong(PlaylistSongEntity(plId, s.id, i))
            }

            runOnUiThread {
                val msg = buildString {
                    append("Imported \"${pl.name}\": ${matched.size}/${songMeta.size} matched")
                    if (missing.isNotEmpty()) append("\nMissing: ${missing.take(3).joinToString(", ")}${if (missing.size > 3) "…" else ""}")
                }
                android.app.AlertDialog.Builder(this@CommunityPlaylistActivity)
                    .setTitle("Import complete")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showDetails(pl: CommunityPlaylistEntity) {
        val meta = parseSongMeta(pl.songMetadataJson)
        val matched = meta.count { (t, a) ->
            allLocalSongs.any { s -> s.title.equals(t, ignoreCase = true) }
        }
        val msg = buildString {
            appendLine("By: ${pl.author}")
            if (pl.description.isNotBlank()) appendLine(pl.description)
            appendLine("Songs: ${meta.size} (${matched} in your library)")
            appendLine("❤ ${pl.likeCount}  ▶ ${pl.playCount}")
            appendLine()
            meta.take(10).forEach { (t, a) -> appendLine("• $t — $a") }
            if (meta.size > 10) appendLine("…and ${meta.size - 10} more")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(pl.name)
            .setMessage(msg)
            .setPositiveButton("Import") { _, _ -> importToLocal(pl) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun parseSongMeta(json: String): List<Pair<String, String>> {
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.optString("title") to obj.optString("artist")
            }
        }.getOrDefault(emptyList())
    }
}

// ── Community Playlist Adapter ────────────────────────────────────────────────
class CommunityAdapter(
    private val items: List<CommunityPlaylistEntity>,
    private val onImport: (CommunityPlaylistEntity) -> Unit,
    private val onDetails: (CommunityPlaylistEntity) -> Unit
) : RecyclerView.Adapter<CommunityAdapter.VH>() {

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val tvName = TextView(root.context).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) }
        val tvMeta = TextView(root.context).apply { textSize = 12f; setTextColor(0xFF888888.toInt()) }
        val btnImport = Button(root.context).apply { text = "Import"; textSize = 12f }
        init {
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(16, 14, 16, 14)
            val row = LinearLayout(root.context).apply { orientation = LinearLayout.HORIZONTAL }
            val col = LinearLayout(root.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(tvName); col.addView(tvMeta)
            row.addView(col); row.addView(btnImport)
            root.addView(row)
        }
    }

    override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): VH {
        val root = LinearLayout(p.context).apply {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        return VH(root)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val pl = items[pos]
        h.tvName.text = pl.name
        h.tvMeta.text = "by ${pl.author} · ❤ ${pl.likeCount} · ▶ ${pl.playCount}"
        h.btnImport.setOnClickListener { onImport(pl) }
        h.root.setOnClickListener { onDetails(pl) }
    }

    override fun getItemCount() = items.size
}
