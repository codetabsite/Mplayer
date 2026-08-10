package com.tdev.mplayr.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.LyricsLineEntity
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch

/**
 * Lyrics 2.0:
 * - LRC otomatik algılama (şarkı adı/sanatçıya göre /sdcard/ taraması)
 * - Senkronize / karaoke lyrics (satır vurgulama)
 * - Lyrics editor (satır ekle/sil, zaman damgası düzenle)
 * - LRC import / export
 */
class LyricsEditorActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_IMPORT_LRC = 201
        private const val REQUEST_EXPORT_LRC = 202
    }

    private var svc: PlayerService? = null
    private var bound = false
    private var songId: Long = -1
    private var songTitle: String = ""
    private var songArtist: String = ""

    private lateinit var rvLines: RecyclerView
    private lateinit var adapter: LyricsLineAdapter
    private lateinit var etNewLine: EditText
    private lateinit var tvCurrentPos: TextView
    private lateinit var tvKaraokeHighlight: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lines: MutableList<LyricsLineEntity> = mutableListOf()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as PlayerService.LocalBinder).get()
            bound = true
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null; bound = false }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val pos = svc?.position ?: 0
            tvCurrentPos.text = getString(R.string.lyrics_position_dynamic, formatMs(pos))
            highlightCurrentLine(pos)
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_editor)

        songId = intent.getLongExtra("songId", -1)
        songTitle = intent.getStringExtra("songTitle") ?: ""
        songArtist = intent.getStringExtra("songArtist") ?: ""
        findViewById<TextView>(R.id.tvLyricsSongTitle).text = songTitle
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        rvLines = RecyclerView(this)   // will use existing container in layout or add programmatically
        etNewLine = findViewById(R.id.etNewLine)
        tvCurrentPos = findViewById(R.id.tvCurrentPos)
        tvKaraokeHighlight = TextView(this) // karaoke overlay — attached to layout below

        // Try to find rv in layout; if not present, we rely on container LinearLayout
        val container = findViewById<LinearLayout>(R.id.lyricsLinesContainer)

        adapter = LyricsLineAdapter(
            lines,
            onDelete = { line -> deleteLine(line) },
            onEditTime = { line -> editLineTime(line) }
        )
        rvLines.layoutManager = LinearLayoutManager(this)
        rvLines.adapter = adapter
        container.removeAllViews()
        container.addView(rvLines, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        findViewById<Button>(R.id.btnAddLineNow).setOnClickListener { addLineAtCurrentPosition() }
        findViewById<Button>(R.id.btnClearLyrics).setOnClickListener { clearAllLines() }

        // Import / Export buttons — add dynamically since layout may not have them
        val btnImport = Button(this).apply {
            text = "Import LRC"
            setOnClickListener { importLrc() }
        }
        val btnExport = Button(this).apply {
            text = "Export LRC"
            setOnClickListener { exportLrc() }
        }
        val btnAutoDetect = Button(this).apply {
            text = "Auto Detect"
            setOnClickListener { autoDetectLrc() }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnImport, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnExport, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnAutoDetect, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val rootLl = findViewById<LinearLayout>(R.id.lyricsRootLayout) ?: container.parent as? LinearLayout
        rootLl?.addView(btnRow, 0)

        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)
        loadLines()
        autoDetectLrcSilent()
    }

    // ── Karaoke highlight ──────────────────────────────────────────────────────
    private fun highlightCurrentLine(posMs: Int) {
        if (lines.isEmpty()) return
        val idx = lines.indexOfLast { it.timeMs <= posMs }
        adapter.highlightedIndex = idx
        adapter.notifyItemRangeChanged(0, lines.size)
        if (idx >= 0) rvLines.smoothScrollToPosition(idx)
    }

    // ── Add line at current position ───────────────────────────────────────────
    private fun addLineAtCurrentPosition() {
        if (songId < 0) return
        val text = etNewLine.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_write_lyrics_first), Toast.LENGTH_SHORT).show()
            return
        }
        val posMs = svc?.position?.toLong() ?: 0L
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@LyricsEditorActivity).lyricsDao()
            dao.insertLine(LyricsLineEntity(songId = songId, timeMs = posMs, text = text))
            etNewLine.setText("")
            loadLines()
        }
    }

    private fun deleteLine(line: LyricsLineEntity) {
        lifecycleScope.launch {
            // Remove by id — no direct DAO method, use clearForSong + re-insert minus this line
            val dao = AppDatabase.get(this@LyricsEditorActivity).lyricsDao()
            val all = dao.getLines(songId).toMutableList()
            all.removeAll { it.id == line.id }
            dao.clearForSong(songId)
            if (all.isNotEmpty()) dao.insertLines(all.map { it.copy(id = 0) })
            loadLines()
        }
    }

    private fun editLineTime(line: LyricsLineEntity) {
        val etMs = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(line.timeMs.toString())
            hint = "Time in ms"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit timestamp (ms)")
            .setView(etMs)
            .setPositiveButton("OK") { _, _ ->
                val newMs = etMs.text.toString().toLongOrNull() ?: return@setPositiveButton
                lifecycleScope.launch {
                    val dao = AppDatabase.get(this@LyricsEditorActivity).lyricsDao()
                    val all = dao.getLines(songId).toMutableList()
                    val i = all.indexOfFirst { it.id == line.id }
                    if (i >= 0) {
                        all[i] = all[i].copy(timeMs = newMs)
                        dao.clearForSong(songId)
                        dao.insertLines(all.map { it.copy(id = 0) })
                    }
                    loadLines()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllLines() {
        if (songId < 0) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear all lyrics")
            .setMessage("Delete all synced lyrics for this song?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@LyricsEditorActivity).lyricsDao().clearForSong(songId)
                    loadLines()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadLines() {
        if (songId < 0) return
        lifecycleScope.launch {
            val fetched = AppDatabase.get(this@LyricsEditorActivity).lyricsDao().getLines(songId)
            lines.clear()
            lines.addAll(fetched)
            runOnUiThread { adapter.notifyDataSetChanged() }
        }
    }

    // ── LRC Auto-detect ───────────────────────────────────────────────────────
    private fun autoDetectLrcSilent() {
        if (songId < 0 || songTitle.isBlank()) return
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@LyricsEditorActivity).lyricsDao()
            if (dao.hasLyrics(songId)) return@launch          // already have lyrics
            // Try to find .lrc file next to the audio file
            val songUri = intent.getStringExtra("songUri") ?: return@launch
            val filePath = uriToFilePath(Uri.parse(songUri)) ?: return@launch
            val lrcPath = filePath.replaceAfterLast('.', "lrc")
            val lrcFile = java.io.File(lrcPath)
            if (lrcFile.exists()) {
                val parsed = parseLrc(lrcFile.readText())
                if (parsed.isNotEmpty()) {
                    dao.clearForSong(songId)
                    dao.insertLines(parsed.map { LyricsLineEntity(songId = songId, timeMs = it.first, text = it.second) })
                    loadLines()
                    runOnUiThread { Toast.makeText(this@LyricsEditorActivity, "LRC bulundu", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun autoDetectLrc() {
        autoDetectLrcSilent()
        Toast.makeText(this, "Searching for LRC file…", Toast.LENGTH_SHORT).show()
    }

    // ── LRC Import ────────────────────────────────────────────────────────────
    private fun importLrc() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream", "text/x-lrc"))
        }
        startActivityForResult(intent, REQUEST_IMPORT_LRC)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT_LRC -> {
                val uri = data?.data ?: return
                lifecycleScope.launch {
                    try {
                        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
                        val parsed = parseLrc(text)
                        if (parsed.isEmpty()) {
                            runOnUiThread { Toast.makeText(this@LyricsEditorActivity, "No valid LRC timestamps found", Toast.LENGTH_SHORT).show() }
                            return@launch
                        }
                        val dao = AppDatabase.get(this@LyricsEditorActivity).lyricsDao()
                        dao.clearForSong(songId)
                        dao.insertLines(parsed.map { LyricsLineEntity(songId = songId, timeMs = it.first, text = it.second) })
                        loadLines()
                        runOnUiThread { Toast.makeText(this@LyricsEditorActivity, "${parsed.size} satır içe aktarıldı", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this@LyricsEditorActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            REQUEST_EXPORT_LRC -> {
                val uri = data?.data ?: return
                lifecycleScope.launch {
                    try {
                        val lrcText = buildLrc()
                        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(lrcText) }
                        runOnUiThread { Toast.makeText(this@LyricsEditorActivity, "Dışa aktarıldı", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this@LyricsEditorActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    private fun exportLrc() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "No lyrics to export", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "${songTitle}.lrc")
        }
        startActivityForResult(intent, REQUEST_EXPORT_LRC)
    }

    private fun buildLrc(): String {
        val sb = StringBuilder()
        sb.appendLine("[ti:$songTitle]")
        sb.appendLine("[ar:$songArtist]")
        sb.appendLine("[tool:MPlayer]")
        for (line in lines) {
            val ms = line.timeMs
            val min = ms / 60000
            val sec = (ms % 60000) / 1000
            val centisec = (ms % 1000) / 10
            sb.appendLine("[%02d:%02d.%02d]%s".format(min, sec, centisec, line.text))
        }
        return sb.toString()
    }

    /** Parse standard LRC format: [mm:ss.xx]text or [mm:ss.xxx]text */
    private fun parseLrc(text: String): List<Pair<Long, String>> {
        val regex = Regex("""\[(\d{1,2}):(\d{2})\.(\d{2,3})](.*?)$""")
        val result = mutableListOf<Pair<Long, String>>()
        for (line in text.lines()) {
            val match = regex.find(line.trim()) ?: continue
            val (minS, secS, csS, lyric) = match.destructured
            val min = minS.toLongOrNull() ?: continue
            val sec = secS.toLongOrNull() ?: continue
            val ms = if (csS.length == 3) csS.toLongOrNull() ?: 0L
                     else (csS.toLongOrNull() ?: 0L) * 10L
            val totalMs = min * 60000L + sec * 1000L + ms
            if (lyric.isNotBlank()) result.add(totalMs to lyric.trim())
        }
        return result.sortedBy { it.first }
    }

    private fun uriToFilePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        // Try DATA column for content URIs
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return null
    }

    private fun formatMs(ms: Int): String {
        val s = ms / 1000
        val min = s / 60; val sec = s % 60; val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(min, sec, cs)
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }
    override fun onDestroy() {
        super.onDestroy()
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}

// ── RecyclerView Adapter for lyrics lines ────────────────────────────────────
class LyricsLineAdapter(
    private val items: List<LyricsLineEntity>,
    private val onDelete: (LyricsLineEntity) -> Unit,
    private val onEditTime: (LyricsLineEntity) -> Unit
) : RecyclerView.Adapter<LyricsLineAdapter.VH>() {

    var highlightedIndex: Int = -1

    inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val tvTime: TextView = TextView(row.context).apply {
            setTextColor(0xFF64B5F6.toInt()); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(90.dpToPx(row.context), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tvText: TextView = TextView(row.context).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnDel: TextView = TextView(row.context).apply {
            text = "✕"; setTextColor(0xFFFF5252.toInt()); textSize = 14f; setPadding(8, 0, 8, 0)
        }
        init {
            row.addView(tvTime); row.addView(tvText); row.addView(btnDel)
        }
        fun Int.dpToPx(ctx: android.content.Context) = (this * ctx.resources.displayMetrics.density).toInt()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 10, 12, 10)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ms = item.timeMs
        val min = ms / 60000; val sec = (ms % 60000) / 1000; val cs = (ms % 1000) / 10
        holder.tvTime.text = "%02d:%02d.%02d".format(min, sec, cs)
        holder.tvText.text = item.text
        holder.row.setBackgroundColor(if (position == highlightedIndex) 0x33FFFFFF else 0x00000000)
        holder.tvText.setTypeface(null, if (position == highlightedIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        holder.btnDel.setOnClickListener { onDelete(item) }
        holder.tvTime.setOnClickListener { onEditTime(item) }
    }

    override fun getItemCount() = items.size
}
