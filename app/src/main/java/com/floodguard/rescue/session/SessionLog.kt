package com.floodguard.rescue.session

import com.floodguard.rescue.memory.LandmarkRecord

data class PoseSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long
)

data class SessionLog(
    val startTime: Long,
    val endTime: Long,
    val landmarks: List<LandmarkRecord>,
    val trail: List<PoseSample>,
    val revisitCount: Int,
    val distanceMeters: Float
) {
    val durationMinutes: Int
        get() = ((endTime - startTime) / 1000 / 60).toInt()
}
