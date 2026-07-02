package com.tdev.mplayr.data

import com.tdev.mplayr.db.AchievementEntity
import com.tdev.mplayr.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val emoji: String,
    val hidden: Boolean = false // [15] Gizli Başarım — kilitliyken listede "???" gösterilir
)

data class AchievementProgress(
    val achievement: Achievement,
    val unlocked: Boolean,
    val currentValue: Long,
    val targetValue: Long
) {
    val percent: Int get() = if (targetValue <= 0) 0 else
        ((currentValue.toFloat() / targetValue.toFloat()) * 100).toInt().coerceIn(0, 100)
}

object AchievementManager {
    val all = listOf(
        Achievement("100h",      "Müzik Ustası",       "100 saat dinleme",          "🎓"),
        Achievement("1000songs", "Bin Şarkı",          "1000 farklı şarkı",         "🎵"),
        Achievement("nightowl",  "Gece Kuşu",          "Gece 00:00-05:00 arası 20+ dinleme", "🦉", hidden = true),
        Achievement("weekend",   "Hafta Sonu Maratoncusu", "Hafta sonları 100+ dinleme", "🏃"),
        Achievement("10h",       "Dinleyici",          "10 saat dinleme",           "🎧"),
        Achievement("explorer",  "Kaşif",              "50 farklı sanatçı",         "🗺️"),
        Achievement("retro",     "Retro Ruhlu",        "Gizli retro temayı aç",     "📼", hidden = true),
        // [15] Gizli Başarımlar (Easter Egg)
        Achievement("earlybird", "Erkenci Kuş",        "Sabah 05:00-07:00 arası 10+ dinleme", "🐦", hidden = true),
        Achievement("hoarder",   "Koleksiyoncu",       "500+ farklı şarkı çaldın",  "📦", hidden = true)
    )

    /** [14] Her başarım için ilerleme yüzdesi hesaplar (UI'daki progress bar için) */
    suspend fun getProgress(db: AppDatabase): List<AchievementProgress> {
        val dao = db.playHistoryDao()
        val unlocked = db.achievementDao().getUnlockedIds().toSet()
        val totalMs = dao.getTotalListenedMs()
        val totalSongs = dao.getTotalDistinctSongs()
        val nightCount = dao.getNightPlayCount()
        val weekendCount = dao.getWeekendPlayCount()
        val earlyBirdCount = dao.getEarlyBirdPlayCount()

        fun progressFor(id: String, current: Long, target: Long) =
            AchievementProgress(all.first { it.id == id }, id in unlocked, current, target)

        return listOf(
            progressFor("10h", TimeUnit_toHours(totalMs), 10),
            progressFor("100h", TimeUnit_toHours(totalMs), 100),
            progressFor("1000songs", totalSongs.toLong(), 1000),
            progressFor("nightowl", nightCount.toLong(), 20),
            progressFor("weekend", weekendCount.toLong(), 100),
            progressFor("hoarder", totalSongs.toLong(), 500),
            progressFor("earlybird", earlyBirdCount.toLong(), 10)
        )
    }

    private fun TimeUnit_toHours(ms: Long) = ms / 3_600_000L

    suspend fun check(db: AppDatabase, scope: CoroutineScope, onUnlock: (Achievement) -> Unit) {
        val dao     = db.playHistoryDao()
        val achDao  = db.achievementDao()
        val unlocked = achDao.getUnlockedIds().toSet()

        suspend fun tryUnlock(id: String) {
            if (id !in unlocked) {
                val rows = achDao.unlock(AchievementEntity(id))
                if (rows > 0) {
                    val ach = all.find { it.id == id } ?: return
                    scope.launch(Dispatchers.Main) { onUnlock(ach) }
                }
            }
        }

        val totalMs       = dao.getTotalListenedMs()
        val totalSongs    = dao.getTotalDistinctSongs()
        val nightCount    = dao.getNightPlayCount()
        val weekendCount  = dao.getWeekendPlayCount()
        val earlyBirdCount = dao.getEarlyBirdPlayCount()

        if (totalMs >= 10 * 3_600_000L)  tryUnlock("10h")
        if (totalMs >= 100 * 3_600_000L) tryUnlock("100h")
        if (totalSongs >= 1000)           tryUnlock("1000songs")
        if (nightCount >= 20)             tryUnlock("nightowl")
        if (weekendCount >= 100)          tryUnlock("weekend")
        if (earlyBirdCount >= 10)         tryUnlock("earlybird")
        if (totalSongs >= 500)            tryUnlock("hoarder")
    }
}
