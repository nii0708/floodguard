package com.floodguard.rescue.navigation

import com.floodguard.rescue.memory.SpatialMemory
import com.floodguard.rescue.session.PoseSample
import org.junit.Assert.*
import org.junit.Test

class NavigationAdvisorTest {

    @Test
    fun `test checkTrajectoryRevisit detects loop`() {
        val advisor = NavigationAdvisor(SpatialMemory())
        val now = System.currentTimeMillis()
        
        // Trail starts 1 minute ago at origin
        val trail = listOf(
            PoseSample(0f, 0f, 0f, now - 60_000)
        )
        
        // Current position is also at origin
        val currentPose = floatArrayOf(0.1f, 0f, 0.1f)
        
        val alert = advisor.checkTrajectoryRevisit(currentPose, trail)
        
        assertNotNull("Should detect revisit", alert)
        assertEquals("Peringatan. Anda sudah melewati area ini, sekitar 1 menit lalu. Cari rute lain.", alert?.message)
    }

    @Test
    fun `test checkTrajectoryRevisit ignores recent points`() {
        val advisor = NavigationAdvisor(SpatialMemory())
        val now = System.currentTimeMillis()
        
        // Trail point is too recent (5 seconds ago)
        val trail = listOf(
            PoseSample(0f, 0f, 0f, now - 5_000)
        )
        
        val currentPose = floatArrayOf(0f, 0f, 0f)
        
        val alert = advisor.checkTrajectoryRevisit(currentPose, trail)
        
        assertNull("Should NOT detect revisit for recent points", alert)
    }

    @Test
    fun `test checkTrajectoryRevisit ignores far points`() {
        val advisor = NavigationAdvisor(SpatialMemory())
        val now = System.currentTimeMillis()
        
        // Trail point is 1 minute ago but 10m away
        val trail = listOf(
            PoseSample(10f, 0f, 10f, now - 60_000)
        )
        
        val currentPose = floatArrayOf(0f, 0f, 0f)
        
        val alert = advisor.checkTrajectoryRevisit(currentPose, trail)
        
        assertNull("Should NOT detect revisit for far points", alert)
    }
}
