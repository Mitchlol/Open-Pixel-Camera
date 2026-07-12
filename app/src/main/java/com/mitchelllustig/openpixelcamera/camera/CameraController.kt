package com.mitchelllustig.openpixelcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface

class CameraController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private var lastFrameTimeNanos = 0L
    private var frameCount = 0
    @Volatile private var pendingFrame: FrameData? = null
    @Volatile private var processingIdle = true
    @Volatile private var opening = false
    @Volatile private var openGeneration = 0

    private var cachedPixels: IntArray? = null
    private var cachedBitmap: Bitmap? = null

    var onFrameAvailable: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onActualResolutionChanged: ((Int, Int) -> Unit)? = null
    var sensorOrientation: Int = 0
        private set

    private var targetFpsRange: Range<Int> = Range(30, 30)
    private var exposureRange: Range<Long> = Range(33_333_333L, 33_333_333L)
    var isoRange: Range<Int> = Range(100, 1600)
        private set
    private var currentIso: Int = 200

    var availableFpsOptions: List<Int> = emptyList()
        private set

    var availableResolutions: List<Size> = emptyList()
        private set

    @SuppressLint("MissingPermission")
    fun openCamera(
        width: Int = 640,
        height: Int = 360,
        iso: Int = 200,
        onReady: (Surface) -> Unit = {}
    ) {
        if (opening) return
        opening = true
        openGeneration++

        close()

        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        processingThread = HandlerThread("FrameProcessing").apply { start() }
        processingHandler = Handler(processingThread!!.looper)

        val cameraId = findBackCamera()
        if (cameraId == null) {
            onError?.invoke("No back camera found")
            return
        }

        queryCameraCapabilities(cameraId)

        imageReader = ImageReader.newInstance(
            width, height,
            ImageFormat.YUV_420_888, 3
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = System.nanoTime()
                val deltaMs = if (lastFrameTimeNanos > 0) (now - lastFrameTimeNanos) / 1_000_000.0 else 0.0
                lastFrameTimeNanos = now
                frameCount++
                if (frameCount % 30 == 0) {
                    Log.i(TAG, "Frame #$frameCount delivered, delta=${"%.1f".format(deltaMs)}ms")
                }
                if (deltaMs > 50.0) {
                    Log.w(TAG, "FRAME GAP: ${"%.1f".format(deltaMs)}ms since last frame")
                }

                val planes = image.planes.map { plane ->
                    val buf = ByteArray(plane.buffer.remaining())
                    plane.buffer.get(buf)
                    BufferData(buf, plane.rowStride, plane.pixelStride)
                }
                val w = image.width
                val h = image.height
                image.close()

                pendingFrame = FrameData(planes, w, h)

                if (processingIdle) {
                    processingIdle = false
                    processingHandler?.post { drainPendingFrame() }
                }
            }, backgroundHandler)
        }

        val expectedGeneration = openGeneration

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (backgroundHandler == null || openGeneration != expectedGeneration) { opening = false; return }
                cameraDevice = camera
                onActualResolutionChanged?.invoke(width, height)
                createPreviewSession(camera, iso, onReady)
            }

            override fun onDisconnected(camera: CameraDevice) {
                opening = false
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                opening = false
                camera.close()
                cameraDevice = null
                onError?.invoke("Camera error: $error")
            }
        }, backgroundHandler)
    }

    private fun drainPendingFrame() {
        val frame = pendingFrame ?: run {
            processingIdle = true
            if (pendingFrame != null && processingIdle) {
                processingIdle = false
                processingHandler?.post { drainPendingFrame() }
            }
            return
        }
        pendingFrame = null

        val processStart = System.nanoTime()
        val bitmap = yuv420ToBitmap(frame.planes, frame.width, frame.height)
        val processMs = (System.nanoTime() - processStart) / 1_000_000.0
        if (processMs > 35.0) {
            Log.w(TAG, "SLOW PROCESS: ${"%.1f".format(processMs)}ms")
        }
        bitmap?.let { onFrameAvailable?.invoke(it) }

        if (pendingFrame != null) {
            processingHandler?.post { drainPendingFrame() }
        } else {
            processingIdle = true
            if (pendingFrame != null && processingIdle) {
                processingIdle = false
                processingHandler?.post { drainPendingFrame() }
            }
        }
    }

    private fun queryCameraCapabilities(cameraId: String) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        Log.i(TAG, "=== Camera Capabilities ===")
        Log.i(TAG, "Supported FPS ranges: ${fpsRanges?.joinToString { "${it.lower}..${it.upper}" }}")

        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minExpMs = expRange?.lower?.let { "%.1f".format(it / 1_000_000.0) } ?: "?"
        val maxExpMs = expRange?.upper?.let { "%.1f".format(it / 1_000_000.0) } ?: "?"
        Log.i(TAG, "Exposure time range: ${minExpMs}ms .. ${maxExpMs}ms")
        if (expRange != null) exposureRange = expRange

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        Log.i(TAG, "AF modes: ${afModes?.joinToString()}")

        val sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (sensitivityRange != null) {
            isoRange = sensitivityRange
            Log.i(TAG, "ISO range: ${sensitivityRange.lower}..${sensitivityRange.upper}")
        } else {
            Log.w(TAG, "ISO range not available, using default ${isoRange}")
        }

        val streamConfigs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (streamConfigs != null) {
            availableResolutions = streamConfigs.getOutputSizes(ImageFormat.YUV_420_888)
                ?.sortedBy { it.width * it.height }
                ?.distinct()
                ?: emptyList()
            Log.i(TAG, "Available resolutions: ${availableResolutions.joinToString { "${it.width}x${it.height}" }}")
        }

        Log.i(TAG, "=========================")

        val candidateFps = listOf(4, 7, 15, 20, 24, 30, 45, 48, 60)

        val inAeRange = candidateFps.filter { fps ->
            fpsRanges?.any { fps in it.lower..it.upper } == true
        }
        Log.i(TAG, "FPS in AE ranges: $inAeRange")

        val minExposureNs = expRange?.lower ?: 0L
        val maxExposureNs = expRange?.upper ?: Long.MAX_VALUE
        availableFpsOptions = inAeRange.filter { fps ->
            val requiredNs = 1_000_000_000L / fps
            requiredNs in minExposureNs..maxExposureNs
        }
        Log.i(TAG, "FPS after exposure filter: $availableFpsOptions")

        targetFpsRange = if (availableFpsOptions.contains(30)) Range(30, 30)
            else availableFpsOptions.lastOrNull()?.let { Range(it, it) }
            ?: fpsRanges?.lastOrNull { it.lower == 30 && it.upper == 30 }
            ?: fpsRanges?.lastOrNull()
            ?: Range(30, 30)

        Log.i(TAG, "Selected FPS range: $targetFpsRange")

        val initialFps = if (availableFpsOptions.contains(30)) 30
            else availableFpsOptions.lastOrNull() ?: 30
        actualExposureNanos = (1_000_000_000L / initialFps).coerceIn(
            expRange?.lower ?: 0L,
            expRange?.upper ?: Long.MAX_VALUE
        )
        Log.i(TAG, "Selected FPS: $initialFps, exposure: ${actualExposureNanos / 1_000_000.0}ms")
    }

    fun updateIso(iso: Int) {
        currentIso = iso
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val surface = imageReader?.surface ?: return

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposureNanos)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, actualExposureNanos)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
            }

            session.setRepeatingRequest(request.build(), null, backgroundHandler)
        } catch (_: IllegalStateException) {}
    }

    fun updateFps(fps: Int) {
        targetFpsRange = Range(fps, fps)
        actualExposureNanos = (1_000_000_000L / fps).coerceIn(exposureRange.lower, exposureRange.upper)
        updateIso(currentIso)
    }

    private fun createPreviewSession(
        camera: CameraDevice,
        iso: Int,
        onReady: (Surface) -> Unit
    ) {
        val surface = imageReader?.surface ?: return

        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(session, iso)
                        opening = false
                        onReady(surface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        opening = false
                        onError?.invoke("Camera session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (_: IllegalStateException) {
            opening = false
        }
    }

    private fun startPreview(session: CameraCaptureSession, iso: Int) {
        val camera = cameraDevice ?: return
        val surface = imageReader?.surface ?: return

        Log.i(TAG, "Starting preview: exposure=${actualExposureNanos}ns (${actualExposureNanos / 1_000_000.0}ms), fps=$targetFpsRange, iso=$iso")

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposureNanos)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, actualExposureNanos)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
            }

            session.setRepeatingRequest(request.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview: ${e.message}")
        }
    }

    fun close() {
        opening = false
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        pendingFrame = null
        processingIdle = true
        processingThread?.quitSafely()
        try { processingThread?.join() } catch (_: InterruptedException) {}
        processingThread = null
        processingHandler = null
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    private fun findBackCamera(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                true
            } else {
                false
            }
        }
    }

    private data class BufferData(
        val data: ByteArray,
        val rowStride: Int,
        val pixelStride: Int
    )

    private data class FrameData(
        val planes: List<BufferData>,
        val width: Int,
        val height: Int
    )

    private fun yuv420ToBitmap(planes: List<BufferData>, width: Int, height: Int): Bitmap? {
        val yData = planes[0].data
        val uData = planes[1].data
        val vData = planes[2].data
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        val size = width * height
        var pixels = cachedPixels
        if (pixels == null || pixels.size != size) {
            pixels = IntArray(size)
            cachedPixels = pixels
            cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        for (row in 0 until height) {
            for (col in 0 until width) {
                val y = (yData[row * yRowStride + col].toInt() and 0xFF) - 16

                val uvIndex = (row / 2) * uvRowStride + (col / 2) * uvPixelStride
                val u = (uData[uvIndex].toInt() and 0xFF) - 128
                val v = (vData[uvIndex].toInt() and 0xFF) - 128

                val c = 298 * y
                val r = ((c + 409 * v) / 256).coerceIn(0, 255)
                val g = ((c - 100 * v - 208 * u) / 256).coerceIn(0, 255)
                val b = ((c + 516 * u) / 256).coerceIn(0, 255)

                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = cachedBitmap!!
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    companion object {
        private const val TAG = "CameraController"
        private var actualExposureNanos = 33_333_333L
    }
}
