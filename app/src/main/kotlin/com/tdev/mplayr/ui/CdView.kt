package com.tdev.mplayr.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CdView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var albumBitmap: Bitmap? = null
    private var albumShader: BitmapShader? = null

    private val bitmapPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rimPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
        strokeWidth = 3f
    }
    private val shimmerPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val reflectPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val clipPath = Path()
    private val matrix   = Matrix()

    var rotationDeg: Float = 0f
        set(v) { field = v; invalidate() }

    // [8] CdView (Plak) Özelleştirme Seçenekleri
    var shimmerEnabled: Boolean = true
        set(v) { field = v; invalidate() }
    var reflectionRingsEnabled: Boolean = true
        set(v) { field = v; invalidate() }
    var diskFallbackColor: Int = 0xFF222222.toInt()
        set(v) { field = v; invalidate() }
    var rimColor: Int = 0x33FFFFFF
        set(v) { field = v; invalidate() }
    var holeColor: Int = Color.BLACK
        set(v) { field = v; invalidate() }

    fun setAlbumBitmap(bmp: Bitmap?) {
        albumBitmap = bmp
        albumShader = bmp?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width  / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) - 4f

        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)

        albumShader?.let { shader ->
            val bmp = albumBitmap!!
            val scale = (r * 2f) / minOf(bmp.width, bmp.height).toFloat()
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(cx - bmp.width * scale / 2f, cy - bmp.height * scale / 2f)
            shader.setLocalMatrix(matrix)
            bitmapPaint.shader = shader

            clipPath.reset()
            clipPath.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawCircle(cx, cy, r, bitmapPaint)
            canvas.restore()
        } ?: run {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = diskFallbackColor }
            canvas.drawCircle(cx, cy, r, p)
        }

        if (shimmerEnabled) {
            val shimmerColors  = intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF)
            val shimmerPositions = floatArrayOf(0f, 0.5f, 1f)
            val sweep = SweepGradient(cx, cy, shimmerColors, shimmerPositions)
            shimmerPaint.shader = sweep
            canvas.drawCircle(cx, cy, r * 0.75f, shimmerPaint)
            canvas.drawCircle(cx, cy, r * 0.55f, shimmerPaint)
        }

        if (reflectionRingsEnabled) {
            reflectPaint.color = 0x22FFFFFF
            for (i in 1..4) {
                canvas.drawCircle(cx, cy, r * (0.55f + i * 0.11f), reflectPaint)
            }
        }

        canvas.drawCircle(cx, cy, r * 0.14f, holePaint.apply { color = holeColor })
        rimPaint.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(cx, cy, r * 0.14f, rimPaint)
        rimPaint.color = rimColor
        canvas.drawCircle(cx, cy, r, rimPaint)

        canvas.restore()

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x22000000
            strokeWidth = 12f
            maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy + 8f, r, shadowPaint)
    }
}
