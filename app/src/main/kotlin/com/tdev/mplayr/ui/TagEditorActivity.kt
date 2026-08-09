package com.tdev.mplayr.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.tdev.mplayr.R
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.SongTagEntity
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tag Editor — Title, Artist, Album, Genre, Year, Track, Cover
 * Supports single and multi-song editing.
 * Tags stored in Room (song_tags) as overrides — original file untouched.
 */
class TagEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SONG_IDS = "song_ids"   // LongArray for multi-edit
        const val REQUEST_PICK_COVER = 301
    }

    private var songIds: LongArray = longArrayOf()
    private var isMultiEdit = false
    private var selectedCoverUri: Uri? = null

    private lateinit var ivCover: ImageView
    private lateinit var etTitle: EditText
    private lateinit var etArtist: EditText
    private lateinit var etAlbum: EditText
    private lateinit var etGenre: EditText
    private lateinit var etYear: EditText
    private lateinit var etTrack: EditText
    private lateinit var tvMultiHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag_editor)

        songIds = intent.getLongArrayExtra(EXTRA_SONG_IDS) ?: longArrayOf()
        isMultiEdit = songIds.size > 1

        ivCover = findViewById(R.id.ivTagCover)
        etTitle = findViewById(R.id.etTagTitle)
        etArtist = findViewById(R.id.etTagArtist)
        etAlbum = findViewById(R.id.etTagAlbum)
        etGenre = findViewById(R.id.etTagGenre)
        etYear = findViewById(R.id.etTagYear)
        etTrack = findViewById(R.id.etTagTrack)
        tvMultiHint = findViewById(R.id.tvTagMultiHint)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        ivCover.setOnClickListener { pickCover() }
        findViewById<Button>(R.id.btnSaveTag).setOnClickListener { saveTags() }

        if (isMultiEdit) {
            tvMultiHint.text = "Editing ${songIds.size} songs — blank fields won't overwrite"
            tvMultiHint.visibility = android.view.View.VISIBLE
            etTitle.hint = "(leave blank to keep each song's title)"
            etTrack.hint = "(leave blank)"
        }

        loadExistingTags()
    }

    private fun loadExistingTags() {
        if (songIds.isEmpty()) return
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@TagEditorActivity).songTagDao()
            if (!isMultiEdit) {
                val tag = dao.get(songIds[0])
                runOnUiThread {
                    tag?.let {
                        etTitle.setText(it.title)
                        etArtist.setText(it.artist)
                        etAlbum.setText(it.album)
                        etGenre.setText(it.genre)
                        etYear.setText(it.year)
                        etTrack.setText(it.track)
                        if (it.coverPath.isNotBlank()) {
                            Glide.with(this@TagEditorActivity).load(File(it.coverPath)).into(ivCover)
                        }
                    }
                }
            } else {
                // For multi-edit pre-fill shared fields
                val tags = dao.getForSongs(songIds.toList())
                val artists = tags.map { it.artist }.distinct()
                val albums = tags.map { it.album }.distinct()
                val genres = tags.map { it.genre }.distinct()
                runOnUiThread {
                    if (artists.size == 1) etArtist.setText(artists[0])
                    if (albums.size == 1) etAlbum.setText(albums[0])
                    if (genres.size == 1) etGenre.setText(genres[0])
                }
            }
        }
    }

    private fun pickCover() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PICK_COVER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_COVER && resultCode == Activity.RESULT_OK) {
            selectedCoverUri = data?.data
            Glide.with(this).load(selectedCoverUri).centerCrop().into(ivCover)
        }
    }

    private fun saveTags() {
        val titleVal = etTitle.text.toString().trim()
        val artistVal = etArtist.text.toString().trim()
        val albumVal = etAlbum.text.toString().trim()
        val genreVal = etGenre.text.toString().trim()
        val yearVal = etYear.text.toString().trim()
        val trackVal = etTrack.text.toString().trim()
        val coverPath = copyCoverLocally(selectedCoverUri)

        lifecycleScope.launch {
            val dao = AppDatabase.get(this@TagEditorActivity).songTagDao()
            for (id in songIds) {
                val existing = dao.get(id)
                val updated = SongTagEntity(
                    songId = id,
                    title = if (titleVal.isBlank() && isMultiEdit) existing?.title ?: "" else titleVal,
                    artist = if (artistVal.isBlank()) existing?.artist ?: "" else artistVal,
                    album = if (albumVal.isBlank()) existing?.album ?: "" else albumVal,
                    genre = if (genreVal.isBlank()) existing?.genre ?: "" else genreVal,
                    year = if (yearVal.isBlank()) existing?.year ?: "" else yearVal,
                    track = if (trackVal.isBlank() && isMultiEdit) existing?.track ?: "" else trackVal,
                    coverPath = coverPath ?: existing?.coverPath ?: ""
                )
                dao.set(updated)
            }
            runOnUiThread {
                Toast.makeText(this@TagEditorActivity, "Tags saved ✓", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /** Copy cover to app-private dir so URI stays valid */
    private fun copyCoverLocally(uri: Uri?): String? {
        uri ?: return null
        return runCatching {
            val dir = File(filesDir, "covers").also { it.mkdirs() }
            val dest = File(dir, "cover_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            dest.absolutePath
        }.getOrNull()
    }
}
