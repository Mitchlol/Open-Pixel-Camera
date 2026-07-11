package com.mitchelllustig.openpixelcamera.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.mitchelllustig.openpixelcamera.camera.CameraController
import com.mitchelllustig.openpixelcamera.processing.TrailProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val PREFS_NAME = "open_pixel_camera"
private const val KEY_ISO_POSITION = "iso_position"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_TRAIL_LENGTH = "trail_length"

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var isoPosition by remember { mutableFloatStateOf(prefs.getFloat(KEY_ISO_POSITION, 25f)) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat(KEY_THRESHOLD, 50f)) }
    var trailLength by remember { mutableIntStateOf(prefs.getInt(KEY_TRAIL_LENGTH, 3)) }
    var hasPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isoRange by remember { mutableStateOf(100f..6400f) }

    val cameraController = remember { CameraController(context) }
    val trailProcessor = remember { TrailProcessor() }

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

    LaunchedEffect(isoPosition) {
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
                    onFrameUpdate = { },
                    onIsoRangeReady = { lower, upper ->
                        isoRange = lower.toFloat()..upper.toFloat()
                        isoPosition = isoToPosition(isoFromPosition(isoPosition))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
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
private fun CameraPreview(
    cameraController: CameraController,
    trailProcessor: TrailProcessor,
    onFrameUpdate: (Bitmap) -> Unit,
    onIsoRangeReady: (lower: Int, upper: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var latestFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isoRangeReported by remember { mutableStateOf(false) }

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        cameraController.onFrameAvailable = { frame ->
                            if (!isoRangeReported) {
                                isoRangeReported = true
                                val range = cameraController.isoRange
                                onIsoRangeReady(range.lower, range.upper)
                            }
                            val processed = trailProcessor.processFrame(frame)
                            latestFrame = processed
                            onFrameUpdate(processed)

                            holder.lockCanvas()?.let { canvas ->
                                canvas.drawColor(Color.Black.hashCode())
                                val canvasW = canvas.width.toFloat()
                                val canvasH = canvas.height.toFloat()
                                val bitmapW = processed.width.toFloat()
                                val bitmapH = processed.height.toFloat()

                                val isRotated = cameraController.sensorOrientation == 90 || cameraController.sensorOrientation == 270
                                val rotW = if (isRotated) bitmapH else bitmapW
                                val rotH = if (isRotated) bitmapW else bitmapH

                                val matrix = android.graphics.Matrix()
                                matrix.setTranslate(-bitmapW / 2f, -bitmapH / 2f)
                                matrix.postRotate(cameraController.sensorOrientation.toFloat())
                                val scale = maxOf(canvasW / rotW, canvasH / rotH)
                                matrix.postScale(scale, scale)
                                matrix.postTranslate(canvasW / 2f, canvasH / 2f)

                                canvas.drawBitmap(processed, matrix, null)
                                holder.unlockCanvasAndPost(canvas)
                            }
                        }
                        cameraController.openCamera()
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        cameraController.close()
                    }
                })
            }
        },
        modifier = modifier
    )
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
                label = "ISO",
                value = iso,
                onValueChange = onIsoChange,
                valueRange = isoRange,
                displayValue = isoDisplay.toString(),
                steps = 0
            )

            ControlSlider(
                label = "Threshold",
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0f..75f,
                steps = 0
            )

            ControlSlider(
                label = "Trail Length",
                value = trailLength.toFloat(),
                onValueChange = { onTrailLengthChange(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = displayValue
                    ?: if (label == "Trail Length" || label == "ISO" || label == "Threshold") value.toInt().toString()
                    else String.format("%.1f", value),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
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
