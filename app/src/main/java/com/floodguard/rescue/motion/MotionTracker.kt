package com.floodguard.rescue.motion

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * EKF-based Pedestrian Dead Reckoning (PDR).
 *
 * State vector x = [px, pz, vx, vz] (position and velocity in horizontal world frame, metres).
 * Heading is maintained externally as rawHeading + visualYawAccum and fed in as a known input.
 *
 * Process model (linear, driven by linear acceleration):
 *   px ← px + vx·dt
 *   pz ← pz + vz·dt
 *   vx ← vx·(1 − damp·dt) + ax·dt·gain
 *   vz ← vz·(1 − damp·dt) + az·dt·gain
 *
 * Measurement updates:
 *   • Step event  — velocity observation [vx_step, vz_step], noise ∝ step-length uncertainty
 *   • ZUPT        — zero-velocity observation [0, 0], very low noise
 *   • Visual yaw  — heading bias correction, applied outside the EKF state (same approach as before)
 *
 * Both measurement types share the same 2-D velocity update (H = [[0,0,1,0],[0,0,0,1]]),
 * differing only in the measured value and noise covariance.
 */
class MotionTracker : SensorEventListener {

    private val lock = Any()

    // EKF state [px, pz, vx, vz] and 4×4 covariance (row-major).
    private val x = FloatArray(4)
    private val P = FloatArray(16)

    // Pre-allocated scratch buffers for ekfUpdateVelocity (called up to ~100 Hz at ZUPT).
    private val scratchRow2 = FloatArray(4)
    private val scratchRow3 = FloatArray(4)
    private val scratchPh   = FloatArray(8)   // 4×2, [i*2+j]
    private val scratchK    = FloatArray(8)   // 4×2, [i*2+j]

    private val rotationMatrix = FloatArray(9)
    private var hasRotation    = false

    private var rawHeadingRad = 0f
    private var headingRad    = 0f

    private var lastStepTsNs        = 0L
    private var stepCounterBase     : Float? = null
    private var lastStepCounterValue: Float? = null
    private var lastAccelTsNs       = 0L
    private var accelBiasX          = 0f
    private var accelBiasY          = 0f
    private var accelBiasZ          = 0f
    private var biasSamples         = 0
    private var stillTimeSec        = 0f
    private var recentPeakAccelMps2 = 0f

    @Volatile private var arcoreIsTracking = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun reset() = synchronized(lock) {
        x.fill(0f)
        P.fill(0f)
        P[0] = INIT_POS_VAR;  P[5]  = INIT_POS_VAR
        P[10] = INIT_VEL_VAR; P[15] = INIT_VEL_VAR

        hasRotation = false
        rawHeadingRad = 0f; headingRad = 0f
        lastStepTsNs = 0L
        stepCounterBase = null; lastStepCounterValue = null
        lastAccelTsNs = 0L
        accelBiasX = 0f; accelBiasY = 0f; accelBiasZ = 0f
        biasSamples = 0; stillTimeSec = 0f; recentPeakAccelMps2 = 0f
        arcoreIsTracking = false
    }

    fun getPose(): FloatArray = synchronized(lock) { floatArrayOf(x[0], 0f, x[1]) }

    fun updateArcore(pose: ArcoreTracker.ArPose) = synchronized(lock) {
        arcoreIsTracking = pose.isTracking
        if (pose.isTracking) {
            // Sync EKF position to ARCore's absolute world position.
            x[0] = pose.x; x[1] = pose.z
            headingRad    = pose.headingRad
            rawHeadingRad = pose.headingRad
            hasRotation   = true
        }
    }

    // -------------------------------------------------------------------------
    // SensorEventListener
    // -------------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {

