package com.example.pcremote

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

/** Icon ids for [IconDrawable]. */
object Ic {
    const val MENU = 0
    const val BACK = 1
    const val TUNE = 2
    const val EXPAND = 3
    const val SHRINK = 4
    const val KEYBOARD = 5
    const val HELP = 6
    const val TOUCHPAD = 7
}

/**
 * Simple line icons drawn in code (24x24 grid), so the app needs no image files.
 * The colour is asked for every time it draws, so icons follow the light/dark theme.
 */
class IconDrawable(
    private val type: Int,
    private val sizePx: Int,
    private val colorProvider: () -> Int
) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 13f
    }
    private val path = Path()

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    private fun poly(c: Canvas, vararg v: Float) {
        path.reset()
        path.moveTo(v[0], v[1])
        var i = 2
        while (i < v.size - 1) {
            path.lineTo(v[i], v[i + 1])
            i += 2
        }
        c.drawPath(path, p)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        val color = colorProvider()
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        canvas.scale(s, s)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f

        when (type) {
            Ic.MENU -> {
                poly(canvas, 4f, 7f, 20f, 7f)
                poly(canvas, 4f, 12f, 20f, 12f)
                poly(canvas, 4f, 17f, 20f, 17f)
            }
            Ic.BACK -> {
                poly(canvas, 20f, 12f, 5f, 12f)
                poly(canvas, 11f, 6f, 5f, 12f, 11f, 18f)
            }
            Ic.TUNE -> {
                poly(canvas, 4f, 6f, 20f, 6f)
                poly(canvas, 4f, 12f, 20f, 12f)
                poly(canvas, 4f, 18f, 20f, 18f)
                p.style = Paint.Style.FILL
                canvas.drawCircle(9f, 6f, 2.6f, p)
                canvas.drawCircle(15f, 12f, 2.6f, p)
                canvas.drawCircle(8f, 18f, 2.6f, p)
            }
            Ic.EXPAND -> {
                poly(canvas, 4f, 9f, 4f, 4f, 9f, 4f)
                poly(canvas, 15f, 4f, 20f, 4f, 20f, 9f)
                poly(canvas, 20f, 15f, 20f, 20f, 15f, 20f)
                poly(canvas, 9f, 20f, 4f, 20f, 4f, 15f)
            }
            Ic.SHRINK -> {
                poly(canvas, 9f, 4f, 9f, 9f, 4f, 9f)
                poly(canvas, 15f, 4f, 15f, 9f, 20f, 9f)
                poly(canvas, 20f, 15f, 15f, 15f, 15f, 20f)
                poly(canvas, 4f, 15f, 9f, 15f, 9f, 20f)
            }
            Ic.KEYBOARD -> {
                canvas.drawRoundRect(RectF(2.5f, 6f, 21.5f, 18f), 2.5f, 2.5f, p)
                p.style = Paint.Style.FILL
                for (x in floatArrayOf(6.5f, 10f, 14f, 17.5f)) canvas.drawCircle(x, 10f, 1.05f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.8f
                poly(canvas, 8f, 14.5f, 16f, 14.5f)
            }
            Ic.HELP -> {
                canvas.drawCircle(12f, 12f, 9f, p)
                textPaint.color = color
                canvas.drawText("?", 12f, 16.6f, textPaint)
            }
            Ic.TOUCHPAD -> {
                canvas.drawRoundRect(RectF(3.5f, 4.5f, 20.5f, 19.5f), 3.5f, 3.5f, p)
                p.strokeWidth = 1.6f
                poly(canvas, 3.5f, 14.5f, 20.5f, 14.5f)
                poly(canvas, 12f, 14.5f, 12f, 19.5f)
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(8f, 7f)
                path.lineTo(8f, 12.2f)
                path.lineTo(9.5f, 10.9f)
                path.lineTo(10.6f, 13.2f)
                path.lineTo(11.6f, 12.8f)
                path.lineTo(10.5f, 10.5f)
                path.lineTo(12.5f, 10.4f)
                path.close()
                canvas.drawPath(path, p)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
