package com.mitchelllustig.openpixelcamera.camera

import android.annotation.SuppressLint
import android.content.Context
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
    @Volatile private var pendingResult: PendingResult? = null
    @Volatile private var processingIdle = true
    @Volatile private var opening = false
    @Volatile private var openGeneration = 0

    private var cachedArgbBuf: ByteBuffer? = null
    private var cachedYBuf: ByteBuffer? = null
    private var cachedUvBuf: ByteBuffer? = null
    private var cachedRotatedYBuf: ByteBuffer? = null
    private var cachedRotatedUvBuf: ByteBuffer? = null
    private var cachedRotateMode = RotateMode.ROTATE_0

    var onFrameAvailable: ((ByteBuffer, Int, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onActualResolutionChanged: ((Int, Int) -> Unit)? = null
    var sensorOrientation: Int = 0
        private set

    private var targetFpsRange: Range<Int> = Range(30, 30)
    private var userFps: Int = 30
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

        cachedRotateMode = when (sensorOrientation) {
            90 -> RotateMode.ROTATE_90
            180 -> RotateMode.ROTATE_180
            270 -> RotateMode.ROTATE_270
            else -> RotateMode.ROTATE_0
        }

        queryCameraCapabilities(cameraId)

        imageReader = ImageReader.newInstance(
            width, height,
            ImageFormat.YUV_420_888, 3
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val frameStart = System.nanoTime()
                val deltaMs = if (lastFrameTimeNanos > 0) (frameStart - lastFrameTimeNanos) / 1_000_000.0 else 0.0
                lastFrameTimeNanos = frameStart
                frameCount++

                val w = image.width
                val h = image.height

                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]

                val yRowStride = yPlane.rowStride
                val uvRowStride = uPlane.rowStride
                val uvPixelStride = uPlane.pixelStride
                val isNv21 = vPlane.buffer.position() == 0

                val t0 = System.nanoTime()

                val yBufSize = yRowStride * h
                var yBuf = cachedYBuf
                if (yBuf == null || yBuf.capacity() < yBufSize + SIMD_PAD) {
                    yBuf = ByteBuffer.allocateDirect(yBufSize + SIMD_PAD).order(ByteOrder.nativeOrder())
                    cachedYBuf = yBuf
                }
                yBuf.clear()
                yPlane.buffer.position(0)
                val yCopy = minOf(yBufSize, yPlane.buffer.capacity())
                yPlane.buffer.limit(yCopy)
                yBuf.put(yPlane.buffer)
                yBuf.flip()

                val uvBufSize = uvRowStride * (h / 2)
                var uvBuf = cachedUvBuf
                if (uvBuf == null || uvBuf.capacity() < uvBufSize + SIMD_PAD) {
                    uvBuf = ByteBuffer.allocateDirect(uvBufSize + SIMD_PAD).order(ByteOrder.nativeOrder())
                    cachedUvBuf = uvBuf
                }

                var vBuf: ByteBuffer? = null

                if (uvPixelStride == 1) {
                    uvBuf.clear()
                    uPlane.buffer.position(0)
                    val uCopy = minOf(uvBufSize, uPlane.buffer.capacity())
                    uPlane.buffer.limit(uCopy)
                    uvBuf.put(uPlane.buffer)
                    uvBuf.flip()

                    vPlane.buffer.position(0)
                    val vCopy = minOf(uvBufSize, vPlane.buffer.capacity())
                    vPlane.buffer.limit(vCopy)
                    val vb = ByteBuffer.allocateDirect(vCopy + SIMD_PAD).order(ByteOrder.nativeOrder())
                    vb.put(vPlane.buffer)
                    vb.flip()
                    vBuf = vb
                } else {
                    if (isNv21) {
                        uvBuf.clear()
                        uPlane.buffer.position(0)
                        val uvCopy = minOf(uvBufSize, uPlane.buffer.capacity())
                        uPlane.buffer.limit(uvCopy)
                        uvBuf.put(vPlane.buffer)
                        uvBuf.flip()
                    } else {
                        uvBuf.clear()
                        vPlane.buffer.position(0)
                        val uvCopy = minOf(uvBufSize, vPlane.buffer.capacity())
                        vPlane.buffer.limit(uvCopy)
                        uvBuf.put(uPlane.buffer)
                        uvBuf.flip()
                    }
                }

                val t1 = System.nanoTime()

                val outW: Int
                val outH: Int

                if (cachedRotateMode == RotateMode.ROTATE_0) {
                    outW = w
                    outH = h

                    val argbSize = w * h * 4
                    var argbBuf = cachedArgbBuf
                    if (argbBuf == null || argbBuf.capacity() < argbSize) {
                        argbBuf = ByteBuffer.allocateDirect(argbSize).order(ByteOrder.nativeOrder())
                        cachedArgbBuf = argbBuf
                    }
                    argbBuf.clear()

                    if (uvPixelStride == 1) {
                        Yuv.convertI420ToARGB(
                            yBuf, RowStride(yRowStride), 0,
                            uvBuf, RowStride(uvRowStride), 0,
                            vBuf!!, RowStride(uvRowStride), 0,
                            argbBuf, RowStride(w * 4), 0,
                            w, h
                        )
                    } else if (isNv21) {
                        Yuv.convertNV21ToARGB(
                            yBuf, RowStride(yRowStride), 0,
                            uvBuf, RowStride(uvRowStride), 0,
                            argbBuf, RowStride(w * 4), 0,
                            w, h
                        )
                    } else {
                        Yuv.convertNV12ToARGB(
                            yBuf, RowStride(yRowStride), 0,
                            uvBuf, RowStride(uvRowStride), 0,
                            argbBuf, RowStride(w * 4), 0,
                            w, h
                        )
                    }
                } else {
                    outW = h
                    outH = w

                    val rotYSize = w * h
                    var rotYBuf = cachedRotatedYBuf
                    if (rotYBuf == null || rotYBuf.capacity() < rotYSize + SIMD_PAD) {
                        rotYBuf = ByteBuffer.allocateDirect(rotYSize + SIMD_PAD).order(ByteOrder.nativeOrder())
                        cachedRotatedYBuf = rotYBuf
                    }
                    rotYBuf.clear()

                    val rotUvSize = (h / 2) * w * 2
                    var rotUvBuf = cachedRotatedUvBuf
                    if (rotUvBuf == null || rotUvBuf.capacity() < rotUvSize + SIMD_PAD) {
                        rotUvBuf = ByteBuffer.allocateDirect(rotUvSize + SIMD_PAD).order(ByteOrder.nativeOrder())
                        cachedRotatedUvBuf = rotUvBuf
                    }
                    rotUvBuf.clear()

                    val rotDstYStride = RowStride(h)
                    val rotDstUvStride = RowStride(h)

                    if (uvPixelStride == 1) {
                        val rotVBuf = ByteBuffer.allocateDirect(rotUvSize + SIMD_PAD).order(ByteOrder.nativeOrder())
                        val rotI420UStride = RowStride(h / 2)
                        Yuv.rotateI420Rotate(
                            yBuf, RowStride(yRowStride), 0,
                            uvBuf, RowStride(uvRowStride), 0,
                            vBuf!!, RowStride(uvRowStride), 0,
                            rotYBuf, rotDstYStride, 0,
                            rotUvBuf, rotI420UStride, 0,
                            rotVBuf, rotI420UStride, 0,
                            w, h,
                            cachedRotateMode.degrees
                        )
                        val argbSize = outW * outH * 4
                        var argbBuf = cachedArgbBuf
                        if (argbBuf == null || argbBuf.capacity() < argbSize) {
                            argbBuf = ByteBuffer.allocateDirect(argbSize).order(ByteOrder.nativeOrder())
                            cachedArgbBuf = argbBuf
                        }
                        argbBuf.clear()
                        Yuv.convertI420ToARGB(
                            rotYBuf, rotDstYStride, 0,
                            rotUvBuf, rotI420UStride, 0,
                            rotVBuf, rotI420UStride, 0,
                            argbBuf, RowStride(outW * 4), 0,
                            outW, outH
                        )
                    } else if (isNv21) {
                        Yuv.rotateNV21Rotate(
                            yBuf, RowStride(yRowStride), 0,
                            uvBuf, RowStride(uvRowStride), 0,
                            rotYBuf, rotDstYStride, 0,
                            rotUvBuf, rotDstUvStride, 0,
                            w, h,
                            cachedRotateMode.degrees
                        )
                        val argbSize = outW * outH * 4
                        var argbBuf = cachedArgbBuf
                        if (argbBuf == null || argbBuf.capacity() < argbSize) {
                            argbBuf = ByteBuffer.allocateDirect(argbSize).order(ByteOrder.nativeOrder())
                            cachedArgbBuf = argbBuf
                        }
                        argbBuf.clear()
                        Yuv.convertNV21ToARGB(
                            rotYBuf, rotDstYStride, 0,
                            rotUvBuf, rotDstUvStride, 0,
                            argbBuf, RowStride(outW * 4), 0,
                            outW, outH
                        )
                    } else {
                        Yuv.rotateNV12Rotate(
                            yBuf, RowStride(yRowStride), 0,
                            uvBuf, RowStride(uvRowStride), 0,
                            rotYBuf, rotDstYStride, 0,
                            rotUvBuf, rotDstUvStride, 0,
                            w, h,
                            cachedRotateMode.degrees
                        )
                        val argbSize = outW * outH * 4
                        var argbBuf = cachedArgbBuf
                        if (argbBuf == null || argbBuf.capacity() < argbSize) {
                            argbBuf = ByteBuffer.allocateDirect(argbSize).order(ByteOrder.nativeOrder())
                            cachedArgbBuf = argbBuf
                        }
                        argbBuf.clear()
                        Yuv.convertNV12ToARGB(
                            rotYBuf, rotDstYStride, 0,
                            rotUvBuf, rotDstUvStride, 0,
                            argbBuf, RowStride(outW * 4), 0,
                            outW, outH
                        )
                    }
                }

                image.close()

                val t2 = System.nanoTime()

                val srcBuf = cachedArgbBuf!!

                srcBuf.position(0)
                srcBuf.limit(outW * outH * 4)
                pendingResult = PendingResult(srcBuf, outW, outH)

                if (processingIdle) {
                    processingIdle = false
                    processingHandler?.post { drainPendingResult() }
                }

                val copyMs = (t1 - t0) / 1_000_000.0
                val convertMs = (t2 - t1) / 1_000_000.0
                val totalMs = (t2 - frameStart) / 1_000_000.0
                if (frameCount % 30 == 0) {
                    Log.i(TAG, "Frame #$frameCount | ${w}x${h} | delta=${"%.1f".format(deltaMs)}ms | copy=${"%.1f".format(copyMs)}ms convert+rotate=${"%.1f".format(convertMs)}ms total=${"%.1f".format(totalMs)}ms")
                }
                if (totalMs > 35.0) {
                    Log.w(TAG, "SLOW FRAME #$frameCount | copy=${"%.1f".format(copyMs)}ms convert+rotate=${"%.1f".format(convertMs)}ms total=${"%.1f".format(totalMs)}ms")
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

    private fun drainPendingResult() {
        val result = pendingResult ?: run {
            processingIdle = true
            if (pendingResult != null && processingIdle) {
                processingIdle = false
                processingHandler?.post { drainPendingResult() }
            }
            return
        }
        pendingResult = null

        result.srcBuf.position(0)
        onFrameAvailable?.invoke(result.srcBuf, result.outWidth, result.outHeight)

        if (pendingResult != null) {
            processingHandler?.post { drainPendingResult() }
        } else {
            processingIdle = true
            if (pendingResult != null && processingIdle) {
                processingIdle = false
                processingHandler?.post { drainPendingResult() }
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

        targetFpsRange = if (availableFpsOptions.contains(userFps)) Range(userFps, userFps)
            else if (availableFpsOptions.contains(30)) Range(30, 30)
            else availableFpsOptions.lastOrNull()?.let { Range(it, it) }
            ?: fpsRanges?.lastOrNull { it.lower == 30 && it.upper == 30 }
            ?: fpsRanges?.lastOrNull()
            ?: Range(30, 30)

        Log.i(TAG, "Selected FPS range: $targetFpsRange")

        val initialFps = if (availableFpsOptions.contains(userFps)) userFps
            else if (availableFpsOptions.contains(30)) 30
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
        userFps = fps
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
        pendingResult = null
        processingIdle = true
        processingThread?.quitSafely()
        try { processingThread?.join() } catch (_: InterruptedException) {}
        processingThread = null
        processingHandler = null
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
        imageReader?.close()
        imageReader = null
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

    private data class PendingResult(
        val srcBuf: ByteBuffer,
        val outWidth: Int,
        val outHeight: Int
    )

    companion object {
        private const val SIMD_PAD = 64
        private const val TAG = "CameraController"
        private var actualExposureNanos = 33_333_333L
    }
}
