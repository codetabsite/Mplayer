package com.tdev.mplayr.data

import android.content.Context
import android.provider.MediaStore

object MusicLoader {

    fun loadAll(ctx: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"

        ctx.contentResolver.query(
            uri, proj, sel, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId  = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTit = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArt = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlb = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iPth = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                songs += Song(
                    id       = c.getLong(iId),
                    title    = c.getString(iTit)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    artist   = c.getString(iArt)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    album    = c.getString(iAlb)?.takeIf { it.isNotBlank() } ?: "Unknown",
                    duration = c.getLong(iDur),
                    path     = c.getString(iPth) ?: ""
                )
            }
        }
        return songs
    }
}
