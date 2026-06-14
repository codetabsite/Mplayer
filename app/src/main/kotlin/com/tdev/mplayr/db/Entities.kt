package com.tdev.mplayr.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Playlist ──────────────────────────────────────────────────────────────────

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis()
)

// ── Favoriler ─────────────────────────────────────────────────────────────────

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long = System.currentTimeMillis()
)

// ── Play History (Son dinlenenler + en çok dinlenenler + istatistik) ──────────

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val title: String,
    val artist: String,
    val playedAt: Long = System.currentTimeMillis(),
    /** Kaç milisaniye dinlendi (sessizlik algısı için) */
    val listenedMs: Long = 0L
)

// ── Prefs (son pozisyon vb.) ──────────────────────────────────────────────────

@Entity(tableName = "app_prefs")
data class AppPrefEntity(
    @PrimaryKey val key: String,
    val value: String
)
