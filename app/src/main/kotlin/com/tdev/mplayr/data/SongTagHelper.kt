package com.tdev.mplayr.data

import com.tdev.mplayr.db.SongTagEntity

/**
 * Applies stored tag overrides to a Song.
 * If a field is blank in the override, the original MediaStore value is kept.
 */
fun Song.applyTag(tag: SongTagEntity?): Song {
    if (tag == null) return this
    return copy(
        title  = tag.title.ifBlank { title },
        artist = tag.artist.ifBlank { artist },
        album  = tag.album.ifBlank { album },
        genre  = tag.genre.ifBlank { genre }
    )
}
