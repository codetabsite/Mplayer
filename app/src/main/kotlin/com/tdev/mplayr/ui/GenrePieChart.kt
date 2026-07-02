package com.tdev.mplayr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * [16] Tür Dağılımı Pasta Grafiği:
 *   Basit Canvas ile çizilen pasta dilimleri. Karmaşık grafik kütüphanesi kullanılmaz.
 */
class GenrePieChart(ctx: Context, private val data: List<Pair<String, Int>>) : View(ctx) {

    private val colors = intArrayOf(
        0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFF81C784.toInt(), 0xFFFFD54F.toInt(),
        0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(), 0xFFFF8A65.toInt(), 0xFF90A4AE.toInt()
    )
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f
    }
    private val legendBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val total = data.sumOf { it.second }.coerceAtLeast(1)

        val diameter = minOf(width * 0.55f, height.toFloat())
        val cx = diameter / 2f + 16f
        val cy = height / 2f
        rect.set(cx - diameter / 2f, cy - diameter / 2f, cx + diameter / 2f, cy + diameter / 2f)

        var startAngle = -90f
        data.forEachIndexed { i, (_, count) ->
            val sweep = 360f * count / total
            slicePaint.color = colors[i % colors.size]
            canvas.drawArc(rect, startAngle, sweep, true, slicePaint)
            startAngle += sweep
        }

        // Legend (sağ taraf, tür adı + yüzde)
        var legendY = cy - diameter / 2f + 10f
        val legendX = cx + diameter / 2f + 30f
        data.forEachIndexed { i, (genre, count) ->
            if (legendY > height - 10f) return@forEachIndexed
            legendBoxPaint.color = colors[i % colors.size]
            canvas.drawRect(legendX, legendY, legendX + 24f, legendY + 24f, legendBoxPaint)
            val pct = (count * 100f / total).toInt()
            canvas.drawText("$genre ($pct%)", legendX + 32f, legendY + 20f, textPaint)
            legendY += 36f
        }
    }
}
