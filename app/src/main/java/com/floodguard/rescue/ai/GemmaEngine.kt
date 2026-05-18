package com.floodguard.rescue.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * On-device Gemma 4 E2B inference via LiteRT-LM 0.11.0.
 */
class GemmaEngine private constructor(
    private val engine: Engine,
    private val cacheDir: File
) {
    // The LiteRT-LM Engine is not re-entrant; serialize all inference calls.
    private val mutex = Mutex()

    /**
     * Handles text-only chat interaction for voice commands.
     */
    suspend fun chat(text: String, systemPrompt: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.2)
                )
                engine.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(Contents.of(Content.Text(text)))
                    val responseText = response.toString().trim()
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Gemma Chat SUCCESS [${duration}ms]: $responseText")
                    responseText.ifEmpty { null }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemma Chat FAILED", e)
                null
            }
        }
    }

    /**
     * Processes an audio file and returns the model's response.
     * Deletes the audio file after processing to save space.
     */
    suspend fun processAudio(
        audioFile: File,
        systemPrompt: String,
        userPrompt: String
    ): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.2)
                )
                engine.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(
                        Contents.of(
                            Content.AudioFile(audioFile.absolutePath),
                            Content.Text(userPrompt)
                        )
                    )
                    val text = response.toString().trim()
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Gemma Audio SUCCESS [${duration}ms]: $text")
                    text.ifEmpty { null }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemma Audio FAILED", e)
                null
            } finally {
                if (audioFile.exists()) {
                    audioFile.delete()
                    Log.d(TAG, "Gemma Audio temp file deleted: ${audioFile.name}")
                }
            }
        }
    }

    /**
     * Returns a 1-2 sentence scene description, or null on inference failure.
     */
    suspend fun describeScene(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String
    ): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val tempFile = File(cacheDir, "scene_frame_${Thread.currentThread().id}.jpg")
            try {
                FileOutputStream(tempFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.2)
                )
                engine.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(
                        Contents.of(
                            Content.ImageFile(tempFile.absolutePath),
                            Content.Text(userPrompt)
                        )
                    )
                    val text = response.toString().trim()
                    val duration = System.currentTimeMillis() - startTime

                    if (text.isNotEmpty()) {
                        Log.d(TAG, "Gemma Inference SUCCESS [${duration}ms]: $text")
                    } else {
                        Log.w(TAG, "Gemma Inference EMPTY [${duration}ms]")
                    }

                    text.ifEmpty { null }
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "Gemma Inference FAILED [${duration}ms]", e)
                null
            } finally {
                tempFile.delete()
            }
        }
    }

    fun close() {
        try { engine.close() } catch (e: Exception) { Log.w(TAG, "Engine close error.", e) }
    }

    companion object {
        private const val TAG = "GemmaEngine"

        @OptIn(ExperimentalApi::class)
        suspend fun create(context: Context): GemmaEngine = withContext(Dispatchers.IO) {
            val check = ModelManager.checkModel(context)
            require(check.isCompatible) { check.reason ?: "Model validation failed." }

            val modelPath = ModelManager.modelPath(context)
            val cachePath = context.cacheDir.path

            // Enable performance optimizations for GPU
            ExperimentalFlags.enableSpeculativeDecoding = true

            // 1. Try GPU first (Best performance)
            val engine = try {
                Log.d(TAG, "Attempting to initialize Gemma with GPU backend...")
                val gpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    audioBackend = Backend.CPU(),
                    maxNumImages = 1,
                    cacheDir = cachePath
                )
                val gpuEngine = Engine(gpuConfig)
                try {
                    gpuEngine.initialize()
                    Log.i(TAG, "Gemma initialized successfully with GPU backend.")
                    gpuEngine
                } catch (e: Exception) {
                    Log.w(TAG, "GPU Engine initialization failed: ${e.message}. Closing GPU resources.")
                    try { gpuEngine.close() } catch (ce: Exception) { /* ignore */ }
                    throw e
                }
            } catch (e: Exception) {
                // 2. Fallback to CPU (Highest compatibility)
                Log.w(TAG, "GPU not supported or failed to initialize. Falling back to CPU backend.")
                val cpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    audioBackend = Backend.CPU(),
                    maxNumImages = 1,
                    cacheDir = cachePath
                )
                val cpuEngine = Engine(cpuConfig)
                cpuEngine.initialize()
                Log.i(TAG, "Gemma initialized successfully with CPU backend.")
                cpuEngine
            }

            GemmaEngine(engine, context.cacheDir)
        }
    }
}
