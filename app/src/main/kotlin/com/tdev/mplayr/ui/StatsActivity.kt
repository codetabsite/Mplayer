package com.tdev.mplayr.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.db.AppDatabase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val db = AppDatabase.get(this)
        val histDao = db.playHistoryDao()
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L

        // Haftalık dinleme süresi
        lifecycleScope.launch {
            histDao.getListenedMsSinceFlow(weekAgo).collect { ms ->
                val hours   = TimeUnit.MILLISECONDS.toHours(ms)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
                findViewById<TextView>(R.id.tvWeeklyTime).text =
                    if (hours > 0) "${hours}s ${minutes}dk" else "${minutes} dakika"
            }
        }

        // En çok dinlenen sanatçı (bu hafta)
        lifecycleScope.launch {
            val top = histDao.getTopArtistSince(weekAgo)
            findViewById<TextView>(R.id.tvTopArtist).text = top?.artist ?: "—"
        }

        // En çok dinlenen şarkı (bu hafta)
        lifecycleScope.launch {
            val top = histDao.getTopSongSince(weekAgo)
            findViewById<TextView>(R.id.tvTopSong).text =
                if (top != null) "${top.title}\n${top.artist}" else "—"
        }

        // En çok dinlenenler listesi
        val rvMostPlayed = findViewById<RecyclerView>(R.id.rvMostPlayed)
        rvMostPlayed.layoutManager = LinearLayoutManager(this)
        val adapter = MostPlayedAdapter()
        rvMostPlayed.adapter = adapter

        lifecycleScope.launch {
            histDao.getMostPlayedFlow(10).collect { list ->
                adapter.setData(list)
            }
        }

        // Son dinlenenler listesi
        val rvRecent = findViewById<RecyclerView>(R.id.rvRecent)
        rvRecent.layoutManager = LinearLayoutManager(this)
        val recentAdapter = RecentHistoryAdapter()
        rvRecent.adapter = recentAdapter

        lifecycleScope.launch {
            histDao.getRecentFlow(20).collect { list ->
                recentAdapter.setData(list)
            }
        }
    }
}
