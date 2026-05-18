package com.floodguard.rescue.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.floodguard.rescue.MainActivity
import com.floodguard.rescue.R
import com.floodguard.rescue.ai.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloadActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_download)

        val statusText = findViewById<TextView>(R.id.download_status)
        val progressBar = findViewById<ProgressBar>(R.id.download_progress)
        val downloadBtn = findViewById<Button>(R.id.btn_download)

        downloadBtn.setOnClickListener {
            downloadBtn.isEnabled = false
            progressBar.visibility = View.VISIBLE
            statusText.text = getString(R.string.downloading_model)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    downloadModel { progressPct, downloadedMb ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressBar.progress = progressPct
                            statusText.text = getString(
                                R.string.download_progress_format,
                                downloadedMb,
                                progressPct
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        startActivity(Intent(this@ModelDownloadActivity, MainActivity::class.java))
                        finish()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusText.text = getString(R.string.download_failed, e.message)
                        downloadBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun downloadModel(onProgress: (Int, Int) -> Unit) {
        val destFile = ModelManager.modelFile(this)
        val request = Request.Builder().url(MODEL_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty response body")
            val total = body.contentLength().takeIf { it > 0 } ?: ModelManager.EXPECTED_MIN_BYTES
            var downloaded = 0L
            FileOutputStream(destFile).use { out ->
                val buf = ByteArray(8192)
                body.byteStream().use { input ->
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        val mb = (downloaded / 1_000_000).toInt()
                        onProgress(pct, mb)
                    }
                }
            }
        }

        val check = ModelManager.checkModel(this)
        if (!check.isCompatible) {
            destFile.delete()
            error(check.reason ?: "Downloaded model is incompatible with this app build.")
        }
    }

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }
}
