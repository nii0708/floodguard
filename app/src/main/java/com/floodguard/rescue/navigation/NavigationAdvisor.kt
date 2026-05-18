package com.floodguard.rescue.navigation

import com.floodguard.rescue.memory.LandmarkRecord
import com.floodguard.rescue.memory.SpatialMemory
import com.floodguard.rescue.session.PoseSample
import kotlin.math.sqrt

enum class AlertPriority { LOW, MEDIUM, HIGH }

enum class AlertMessageType { TRAJECTORY_REVISIT, NEAR_LANDMARK, PAST_SESSION }

data class NavigationAlert(
    val messageType: AlertMessageType,
    val priority: AlertPriority,
    val matchedLandmark: LandmarkRecord? = null,
    val minutesAgo: Int = 0
) {
    val message: String
        get() = when (messageType) {
            AlertMessageType.TRAJECTORY_REVISIT ->
                "Peringatan. Anda sudah melewati area ini, sekitar $minutesAgo menit lalu. Cari rute lain."
            AlertMessageType.NEAR_LANDMARK ->
                "Info. Anda mendekati landmark: ${matchedLandmark?.description ?: "Unknown"}"
            AlertMessageType.PAST_SESSION ->
                "Info. Area ini terekam pada sesi sebelumnya."
        }
}

class NavigationAdvisor(private val memory: SpatialMemory) {

    fun checkTrajectoryRevisit(
        currentPose: FloatArray,
        trail: List<PoseSample>,
        pastLandmarks: List<LandmarkRecord> = emptyList()
    ): NavigationAlert? {
        val now = System.currentTimeMillis()

        // 1. Check current session's trajectory
        val trailMatch = trail.findLast { sample ->
            val dt = now - sample.timestamp
            if (dt < MIN_TIME_DIFF_MS) return@findLast false
            
            val dx = currentPose[0] - sample.x
            val dy = currentPose[1] - sample.y
            val dz = currentPose[2] - sample.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            
            dist < PROXIMITY_RADIUS_M
        }

        if (trailMatch != null) {
            val age = ((now - trailMatch.timestamp) / 1000 / 60).toInt()
            return NavigationAlert(
                messageType = AlertMessageType.TRAJECTORY_REVISIT,
                priority = AlertPriority.HIGH,
                minutesAgo = age
            )
        }

        // 2. Check current session's landmarks in memory
        val memoryMatch = memory.getAllLandmarks().find { lm ->
            val dx = currentPose[0] - lm.position[0]
            val dy = currentPose[1] - lm.position[1]
            val dz = currentPose[2] - lm.position[2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            dist < PROXIMITY_RADIUS_M
        }

        if (memoryMatch != null) {
            return NavigationAlert(
                messageType = AlertMessageType.NEAR_LANDMARK,
                priority = AlertPriority.MEDIUM,
                matchedLandmark = memoryMatch
            )
        }

        // 3. Check past session landmarks
        val pastMatch = pastLandmarks.find { lm ->
            val dx = currentPose[0] - lm.position[0]
            val dy = currentPose[1] - lm.position[1]
            val dz = currentPose[2] - lm.position[2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            dist < PROXIMITY_RADIUS_M
        }

        if (pastMatch != null) {
            return NavigationAlert(
                messageType = AlertMessageType.PAST_SESSION,
                priority = AlertPriority.MEDIUM,
                matchedLandmark = pastMatch
            )
        }

        return null
    }

    fun evaluate(position: FloatArray, description: String): NavigationAlert? {
        val match = memory.findRevisit(position, description) ?: return null

        val age = ((System.currentTimeMillis() - match.timestamp) / 1000 / 60).toInt()

        return NavigationAlert(
            messageType = AlertMessageType.TRAJECTORY_REVISIT,
            priority = AlertPriority.HIGH,
            matchedLandmark = match,
            minutesAgo = age
        )
    }

    companion object {
        private const val PROXIMITY_RADIUS_M = 1.8f
        private const val MIN_TIME_DIFF_MS = 30_000L // Don't match the same location within 30s
    }
}
