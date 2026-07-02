package com.tdev.mplayr.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        FavoriteEntity::class,
        PlayHistoryEntity::class,
        AppPrefEntity::class,
        AchievementEntity::class,
        SavedQueueEntity::class,
        SongNoteEntity::class,
        LyricsLineEntity::class,
        DeletedSongEntity::class,
        BlacklistedFolderEntity::class,
        ABLoopEntity::class,
        SongGainEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun appPrefDao(): AppPrefDao
    abstract fun achievementDao(): AchievementDao
    abstract fun savedQueueDao(): SavedQueueDao
    abstract fun songNoteDao(): SongNoteDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun deletedSongDao(): DeletedSongDao
    abstract fun blacklistDao(): BlacklistDao
    abstract fun abLoopDao(): ABLoopDao
    abstract fun songGainDao(): SongGainDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS play_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        songId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        playedAt INTEGER NOT NULL,
                        listenedMs INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_prefs (
                        `key` TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN albumId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE play_history ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS achievements (
                        id TEXT PRIMARY KEY NOT NULL,
                        unlockedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_queues (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        songIds TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS song_notes (
                        songId INTEGER PRIMARY KEY NOT NULL,
                        note TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lyrics_lines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        songId INTEGER NOT NULL,
                        timeMs INTEGER NOT NULL,
                        text TEXT NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS deleted_songs (
                        songId INTEGER PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS blacklisted_folders (
                        path TEXT PRIMARY KEY NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ab_loops (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        songId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        startMs INTEGER NOT NULL,
                        endMs INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS song_gain (
                        songId INTEGER PRIMARY KEY NOT NULL,
                        gainDb REAL NOT NULL
                    )
                """)
            }
        }

        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                AppDatabase::class.java,
                "mplayr.db"
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build().also { INSTANCE = it }
        }
    }
}
