package com.floodguard.rescue.ai

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * Locates and validates the on-device model file used by MediaPipe LLM Inference.
 */
object ModelManager {

    private const val MODEL_DIR = "models"
    const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

    /** Approximate expected file size used to reject partial downloads. */
    const val EXPECTED_MIN_BYTES = 2_400_000_000L

    // LiteRT-LM files start with the 8-byte ASCII string "LITERTLM"; first 4 bytes = "LITE".
    // Legacy TFLite flat-buffer files start with "TFL3".
    private val SUPPORTED_MAGICS = setOf("LITE", "TFL3")

    data class ModelCheck(
        val exists: Boolean,
        val sizeBytes: Long,
        val magic: String?,
        val isCompatible: Boolean,
        val reason: String?
    )

    fun modelPath(context: Context): String = modelFile(context).absolutePath

    fun isModelPresent(context: Context): Boolean = checkModel(context).isCompatible

    fun modelFile(context: Context): File {
        val internalDir = File(context.filesDir, MODEL_DIR)
        val internalFile = File(internalDir, MODEL_FILENAME)
        if (internalFile.exists()) return internalFile

        val externalDir = context.getExternalFilesDir(MODEL_DIR)
        if (externalDir != null) {
            val externalFile = File(externalDir, MODEL_FILENAME)
            if (externalFile.exists()) return externalFile
        }

        // Default to internal for downloads
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalFile
    }

    fun checkModel(context: Context): ModelCheck {
        val file = modelFile(context)
        if (!file.exists()) {
            return ModelCheck(
                exists = false,
                sizeBytes = 0L,
                magic = null,
                isCompatible = false,
                reason = "Model file not found: ${file.absolutePath}"
            )
        }

        val size = file.length()
        if (size < EXPECTED_MIN_BYTES) {
            return ModelCheck(
                exists = true,
                sizeBytes = size,
                magic = readMagic(file),
                isCompatible = false,
                reason = "Model file looks incomplete (${size} bytes)."
            )
        }

        val magic = readMagic(file)
        if (magic !in SUPPORTED_MAGICS) {
            return ModelCheck(
                exists = true,
                sizeBytes = size,
                magic = magic,
                isCompatible = false,
                reason = "Incompatible model format header '$magic' (supported: ${SUPPORTED_MAGICS.joinToString()})."
            )
        }

        return ModelCheck(
            exists = true,
            sizeBytes = size,
            magic = magic,
            isCompatible = true,
            reason = null
        )
    }

    private fun readMagic(file: File): String? {
        if (!file.exists() || file.length() < 4) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                String(bytes, Charsets.US_ASCII)
            }
        } catch (_: Exception) {
            null
        }
    }
}
