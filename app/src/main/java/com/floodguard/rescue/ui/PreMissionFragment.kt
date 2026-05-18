package com.floodguard.rescue.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.floodguard.rescue.R
import com.floodguard.rescue.ui.GemmaState
import com.floodguard.rescue.session.ExplorationHistory
import com.floodguard.rescue.state.MissionState
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.launch

class PreMissionFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pre_mission, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val heroCard = view.findViewById<LinearLayout>(R.id.status_hero_card)
        val iconBox = view.findViewById<FrameLayout>(R.id.status_icon_box)
        val heroIcon = view.findViewById<TextView>(R.id.status_hero_icon)
        val heroText = view.findViewById<TextView>(R.id.status_hero_text)
        val heroSubtext = view.findViewById<TextView>(R.id.status_hero_subtext)
        
        val cameraStatus = view.findViewById<TextView>(R.id.check_camera_status)
        val sensorsStatus = view.findViewById<TextView>(R.id.check_sensors_status)
        val modelStatus = view.findViewById<TextView>(R.id.check_model_status)
        val audioStatus = view.findViewById<TextView>(R.id.check_audio_status)
        val batteryStatus = view.findViewById<TextView>(R.id.check_battery_status)
        
        val btnStart = view.findViewById<Button>(R.id.btn_start)
        val countdownOverlay = view.findViewById<FrameLayout>(R.id.countdown_overlay)
        val countdownText = view.findViewById<TextView>(R.id.countdown_text)

        // Language Switcher
        val btnLangEn = view.findViewById<TextView>(R.id.btn_lang_en)
        val btnLangId = view.findViewById<TextView>(R.id.btn_lang_id)

        val currentLocale = AppCompatDelegate.getApplicationLocales().get(0)?.language
        if (currentLocale == "in" || currentLocale == "id") {
            btnLangId.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            btnLangEn.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
        } else {
            btnLangEn.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            btnLangId.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
        }

        btnLangEn.setOnClickListener {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        btnLangId.setOnClickListener {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("in"))
        }

        // Tutorial Button
        view.findViewById<View>(R.id.btn_tutorial).setOnClickListener {
            TutorialBottomSheetFragment().show(parentFragmentManager, "tutorial")
        }

        // Run system checks
        var criticalFails = 0
        var partialFails = 0

        fun updateModelBadge(state: GemmaState) {
            when (state) {
                GemmaState.LOADING -> {
                    modelStatus.text = getString(R.string.ai_badge_loading)
                    modelStatus.setBackgroundResource(R.drawable.badge_degraded)
                    modelStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                }
                GemmaState.READY -> {
                    modelStatus.text = "OK"
                    modelStatus.setBackgroundResource(R.drawable.badge_healthy)
                    modelStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
                }
                GemmaState.FAILED -> {
                    modelStatus.text = "FAIL"
                    modelStatus.setBackgroundResource(R.drawable.badge_critical)
                    modelStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_critical))
                }
            }
        }

        // 1. Camera
        val hasCam = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val arAvailable = ArCoreApk.getInstance().checkAvailability(ctx).isSupported
        when {
            hasCam && arAvailable -> {
                cameraStatus.text = "OK"
                cameraStatus.setBackgroundResource(R.drawable.badge_healthy)
                cameraStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
            }
            hasCam -> {
                cameraStatus.text = "LIMITED"
                cameraStatus.setBackgroundResource(R.drawable.badge_degraded)
                cameraStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                partialFails++
            }
            else -> {
                cameraStatus.text = "FAIL"
                cameraStatus.setBackgroundResource(R.drawable.badge_critical)
                cameraStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_critical))
                criticalFails++
            }
        }

        // 2. Sensors
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var sensorCount = 0
        if (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null) sensorCount++
        if (sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null) sensorCount++
        if (sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null) sensorCount++
        sensorsStatus.text = getString(R.string.sensor_count_format, sensorCount)
        when {
            sensorCount == 3 -> {
                sensorsStatus.setBackgroundResource(R.drawable.badge_healthy)
                sensorsStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
            }
            sensorCount >= 1 -> {
                sensorsStatus.setBackgroundResource(R.drawable.badge_degraded)
                sensorsStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                partialFails++
            }
            else -> {
                sensorsStatus.setBackgroundResource(R.drawable.badge_critical)
                sensorsStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_critical))
                criticalFails++
            }
        }

        // 3. Model AI — badge and hero card are updated live via gemmaState observer below

        // 4. Audio
        if (viewModel.alertSystem?.isReady() == true) {
            audioStatus.text = "OK"
            audioStatus.setBackgroundResource(R.drawable.badge_healthy)
            audioStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
        } else {
            audioStatus.text = "LIMITED"
            audioStatus.setBackgroundResource(R.drawable.badge_degraded)
            audioStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
            partialFails++
        }

        // 5. Battery
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0) (level * 100 / scale) else 100
        batteryStatus.text = getString(R.string.battery_level_format, batteryPct)
        when {
            batteryPct >= 30 -> {
                batteryStatus.setBackgroundResource(R.drawable.badge_healthy)
                batteryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
            }
            batteryPct >= 15 -> {
                batteryStatus.setBackgroundResource(R.drawable.badge_degraded)
                batteryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                partialFails++
            }
            else -> {
                batteryStatus.setBackgroundResource(R.drawable.badge_critical)
                batteryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_critical))
                criticalFails++
            }
        }

        // Update hero card
        fun applyHeroState(gemmaState: GemmaState) {
            updateModelBadge(gemmaState)
            val totalCritical = criticalFails + if (gemmaState == GemmaState.FAILED) 1 else 0
            val isLoading = gemmaState == GemmaState.LOADING
            when {
                isLoading -> {
                    heroCard.setBackgroundResource(R.drawable.card_status_degraded)
                    iconBox.setBackgroundResource(R.drawable.bg_status_icon_box_degraded)
                    heroIcon.text = "…"
                    heroIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                    heroText.text = getString(R.string.system_status_ai_loading)
                    heroText.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                    heroSubtext.text = getString(R.string.system_status_ai_loading_sub)
                    heroSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                    btnStart.isEnabled = false
                    btnStart.alpha = 0.4f
                }
                totalCritical > 0 -> {
                    heroCard.setBackgroundResource(R.drawable.card_status_critical)
                    iconBox.setBackgroundResource(R.drawable.bg_status_icon_box_critical)
                    heroIcon.text = "!"
                    heroIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_critical))
                    heroText.text = getString(R.string.system_status_critical)
                    heroText.setTextColor(ContextCompat.getColor(ctx, R.color.status_critical))
                    heroSubtext.text = "Action required before start"
                    heroSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_critical))
                    btnStart.isEnabled = false
                    btnStart.alpha = 0.4f
                }
                partialFails > 0 -> {
                    heroCard.setBackgroundResource(R.drawable.card_status_degraded)
                    iconBox.setBackgroundResource(R.drawable.bg_status_icon_box_degraded)
                    heroIcon.text = "!"
                    heroIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                    heroText.text = getString(R.string.system_status_degraded)
                    heroText.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                    heroSubtext.text = "System limited but functional"
                    heroSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_degraded))
                    btnStart.isEnabled = true
                    btnStart.alpha = 1f
                }
                else -> {
                    heroCard.setBackgroundResource(R.drawable.card_status_healthy)
                    iconBox.setBackgroundResource(R.drawable.bg_status_icon_box_healthy)
                    heroIcon.text = "✓"
                    heroIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
                    heroText.text = getString(R.string.system_status_healthy)
                    heroText.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
                    heroSubtext.text = "All systems operational"
                    heroSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_healthy))
                    btnStart.isEnabled = true
                    btnStart.alpha = 1f
                }
            }
        }

        applyHeroState(viewModel.gemmaState.value)

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            viewModel.startMission()
        }

        // Observe countdown state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.missionState.collect { state ->
                    if (state is MissionState.Starting) {
                        countdownOverlay.visibility = View.VISIBLE
                        countdownText.text = state.remainingSeconds.toString()
                        if (state.remainingSeconds <= 2) {
                            countdownText.setTextColor(ContextCompat.getColor(ctx, R.color.amber))
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gemmaState.collect { state ->
                    applyHeroState(state)
                }
            }
        }

        // Exploration history section
        val historySection = view.findViewById<LinearLayout>(R.id.history_section)
        val historyCount = view.findViewById<TextView>(R.id.history_count)
        val historyMap = view.findViewById<MapOverlayView>(R.id.history_map)
        val btnClearHistory = view.findViewById<View>(R.id.btn_clear_history)

        historyMap.isReviewMode = true
        historyMap.onLandmarkTapListener = { landmark ->
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.landmark_detail_title))
                .setMessage(landmark.description)
                .setPositiveButton("OK", null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pastLandmarks.collect { past ->
                    if (past.isNotEmpty()) {
                        historySection.visibility = View.VISIBLE
                        val sessionCount = ExplorationHistory.sessionCount(ctx)
                        historyCount.text = getString(R.string.pre_mission_history_count, past.size, sessionCount)
                        historyMap.updatePastLandmarks(past)
                    } else {
                        historySection.visibility = View.GONE
                    }
                }
            }
        }

        btnClearHistory.setOnClickListener {
            viewModel.clearExplorationHistory()
            Toast.makeText(ctx, R.string.clear_history_confirm, Toast.LENGTH_SHORT).show()
        }
    }
}
