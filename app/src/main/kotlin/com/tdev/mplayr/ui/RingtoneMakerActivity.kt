package com.tdev.mplayr.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tdev.mplayr.R
import com.tdev.mplayr.audio.RingtoneMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [30] Ringtone Maker (Basit Ses Kesici/Trim):
 *   İki SeekBar ile başlangıç/bitiş noktası seçilir (maksimum 30 saniyelik zil sesi
 *   önerilir ama sınırlanmaz), RingtoneMaker.trimAndSave() ile kırpılıp
 *   MediaStore'a zil sesi olarak kaydedilir.
 */
class RingtoneMakerActivity : AppCompatActivity() {

    private var durationMs = 0
    private var startMs = 0
    private var endMs = 0
    private lateinit var songUri: Uri
    private var songTitle = ""

    private lateinit var seekStart: SeekBar
    private lateinit var seekEnd: SeekBar
    private lateinit var tvRange: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ringtone_maker)

        val uriStr = intent.getStringExtra("songUri") ?: run { finish(); return }
        songUri = Uri.parse(uriStr)
        songTitle = intent.getStringExtra("songTitle") ?: "ringtone"
        durationMs = intent.getIntExtra("durationMs", 30000)
        endMs = durationMs.coerceAtMost(30000) // varsayılan bitiş: ilk 30 sn ya da süre

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvRingtoneSongTitle).text = songTitle

        seekStart = findViewById(R.id.seekStart)
        seekEnd = findViewById(R.id.seekEnd)
        tvRange = findViewById(R.id.tvRange)

        seekStart.max = durationMs
        seekEnd.max = durationMs
        seekEnd.progress = endMs

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                if (!user) return
                if (sb == seekStart) {
                    startMs = p.coerceAtMost(endMs - 500)
                    seekStart.progress = startMs
                } else {
                    endMs = p.coerceAtLeast(startMs + 500)
                    seekEnd.progress = endMs
                }
                updateRangeLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        seekStart.setOnSeekBarChangeListener(listener)
        seekEnd.setOnSeekBarChangeListener(listener)
        updateRangeLabel()

        findViewById<Button>(R.id.btnSaveRingtone).setOnClickListener { saveRingtone() }
    }

    private fun updateRangeLabel() {
        tvRange.text = "${fmt(startMs)} — ${fmt(endMs)}  (${fmt(endMs - startMs)})"
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun saveRingtone() {
        val outputName = songTitle.take(40).replace(Regex("[^a-zA-Z0-9_ ]"), "_")
        lifecycleScope.launch {
            Toast.makeText(this@RingtoneMakerActivity, "Kesiliyor...", Toast.LENGTH_SHORT).show()
            val resultUri = withContext(Dispatchers.IO) {
                RingtoneMaker.trimAndSave(this@RingtoneMakerActivity, songUri, startMs.toLong(), endMs.toLong(), outputName)
            }
            if (resultUri != null) {
                Toast.makeText(this@RingtoneMakerActivity, "Zil sesi kaydedildi ✓", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this@RingtoneMakerActivity, "Kesme başarısız oldu", Toast.LENGTH_LONG).show()
            }
        }
    }
}
