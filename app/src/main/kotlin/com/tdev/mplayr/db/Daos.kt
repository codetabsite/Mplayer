package com.tdev.mplayr.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Playlist ──────────────────────────────────────────────────────────────────

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY pinned DESC, createdAt DESC")
    fun getAllFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY pinned DESC, createdAt DESC")
    suspend fun getAll(): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE playlists SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getSongIds(playlistId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSong(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun songCount(playlistId: Long): Int
}

// ── Favoriler ─────────────────────────────────────────────────────────────────

@Dao
interface FavoriteDao {

    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    fun getAllIdsFlow(): Flow<List<Long>>

    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    suspend fun getAllIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(fav: FavoriteEntity)

    @Delete
    suspend fun remove(fav: FavoriteEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    fun isFavoriteFlow(songId: Long): Flow<Boolean>
}

// ── Play History ──────────────────────────────────────────────────────────────

data class SongPlayCount(val songId: Long, val title: String, val artist: String, val playCount: Int)
data class ArtistPlayCount(val artist: String, val playCount: Int)

@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    /** Son dinlenenler — tekrar yok, en yeni önce */
    @Query("""
        SELECT * FROM play_history
        WHERE id IN (
            SELECT MAX(id) FROM play_history GROUP BY songId
        )
        ORDER BY playedAt DESC
        LIMIT :limit
    """)
    fun getRecentFlow(limit: Int = 50): Flow<List<PlayHistoryEntity>>

    @Query("""
        SELECT * FROM play_history
        WHERE id IN (
            SELECT MAX(id) FROM play_history GROUP BY songId
        )
        ORDER BY playedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecent(limit: Int = 50): List<PlayHistoryEntity>

    /** En çok dinlenenler */
    @Query("""
        SELECT songId, title, artist, COUNT(*) AS playCount
        FROM play_history
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    fun getMostPlayedFlow(limit: Int = 20): Flow<List<SongPlayCount>>

    /** Bu hafta toplam dinleme süresi (ms) */
    @Query("""
        SELECT COALESCE(SUM(listenedMs), 0)
        FROM play_history
        WHERE playedAt >= :since
    """)
    fun getListenedMsSinceFlow(since: Long): Flow<Long>

    /** Bu hafta en çok dinlenen sanatçı */
    @Query("""
        SELECT artist, COUNT(*) AS playCount
        FROM play_history
        WHERE playedAt >= :since
        GROUP BY artist
        ORDER BY playCount DESC
        LIMIT 1
    """)
    suspend fun getTopArtistSince(since: Long): ArtistPlayCount?

    /** Bu hafta en çok dinlenen şarkı */
    @Query("""
        SELECT songId, title, artist, COUNT(*) AS playCount
        FROM play_history
        WHERE playedAt >= :since
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT 1
    """)
    suspend fun getTopSongSince(since: Long): SongPlayCount?

    @Query("DELETE FROM play_history WHERE playedAt < :before")
    suspend fun purgeOlderThan(before: Long)
}

// ── App Prefs ─────────────────────────────────────────────────────────────────

@Dao
interface AppPrefDao {
    @Query("SELECT value FROM app_prefs WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(pref: AppPrefEntity)
}
