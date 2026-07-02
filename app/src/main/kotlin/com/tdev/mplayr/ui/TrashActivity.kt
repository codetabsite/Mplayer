package com.tdev.mplayr.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tdev.mplayr.R
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.DeletedSongEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * [18] Son Sildiklerim Çöp Kutusu:
 *   Room'daki DeletedSongEntity kayıtlarını listeler. "Geri Yükle" ile isDeleted
 *   kaydı silinir (şarkı tekrar ana listede görünür). Fiziksel dosya SİLİNMEZ,
 *   sadece uygulama içi görünürlük kontrolü yapılır.
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("tr"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        container = findViewById(R.id.trashContainer)

        findViewById<Button>(R.id.btnEmptyTrash).setOnClickListener {
            lifecycleScope.launch {
                AppDatabase.get(this@TrashActivity).deletedSongDao().clearAll()
                loadList()
            }
        }

        loadList()
    }

    private fun loadList() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@TrashActivity).deletedSongDao()
            renderList(dao.getAll())
        }
    }

    private fun renderList(items: List<DeletedSongEntity>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Çöp kutusu boş"
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                setPadding(0, 24, 0, 24)
            }
            container.addView(tv)
            return
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = item.title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
            })
            info.addView(TextView(this).apply {
                text = "${item.artist} • Silindi: ${dateFmt.format(java.util.Date(item.deletedAt))}"
                setTextColor(0xFF888888.toInt())
                textSize = 11f
            })
            row.addView(info)

            val restoreBtn = Button(this).apply {
                text = "Geri Yükle"
                textSize = 12f
                setOnClickListener {
                    lifecycleScope.launch {
                        AppDatabase.get(this@TrashActivity).deletedSongDao().restore(item.songId)
                        loadList()
                    }
                }
            }
            row.addView(restoreBtn)
            container.addView(row)
        }
    }
}
