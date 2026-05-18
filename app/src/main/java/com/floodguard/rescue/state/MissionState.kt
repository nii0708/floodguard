package com.floodguard.rescue.state

import com.floodguard.rescue.session.SessionLog

sealed class MissionState {
    data object Idle : MissionState()
    data class Starting(val remainingSeconds: Int) : MissionState()
    data class Active(
        val isAlerting: Boolean = false,
        val alertMessage: String = ""
    ) : MissionState()
    data class Review(val sessionData: SessionLog) : MissionState()
}
