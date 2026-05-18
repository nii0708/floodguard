package com.floodguard.rescue.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.floodguard.rescue.R
import com.floodguard.rescue.memory.LandmarkRecord
import kotlin.math.max
import kotlin.math.min

class MapOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val landmarks = mutableListOf<LandmarkRecord>()
    private val pastLandmarks = mutableListOf<LandmarkRecord>()
    private val trail = mutableListOf<PointF>()
    private var currentWorldPos = floatArrayOf(0f, 0f, 0f)

    var isReviewMode = false
        set(value) {
            field = value
            invalidate()
        }

    private var isAlertActive = false
    private var alertLandmarkId: String? = null
    private var haloAlpha = 0f

    // Review mode pan/zoom
    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Tap detection for landmark selection
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTime = 0L
    private var selectedLandmark: LandmarkRecord? = null
    private val landmarkScreenPos = mutableMapOf<String, PointF>()

    var onLandmarkTapListener: ((LandmarkRecord) -> Unit)? = null

    // Cached legend strings
    private val legendPositionText = context.getString(R.string.legend_position)
    private val legendPastText = context.getString(R.string.legend_past_session)

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 5f)
                invalidate()
                return true
            }
        }
    )

    private val haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            haloAlpha = it.animatedValue as Float
            if (isAlertActive) invalidate()
        }
    }

    // Paints with design token colors
    private val bgPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.bg_surface)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.border_default)
        strokeWidth = 2.4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val trailPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.amber)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val landmarkPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.map_position)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val currentPosPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.map_position)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val posHaloPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.map_position_halo)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val pastLandmarkPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val alertHaloPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.status_critical_alpha27)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val selectionRingPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        isAntiAlias = true
    }
    private val scaleLabelPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        textSize = 28f
        isAntiAlias = true
    }
    private val northPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val landmarkNumberPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val scaleBarPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Legend paints
    private val legendBgPaint = Paint().apply {
        color = Color.parseColor("#CC0D1520") // bg_surface with 80% alpha
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val legendBorderPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.border_default)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val legendTextPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 20f
        isAntiAlias = true
    }
    private val legendDotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateLandmarks(records: List<LandmarkRecord>) {
        landmarks.clear()
        landmarks.addAll(records)
        invalidate()
    }

    fun updatePastLandmarks(records: List<LandmarkRecord>) {
        pastLandmarks.clear()
        pastLandmarks.addAll(records)
        invalidate()
    }

    fun updateCurrentPose(pose: FloatArray) {
        currentWorldPos = pose
        trail.add(PointF(pose[0], pose[2]))
        invalidate()
    }

    fun updateTrail(points: List<PointF>) {
        trail.clear()
        trail.addAll(points)
        if (points.isNotEmpty()) {
            val last = points.last()
            currentWorldPos = floatArrayOf(last.x, 0f, last.y)
        }
        invalidate()
    }

    fun setAlertActive(landmarkId: String?) {
        if (landmarkId != null) {
            isAlertActive = true
            alertLandmarkId = landmarkId
            if (!haloAnimator.isRunning) haloAnimator.start()
        } else {
            isAlertActive = false
            alertLandmarkId = null
            haloAnimator.cancel()
        }
        invalidate()
    }

    fun clear() {
        trail.clear()
        landmarks.clear()
        pastLandmarks.clear()
        currentWorldPos = floatArrayOf(0f, 0f, 0f)
        isAlertActive = false
        alertLandmarkId = null
        selectedLandmark = null
        landmarkScreenPos.clear()
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isReviewMode) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX = event.x
                tapDownY = event.y
                tapDownTime = System.currentTimeMillis()
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                }
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - tapDownX
                val dy = event.y - tapDownY
                val distSq = dx * dx + dy * dy
                val elapsed = System.currentTimeMillis() - tapDownTime
                if (distSq < TAP_SLOP_SQ && elapsed < TAP_TIMEOUT_MS) {
                    handleLandmarkTap(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun handleLandmarkTap(x: Float, y: Float) {
        var closest: LandmarkRecord? = null
        var closestDist = Float.MAX_VALUE

        for ((id, pos) in landmarkScreenPos) {
            val dx = x - pos.x
            val dy = y - pos.y
            val dist = dx * dx + dy * dy
            if (dist < closestDist && dist < HIT_RADIUS_SQ) {
                closestDist = dist
                closest = landmarks.find { it.id == id }
                    ?: pastLandmarks.find { it.id == id }
            }
        }

        if (closest != null) {
            selectedLandmark = closest
            onLandmarkTapListener?.invoke(closest)
            invalidate()
        } else {
            if (selectedLandmark != null) {
                selectedLandmark = null
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return

        landmarkScreenPos.clear()

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, 14f, 14f, bgPaint)

        // Border
        borderPaint.color = if (isAlertActive) ContextCompat.getColor(context, R.color.status_critical_alpha27) else ContextCompat.getColor(context, R.color.border_default)
        borderPaint.strokeWidth = if (isAlertActive) 3.6f else 2.4f
        canvas.drawRoundRect(0f, 0f, w, h, 14f, 14f, borderPaint)

        // Bounds (include past landmarks)
        val allX = trail.map { it.x } + landmarks.map { it.position[0] } + pastLandmarks.map { it.position[0] } + listOf(currentWorldPos[0])
        val allZ = trail.map { it.y } + landmarks.map { it.position[2] } + pastLandmarks.map { it.position[2] } + listOf(currentWorldPos[2])
        val minX = allX.minOrNull() ?: 0f; val maxX = max(allX.maxOrNull() ?: 1f, minX + 1f)
        val minZ = allZ.minOrNull() ?: 0f; val maxZ = max(allZ.maxOrNull() ?: 1f, minZ + 1f)
        val rangeX = maxX - minX
        val rangeZ = maxZ - minZ

        val pad = 28f
        fun worldToScreen(wx: Float, wz: Float): PointF {
            val sx = pad + (wx - minX) / rangeX * (w - pad * 2)
            val sy = pad + (wz - minZ) / rangeZ * (h - pad * 2)
            return if (isReviewMode) {
                PointF(sx * scaleFactor + panX, sy * scaleFactor + panY)
            } else {
                PointF(sx, sy)
            }
        }

        // Trail
        if (trail.size >= 2) {
            val total = trail.size - 1
            for (i in 0 until total) {
                val p1 = worldToScreen(trail[i].x, trail[i].y)
                val p2 = worldToScreen(trail[i + 1].x, trail[i + 1].y)

                val ratio = i.toFloat() / total
                val amber = ContextCompat.getColor(context, R.color.amber)
                trailPaint.color = amber
                trailPaint.alpha = (80 + ratio * 175).toInt().coerceIn(0, 255)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, trailPaint)
            }
        }

        // Past landmarks
        pastLandmarks.forEach { lm ->
            val pt = worldToScreen(lm.position[0], lm.position[2])
            landmarkScreenPos[lm.id] = pt
            canvas.drawCircle(pt.x, pt.y, 6f, pastLandmarkPaint)
        }

        // Current landmarks
        val sorted = landmarks.sortedBy { it.timestamp }
        sorted.forEachIndexed { index, lm ->
            val pt = worldToScreen(lm.position[0], lm.position[2])
            landmarkScreenPos[lm.id] = pt
            if (isAlertActive && lm.id == alertLandmarkId) {
                alertHaloPaint.alpha = (haloAlpha * 128).toInt().coerceIn(0, 255)
                canvas.drawCircle(pt.x, pt.y, 24f, alertHaloPaint)
            }
            canvas.drawCircle(pt.x, pt.y, 14f, landmarkPaint)
            canvas.drawText((index + 1).toString(), pt.x, pt.y + 5f, landmarkNumberPaint)
        }

        selectedLandmark?.let { sel ->
            landmarkScreenPos[sel.id]?.let { pt ->
                canvas.drawCircle(pt.x, pt.y, 16f, selectionRingPaint)
            }
        }

        // Current position
        val cp = worldToScreen(currentWorldPos[0], currentWorldPos[2])
        canvas.drawCircle(cp.x, cp.y, 22f, posHaloPaint)
        canvas.drawCircle(cp.x, cp.y, 12f, currentPosPaint)

        // Scale bar
        val scaleBarMeters = when {
            rangeX > 50 -> 20f
            rangeX > 20 -> 10f
            else -> 5f
        }
        val scaleBarPx = scaleBarMeters / rangeX * (w - pad * 2)
        val sby = h - 18f
        val sbx = 16f
        canvas.drawLine(sbx, sby, sbx + scaleBarPx, sby, scaleBarPaint)
        canvas.drawText("${scaleBarMeters.toInt()}m", sbx, sby - 6f, scaleLabelPaint)

        // North
        canvas.drawText("N", w - 22f, 26f, northPaint)

        // Landmark count
        val pastSuffix = if (pastLandmarks.isNotEmpty()) {
            " (+${pastLandmarks.size} past)"
        } else ""
        val countText = "Landmarks: ${landmarks.size}$pastSuffix"
        canvas.drawText(countText, 20f, h - 40f, labelPaint)

        drawLegend(canvas)
    }

    private fun drawLegend(canvas: Canvas) {
        val hasPosition = trail.isNotEmpty() || landmarks.isNotEmpty()
        val hasCurrent = landmarks.isNotEmpty()
        val hasPast = pastLandmarks.isNotEmpty()

        if (!hasPosition && !hasCurrent && !hasPast) return

        val items = mutableListOf<Pair<Int, String>>()
        if (hasPosition) items.add(ContextCompat.getColor(context, R.color.map_position) to legendPositionText)
        if (hasCurrent) items.add(ContextCompat.getColor(context, R.color.map_position) to "Landmarks")
        if (hasPast) items.add(ContextCompat.getColor(context, R.color.text_tertiary) to legendPastText)

        if (items.isEmpty()) return

        val dotRadius = 5f
        val dotTextGap = 8f
        val lineHeight = 22f
        val padH = 10f
        val padV = 8f
        val startX = 10f
        val startY = 10f

        var maxTextWidth = 0f
        for ((_, label) in items) {
            val tw = legendTextPaint.measureText(label)
            if (tw > maxTextWidth) maxTextWidth = tw
        }

        val boxW = padH * 2 + dotRadius * 2 + dotTextGap + maxTextWidth
        val boxH = padV * 2 + items.size * lineHeight

        val rect = RectF(startX, startY, startX + boxW, startY + boxH)
        canvas.drawRoundRect(rect, 8f, 8f, legendBgPaint)
        canvas.drawRoundRect(rect, 8f, 8f, legendBorderPaint)

        items.forEachIndexed { index, (color, label) ->
            val cy = startY + padV + index * lineHeight + lineHeight / 2f
            val cx = startX + padH + dotRadius
            legendDotPaint.color = color
            canvas.drawCircle(cx, cy, dotRadius, legendDotPaint)
            canvas.drawText(label, cx + dotRadius + dotTextGap, cy + legendTextPaint.textSize / 3f, legendTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        haloAnimator.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAP_SLOP_SQ = 400f
        private const val TAP_TIMEOUT_MS = 300L
        private const val HIT_RADIUS_SQ = 900f
    }
}
