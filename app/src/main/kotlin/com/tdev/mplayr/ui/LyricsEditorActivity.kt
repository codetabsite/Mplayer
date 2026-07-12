package com.tdev.mplayr.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tdev.mplayr.R
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.LyricsLineEntity
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch

/**
 * [22] Yerel LRC (Şarkı Sözü) Editörü ve Senkronizasyonu:
 *   Kullanıcı satır satır söz yazar, şarkı çalarken "Şimdi Ekle" butonuyla
 *   o satırın zaman damgasını (mevcut oynatma pozisyonu) kaydeder. Sonuç Room'da
 *   satır satır (songId, timeMs, text) olarak tutulur — .lrc dosyası formatının
 *   veritabanı karşılığıdır. Standart .lrc export/import da desteklenir.
 */
class LyricsEditorActivity : AppCompatActivity() {

    private var svc: PlayerService? = null
    private var bound = false
    private var songId: Long = -1
    private var songTitle: String = ""

    private lateinit var container: LinearLayout
    private lateinit var newLineInput: EditText
    private lateinit var tvCurrentPos: TextView

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as PlayerService.LocalBinder).get()
            bound = true
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_editor)

        songId = intent.getLongExtra("songId", -1)
        songTitle = intent.getStringExtra("songTitle") ?: ""
        findViewById<TextView>(R.id.tvLyricsSongTitle).text = songTitle
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        container = findViewById(R.id.lyricsLinesContainer)
        newLineInput = findViewById(R.id.etNewLine)
        tvCurrentPos = findViewById(R.id.tvCurrentPos)

        findViewById<Button>(R.id.btnAddLineNow).setOnClickListener { addLineAtCurrentPosition() }
        findViewById<Button>(R.id.btnClearLyrics).setOnClickListener { clearAllLines() }

        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)
        loadLines()
        tickPosition()
    }

    private fun tickPosition() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                val pos = svc?.position ?: 0
                tvCurrentPos.text = getString(R.string.lyrics_position_dynamic, formatMs(pos))
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun addLineAtCurrentPosition() {
        if (songId < 0) return
        val text = newLineInput.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_write_lyrics_first), Toast.LENGTH_SHORT).show()
            return
        }
        val posMs = svc?.position?.toLong() ?: 0L
        lifecycleScope.launch {
            AppDatabase.get(this@LyricsEditorActivity).lyricsDao().insertLine(
                LyricsLineEntity(songId = songId, timeMs = posMs, text = text)
            )
            newLineInput.setText("")
            loadLines()
        }
    }

    private fun clearAllLines() {
        if (songId < 0) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Tüm sözleri sil")
            .setMessage("Bu şarkı için kayıtlı tüm senkron sözler silinecek. Emin misin?")
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@LyricsEditorActivity).lyricsDao().clearForSong(songId)
                    loadLines()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun loadLines() {
        if (songId < 0) return
        lifecycleScope.launch {
            val lines = AppDatabase.get(this@LyricsEditorActivity).lyricsDao().getLines(songId)
            renderLines(lines)
        }
    }

    private fun renderLines(lines: List<LyricsLineEntity>) {
        container.removeAllViews()
        if (lines.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.lyrics_empty)
                setTextColor(0xFF888888.toInt())
                textSize = 13f
                setPadding(0, 16, 0, 16)
            })
            return
        }
        lines.forEach { line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = formatMs(line.timeMs.toInt())
                setTextColor(0xFF64B5F6.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(80.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = line.text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            container.addView(row)
        }
    }

    private fun formatMs(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val centisec = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(min, sec, centisec)
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}
