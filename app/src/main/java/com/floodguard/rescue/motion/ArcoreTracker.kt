package com.floodguard.rescue.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2

/**
 * ARCore-based visual-inertial odometry.
 *
 * Implements GLSurfaceView.Renderer; attach it to an ar_surface_view in the layout.
 * Calls onPoseUpdate on every GL frame (≈30–60 Hz) and onFrameAvailable every
 * FRAME_SKIP frames (≈7 Hz) for Gemma inference.
 */
class ArcoreTracker(
    private val context: Context,
    private val onPoseUpdate: (ArPose) -> Unit,
    private val onFrameAvailable: (Bitmap) -> Unit
) : GLSurfaceView.Renderer {

    data class ArPose(
        val x: Float,
        val z: Float,
        val headingRad: Float,
        val isTracking: Boolean,
        val confidence: Float
    )

    private var session: Session? = null
    private var oesTextureId = 0
    private var bgProgram = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var displayRotation = 0
    private var pendingCaptureCallback: ((Bitmap) -> Unit)? = null

    // NDC quad for TRIANGLE_STRIP: bl, br, tl, tr
    private val quadCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }

    private val texCoordsBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun setup(): Boolean {
        if (!ArCoreApk.getInstance().checkAvailability(context).isSupported) return false
        return try {
            val s = Session(context)
            val config = Config(s).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    depthMode = Config.DepthMode.AUTOMATIC
                }
            }
            s.configure(config)
            session = s
            true
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore unavailable: $e")
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission not granted: $e")
            false
        }
    }

    fun setDisplayRotation(rotation: Int) { displayRotation = rotation }

    fun resume(): Boolean = try { session?.resume(); true }
        catch (e: CameraNotAvailableException) { Log.e(TAG, "Camera not available: $e"); false }

    fun pause() { session?.pause() }

    fun release() { session?.close(); session = null }

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        session?.setCameraTextureName(oesTextureId)
        bgProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")
        textureHandle  = GLES20.glGetUniformLocation(bgProgram, "u_Texture")
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        val frame: Frame = try { s.update() } catch (e: CameraNotAvailableException) { return }

        drawBackground(frame)

        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val confidence = when (camera.trackingState) {
            TrackingState.TRACKING -> 1f
            TrackingState.PAUSED   -> 0.3f
            TrackingState.STOPPED  -> 0f
        }
        val p = camera.pose
        val qx = p.qx(); val qy = p.qy(); val qz = p.qz(); val qw = p.qw()
        // Yaw (rotation around world Y axis) from quaternion.
        // ARCore: X right, Y up, -Z forward → negate Z for our +Z-forward convention.
        val heading = atan2(
            2.0 * (qy * qw - qx * qz),
            1.0 - 2.0 * (qy * qy + qz * qz)
        ).toFloat()
        onPoseUpdate(ArPose(p.tx(), -p.tz(), heading, tracking, confidence))

        pendingCaptureCallback?.let { callback ->
            try {
                frame.acquireCameraImage().use { img ->
                    // Manual capture uses 512x512 for better AI detail
                    img.toScaledBitmap(512, 512)?.let { callback(it) }
                }
                pendingCaptureCallback = null
            } catch (_: NotYetAvailableException) {
                // Try again on next frame
            } catch (e: Exception) {
                Log.w(TAG, "Manual capture error: $e")
                pendingCaptureCallback = null
            }
        }
    }

    fun requestCapture(callback: (Bitmap) -> Unit) {
        pendingCaptureCallback = callback
    }

    private fun drawBackground(frame: Frame) {
        val uvOut = FloatArray(8)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            Coordinates2d.TEXTURE_NORMALIZED,
            uvOut
        )
        texCoordsBuffer.position(0); texCoordsBuffer.put(uvOut); texCoordsBuffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(textureHandle, 0)
        quadCoords.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordsBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    companion object {
        private const val TAG = "ArcoreTracker"

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }"""

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }"""

        private fun createProgram(vertSrc: String, fragSrc: String): Int {
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, compileShader(GLES20.GL_VERTEX_SHADER, vertSrc))
            GLES20.glAttachShader(prog, compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc))
            GLES20.glLinkProgram(prog)
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            return shader
        }
    }
}

private fun Image.toScaledBitmap(targetW: Int, targetH: Int): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null
    val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
    val w = width; val h = height
    val uvW = (w + 1) / 2; val uvH = (h + 1) / 2
    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride
    val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer

    // Build NV21 directly from the plane ByteBuffers using the reported strides so
    // we never allocate the full (padded) plane buffers as intermediate byte arrays.
    val nv21 = ByteArray(w * h + 2 * uvW * uvH)
    var yDst = 0
    for (row in 0 until h) {
        yBuf.position(row * yRowStride)
        yBuf.get(nv21, yDst, w)
        yDst += w
    }
    var uvDst = w * h
    for (row in 0 until uvH) {
        for (col in 0 until uvW) {
            val idx = row * uvRowStride + col * uvPixelStride
            nv21[uvDst++] = vBuf.get(idx)
            nv21[uvDst++] = uBuf.get(idx)
        }
    }

    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 70, out)
    val jpegBytes = out.toByteArray()

    // Decode at a reduced sample size so we never inflate a full-resolution Bitmap
    // (e.g. 4032×3024 → 48 MB) just to immediately scale it down to 512×512.
    val sampleSize = run {
        var s = 1
        var sw = w; var sh = h
        while (sw / 2 >= targetW && sh / 2 >= targetH) { s *= 2; sw /= 2; sh /= 2 }
        s
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val sampled = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
    if (sampled.width == targetW && sampled.height == targetH) return sampled
    val scaled = Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
    sampled.recycle()
    return scaled
}
