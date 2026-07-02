package com.tdev.mplayr.data

import android.content.ContentUris
import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val uri: Uri,          // content:// Uri — DATA kolonu yerine
    val filePath: String = "", // [27][19] klasör kara listesi ve kopya tespiti için dosya yolu
    val genre: String = "Bilinmiyor" // [16] Tür dağılımı pasta grafiği için
) {
    val artUri: Uri get() = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"), albumId
    )

    fun formatDuration(): String {
        val secs = duration / 1000
        return "%d:%02d".format(secs / 60, secs % 60)
    }
}
