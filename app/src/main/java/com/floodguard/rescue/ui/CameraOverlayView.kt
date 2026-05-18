package com.floodguard.rescue.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.floodguard.rescue.R

class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint().apply {
        color = 0xFF0D2218.toInt()
        strokeWidth = 1.4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Rule-of-thirds grid
        canvas.drawLine(w * 0.333f, 0f, w * 0.333f, h, gridPaint)
        canvas.drawLine(w * 0.667f, 0f, w * 0.667f, h, gridPaint)
        canvas.drawLine(0f, h * 0.333f, w, h * 0.333f, gridPaint)
        canvas.drawLine(0f, h * 0.667f, w, h * 0.667f, gridPaint)
    }
}
