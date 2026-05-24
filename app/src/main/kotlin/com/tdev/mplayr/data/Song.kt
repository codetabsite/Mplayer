package com.tdev.mplayr.data

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String
) {
    fun formatDuration(): String {
        val secs = duration / 1000
        return "%d:%02d".format(secs / 60, secs % 60)
    }
}