            Sensor.TYPE_ROTATION_VECTOR -> synchronized(lock) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val forwardX = -rotationMatrix[2]
                val forwardZ = -rotationMatrix[8]
                rawHeadingRad = atan2(forwardX, forwardZ)
                if (!arcoreIsTracking) headingRad = rawHeadingRad
                hasRotation   = true
            }

            Sensor.TYPE_STEP_DETECTOR -> synchronized(lock) {
                if (!hasRotation) return
                applyStep(1, event.timestamp)
            }

            Sensor.TYPE_STEP_COUNTER -> synchronized(lock) {
                if (!hasRotation) return
                val value = event.values.firstOrNull() ?: return
                if (stepCounterBase == null) {
                    stepCounterBase = value; lastStepCounterValue = value; return
                }
                val delta = (value - (lastStepCounterValue ?: value)).toInt()
                lastStepCounterValue = value
                if (delta > 0) applyStep(delta, event.timestamp)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> synchronized(lock) {
                if (!hasRotation) return
                if (lastAccelTsNs == 0L) { lastAccelTsNs = event.timestamp; return }

                val dt = (event.timestamp - lastAccelTsNs).coerceAtLeast(0L) / 1_000_000_000f
                lastAccelTsNs = event.timestamp
                if (dt <= 0f || dt > 0.2f) return

                val axBody = event.values[0]
                val ayBody = event.values[1]
                val azBody = event.values[2]

                // Accumulate bias during the warm-up window (device assumed stationary at launch).
                if (biasSamples < BIAS_WARMUP_SAMPLES) {
                    biasSamples++
                    val a = 1f / biasSamples
                    accelBiasX += (axBody - accelBiasX) * a
                    accelBiasY += (ayBody - accelBiasY) * a
                    accelBiasZ += (azBody - accelBiasZ) * a
                    return
                }

                val axC = axBody - accelBiasX
                val ayC = ayBody - accelBiasY
                val azC = azBody - accelBiasZ

                // Rotate device-frame acceleration to world horizontal frame.
                val axW = rotationMatrix[0]*axC + rotationMatrix[1]*ayC + rotationMatrix[2]*azC
                val azW = rotationMatrix[6]*axC + rotationMatrix[7]*ayC + rotationMatrix[8]*azC

                // Track peak magnitude between steps for adaptive stride scaling.
                val horizMag = sqrt(axW*axW + azW*azW)
                if (horizMag > recentPeakAccelMps2) recentPeakAccelMps2 = horizMag

                val noStepSec = if (lastStepTsNs != 0L)
                    (event.timestamp - lastStepTsNs) / 1_000_000_000f else Float.MAX_VALUE
                val isWalking = lastStepTsNs != 0L && noStepSec < NO_STEP_STOP_SEC

                val ax = if (isWalking && abs(axW) >= ACCEL_DEADBAND) axW else 0f
                val az = if (isWalking && abs(azW) >= ACCEL_DEADBAND) azW else 0f

                ekfPredict(ax, az, dt)

                // ZUPT: zero-velocity update when device has been still long enough.
                val accelMag = abs(ax) + abs(az)
                if (accelMag < STILL_ACCEL_THRESHOLD) {
                    stillTimeSec += dt
                    if (stillTimeSec >= STILL_LOCK_TIME_SEC) {
                        ekfUpdateVelocity(0f, 0f, R_ZUPT)
                        recentPeakAccelMps2 = 0f
                    }
                } else {
                    stillTimeSec = 0f
                }

                // Hard stop if no step has fired recently.
                if (lastStepTsNs != 0L && noStepSec > NO_STEP_STOP_SEC) {
                    ekfUpdateVelocity(0f, 0f, R_ZUPT)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // -------------------------------------------------------------------------
    // Step application
    // -------------------------------------------------------------------------

    private fun applyStep(count: Int, timestampNs: Long) {
        if (arcoreIsTracking) return   // ARCore provides absolute position; PDR not needed

        repeat(count) {
            val dt = if (lastStepTsNs == 0L) DEFAULT_STEP_DT_SEC
                     else ((timestampNs - lastStepTsNs).coerceAtLeast(1L) / 1_000_000_000f)
                         .coerceIn(0.25f, 2.0f)

            // Cadence-based stride scaling.
            val cadence = 1f / dt
            val strideScale = (1f + (cadence - REFERENCE_CADENCE_HZ) * CADENCE_STRIDE_GAIN)
                .coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)

            // Amplitude-based stride scaling: lower accel peak → shorter step.
            val peak = if (recentPeakAccelMps2 > 0f) recentPeakAccelMps2 else REFERENCE_STEP_ACCEL_MPS2
            val accelScale = (peak / REFERENCE_STEP_ACCEL_MPS2).coerceIn(0.75f, 1.25f)
            recentPeakAccelMps2 = 0f

            val stepLen = BASE_STEP_LEN * strideScale * accelScale

            // Expected velocity this step = displacement / interval.
            val vxStep = stepLen * sin(headingRad) / dt
            val vzStep = stepLen * cos(headingRad) / dt

            // Step-length uncertainty (~15%) converts to velocity noise.
            val sigmaVel = STEP_LEN_SIGMA_FRAC * stepLen / dt
            ekfUpdateVelocity(vxStep, vzStep, sigmaVel * sigmaVel)

            lastStepTsNs = timestampNs
        }
    }

    // -------------------------------------------------------------------------
    // EKF core — allocation-free in the predict path
    // -------------------------------------------------------------------------

    /**
     * EKF predict.
     *
     * State transition: F = [[1,0,dt,0],[0,1,0,dt],[0,0,d,0],[0,0,0,d]]
     * Covariance:       P ← F·P·F^T + Q   (inlined, no heap allocation)
     */
    private fun ekfPredict(ax: Float, az: Float, dt: Float) {
        val d = max(0f, 1f - VELOCITY_DAMPING * dt)

        x[0] += x[2] * dt
        x[1] += x[3] * dt
        x[2] = x[2] * d + ax * dt * ACCEL_GAIN
        x[3] = x[3] * d + az * dt * ACCEL_GAIN

        // Load P into locals so we read the pre-update values throughout.
        val p0  = P[0];  val p1  = P[1];  val p2  = P[2];  val p3  = P[3]
        val p4  = P[4];  val p5  = P[5];  val p6  = P[6];  val p7  = P[7]
        val p8  = P[8];  val p9  = P[9];  val p10 = P[10]; val p11 = P[11]
        val p12 = P[12]; val p13 = P[13]; val p14 = P[14]; val p15 = P[15]

        // F·P  (4 rows, each computed from F's sparse structure)
        // row 0: F[0]=[1,0,dt,0]  → FP[0][j] = P[0][j] + dt·P[2][j]
        val fp00 = p0+dt*p8;  val fp01 = p1+dt*p9;  val fp02 = p2+dt*p10; val fp03 = p3+dt*p11
        // row 1: F[1]=[0,1,0,dt]  → FP[1][j] = P[1][j] + dt·P[3][j]
        val fp10 = p4+dt*p12; val fp11 = p5+dt*p13; val fp12 = p6+dt*p14; val fp13 = p7+dt*p15
        // row 2: F[2]=[0,0,d,0]   → FP[2][j] = d·P[2][j]
        val fp20 = d*p8;  val fp21 = d*p9;  val fp22 = d*p10; val fp23 = d*p11
        // row 3: F[3]=[0,0,0,d]   → FP[3][j] = d·P[3][j]
        val fp30 = d*p12; val fp31 = d*p13; val fp32 = d*p14; val fp33 = d*p15

        // (F·P)·F^T  col j computed using F^T col j = F row j
        // col 0 (F[0]=[1,0,dt,0]): result[i][0] = FP[i][0] + dt·FP[i][2]
        // col 1 (F[1]=[0,1,0,dt]): result[i][1] = FP[i][1] + dt·FP[i][3]
        // col 2 (F[2]=[0,0,d, 0]): result[i][2] = d·FP[i][2]
        // col 3 (F[3]=[0,0,0, d]): result[i][3] = d·FP[i][3]
        P[0]  = fp00+dt*fp02 + Q_POS; P[1]  = fp01+dt*fp03
        P[2]  = d*fp02;                P[3]  = d*fp03
        P[4]  = fp10+dt*fp12;          P[5]  = fp11+dt*fp13 + Q_POS
        P[6]  = d*fp12;                P[7]  = d*fp13
        P[8]  = fp20+dt*fp22;          P[9]  = fp21+dt*fp23
        P[10] = d*fp22 + Q_VEL;        P[11] = d*fp23
        P[12] = fp30+dt*fp32;          P[13] = fp31+dt*fp33
        P[14] = d*fp32;                P[15] = d*fp33 + Q_VEL
    }

    /**
     * EKF measurement update for a 2-D velocity observation.
     *
     * H = [[0,0,1,0],[0,0,0,1]]  (selects vx, vz from state)
     * z = [vxMeas, vzMeas]
     * R = rVel · I₂  (scalar noise, same on both axes)
     *
     * Uses pre-allocated scratch arrays — no heap allocation.
     */
    private fun ekfUpdateVelocity(vxMeas: Float, vzMeas: Float, rVel: Float) {
        // S = H·P·H^T + R·I₂  →  top-left 2×2 of P at vx/vz rows/cols, plus R on diagonal.
        val s00 = P[10] + rVel;  val s01 = P[11]
        val s10 = P[14];          val s11 = P[15] + rVel
        val det = s00*s11 - s01*s10
        if (abs(det) < 1e-10f) return

        val di  = 1f / det
        val si00 =  s11*di;  val si01 = -s01*di
        val si10 = -s10*di;  val si11 =  s00*di

        // P·H^T: columns 2 and 3 of P  → stored in scratchPh[i*2+{0,1}]
        for (i in 0..3) {
            scratchPh[i*2]   = P[i*4+2]
            scratchPh[i*2+1] = P[i*4+3]
        }

        // K = (P·H^T)·S^-1  →  4×2, stored in scratchK
        for (i in 0..3) {
            val ph0 = scratchPh[i*2]; val ph1 = scratchPh[i*2+1]
            scratchK[i*2]   = ph0*si00 + ph1*si10
            scratchK[i*2+1] = ph0*si01 + ph1*si11
        }

        // x ← x + K·y
        val y0 = vxMeas - x[2];  val y1 = vzMeas - x[3]
        for (i in 0..3) x[i] += scratchK[i*2]*y0 + scratchK[i*2+1]*y1

        // P ← (I − K·H)·P  =  P − K·(H·P)
        // H·P selects rows 2 and 3 of P; save them before any modification.
        for (j in 0..3) { scratchRow2[j] = P[8+j]; scratchRow3[j] = P[12+j] }
        for (i in 0..3) {
            val k0 = scratchK[i*2]; val k1 = scratchK[i*2+1]
            for (j in 0..3) P[i*4+j] -= k0*scratchRow2[j] + k1*scratchRow3[j]
        }

        // Symmetrise to prevent numerical drift from accumulating.
        for (i in 0..3) for (j in i+1..3) {
            val avg = (P[i*4+j] + P[j*4+i]) * 0.5f
            P[i*4+j] = avg;  P[j*4+i] = avg
        }
        // Clamp diagonal: variance must stay positive.
        for (i in 0..3) if (P[i*4+i] < MIN_VARIANCE) P[i*4+i] = MIN_VARIANCE
    }

    // -------------------------------------------------------------------------
    companion object {
        // Sensor processing
        private const val BIAS_WARMUP_SAMPLES      = 60
        private const val ACCEL_DEADBAND           = 0.08f
        private const val STILL_ACCEL_THRESHOLD    = 0.12f
        private const val STILL_LOCK_TIME_SEC      = 0.20f
        private const val VELOCITY_DAMPING         = 0.4f
        private const val ACCEL_GAIN               = 0.08f
        private const val NO_STEP_STOP_SEC         = 2.5f

        // Step length model
        private const val BASE_STEP_LEN            = 0.62f
        private const val DEFAULT_STEP_DT_SEC      = 0.55f
        private const val REFERENCE_CADENCE_HZ     = 1.8f
        private const val CADENCE_STRIDE_GAIN      = 0.18f
        private const val MIN_STRIDE_SCALE         = 0.8f
        private const val MAX_STRIDE_SCALE         = 1.25f
        private const val REFERENCE_STEP_ACCEL_MPS2 = 1.2f
        private const val STEP_LEN_SIGMA_FRAC      = 0.15f

        // EKF noise parameters
        private const val INIT_POS_VAR  = 0.25f
        private const val INIT_VEL_VAR  = 1.0f
        private const val Q_POS         = 1e-4f
        private const val Q_VEL         = 1e-3f
        private const val R_ZUPT        = 1e-4f
        private const val MIN_VARIANCE  = 1e-6f
    }
}
