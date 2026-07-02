package com.tdev.mplayr.db

import androidx.room.Entity
import androidx.room.PrimaryKey

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

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val title: String,
    val artist: String,
    val albumId: Long = 0L,
    val playedAt: Long = System.currentTimeMillis(),
    val listenedMs: Long = 0L,
    val durationMs: Long = 0L
)

@Entity(tableName = "app_prefs")
data class AppPrefEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_queues")
data class SavedQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val songIds: String,
    val createdAt: Long = System.currentTimeMillis()
)

// [24] Şarkı notu — songId'ye göre 1 not
@Entity(tableName = "song_notes")
data class SongNoteEntity(
    @PrimaryKey val songId: Long,
    val note: String,
    val updatedAt: Long = System.currentTimeMillis()
)

// [22] LRC senkronize söz — satır satır tablo
@Entity(tableName = "lyrics_lines")
data class LyricsLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val timeMs: Long,
    val text: String
)

// [18] Çöp kutusu — silinen şarkı kaydı (uygulama listesinden gizleniyor)
@Entity(tableName = "deleted_songs")
data class DeletedSongEntity(
    @PrimaryKey val songId: Long,
    val title: String,
    val artist: String,
    val deletedAt: Long = System.currentTimeMillis()
)

// [27] Kara listeye alınmış klasör yolları
@Entity(tableName = "blacklisted_folders")
data class BlacklistedFolderEntity(
    @PrimaryKey val path: String
)

// [7] İsimlendirilebilir A-B döngü kaydı
@Entity(tableName = "ab_loops")
data class ABLoopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val name: String,
    val startMs: Int,
    val endMs: Int,
    val createdAt: Long = System.currentTimeMillis()
)

// [2] Ses normalizasyonu için hesaplanan kazanç (ReplayGain benzeri, basit peak-based)
@Entity(tableName = "song_gain")
data class SongGainEntity(
    @PrimaryKey val songId: Long,
    val gainDb: Float
)
