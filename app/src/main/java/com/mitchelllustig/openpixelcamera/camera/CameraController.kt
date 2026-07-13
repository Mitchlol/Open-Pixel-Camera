package com.mitchelllustig.openpixelcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import io.github.crow_misia.libyuv.RotateMode
import io.github.crow_misia.libyuv.RowStride
import io.github.crow_misia.libyuv.Yuv
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private var cachedYBuf: ByteBuffer? = null
    private var cachedUBuf: ByteBuffer? = null
    private var cachedVBuf: ByteBuffer? = null
    private var cachedArgbBuf: ByteBuffer? = null
    private var cachedRotatedArgbBuf: ByteBuffer? = null
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

                val w = image.width
                val h = image.height

                val planes = image.planes
                val yPlane = planes[0]
                val uPlane = planes[1]
                val vPlane = planes[2]

                val yRowStride = yPlane.rowStride
                val uvRowStride = uPlane.rowStride
                val uvPixelStride = uPlane.pixelStride
                val vBufPosition0 = vPlane.buffer.position() == 0

                val ySize = yPlane.buffer.remaining()
                val uSize = uPlane.buffer.remaining()
                val vSize = vPlane.buffer.remaining()

                var yBuf = cachedYBuf
                if (yBuf == null || yBuf.capacity() < ySize) {
                    yBuf = ByteBuffer.allocateDirect(ySize).order(ByteOrder.nativeOrder())
                    cachedYBuf = yBuf
                }
                yBuf.clear()
                val yTmp = ByteArray(ySize)
                yPlane.buffer.get(yTmp)
                yBuf.put(yTmp)
                yBuf.flip()

                var uBuf = cachedUBuf
                if (uBuf == null || uBuf.capacity() < uSize) {
                    uBuf = ByteBuffer.allocateDirect(uSize).order(ByteOrder.nativeOrder())
                    cachedUBuf = uBuf
                }
                uBuf.clear()
                val uTmp = ByteArray(uSize)
                uPlane.buffer.get(uTmp)
                uBuf.put(uTmp)
                uBuf.flip()

                var vBuf = cachedVBuf
                if (vBuf == null || vBuf.capacity() < vSize) {
                    vBuf = ByteBuffer.allocateDirect(vSize).order(ByteOrder.nativeOrder())
                    cachedVBuf = vBuf
                }
                vBuf.clear()
                val vTmp = ByteArray(vSize)
                vPlane.buffer.get(vTmp)
                vBuf.put(vTmp)
                vBuf.flip()

                image.close()

                pendingFrame = FrameData(
                    yBuf, uBuf, vBuf,
                    yRowStride, uvRowStride, uvPixelStride,
                    vBufPosition0,
                    w, h
                )

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
        val bitmap = yuv420ToBitmap(frame, sensorOrientation)
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

    private data class FrameData(
        val yBuf: ByteBuffer,
        val uBuf: ByteBuffer,
        val vBuf: ByteBuffer,
        val yRowStride: Int,
        val uvRowStride: Int,
        val uvPixelStride: Int,
        val isNv21: Boolean,
        val width: Int,
        val height: Int
    )

    private fun yuv420ToBitmap(frame: FrameData, orientation: Int): Bitmap? {
        val width = frame.width
        val height = frame.height

        val argbSize = width * height * 4
        var argbBuf = cachedArgbBuf
        if (argbBuf == null || argbBuf.capacity() < argbSize) {
            argbBuf = ByteBuffer.allocateDirect(argbSize).order(ByteOrder.nativeOrder())
            cachedArgbBuf = argbBuf
        }
        argbBuf.clear()

        if (frame.uvPixelStride == 1) {
            Yuv.convertI420ToARGB(
                frame.yBuf, RowStride(frame.yRowStride), 0,
                frame.uBuf, RowStride(frame.uvRowStride), 0,
                frame.vBuf, RowStride(frame.uvRowStride), 0,
                argbBuf, RowStride(width * 4), 0,
                width, height
            )
        } else if (frame.isNv21) {
            Yuv.convertNV21ToARGB(
                frame.yBuf, RowStride(frame.yRowStride), 0,
                frame.uBuf, RowStride(frame.uvRowStride), 0,
                argbBuf, RowStride(width * 4), 0,
                width, height
            )
        } else {
            Yuv.convertNV12ToARGB(
                frame.yBuf, RowStride(frame.yRowStride), 0,
                frame.vBuf, RowStride(frame.uvRowStride), 0,
                argbBuf, RowStride(width * 4), 0,
                width, height
            )
        }

        val rotateMode = when (orientation) {
            90 -> RotateMode.ROTATE_90
            180 -> RotateMode.ROTATE_180
            270 -> RotateMode.ROTATE_270
            else -> RotateMode.ROTATE_0
        }

        val outWidth: Int
        val outHeight: Int
        val srcBuf: ByteBuffer
        val srcStride: Int

        if (rotateMode == RotateMode.ROTATE_0) {
            outWidth = width
            outHeight = height
            srcBuf = argbBuf
            srcStride = width * 4
        } else {
            outWidth = height
            outHeight = width
            val rotatedSize = outWidth * outHeight * 4
            var rotatedBuf = cachedRotatedArgbBuf
            if (rotatedBuf == null || rotatedBuf.capacity() < rotatedSize) {
                rotatedBuf = ByteBuffer.allocateDirect(rotatedSize).order(ByteOrder.nativeOrder())
                cachedRotatedArgbBuf = rotatedBuf
            }
            rotatedBuf.clear()
            Yuv.rotateARGBRotate(
                argbBuf, RowStride(width * 4), 0,
                rotatedBuf, RowStride(outWidth * 4), 0,
                width, height,
                rotateMode.degrees
            )
            srcBuf = rotatedBuf
            srcStride = outWidth * 4
        }

        var bitmap = cachedBitmap
        if (bitmap == null || bitmap.width != outWidth || bitmap.height != outHeight) {
            bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            cachedBitmap = bitmap
        }
        srcBuf.position(0)
        bitmap.copyPixelsFromBuffer(srcBuf)
        return bitmap
    }

    companion object {
        private const val TAG = "CameraController"
        private var actualExposureNanos = 33_333_333L
    }
}
