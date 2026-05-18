package com.floodguard.rescue.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.floodguard.rescue.R

class AlertBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val messageText: TextView
    private val dismissProgress: ProgressBar

    init {
        LayoutInflater.from(context).inflate(R.layout.view_alert_banner, this, true)
        messageText = findViewById(R.id.alert_message_text)
        dismissProgress = findViewById(R.id.dismiss_progress)
    }

    fun show(message: String) {
        messageText.text = message

        // Slide-down entrance
        translationY = -height.toFloat().coerceAtLeast(200f)
        alpha = 1f
        animate()
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Auto-dismiss progress countdown (6s)
        dismissProgress.progress = 100
        val progressAnim = ObjectAnimator.ofInt(dismissProgress, "progress", 100, 0)
        progressAnim.duration = AUTO_DISMISS_MS
        progressAnim.interpolator = AccelerateInterpolator()
        progressAnim.start()

        // Auto-dismiss fade-out
        postDelayed({
            animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    (parent as? android.view.ViewGroup)?.removeView(this)
                }
                .start()
        }, AUTO_DISMISS_MS)
    }

    companion object {
        private const val AUTO_DISMISS_MS = 6_000L
    }
}
