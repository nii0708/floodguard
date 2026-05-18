package com.floodguard.rescue.session

import android.content.Context
import com.floodguard.rescue.memory.LandmarkRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ExplorationHistory {

    private const val DIR_NAME = "exploration_history"

    private fun historyDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveSession(context: Context, landmarks: List<LandmarkRecord>) {
        if (landmarks.isEmpty()) return
        val dir = historyDir(context)
        val filename = "session_${System.currentTimeMillis()}.json"
        val file = File(dir, filename)

        val jsonArray = JSONArray()
        landmarks.forEach { lm ->
            jsonArray.put(JSONObject().apply {
                put("id", lm.id)
                put("description", lm.description)
                put("x", lm.position[0].toDouble())
                put("y", lm.position[1].toDouble())
                put("z", lm.position[2].toDouble())
                put("timestamp", lm.timestamp)
                put("keywords", JSONArray(lm.keywords.toList()))
            })
        }

        file.writeText(jsonArray.toString())
    }

    fun loadAll(context: Context): List<LandmarkRecord> {
        val dir = historyDir(context)
        val allLandmarks = mutableListOf<LandmarkRecord>()

        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val jsonArray = JSONArray(file.readText())
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val position = floatArrayOf(
                        obj.getDouble("x").toFloat(),
                        obj.getDouble("y").toFloat(),
                        obj.getDouble("z").toFloat()
                    )
                    val description = obj.getString("description")
                    val keywords = mutableSetOf<String>()
                    val kwArray = obj.optJSONArray("keywords")
                    if (kwArray != null) {
                        for (k in 0 until kwArray.length()) {
                            keywords.add(kwArray.getString(k))
                        }
                    }
                    allLandmarks.add(
                        LandmarkRecord(
                            id = obj.getString("id"),
                            description = description,
                            position = position,
                            timestamp = obj.getLong("timestamp"),
                            keywords = keywords.ifEmpty { LandmarkRecord.extractKeywords(description) }
                        )
                    )
                }
            } catch (_: Exception) {
                // Skip corrupt files
            }
        }

        return allLandmarks.sortedBy { it.timestamp }
    }

    fun clearAll(context: Context) {
        val dir = historyDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    fun sessionCount(context: Context): Int {
        val dir = historyDir(context)
        return dir.listFiles()?.count { it.extension == "json" } ?: 0
    }
}
