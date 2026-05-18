package com.floodguard.rescue.session

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionExporter {

    fun export(context: Context, log: SessionLog): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "floodguard"
        )
        if (!dir.exists()) dir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(log.endTime))
        val file = File(dir, "session_$timestamp.json")

        val json = JSONObject().apply {
            put("startTime", log.startTime)
            put("endTime", log.endTime)
            put("durationMinutes", log.durationMinutes)
            put("distanceMeters", log.distanceMeters.toDouble())
            put("revisitCount", log.revisitCount)
            put("landmarkCount", log.landmarks.size)
            put("trailPointCount", log.trail.size)
            put("trail", JSONArray().apply {
                log.trail.forEachIndexed { index, sample ->
                    put(JSONObject().apply {
                        put("index", index + 1)
                        put("x", sample.x.toDouble())
                        put("y", sample.y.toDouble())
                        put("z", sample.z.toDouble())
                        put("timestamp", sample.timestamp)
                    })
                }
            })
            put("landmarks", JSONArray().apply {
                log.landmarks.forEachIndexed { index, lm ->
                    put(JSONObject().apply {
                        put("index", index + 1)
                        put("id", lm.id)
                        put("description", lm.description)
                        put("x", lm.position[0].toDouble())
                        put("y", lm.position[1].toDouble())
                        put("z", lm.position[2].toDouble())
                        put("timestamp", lm.timestamp)
                        put("keywords", JSONArray(lm.keywords.toList()))
                    })
                }
            })
        }

        file.writeText(json.toString(2))
        return file
    }
}
