package com.tdev.mplayr.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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

data class SongPlayCount(val songId: Long, val title: String, val artist: String, val playCount: Int)
data class ArtistPlayCount(val artist: String, val playCount: Int)
data class HourPlayCount(val hour: Int, val playCount: Int)
data class DayListenMs(val day: String, val totalMs: Long)

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

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

    @Query("""
        SELECT songId, title, artist, COUNT(*) AS playCount
        FROM play_history
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    fun getMostPlayedFlow(limit: Int = 20): Flow<List<SongPlayCount>>

    @Query("""
        SELECT songId, title, artist, COUNT(*) AS playCount
        FROM play_history
        WHERE playedAt >= :since
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    suspend fun getMostPlayedSince(since: Long, limit: Int = 20): List<SongPlayCount>

    @Query("""
        SELECT COALESCE(SUM(listenedMs), 0)
        FROM play_history
        WHERE playedAt >= :since
    """)
    fun getListenedMsSinceFlow(since: Long): Flow<Long>

    @Query("""
        SELECT COALESCE(SUM(maxMs), 0)
        FROM (
            SELECT MAX(listenedMs) AS maxMs
            FROM play_history
            GROUP BY songId
        )
    """)
    suspend fun getTotalListenedMs(): Long

    @Query("""
        SELECT artist, COUNT(*) AS playCount
        FROM play_history
        WHERE playedAt >= :since
        GROUP BY artist
        ORDER BY playCount DESC
        LIMIT 1
    """)
    suspend fun getTopArtistSince(since: Long): ArtistPlayCount?

    @Query("""
        SELECT songId, title, artist, COUNT(*) AS playCount
        FROM play_history
        WHERE playedAt >= :since
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT 1
    """)
    suspend fun getTopSongSince(since: Long): SongPlayCount?

    @Query("""
        SELECT strftime('%H', datetime(playedAt/1000, 'unixepoch', 'localtime')) AS hour,
               COUNT(*) AS playCount
        FROM play_history
        GROUP BY hour
        ORDER BY playCount DESC
        LIMIT 1
    """)
    suspend fun getMostActiveHour(): HourPlayCount?

    @Query("""
        SELECT strftime('%Y-%m-%d', datetime(playedAt/1000, 'unixepoch', 'localtime')) AS day,
               SUM(listenedMs) AS totalMs
        FROM play_history
        WHERE playedAt >= :since
        GROUP BY day
        ORDER BY day ASC
    """)
    suspend fun getDailyListenMs(since: Long): List<DayListenMs>

    @Query("""
        SELECT COUNT(DISTINCT songId) FROM play_history
        WHERE playedAt >= :since
    """)
    suspend fun getDistinctSongCountSince(since: Long): Int

    @Query("""
        SELECT COALESCE(MAX(listenedMs), 0) FROM play_history
    """)
    suspend fun getLongestListenMs(): Long

    @Query("""
        SELECT songId FROM play_history
        WHERE playedAt >= :since
        GROUP BY songId
    """)
    suspend fun getPlayedSongIdsSince(since: Long): List<Long>

    @Query("""
        SELECT COUNT(*) FROM play_history
        WHERE strftime('%H', datetime(playedAt/1000, 'unixepoch', 'localtime'))
              BETWEEN '22' AND '23'
           OR strftime('%H', datetime(playedAt/1000, 'unixepoch', 'localtime'))
              BETWEEN '00' AND '04'
    """)
    suspend fun getNightPlayCount(): Int

    @Query("""
        SELECT COUNT(*) FROM play_history
        WHERE strftime('%w', datetime(playedAt/1000, 'unixepoch', 'localtime'))
              IN ('0','6')
    """)
    suspend fun getWeekendPlayCount(): Int

    @Query("""
        SELECT COUNT(DISTINCT songId) FROM play_history
        WHERE listenedMs >= 2000
    """)
    suspend fun getTotalDistinctSongs(): Int

    @Query("DELETE FROM play_history WHERE playedAt < :before")
    suspend fun purgeOlderThan(before: Long)
}

@Dao
interface AchievementDao {
    @Query("SELECT id FROM achievements")
    suspend fun getUnlockedIds(): List<String>

    @Query("SELECT id FROM achievements")
    fun getUnlockedIdsFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun unlock(achievement: AchievementEntity): Long
}

@Dao
interface SavedQueueDao {
    @Query("SELECT * FROM saved_queues ORDER BY createdAt DESC")
    suspend fun getAll(): List<SavedQueueEntity>

    @Insert
    suspend fun insert(queue: SavedQueueEntity): Long

    @Delete
    suspend fun delete(queue: SavedQueueEntity)
}

@Dao
interface AppPrefDao {
    @Query("SELECT value FROM app_prefs WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(pref: AppPrefEntity)
}
