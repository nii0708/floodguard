package com.floodguard.rescue.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.BatteryManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.floodguard.rescue.R
import com.floodguard.rescue.ai.GemmaEngine
import com.floodguard.rescue.ai.ModelManager
import com.floodguard.rescue.alerts.AlertSystem
import com.floodguard.rescue.memory.LandmarkRecord
import com.floodguard.rescue.memory.SpatialMemory
import com.floodguard.rescue.navigation.AlertMessageType
import com.floodguard.rescue.navigation.AlertPriority
import com.floodguard.rescue.navigation.NavigationAdvisor
import com.floodguard.rescue.navigation.NavigationAlert
import com.floodguard.rescue.session.ExplorationHistory
import com.floodguard.rescue.session.PoseSample
import com.floodguard.rescue.session.SessionLog
import com.floodguard.rescue.state.MissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

enum class AudioInputMode {
    REALTIME,
    VOLUME_BUTTON
}

enum class GemmaState { LOADING, READY, FAILED }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val spatialMemory = SpatialMemory()
    private val navigationAdvisor = NavigationAdvisor(spatialMemory)
    private var gemmaEngine: GemmaEngine? = null
    var alertSystem: AlertSystem? = null
        private set

    private val _missionState = MutableStateFlow<MissionState>(MissionState.Idle)
    val missionState = _missionState.asStateFlow()

    private val _statusText = MutableStateFlow(application.getString(R.string.ai_waiting_camera))
    val statusText = _statusText.asStateFlow()

    private val _alertText = MutableStateFlow("")
    val alertText = _alertText.asStateFlow()

    private val _landmarks = MutableStateFlow<List<LandmarkRecord>>(emptyList())
    val landmarks = _landmarks.asStateFlow()

    private val _currentPose = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val currentPose = _currentPose.asStateFlow()

    private val _pastLandmarks = MutableStateFlow<List<LandmarkRecord>>(emptyList())
    val pastLandmarks = _pastLandmarks.asStateFlow()

    private val _trackingState = MutableStateFlow("SENSOR_PDR")
    val trackingState = _trackingState.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds = _elapsedSeconds.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _audioInputMode = MutableStateFlow(AudioInputMode.VOLUME_BUTTON)
    val audioInputMode = _audioInputMode.asStateFlow()

    private val _captureRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val captureRequests = _captureRequests.asSharedFlow()

    private val _voiceTriggerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val voiceTriggerRequests = _voiceTriggerRequests.asSharedFlow()

    private val isAnalyzing = AtomicBoolean(false)
    private val isGemmaInitializing = AtomicBoolean(false)
    private var alertJob: Job? = null
    private var timerJob: Job? = null
    private var statusJob: Job? = null

    private var revisitCount = 0
    private var sessionStartTime = 0L
    private var totalDistance = 0f
    private var lastPose = floatArrayOf(0f, 0f, 0f)
    private val missionTrail = mutableListOf<PoseSample>()
    private var lastTrailPose = floatArrayOf(0f, 0f, 0f)
    private var lastTrailSampleMs = 0L

    private val batteryWarningsPlayed = mutableSetOf<Int>()
    private var batteryReceiver: BroadcastReceiver? = null

    fun isModelReady(): Boolean = ModelManager.isModelPresent(getApplication())

    private val _isAiSpeaking = MutableStateFlow(false)
    val isAiSpeaking = _isAiSpeaking.asStateFlow()

    private val _gemmaState = MutableStateFlow(GemmaState.LOADING)
    val gemmaState = _gemmaState.asStateFlow()

    init {
        // Start loading GemmaEngine immediately so it's ready before the active mission.
        initGemmaEngine()
    }

    private fun initGemmaEngine() {
        if (gemmaEngine != null || !isGemmaInitializing.compareAndSet(false, true)) return
        _gemmaState.value = GemmaState.LOADING
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val check = ModelManager.checkModel(context)
                Log.d("MainViewModel", "Model check: exists=${check.exists}, compatible=${check.isCompatible}, reason=${check.reason}")
                gemmaEngine = GemmaEngine.create(context)
                Log.i("MainViewModel", "GemmaEngine initialized successfully.")
                withContext(Dispatchers.Main) {
                    _gemmaState.value = GemmaState.READY
                    _statusText.value = context.getString(R.string.ai_status_ready)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "GemmaEngine initialization failed!", e)
                gemmaEngine = null
                withContext(Dispatchers.Main) {
                    _gemmaState.value = GemmaState.FAILED
                    _statusText.value = context.getString(R.string.ai_init_failed, e.message ?: "Error")
                }
            } finally {
                isGemmaInitializing.set(false)
            }
            _pastLandmarks.value = ExplorationHistory.loadAll(context)
        }
    }

    fun initialize(locale: Locale) {
        val context = getApplication<Application>()

        if (alertSystem == null) {
            val system = AlertSystem(context)
            system.onSpeechStatusChanged = { speaking ->
                _isAiSpeaking.value = speaking
            }
            alertSystem = system
        }
        alertSystem?.updateLanguage(locale)

        // Engine loading already started in init{}; only retry if it previously failed.
        if (gemmaEngine == null) initGemmaEngine()
    }

    fun setTrackingState(state: String) {
        val previous = _trackingState.value
        _trackingState.value = state
        if (_missionState.value is MissionState.Active) {
            when {
                previous == "AR_VIO" && state == "SENSOR_PDR" ->
                    alertSystem?.speak(R.string.tts_tracking_degraded, AlertPriority.MEDIUM)
                previous == "SENSOR_PDR" && state == "AR_VIO" ->
                    alertSystem?.speak(R.string.tts_tracking_recovered, AlertPriority.LOW)
                state == "LIMITED_SENSORS" ->
                    alertSystem?.speak(R.string.tts_tracking_limited, AlertPriority.HIGH)
            }
        }
    }

    fun setAudioInputMode(mode: AudioInputMode) {
        _audioInputMode.value = mode
    }

    fun onMotionPoseUpdated(pose: FloatArray) {
        if (_missionState.value is MissionState.Active) {
            val dx = pose[0] - lastPose[0]
            val dz = pose[2] - lastPose[2]
            val dist = sqrt(dx * dx + dz * dz)
            if (dist in 0.01f..5f) {
                totalDistance += dist
            }
            lastPose = pose.copyOf()
            maybeRecordTrailSample(pose)
        }
        _currentPose.value = pose
    }

    fun startMission() {
        viewModelScope.launch {
            alertSystem?.setMaxVolume()
            delay(400) // let audio system settle after volume change before TTS starts
            // Countdown 5 → 1
            for (i in 5 downTo 1) {
                _missionState.value = MissionState.Starting(i)
                alertSystem?.speak("$i", AlertPriority.HIGH)
                delay(1_200)
            }
            // Transition to Active
            sessionStartTime = System.currentTimeMillis()
            revisitCount = 0
            totalDistance = 0f
            lastPose = _currentPose.value.copyOf()
            lastTrailPose = lastPose.copyOf()
            lastTrailSampleMs = 0L
            missionTrail.clear()
            missionTrail.add(
                PoseSample(
                    x = lastPose[0],
                    y = lastPose[1],
                    z = lastPose[2],
                    timestamp = System.currentTimeMillis()
                )
            )
            batteryWarningsPlayed.clear()
            _elapsedSeconds.value = 0
            _landmarks.value = emptyList()

            _missionState.value = MissionState.Active()
            alertSystem?.speak(R.string.tts_mission_start, AlertPriority.MEDIUM)

            startElapsedTimer()
            startPeriodicStatus()
            registerBatteryMonitor()
        }
    }

    fun stopMission() {
        timerJob?.cancel()
        statusJob?.cancel()
        unregisterBatteryMonitor()

        val log = SessionLog(
            startTime = sessionStartTime,
            endTime = System.currentTimeMillis(),
            landmarks = spatialMemory.getAllLandmarks(),
            trail = missionTrail.toList(),
            revisitCount = revisitCount,
            distanceMeters = totalDistance
        )
        _missionState.value = MissionState.Review(log)

        alertSystem?.speak(
            R.string.tts_mission_complete,
            AlertPriority.HIGH,
            log.durationMinutes,
            log.landmarks.size
        )

        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            ExplorationHistory.saveSession(context, log.landmarks)
            _pastLandmarks.value = ExplorationHistory.loadAll(context)
        }
    }

    fun endReview() {
        spatialMemory.clear()
        _landmarks.value = emptyList()
        val context = getApplication<Application>()
        _statusText.value = context.getString(R.string.ai_status_ready)
        _alertText.value = ""
        _elapsedSeconds.value = 0
        _missionState.value = MissionState.Idle
    }

    fun clearExplorationHistory() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            ExplorationHistory.clearAll(context)
            _pastLandmarks.value = emptyList()
        }
    }

    fun triggerVoiceCommand() {
        if (_missionState.value is MissionState.Active) {
            _voiceTriggerRequests.tryEmit(Unit)
        }
    }

    private fun getLocalizedString(@StringRes resId: Int, vararg formatArgs: Any): String {
        val context = getApplication<Application>()
        val locale = alertSystem?.getCurrentLocale() ?: context.resources.configuration.locales[0]
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        val localizedContext = context.createConfigurationContext(config)
        return if (formatArgs.isNotEmpty()) localizedContext.getString(resId, *formatArgs) else localizedContext.getString(resId)
    }

    override fun onCleared() {
        timerJob?.cancel()
        statusJob?.cancel()
        unregisterBatteryMonitor()
        gemmaEngine?.close()
        alertSystem?.shutdown()
        super.onCleared()
    }

    fun onNewFrameSampled(bitmap: Bitmap) {
        if (!isAnalyzing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        val poseAtCapture = _currentPose.value.copyOf()
        val systemPrompt = getLocalizedString(R.string.ai_vision_system_prompt)
        val userPrompt = getLocalizedString(R.string.ai_user_vision_prompt)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val description = gemmaEngine?.describeScene(bitmap, systemPrompt, userPrompt)
                if (description != null) {
                    val landmark = LandmarkRecord(
                        id = UUID.randomUUID().toString(),
                        description = description,
                        position = poseAtCapture,
                        timestamp = System.currentTimeMillis()
                    )
                    spatialMemory.addLandmark(landmark)
                    _landmarks.value = spatialMemory.getAllLandmarks()
                    _statusText.value = getLocalizedString(R.string.ai_landmark_saved, description)
                    alertSystem?.speak(
                        getLocalizedString(R.string.ai_analysis_result, description),
                        AlertPriority.MEDIUM
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Scene analysis failed", e)
            } finally {
                bitmap.recycle()
                isAnalyzing.set(false)
            }
        }
    }

    fun processVoiceCommand(command: String) {
        Log.d("MainViewModel", "Processing voice command: $command")
        if (!guardVoiceReady()) return
        val systemPrompt = getLocalizedString(R.string.ai_system_prompt)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = gemmaEngine?.chat(command, systemPrompt) ?: return@launch
                handleGemmaResponse(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Voice command error", e)
            }
        }
    }

    fun processVoiceAudio(audioFile: File) {
        Log.d("MainViewModel", "Processing voice audio: ${audioFile.name}")
        if (!guardVoiceReady()) { audioFile.delete(); return }
        val systemPrompt = getLocalizedString(R.string.ai_system_prompt)
        val userPrompt = getLocalizedString(R.string.ai_user_audio_prompt)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = gemmaEngine?.processAudio(audioFile, systemPrompt, userPrompt) ?: return@launch
                handleGemmaResponse(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Voice audio error", e)
            }
        }
    }

    private fun guardVoiceReady(): Boolean {
        if (_missionState.value !is MissionState.Active) {
            Log.d("MainViewModel", "Ignored: Mission state is not Active")
            return false
        }
        if (gemmaEngine == null) {
            if (isGemmaInitializing.get()) {
                alertSystem?.speak(R.string.ai_loading, AlertPriority.MEDIUM)
            } else {
                alertSystem?.speak(R.string.ai_not_ready, AlertPriority.MEDIUM)
                initGemmaEngine()
            }
            return false
        }
        return true
    }

    private fun buildAlertMessage(alert: NavigationAlert): String {
        return when (alert.messageType) {
            AlertMessageType.TRAJECTORY_REVISIT -> if (alert.minutesAgo >= 1)
                getLocalizedString(R.string.tts_revisit_with_time, alert.minutesAgo)
            else
                getLocalizedString(R.string.tts_revisit_basic)
            AlertMessageType.NEAR_LANDMARK ->
                getLocalizedString(R.string.alert_near_landmark, alert.matchedLandmark?.description ?: "")
            AlertMessageType.PAST_SESSION ->
                getLocalizedString(R.string.alert_past_session, alert.matchedLandmark?.description ?: "")
        }
    }

    private fun handleGemmaResponse(response: String) {
        when {
            response.contains("INTENT: REVISIT_QUERY") -> {
                val alert = navigationAdvisor.checkTrajectoryRevisit(
                    currentPose = _currentPose.value,
                    trail = missionTrail,
                    pastLandmarks = _pastLandmarks.value
                )
                if (alert != null) {
                    revisitCount++
                    val message = buildAlertMessage(alert)
                    _alertText.value = message
                    _missionState.value = MissionState.Active(
                        isAlerting = true,
                        alertMessage = message
                    )
                    alertSystem?.speak(message, alert.priority)
                    alertJob?.cancel()
                    alertJob = viewModelScope.launch {
                        delay(6_000)
                        val current = _missionState.value
                        if (current is MissionState.Active && current.isAlerting) {
                            _missionState.value = MissionState.Active()
                        }
                    }
                } else {
                    alertSystem?.speak(R.string.ai_new_area, AlertPriority.LOW)
                }
            }
            response.contains("INTENT: CAPTURE_LANDMARK") -> {
                _captureRequests.tryEmit(Unit)
                alertSystem?.speak(R.string.ai_analyzing, AlertPriority.LOW)
            }
            else -> {
                alertSystem?.speak(response, AlertPriority.MEDIUM)
            }
        }
    }

    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                _elapsedSeconds.value = _elapsedSeconds.value + 1
            }
        }
    }

    private fun startPeriodicStatus() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            while (true) {
                delay(PERIODIC_STATUS_INTERVAL_MS)
                if (_missionState.value is MissionState.Active &&
                    alertSystem?.canSpeakPeriodicStatus() == true
                ) {
                    val minutes = _elapsedSeconds.value / 60
                    val landmarkCount = spatialMemory.size()
                    val battery = _batteryLevel.value
                    alertSystem?.speak(
                        R.string.tts_status_periodic,
                        AlertPriority.LOW,
                        minutes,
                        landmarkCount,
                        battery
                    )
                }
            }
        }
    }

    private fun registerBatteryMonitor() {
        val context = getApplication<Application>()
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = (level * 100 / scale)
                _batteryLevel.value = pct
                checkBatteryWarnings(pct)
            }
        }
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    private fun unregisterBatteryMonitor() {
        batteryReceiver?.let {
            try {
                getApplication<Application>().unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        batteryReceiver = null
    }

    private fun checkBatteryWarnings(pct: Int) {
        if (_missionState.value !is MissionState.Active) return
        when {
            pct <= 5 && 5 !in batteryWarningsPlayed -> {
                batteryWarningsPlayed.add(5)
                alertSystem?.speak(R.string.tts_battery_5, AlertPriority.HIGH)
            }
            pct <= 10 && 10 !in batteryWarningsPlayed -> {
                batteryWarningsPlayed.add(10)
                alertSystem?.speak(R.string.tts_battery_10, AlertPriority.HIGH)
            }
            pct <= 15 && 15 !in batteryWarningsPlayed -> {
                batteryWarningsPlayed.add(15)
                alertSystem?.speak(R.string.tts_battery_15, AlertPriority.MEDIUM)
            }
            pct <= 20 && 20 !in batteryWarningsPlayed -> {
                batteryWarningsPlayed.add(20)
                alertSystem?.speak(R.string.tts_battery_20, AlertPriority.MEDIUM)
            }
        }
    }

    private fun maybeRecordTrailSample(pose: FloatArray) {
        val now = System.currentTimeMillis()
        if (lastTrailSampleMs != 0L && now - lastTrailSampleMs < TRAIL_SAMPLE_INTERVAL_MS) return

        val dx = pose[0] - lastTrailPose[0]
        val dz = pose[2] - lastTrailPose[2]
        val dist = sqrt(dx * dx + dz * dz)
        if (dist < TRAIL_MIN_DISTANCE_METERS) return

        missionTrail.add(
            PoseSample(
                x = pose[0],
                y = pose[1],
                z = pose[2],
                timestamp = now
            )
        )
        lastTrailPose = pose.copyOf()
        lastTrailSampleMs = now
    }

    companion object {
        private const val PERIODIC_STATUS_INTERVAL_MS = 180_000L // 3 minutes
        private const val TRAIL_SAMPLE_INTERVAL_MS = 100L
        private const val TRAIL_MIN_DISTANCE_METERS = 0.02f
    }
}
