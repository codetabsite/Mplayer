package com.tdev.mplayr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * [9] Waveform (Dalga Şekilli) SeekBar:
 *   Basit çubuk (bar) tabanlı dalga formu gösterimi. Gerçek ses analiziyle
 *   üretilen amplitüd listesini alır (bkz. WaveformExtractor), progress'e göre
 *   çalınan kısmı renklendirir. Dokunma/kaydırma ile seek callback'i tetikler.
 */
class WaveformSeekBar @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var amplitudes: List<Float> = emptyList() // 0f..1f normalize edilmiş
    private var progress: Float = 0f // 0f..1f

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }

    var onSeek: ((Float) -> Unit)? = null

    fun setAmplitudes(values: List<Float>) {
        amplitudes = values
        invalidate()
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun setColors(played: Int, unplayed: Int) {
        playedPaint.color = played
        unplayedPaint.color = unplayed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty()) return

        val barCount = amplitudes.size
        val barWidth = width.toFloat() / barCount
        val gap = barWidth * 0.25f
        val centerY = height / 2f
        val playedBars = (barCount * progress).toInt()

        for (i in 0 until barCount) {
            val amp = amplitudes[i].coerceIn(0.05f, 1f)
            val barHeight = height * amp
            val left = i * barWidth + gap / 2
            val right = left + (barWidth - gap)
            val top = centerY - barHeight / 2
            val bottom = centerY + barHeight / 2
            canvas.drawRoundRect(
                left, top, right, bottom, 4f, 4f,
                if (i < playedBars) playedPaint else unplayedPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val ratio = (event.x / width).coerceIn(0f, 1f)
                setProgress(ratio)
                onSeek?.invoke(ratio)
                return true
            }
            MotionEvent.ACTION_UP -> return true
        }
        return super.onTouchEvent(event)
    }
}
