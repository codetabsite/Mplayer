package com.tdev.mplayr.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.data.AchievementManager
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.DayListenMs
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val db      = AppDatabase.get(this)
        val histDao = db.playHistoryDao()
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val monthAgo = System.currentTimeMillis() - 30 * 86_400_000L

        lifecycleScope.launch {
            histDao.getListenedMsSinceFlow(weekAgo).collect { ms ->
                val h = TimeUnit.MILLISECONDS.toHours(ms)
                val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
                findViewById<TextView>(R.id.tvWeeklyTime).text =
                    if (h > 0) "${h}s ${m}dk" else "${m} dakika"
            }
        }
        lifecycleScope.launch {
            val top = histDao.getTopArtistSince(weekAgo)
            findViewById<TextView>(R.id.tvTopArtist).text = top?.artist ?: "—"
        }
        lifecycleScope.launch {
            val top = histDao.getTopSongSince(weekAgo)
            findViewById<TextView>(R.id.tvTopSong).text =
                if (top != null) "${top.title}\n${top.artist}" else "—"
        }

        lifecycleScope.launch {
            val totalMs = histDao.getTotalListenedMs()
            val h = TimeUnit.MILLISECONDS.toHours(totalMs)
            val m = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60
            findViewById<TextView>(R.id.tvTotalTime).text = "${h}s ${m}dk"
        }
        lifecycleScope.launch {
            val count = histDao.getDistinctSongCountSince(monthAgo)
            findViewById<TextView>(R.id.tvMonthSongs).text = "$count şarkı"
        }
        lifecycleScope.launch {
            val hour = histDao.getMostActiveHour()
            if (hour != null) {
                val h = hour.hour
                val label = "%02d:00–%02d:00".format(h, (h + 1) % 24)
                findViewById<TextView>(R.id.tvActiveHour).text = label
            } else {
                findViewById<TextView>(R.id.tvActiveHour).text = "—"
            }
        }
        lifecycleScope.launch {
            val maxMs = histDao.getLongestListenMs()
            val m = TimeUnit.MILLISECONDS.toMinutes(maxMs)
            val s = TimeUnit.MILLISECONDS.toSeconds(maxMs) % 60
            findViewById<TextView>(R.id.tvLongestListen).text = "${m}dk ${s}sn"
        }

        lifecycleScope.launch {
            val days = histDao.getDailyListenMs(weekAgo)
            if (days.isNotEmpty()) {
                val chartView = DailyBarChart(this@StatsActivity, days)
                val container = findViewById<LinearLayout>(R.id.chartContainer)
                container.removeAllViews()
                container.addView(chartView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 260.dpToPx()
                ))
            }
        }

        val rvMost = findViewById<RecyclerView>(R.id.rvMostPlayed)
        rvMost.layoutManager = LinearLayoutManager(this)
        val mostAdapter = MostPlayedAdapter()
        rvMost.adapter = mostAdapter
        lifecycleScope.launch {
            histDao.getMostPlayedFlow(10).collect { mostAdapter.setData(it) }
        }

        val rvRecent = findViewById<RecyclerView>(R.id.rvRecent)
        rvRecent.layoutManager = LinearLayoutManager(this)
        val recentAdapter = RecentHistoryAdapter()
        rvRecent.adapter = recentAdapter
        lifecycleScope.launch {
            histDao.getRecentFlow(20).collect { recentAdapter.setData(it) }
        }

        lifecycleScope.launch {
            val unlocked = db.achievementDao().getUnlockedIds().toSet()
            val achContainer = findViewById<LinearLayout>(R.id.achContainer)
            runOnUiThread {
                achContainer.removeAllViews()
                AchievementManager.all.forEach { ach ->
                    val tv = TextView(this@StatsActivity).apply {
                        val done = ach.id in unlocked
                        text = "${if (done) ach.emoji else "🔒"} ${ach.title}"
                        setTextColor(if (done) 0xFFFFFFFF.toInt() else 0xFF666666.toInt())
                        textSize = 14f
                        setPadding(0, 8, 0, 8)
                    }
                    achContainer.addView(tv)
                }
            }
        }
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()
}

class DailyBarChart(ctx: android.content.Context, private val data: List<DayListenMs>) : View(ctx) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textSize = 28f; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val maxMs = data.maxOf { it.totalMs }.coerceAtLeast(1)
        val w     = width.toFloat()
        val h     = height.toFloat()
        val pad   = 40f
        val barW  = (w - pad * 2) / data.size - 6f
        data.forEachIndexed { i, d ->
            val ratio  = d.totalMs.toFloat() / maxMs
            val left   = pad + i * (barW + 6f)
            val top    = h - pad - (h - pad * 2) * ratio
            barPaint.alpha = 200
            canvas.drawRoundRect(left, top, left + barW, h - pad, 6f, 6f, barPaint)
            val label = d.day.takeLast(5)
            canvas.drawText(label, left + barW / 2, h - 8f, textPaint)
        }
    }
}
