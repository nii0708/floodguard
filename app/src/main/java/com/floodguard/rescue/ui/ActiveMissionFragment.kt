package com.floodguard.rescue.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.floodguard.rescue.R
import com.floodguard.rescue.motion.ArcoreTracker
import com.floodguard.rescue.motion.MotionTracker
import com.floodguard.rescue.state.MissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ActiveMissionFragment : Fragment(), SensorEventListener {

    private val viewModel: MainViewModel by activityViewModels()

    private var sensorManager: SensorManager? = null
    private val motionTracker = MotionTracker()
    private var arcoreTracker: ArcoreTracker? = null
    private var poseUpdateJob: Job? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isListeningLoopActive = false
    private var isCurrentlyListening = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setupArcoreIfNeeded()
        else Toast.makeText(requireContext(), R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
    }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListeningInternal()
        else Toast.makeText(requireContext(), "Izin audio diperlukan", Toast.LENGTH_SHORT).show()
    }

    private val activityPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) registerMotionSensors()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_active_mission, container, false)

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx = requireContext()

        val btnStop = view.findViewById<Button>(R.id.btn_stop)
        val btnAskAi = view.findViewById<LinearLayout>(R.id.btn_ask_ai)
        val btnAskAiText = view.findViewById<TextView>(R.id.btn_ask_ai_text)
        val micIcon = view.findViewById<ImageView>(R.id.mic_icon)
        
        val elapsedText = view.findViewById<TextView>(R.id.metric_elapsed_value)
        val landmarkText = view.findViewById<TextView>(R.id.metric_landmark_value)
        val batteryText = view.findViewById<TextView>(R.id.battery_pct_text)
        val batteryBar = view.findViewById<ProgressBar>(R.id.battery_bar)
        
        val statusPill = view.findViewById<LinearLayout>(R.id.status_pill)
        val statusDot = view.findViewById<View>(R.id.status_dot)
        val statusLabel = view.findViewById<TextView>(R.id.status_label)
        val trackingModeBadge = view.findViewById<TextView>(R.id.tracking_mode_badge)
        val trackingText = view.findViewById<TextView>(R.id.tracking_mode_text)
        
        val audioStatusLabel = view.findViewById<TextView>(R.id.audio_status_label)
        val listeningHint = view.findViewById<TextView>(R.id.listening_hint)
        val recDot = view.findViewById<View>(R.id.rec_dot)
        
        val alertBanner = view.findViewById<LinearLayout>(R.id.alert_banner)
        val alertSubtext = view.findViewById<TextView>(R.id.alert_subtext)
        val alertProgress = view.findViewById<ProgressBar>(R.id.alert_auto_dismiss_progress)

        glSurfaceView = view.findViewById(R.id.ar_surface_view)

        // REC dot and CAM dot blinking
        val blinkAnim = AlphaAnimation(1.0f, 0.12f).apply {
            duration = 1400
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        recDot.startAnimation(blinkAnim)
        view.findViewById<View>(R.id.cam_dot)?.startAnimation(blinkAnim)

        // Update UI labels based on audio mode
        if (viewModel.audioInputMode.value == AudioInputMode.VOLUME_BUTTON) {
            audioStatusLabel.text = getString(R.string.audio_push_to_talk)
        }

        btnStop.setOnClickListener {
            btnStop.isEnabled = false
            viewModel.stopMission()
        }

        btnAskAi.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (isCurrentlyListening) {
                    stopListeningInternal()
                } else {
                    startListeningInternal()
                }
            } else {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.elapsedSeconds.collect { sec ->
                        val m = sec / 60
                        val s = sec % 60
                        elapsedText.text = getString(R.string.elapsed_format, m, s)
                    }
                }

                launch {
                    viewModel.landmarks.collect { list ->
                        landmarkText.text = list.size.toString()
                    }
                }

                launch {
                    viewModel.batteryLevel.collect { pct ->
                        batteryText.text = getString(R.string.battery_level_format, pct)
                        batteryBar.progress = pct
                        val colorRes = when {
                            pct >= 30 -> R.color.battery_high
                            pct >= 15 -> R.color.battery_mid
                            else -> R.color.battery_low
                        }
                        val color = ContextCompat.getColor(requireContext(), colorRes)
                        batteryText.setTextColor(color)
                        batteryBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
                    }
                }

                launch {
                    viewModel.trackingState.collect { state ->
                        val c = requireContext()
                        when (state) {
                            "AR_VIO" -> {
                                trackingText.text = getString(R.string.tracking_ar_vio)
                                trackingModeBadge.text = "AR VIO"
                                trackingModeBadge.setBackgroundResource(R.drawable.badge_healthy)
                                statusPill.setBackgroundResource(R.drawable.bg_status_pill_healthy)
                                statusDot.setBackgroundResource(R.drawable.bg_dot_healthy)
                                statusLabel.text = getString(R.string.system_status_healthy)
                                statusLabel.setTextColor(ContextCompat.getColor(c, R.color.status_healthy))
                            }
                            "SENSOR_PDR" -> {
                                trackingText.text = getString(R.string.tracking_sensor_pdr)
                                trackingModeBadge.text = "PDR"
                                trackingModeBadge.setBackgroundResource(R.drawable.badge_degraded)
                                statusPill.setBackgroundResource(R.drawable.bg_status_pill_degraded)
                                statusDot.setBackgroundResource(R.drawable.bg_dot_degraded)
                                statusLabel.text = getString(R.string.system_status_degraded)
                                statusLabel.setTextColor(ContextCompat.getColor(c, R.color.status_degraded))
                            }
                            else -> {
                                trackingText.text = getString(R.string.tracking_limited)
                                trackingModeBadge.text = "LIM"
                                trackingModeBadge.setBackgroundResource(R.drawable.badge_critical)
                                statusPill.setBackgroundResource(R.drawable.bg_status_pill_critical)
                                statusDot.setBackgroundResource(R.drawable.bg_dot_critical)
                                statusLabel.text = getString(R.string.system_status_critical)
                                statusLabel.setTextColor(ContextCompat.getColor(c, R.color.status_critical))
                            }
                        }
                    }
                }

                launch {
                    viewModel.missionState.collect { state ->
                        if (state is MissionState.Active && state.isAlerting) {
                            alertBanner.visibility = View.VISIBLE
                            alertSubtext.text = state.alertMessage
                            
                            ObjectAnimator.ofInt(alertProgress, "progress", 100, 0).apply {
                                duration = 6000
                                start()
                            }
                            
                            val pulse = AlphaAnimation(1.0f, 0.86f).apply {
                                duration = 1100
                                repeatMode = Animation.REVERSE
                                repeatCount = Animation.INFINITE
                            }
                            alertBanner.startAnimation(pulse)
                        } else {
                            alertBanner.visibility = View.GONE
                            alertBanner.clearAnimation()
                        }
                    }
                }

                launch {
                    viewModel.voiceTriggerRequests.collect {
                        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            if (isCurrentlyListening) stopListeningInternal()
                            else startListeningInternal()
                        }
                    }
                }

                launch {
                    viewModel.captureRequests.collect {
                        arcoreTracker?.requestCapture { bitmap ->
                            viewModel.onNewFrameSampled(bitmap)
                        }
                    }
                }

                launch {
                    viewModel.isAiSpeaking.collect { speaking ->
                        if (speaking) {
                            stopListeningInternal()
                        } else {
                            delay(600)
                            if (isListeningLoopActive && viewModel.audioInputMode.value == AudioInputMode.REALTIME) {
                                startListeningInternal()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val prox = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        prox?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        
        if (hasCameraPermission()) {
            setupArcoreIfNeeded()
            resumeArcore()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        requestMotionPermissionOrRegister()
        startPoseUpdates()

        if (viewModel.audioInputMode.value == AudioInputMode.REALTIME) {
            isListeningLoopActive = true
            startListeningInternal()
        }
    }

    override fun onPause() {
        isListeningLoopActive = false
        stopListeningInternal()
        poseUpdateJob?.cancel()
        poseUpdateJob = null
        glSurfaceView?.onPause()
        arcoreTracker?.pause()
        sensorManager?.unregisterListener(this)
        sensorManager?.unregisterListener(motionTracker)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = event.sensor.maximumRange
            if (distance < maxRange && distance < 1.0f) {
                startListeningInternal()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroyView() {
        isListeningLoopActive = false
        stopListeningInternal()
        arcoreTracker?.release()
        arcoreTracker = null
        super.onDestroyView()
    }

    private fun stopListeningInternal() {
        isCurrentlyListening = false
        audioRecord?.stop() // unblocks any pending read() call in the recording coroutine
        if (recordingJob?.isActive != true) resetMicUiIdle()
    }

    @SuppressLint("MissingPermission")
    private fun startListeningInternal() {
        if (viewModel.isAiSpeaking.value || isCurrentlyListening) return
        isCurrentlyListening = true

        view?.findViewById<LinearLayout>(R.id.btn_ask_ai)?.setBackgroundResource(R.drawable.bg_ask_ai_listening)
        view?.findViewById<TextView>(R.id.btn_ask_ai_text)?.apply {
            text = getString(R.string.listening_hint)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.amber))
        }
        view?.findViewById<ImageView>(R.id.mic_icon)?.setColorFilter(ContextCompat.getColor(requireContext(), R.color.amber))
        view?.findViewById<TextView>(R.id.listening_hint)?.visibility = View.VISIBLE

        val bufSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 4
        )
        audioRecord = recorder
        recorder.startRecording()
        Log.d("AudioDebug", "AudioRecord started, state=${recorder.recordingState}")

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val chunks = mutableListOf<ByteArray>()
            var totalBytes = 0
            val maxBytes = AUDIO_SAMPLE_RATE * 2 * AUDIO_MAX_SECS.toInt()
            val buf = ByteArray(bufSize)

            while (isCurrentlyListening && totalBytes < maxBytes) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) { chunks.add(buf.copyOf(n)); totalBytes += n }
                else break
            }

            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            audioRecord = null
            Log.d("AudioDebug", "AudioRecord stopped, ${totalBytes} bytes captured")

            val minBytes = AUDIO_SAMPLE_RATE * 2 / 3 // ~333 ms minimum
            if (totalBytes >= minBytes) {
                val outFile = File(requireContext().cacheDir, "vc_${System.currentTimeMillis()}.wav")
                writeWavFile(outFile, chunks, totalBytes)
                withContext(Dispatchers.Main) { viewModel.processVoiceAudio(outFile) }
            }

            withContext(Dispatchers.Main) {
                isCurrentlyListening = false
                resetMicUiIdle()
            }
        }

        // Auto-stop after max recording duration
        lifecycleScope.launch {
            delay(AUDIO_MAX_SECS * 1000L)
            if (isCurrentlyListening) stopListeningInternal()
        }
    }

    private fun resetMicUiIdle() {
        view?.findViewById<LinearLayout>(R.id.btn_ask_ai)?.setBackgroundResource(R.drawable.bg_ask_ai_idle)
        view?.findViewById<TextView>(R.id.btn_ask_ai_text)?.apply {
            text = getString(R.string.btn_ask_ai)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        }
        view?.findViewById<ImageView>(R.id.mic_icon)?.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        view?.findViewById<TextView>(R.id.listening_hint)?.visibility = View.INVISIBLE
    }

    private fun writeWavFile(file: File, chunks: List<ByteArray>, dataSize: Int) {
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)                         // PCM
            header.putShort(1)                         // mono
            header.putInt(AUDIO_SAMPLE_RATE)
            header.putInt(AUDIO_SAMPLE_RATE * 2)       // byte rate
            header.putShort(2)                         // block align
            header.putShort(16)                        // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            fos.write(header.array())
            chunks.forEach { fos.write(it) }
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_MAX_SECS = 10L
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMotionPermissionOrRegister() {
        if (hasActivityRecognitionPermission()) {
            registerMotionSensors()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                registerMotionSensors()
            }
        }
    }

    private fun setupArcoreIfNeeded() {
        if (arcoreTracker != null) return
        val tracker = ArcoreTracker(
            context = requireContext(),
            onPoseUpdate = { pose ->
                motionTracker.updateArcore(pose)
                viewModel.onMotionPoseUpdated(motionTracker.getPose())
            },
            onFrameAvailable = {}
        )
        if (tracker.setup()) {
            arcoreTracker = tracker
            glSurfaceView?.apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setRenderer(tracker)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            viewModel.setTrackingState("AR_VIO")
        } else {
            Toast.makeText(requireContext(), R.string.ar_unavailable, Toast.LENGTH_SHORT).show()
            viewModel.setTrackingState("SENSOR_PDR")
        }
    }

    private fun resumeArcore() {
        val tracker = arcoreTracker ?: return
        if (tracker.resume()) glSurfaceView?.onResume()
        else viewModel.setTrackingState("SENSOR_PDR")
    }

    private fun registerMotionSensors() {
        val sm = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val stepD = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val stepC = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (accel == null || rot == null) {
            viewModel.setTrackingState("LIMITED_SENSORS")
        } else {
            sm.registerListener(motionTracker, accel, SensorManager.SENSOR_DELAY_FASTEST)
            sm.registerListener(motionTracker, rot, SensorManager.SENSOR_DELAY_FASTEST)
            stepD?.let { sm.registerListener(motionTracker, it, SensorManager.SENSOR_DELAY_FASTEST) }
            stepC?.let { sm.registerListener(motionTracker, it, SensorManager.SENSOR_DELAY_FASTEST) }
        }
    }

    private fun startPoseUpdates() {
        // pose updates are driven by viewModel.currentPose flow collection
    }
}
