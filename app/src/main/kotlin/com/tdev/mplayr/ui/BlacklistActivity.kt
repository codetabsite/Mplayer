package com.tdev.mplayr.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tdev.mplayr.R
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.BlacklistedFolderEntity
import kotlinx.coroutines.launch

/**
 * [27] Klasör Kara Listesi (Blacklist):
 *   Kullanıcı SAF (Storage Access Framework) ile bir klasör seçer, seçilen klasörün
 *   gerçek dosya yolu (path) Room'a kaydedilir. MusicLoader.filterBlacklisted()
 *   bu yolla başlayan tüm şarkıları listeden gizler.
 */
class BlacklistActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = uriToPath(uri)
            if (path != null) {
                lifecycleScope.launch {
                    AppDatabase.get(this@BlacklistActivity).blacklistDao().add(BlacklistedFolderEntity(path))
                    loadList()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blacklist)
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        container = findViewById(R.id.blacklistContainer)

        findViewById<Button>(R.id.btnAddFolder).setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        loadList()
    }

    /** SAF tree URI'sini gerçek dosya sistemi yoluna çevirir (yalnızca birincil depolama için basit yaklaşım) */
    private fun uriToPath(treeUri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = docId.split(":")
        if (parts.size < 2) return null
        val type = parts[0]
        val relativePath = parts[1]
        return if ("primary".equals(type, ignoreCase = true)) {
            "${Environment.getExternalStorageDirectory()}/$relativePath"
        } else {
            null // harici SD kart desteği bu basit sürümde kapsam dışı
        }
    }

    private fun loadList() {
        lifecycleScope.launch {
            val paths = AppDatabase.get(this@BlacklistActivity).blacklistDao().getAll()
            renderList(paths)
        }
    }

    private fun renderList(paths: List<String>) {
        container.removeAllViews()
        if (paths.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.blacklist_empty)
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                setPadding(0, 24, 0, 24)
            })
            return
        }
        paths.forEach { path ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            row.addView(TextView(this).apply {
                text = path
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = getString(R.string.btn_remove)
                textSize = 12f
                setOnClickListener {
                    lifecycleScope.launch {
                        AppDatabase.get(this@BlacklistActivity).blacklistDao().remove(path)
                        loadList()
                    }
                }
            })
            container.addView(row)
        }
    }
}
