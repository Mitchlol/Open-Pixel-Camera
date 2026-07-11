package com.mitchelllustig.openpixelcamera.ui

import android.Manifest
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mitchelllustig.openpixelcamera.camera.CameraController
import com.mitchelllustig.openpixelcamera.processing.TrailProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var iso by remember { mutableFloatStateOf(200f) }
    var threshold by remember { mutableFloatStateOf(0.5f) }
    var trailLength by remember { mutableIntStateOf(3) }
    var hasPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isoRange by remember { mutableStateOf(100f..1600f) }

    val cameraController = remember { CameraController(context) }
    val trailProcessor = remember { TrailProcessor() }

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

    LaunchedEffect(iso) {
        cameraController.updateIso(iso.toInt())
    }

    LaunchedEffect(threshold) {
        trailProcessor.threshold = threshold
    }

    LaunchedEffect(trailLength) {
        trailProcessor.trailLength = trailLength
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission) {
            CameraPreview(
                cameraController = cameraController,
                trailProcessor = trailProcessor,
                onFrameUpdate = { },
                onIsoRangeReady = { lower, upper ->
                    isoRange = lower.toFloat()..upper.toFloat()
                    if (iso < lower || iso > upper) {
                        iso = iso.coerceIn(lower.toFloat(), upper.toFloat())
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Controls(
            iso = iso,
            onIsoChange = { iso = it },
            isoRange = isoRange,
            threshold = threshold,
            onThresholdChange = { threshold = it },
            trailLength = trailLength,
            onTrailLengthChange = { trailLength = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

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
                            latestFrame?.recycle()
                            latestFrame = processed
                            onFrameUpdate(processed)

                            holder.lockCanvas()?.let { canvas ->
                                canvas.drawColor(Color.Black.hashCode())
                                val canvasW = canvas.width.toFloat()
                                val canvasH = canvas.height.toFloat()
                                val bitmapW = processed.width.toFloat()
                                val bitmapH = processed.height.toFloat()

                                val matrix = android.graphics.Matrix()
                                matrix.postRotate(cameraController.sensorOrientation.toFloat(), bitmapW / 2f, bitmapH / 2f)
                                val scale = maxOf(canvasW / bitmapH, canvasH / bitmapW)
                                matrix.postScale(scale, scale, bitmapW / 2f, bitmapH / 2f)
                                matrix.postTranslate(
                                    (canvasW - bitmapH * scale) / 2f,
                                    (canvasH - bitmapW * scale) / 2f
                                )

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
                steps = 0
            )

            ControlSlider(
                label = "Threshold",
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0f..1f,
                steps = 19
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
                text = if (label == "Trail Length") value.toInt().toString()
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
