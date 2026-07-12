package com.mitchelllustig.openpixelcamera.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AwtCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mitchelllustig.openpixelcamera.camera.CameraController
import com.mitchelllustig.openpixelcamera.processing.TrailProcessor
import com.mitchelllustig.openpixelcamera.recording.VideoRecorder

private const val PREFS_NAME = "open_pixel_camera"
private const val KEY_ISO_POSITION = "iso_position"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_TRAIL_LENGTH = "trail_length"

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var isoPosition by remember { mutableFloatStateOf(prefs.getFloat(KEY_ISO_POSITION, 25f)) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat(KEY_THRESHOLD, 50f)) }
    var trailLength by remember { mutableIntStateOf(prefs.getInt(KEY_TRAIL_LENGTH, 3)) }
    var hasPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isoRange by remember { mutableStateOf(100f..6400f) }

    val cameraController = remember { CameraController(context) }
    val trailProcessor = remember { TrailProcessor() }
    val videoRecorder = remember { VideoRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000L)
                recordingSeconds++
            }
        }
    }

    fun isoFromPosition(position: Float): Int {
        val minIso = isoRange.start.toDouble()
        val maxIso = isoRange.endInclusive.toDouble()
        return (minIso * Math.pow(maxIso / minIso, position / 100.0)).toInt()
    }

    fun isoToPosition(isoValue: Int): Float {
        val minIso = isoRange.start.toDouble()
        val maxIso = isoRange.endInclusive.toDouble()
        return (100.0 * Math.log(isoValue.toDouble() / minIso) / Math.log(maxIso / minIso)).toFloat()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController.close()
            trailProcessor.clear()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraActive by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (videoRecorder.isRecording) {
                        videoRecorder.stop()
                        isRecording = false
                    }
                    cameraActive = false
                    cameraController.close()
                }
                Lifecycle.Event.ON_RESUME -> {
                    cameraActive = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            error = "Camera permission required"
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(isoPosition, cameraActive) {
        if (!cameraActive) return@LaunchedEffect
        cameraController.updateIso(isoFromPosition(isoPosition))
        prefs.edit().putFloat(KEY_ISO_POSITION, isoPosition).apply()
    }

    LaunchedEffect(threshold) {
        trailProcessor.threshold = threshold / 100f
        prefs.edit().putFloat(KEY_THRESHOLD, threshold).apply()
    }

    LaunchedEffect(trailLength) {
        trailProcessor.trailLength = trailLength
        prefs.edit().putInt(KEY_TRAIL_LENGTH, trailLength).apply()
    }

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        if (hasPermission) {
            Column(modifier = Modifier.fillMaxSize()) {
                CameraPreview(
                    cameraController = cameraController,
                    trailProcessor = trailProcessor,
                    cameraActive = cameraActive,
                    videoRecorder = videoRecorder,
                    onFrameUpdate = { },
                    onIsoRangeReady = { lower, upper ->
                        isoRange = lower.toFloat()..upper.toFloat()
                        isoPosition = isoToPosition(isoFromPosition(isoPosition))
                        cameraController.updateIso(isoFromPosition(isoPosition))
                    },
                    initialIso = isoFromPosition(isoPosition),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Controls(
                    iso = isoPosition,
                    isoDisplay = isoFromPosition(isoPosition),
                    onIsoChange = { isoPosition = it },
                    isoRange = 0f..100f,
                    threshold = threshold,
                    onThresholdChange = { threshold = it },
                    trailLength = trailLength,
                    onTrailLengthChange = { trailLength = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) {
                            val mins = recordingSeconds / 60
                            val secs = recordingSeconds % 60
                            Text(
                                text = String.format("%02d:%02d", mins, secs),
                                color = Color.Red,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    RecordButton(
                        isRecording = isRecording,
                        onClick = {
                            if (isRecording) {
                                videoRecorder.stop()
                                isRecording = false
                            } else {
                                val isRotated = cameraController.sensorOrientation == 90 || cameraController.sensorOrientation == 270
                                val w = if (isRotated) 360 else 640
                                val h = if (isRotated) 640 else 360
                                if (videoRecorder.start(w, h)) {
                                    isRecording = true
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        error?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonSize = 64.dp
    val ringStroke = 3.dp
    val ringRadius = 28.dp
    val innerRadius = 22.dp
    val stopSquareSize = 22.dp

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)

            if (!isRecording) {
                drawCircle(
                    color = Color.Red,
                    radius = innerRadius.toPx(),
                    center = center
                )
            } else {
                val half = stopSquareSize.toPx() / 2f
                drawRoundRect(
                    color = Color.Red,
                    topLeft = Offset(cx - half, cy - half),
                    size = Size(stopSquareSize.toPx(), stopSquareSize.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }

            drawCircle(
                color = Color.White,
                radius = ringRadius.toPx(),
                center = center,
                style = Stroke(width = ringStroke.toPx())
            )
        }
    }
}

@Composable
private fun CameraPreview(
    cameraController: CameraController,
    trailProcessor: TrailProcessor,
    cameraActive: Boolean,
    videoRecorder: VideoRecorder,
    onFrameUpdate: (Bitmap) -> Unit,
    onIsoRangeReady: (lower: Int, upper: Int) -> Unit,
    initialIso: Int = 200,
    modifier: Modifier = Modifier
) {
    var isoRangeReported by remember { mutableStateOf(false) }
    var surfaceHolder by remember { mutableStateOf<SurfaceHolder?>(null) }

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    setZOrderMediaOverlay(true)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceHolder = holder
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaceHolder = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    LaunchedEffect(cameraActive, surfaceHolder) {
        if (!cameraActive || surfaceHolder == null) return@LaunchedEffect
        cameraController.onFrameAvailable = { frame ->
            if (!isoRangeReported) {
                isoRangeReported = true
                val range = cameraController.isoRange
                onIsoRangeReady(range.lower, range.upper)
            }
            val processed = trailProcessor.processFrame(frame, cameraController.sensorOrientation)

            val holder = surfaceHolder
            if (holder != null) {
                val canvas: AwtCanvas? = try { holder.lockCanvas() } catch (_: Exception) { null }
                if (canvas != null) {
                    try {
                        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                        val canvasW = canvas.width.toFloat()
                        val canvasH = canvas.height.toFloat()
                        val bitmapW = processed.width.toFloat()
                        val bitmapH = processed.height.toFloat()

                        val scaleX = canvasW / bitmapW
                        val scaleY = canvasH / bitmapH
                        val scale = minOf(scaleX, scaleY)
                        val scaledW = bitmapW * scale
                        val scaledH = bitmapH * scale
                        val offsetX = (canvasW - scaledW) / 2f
                        val offsetY = (canvasH - scaledH) / 2f

                        val src = android.graphics.Rect(0, 0, processed.width, processed.height)
                        val dst = android.graphics.RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)
                        canvas.drawBitmap(processed, src, dst, null)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }

            onFrameUpdate(processed)

            if (videoRecorder.isRecording) {
                videoRecorder.drawFrame(processed)
            }
        }
        cameraController.openCamera(iso = initialIso)
    }
}

@Composable
private fun Controls(
    iso: Float,
    isoDisplay: Int,
    onIsoChange: (Float) -> Unit,
    isoRange: ClosedFloatingPointRange<Float>,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    trailLength: Int,
    onTrailLengthChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ControlSlider(
                label = "Camera Brightness (ISO)",
                value = iso,
                onValueChange = onIsoChange,
                valueRange = isoRange,
                displayValue = isoDisplay.toString(),
                steps = 0
            )

            ControlSlider(
                label = "Trail Sensitivity",
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0f..75f,
                steps = 0
            )

            ControlSlider(
                label = "Trail Length (Frames)",
                value = trailLength.toFloat(),
                onValueChange = { onTrailLengthChange(it.toInt()) },
                valueRange = 1f..20f,
                steps = 18
            )
        }
    }
}

@Composable
private fun ControlSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String? = null,
    steps: Int = 0
) {
    Column {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White
            )
        )
    }
}
