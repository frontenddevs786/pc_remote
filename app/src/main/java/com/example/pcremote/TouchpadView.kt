package com.example.pcremote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One finger = move cursor, tap = left click, two-finger tap = right click,
 * two-finger drag = scroll.
 */
@SuppressLint("ClickableViewAccessibility")
class TouchpadView(context: Context) : View(context) {

    var onMove: (Int, Int) -> Unit = { _, _ -> }
    var onScroll: (Int) -> Unit = {}
    var onTap: () -> Unit = {}
    var onTwoFingerTap: () -> Unit = {}

    /** Base cursor speed multiplier. */
    var sensitivity = 1.8f

    /** When on, faster finger movement moves the cursor further (like a real touchpad). */
    var accel = true

    private val density = resources.displayMetrics.density
    private val tapSlop = 18 * density
    private val scrollStep = 14 * density

    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var accX = 0f
    private var accY = 0f
    private var accScroll = 0f
    private var speedAvg = 0f
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2E.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A92.toInt()
        textSize = 14 * density
        textAlign = Paint.Align.CENTER
    }

    fun setColors(background: Int, hint: Int) {
        bgPaint.color = background
        textPaint.color = hint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(r, 16 * density, 16 * density, bgPaint)
        val cx = width / 2f
        val cy = height / 2f
        textPaint.textSize = 18 * density
        canvas.drawText("Touchpad", cx, cy - 22 * density, textPaint)
        textPaint.textSize = 13 * density
        canvas.drawText("Tap: click", cx, cy + 2 * density, textPaint)
        canvas.drawText("Two-finger tap: right click", cx, cy + 22 * density, textPaint)
        canvas.drawText("Two-finger drag: scroll", cx, cy + 42 * density, textPaint)
    }

    private fun avgX(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getX(i); n++ }
        return if (n == 0) 0f else s / n
    }

    private fun avgY(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getY(i); n++ }
        return if (n == 0) 0f else s / n
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime
                lastTime = e.eventTime
                moved = 0f
                maxPointers = 1
                lastX = e.x
                lastY = e.y
                accX = 0f
                accY = 0f
                accScroll = 0f
                speedAvg = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                lastX = avgX(e)
                lastY = avgY(e)
                lastTime = e.eventTime
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = avgX(e, e.actionIndex)
                lastY = avgY(e, e.actionIndex)
                lastTime = e.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                val x = avgX(e)
                val y = avgY(e)
                val dx = x - lastX
                val dy = y - lastY
                val dt = max(1L, e.eventTime - lastTime)
                lastX = x
                lastY = y
                lastTime = e.eventTime
                moved += abs(dx) + abs(dy)
                if (e.pointerCount >= 2) {
                    accScroll += dy
                    val steps = (accScroll / scrollStep).toInt()
                    if (steps != 0) {
                        accScroll -= steps * scrollStep
                        onScroll(steps)
                    }
                } else {
                    var gain = sensitivity
                    if (accel) {
                        // finger speed in dp per millisecond, smoothed so the gain changes gradually
                        val speed = sqrt(dx * dx + dy * dy) / density / dt
                        speedAvg = 0.7f * speedAvg + 0.3f * speed
                        gain *= 1f + min(speedAvg * 0.6f, 2f)
                    }
                    accX += dx * gain
                    accY += dy * gain
                    val ix = accX.toInt()
                    val iy = accY.toInt()
                    if (ix != 0 || iy != 0) {
                        accX -= ix
                        accY -= iy
                        onMove(ix, iy)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = e.eventTime - downTime
                if (duration < 250 && moved < tapSlop) {
                    if (maxPointers == 1) onTap() else if (maxPointers == 2) onTwoFingerTap()
                }
            }
        }
        return true
    }
}
