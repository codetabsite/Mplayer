package com.tdev.mplayr.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object MusicLoader {

    fun loadAll(ctx: Context): List<Song> {
        val songs = mutableListOf<Song>()

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
            MediaStore.Audio.Media.DURATION
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
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                songs += Song(
                    id       = id,
                    title    = c.getString(iTit)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    artist   = c.getString(iArt)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    album    = c.getString(iAlb)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    albumId  = c.getLong(iAlbId),
                    duration = c.getLong(iDur),
                    uri      = ContentUris.withAppendedId(collection, id)
                )
            }
        }
        return songs
    }

    /** Sanatçıya göre grupla */
    fun groupByArtist(songs: List<Song>): Map<String, List<Song>> =
        songs.groupBy { it.artist }.toSortedMap(compareBy { it.lowercase() })

    /** Albüme göre grupla */
    fun groupByAlbum(songs: List<Song>): Map<String, List<Song>> =
        songs.groupBy { it.album }.toSortedMap(compareBy { it.lowercase() })
}
