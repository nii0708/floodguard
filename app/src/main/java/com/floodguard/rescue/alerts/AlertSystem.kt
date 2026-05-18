package com.floodguard.rescue.alerts

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.StringRes
import com.floodguard.rescue.navigation.AlertPriority
import java.util.Locale
import java.util.UUID

class AlertSystem(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentLocale: Locale = Locale.US
    private val revisitCooldowns = mutableMapOf<String, Long>()
    private var lastHighMediumAlertTime = 0L

    var onSpeechStatusChanged: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                updateLanguage(context.resources.configuration.locales[0])
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.0f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeechStatusChanged?.invoke(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        onSpeechStatusChanged?.invoke(false)
                    }
                    override fun onError(utteranceId: String?) {
                        onSpeechStatusChanged?.invoke(false)
                    }
                })

                ready = true
            }
        }
    }

    fun isReady(): Boolean = ready

    fun getCurrentLocale(): Locale = currentLocale

    fun updateLanguage(locale: Locale) {
        currentLocale = locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            // Fallback for some devices that might use "in" instead of "id"
            if (locale.language == "id" || locale.language == "in") {
                tts?.setLanguage(Locale("id"))
            } else {
                tts?.setLanguage(Locale.US)
            }
        }
    }

    fun speak(message: String, priority: AlertPriority) {
        if (!ready) return
        val queueMode = if (priority == AlertPriority.HIGH)
            TextToSpeech.QUEUE_FLUSH
        else
            TextToSpeech.QUEUE_ADD
        tts?.speak(message, queueMode, null, UUID.randomUUID().toString())
        if (priority == AlertPriority.HIGH || priority == AlertPriority.MEDIUM) {
            lastHighMediumAlertTime = System.currentTimeMillis()
        }
    }

    fun speak(@StringRes resId: Int, priority: AlertPriority, vararg formatArgs: Any) {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(currentLocale)
        val localizedContext = context.createConfigurationContext(configuration)
        
        val message = if (formatArgs.isNotEmpty()) {
            localizedContext.getString(resId, *formatArgs)
        } else {
            localizedContext.getString(resId)
        }
        speak(message, priority)
    }

    fun canSpeakRevisit(landmarkId: String): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = revisitCooldowns[landmarkId] ?: 0L
        if (now - lastTime < REVISIT_COOLDOWN_MS) return false
        revisitCooldowns[landmarkId] = now
        return true
    }

    fun canSpeakPeriodicStatus(): Boolean {
        return System.currentTimeMillis() - lastHighMediumAlertTime > STATUS_SUPPRESSION_MS
    }

    fun setMaxVolume() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val REVISIT_COOLDOWN_MS = 30_000L
        private const val STATUS_SUPPRESSION_MS = 30_000L
    }
}
