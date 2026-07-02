package com.tdev.mplayr.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.abs

object MusicLoader {

    fun loadAll(ctx: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val genreMap = loadGenreMap(ctx) // [16] songId -> genre adı

        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"

        ctx.contentResolver.query(
            collection, proj, sel, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId  = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTit = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArt = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlb = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                songs += Song(
                    id       = id,
                    title    = c.getString(iTit)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    artist   = c.getString(iArt)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    album    = c.getString(iAlb)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    albumId  = c.getLong(iAlbId),
                    duration = c.getLong(iDur),
                    uri      = ContentUris.withAppendedId(collection, id),
                    filePath = c.getString(iData) ?: "",
                    genre    = genreMap[id] ?: "Bilinmiyor"
                )
            }
        }
        return songs
    }

    /** [16] MediaStore'un Genres tablosundan songId -> genre adı haritası çıkarır */
    private fun loadGenreMap(ctx: Context): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        val genresUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
        val genreProj = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)

        ctx.contentResolver.query(genresUri, genreProj, null, null, null)?.use { gc ->
            val iGenreId = gc.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
            val iGenreName = gc.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
            while (gc.moveToNext()) {
                val genreId = gc.getLong(iGenreId)
                val genreName = gc.getString(iGenreName)?.takeIf { it.isNotBlank() } ?: continue
                val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                ctx.contentResolver.query(
                    membersUri,
                    arrayOf(MediaStore.Audio.Genres.Members._ID),
                    null, null, null
                )?.use { mc ->
                    val iMemberId = mc.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members._ID)
                    while (mc.moveToNext()) {
                        map[mc.getLong(iMemberId)] = genreName
                    }
                }
            }
        }
        return map
    }

    /** [27] Kara listedeki klasörlerin içindeki şarkıları filtreler */
    fun filterBlacklisted(songs: List<Song>, blacklistedFolders: List<String>): List<Song> {
        if (blacklistedFolders.isEmpty()) return songs
        return songs.filterNot { song ->
            blacklistedFolders.any { folder -> song.filePath.startsWith(folder) }
        }
    }

    /** [18] Çöp kutusundaki (silinmiş) şarkıları listeden gizler */
    fun filterDeleted(songs: List<Song>, deletedIds: List<Long>): List<Song> {
        if (deletedIds.isEmpty()) return songs
        val deletedSet = deletedIds.toHashSet()
        return songs.filterNot { it.id in deletedSet }
    }

    /**
     * [19] Kopya Şarkı Temizleyici Algoritması:
     *   Başlık + sanatçı (küçük harfe çevrilip boşluklar temizlenerek) VE süre farkı 2 saniyeden az olan
     *   şarkıları aynı grup sayar. Her grupta en büyük dosya boyutuna/en uzun süreye sahip olan "orijinal" kabul edilir,
     *   diğerleri "kopya" olarak işaretlenir. Karmaşık ses parmak izi (fingerprint) YOK — basit metin+süre eşleştirme.
     */
    fun findDuplicates(songs: List<Song>): List<List<Song>> {
        fun normalize(s: String) = s.lowercase().trim().replace(Regex("\\s+"), " ")

        val groups = mutableListOf<MutableList<Song>>()
        val sorted = songs.sortedBy { normalize(it.title) + normalize(it.artist) }

        for (song in sorted) {
            val key = normalize(song.title) to normalize(song.artist)
            val existingGroup = groups.firstOrNull { group ->
                val g = group.first()
                normalize(g.title) == key.first &&
                normalize(g.artist) == key.second &&
                abs(g.duration - song.duration) < 2000
            }
            if (existingGroup != null) {
                existingGroup.add(song)
            } else {
                groups.add(mutableListOf(song))
            }
        }
        return groups.filter { it.size > 1 }
    }

    /** Sanatçıya göre grupla */
    fun groupByArtist(songs: List<Song>): Map<String, List<Song>> =
        songs.groupBy { it.artist }.toSortedMap(compareBy { it.lowercase() })

    /** Albüme göre grupla */
    fun groupByAlbum(songs: List<Song>): Map<String, List<Song>> =
        songs.groupBy { it.album }.toSortedMap(compareBy { it.lowercase() })
}
