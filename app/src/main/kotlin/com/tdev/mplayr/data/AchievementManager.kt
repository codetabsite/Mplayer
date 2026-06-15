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
    val emoji: String
)

object AchievementManager {
    val all = listOf(
        Achievement("100h",      "Müzik Ustası",       "100 saat dinleme",          "🎓"),
        Achievement("1000songs", "Bin Şarkı",          "1000 farklı şarkı",         "🎵"),
        Achievement("nightowl",  "Gece Kuşu",          "Gece 22:00-04:00 arası 50+ dinleme", "🦉"),
        Achievement("weekend",   "Hafta Sonu Maratoncusu", "Hafta sonları 100+ dinleme", "🏃"),
        Achievement("10h",       "Dinleyici",          "10 saat dinleme",           "🎧"),
        Achievement("explorer",  "Kaşif",              "50 farklı sanatçı",         "🗺️"),
        Achievement("retro",     "Retro Ruhlu",        "Gizli retro temayı aç",     "📼")
    )

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

        if (totalMs >= 10 * 3_600_000L)  tryUnlock("10h")
        if (totalMs >= 100 * 3_600_000L) tryUnlock("100h")
        if (totalSongs >= 1000)           tryUnlock("1000songs")
        if (nightCount >= 50)             tryUnlock("nightowl")
        if (weekendCount >= 100)          tryUnlock("weekend")
    }
}
