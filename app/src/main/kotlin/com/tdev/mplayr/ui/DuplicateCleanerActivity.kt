package com.tdev.mplayr.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tdev.mplayr.R
import com.tdev.mplayr.data.MusicLoader
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.DeletedSongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [19] Kopya Şarkı Temizleyici Algoritması:
 *   MusicLoader.findDuplicates() ile bulunan grupları gösterir. Her grupta orijinal
 *   (ilk sıradaki, genelde en büyük/en uzun) hariç diğerleri işaretlenebilir.
 *   Seçilenler MediaStore'dan SİLİNMEZ — [18] Çöp Kutusu mantığıyla sadece
 *   uygulama listesinden gizlenir (deleted_songs tablosuna eklenir). Bu, veri kaybını
 *   önleyen güvenli bir yaklaşımdır.
 */
class DuplicateCleanerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var progressBar: ProgressBar
    private val checkedIds = mutableSetOf<Long>()
    private var duplicateGroups: List<List<Song>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_duplicate_cleaner)
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        container = findViewById(R.id.duplicateContainer)
        progressBar = findViewById(R.id.progressScan)

        findViewById<Button>(R.id.btnDeleteSelected).setOnClickListener { deleteSelected() }

        scanForDuplicates()
    }

    private fun scanForDuplicates() {
        progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                val songs = MusicLoader.loadAll(this@DuplicateCleanerActivity)
                MusicLoader.findDuplicates(songs)
            }
            duplicateGroups = groups
            progressBar.visibility = android.view.View.GONE
            renderGroups(groups)
        }
    }

    private fun renderGroups(groups: List<List<Song>>) {
        container.removeAllViews()
        if (groups.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.duplicate_not_found)
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                setPadding(0, 24, 0, 24)
            })
            return
        }
        groups.forEachIndexed { gi, group ->
            container.addView(TextView(this).apply {
                text = getString(R.string.duplicate_group, gi + 1, group.first().title, group.first().artist)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(0, 16, 0, 4)
            })
            // Grubun ilk elemanı (varsayılan "orijinal") otomatik işaretsiz bırakılır.
            group.forEachIndexed { i, song ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 4)
                }
                val cb = CheckBox(this).apply {
                    isChecked = i != 0 // ilk hariç hepsi varsayılan işaretli
                    if (isChecked) checkedIds.add(song.id)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) checkedIds.add(song.id) else checkedIds.remove(song.id)
                    }
                }
                row.addView(cb)
                row.addView(TextView(this).apply {
                    text = "${song.formatDuration()} • ${song.filePath.substringAfterLast("/")}" +
                        if (i == 0) " (orijinal önerisi)" else ""
                    setTextColor(if (i == 0) 0xFF81C784.toInt() else 0xFFBBBBBB.toInt())
                    textSize = 12f
                })
                container.addView(row)
            }
        }
    }

    private fun deleteSelected() {
        if (checkedIds.isEmpty()) return
        val toDelete = duplicateGroups.flatten().filter { it.id in checkedIds }
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@DuplicateCleanerActivity).deletedSongDao()
            toDelete.forEach { song ->
                dao.add(DeletedSongEntity(songId = song.id, title = song.title, artist = song.artist))
            }
            checkedIds.clear()
            scanForDuplicates()
        }
    }
}
