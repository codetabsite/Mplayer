package com.tdev.mplayr.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // v5: playlist export/import & community
    val description: String = "",
    val coverPath: String = "",
    val shareId: String = "",     // community share ID
    val isPublic: Boolean = false
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

@Entity(tableName = "song_notes")
data class SongNoteEntity(
    @PrimaryKey val songId: Long,
    val note: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "lyrics_lines")
data class LyricsLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val timeMs: Long,
    val text: String
)

@Entity(tableName = "deleted_songs")
data class DeletedSongEntity(
    @PrimaryKey val songId: Long,
    val title: String,
    val artist: String,
    val deletedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blacklisted_folders")
data class BlacklistedFolderEntity(
    @PrimaryKey val path: String
)

@Entity(tableName = "ab_loops")
data class ABLoopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val name: String,
    val startMs: Int,
    val endMs: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "song_gain")
data class SongGainEntity(
    @PrimaryKey val songId: Long,
    val gainDb: Float
)

// v5: Tag editor — overridden metadata per song
@Entity(tableName = "song_tags")
data class SongTagEntity(
    @PrimaryKey val songId: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
    val coverPath: String = "",   // local path to overridden cover image
    val updatedAt: Long = System.currentTimeMillis()
)

// v5: Community shared playlists cache
@Entity(tableName = "community_playlists")
data class CommunityPlaylistEntity(
    @PrimaryKey val shareId: String,
    val name: String,
    val author: String,
    val description: String = "",
    val songMetadataJson: String = "", // JSON array of {title,artist,album}
    val downloadedAt: Long = System.currentTimeMillis(),
    val likeCount: Int = 0,
    val playCount: Int = 0
)

// v5: Power user settings (gapless, replaygain, fade in/out, audio focus, etc.)
@Entity(tableName = "power_settings")
data class PowerSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
