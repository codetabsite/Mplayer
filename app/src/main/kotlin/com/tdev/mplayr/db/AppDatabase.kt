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
        AppPrefEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun appPrefDao(): AppPrefDao

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

        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                AppDatabase::class.java,
                "mplayr.db"
            )
            .addMigrations(MIGRATION_1_2)
            .build().also { INSTANCE = it }
        }
    }
}
