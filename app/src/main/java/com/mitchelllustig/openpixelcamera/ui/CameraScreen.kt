package com.mitchelllustig.openpixelcamera.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mitchelllustig.openpixelcamera.R
import com.mitchelllustig.openpixelcamera.camera.CameraController
import com.mitchelllustig.openpixelcamera.processing.TrailProcessor
import com.mitchelllustig.openpixelcamera.recording.VideoRecorder
import android.graphics.Canvas as AwtCanvas
import kotlin.math.pow

private const val PREFS_NAME = "open_pixel_camera"
private const val KEY_ISO_POSITION = "iso_position"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_TRAIL_LENGTH = "trail_length"
private const val KEY_FPS = "fps"
private const val KEY_RESOLUTION_INDEX = "resolution_index"
private const val KEY_AUDIO_ENABLED = "audio_enabled"
private const val KEY_FADE_PERCENT = "fade_percent"
private const val KEY_HIDE_BANNER = "hide_banner"

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var isoPosition by remember { mutableFloatStateOf(prefs.getFloat(KEY_ISO_POSITION, 25f)) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat(KEY_THRESHOLD, 25f)) }
    var trailLength by remember { mutableIntStateOf(prefs.getInt(KEY_TRAIL_LENGTH, 8)) }
    var hasPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isoRange by remember { mutableStateOf(100f..6400f) }
    var fpsOptions by remember { mutableStateOf<List<Int>>(emptyList()) }
    var fpsSelectedIndex by remember { mutableIntStateOf(0) }
    var resolutionOptions by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var resolutionSelectedIndex by remember { mutableIntStateOf(0) }
    var currentResolution by remember { mutableStateOf(Pair(640, 360)) }
    var isoRangeReported by remember { mutableStateOf(false) }
    var resolutionInitialized by remember { mutableStateOf(false) }
    var cameraRestartNonce by remember { mutableIntStateOf(0) }
    var audioEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_AUDIO_ENABLED, true)) }
    var fadePercent by remember { mutableFloatStateOf(prefs.getFloat(KEY_FADE_PERCENT, 25f)) }
    var hideBanner by remember { mutableStateOf(prefs.getBoolean(KEY_HIDE_BANNER, false)) }

    val cameraController = remember {
        CameraController(context).apply {
            onActualResolutionChanged = { w, h -> currentResolution = Pair(w, h) }
        }
    }
    val trailProcessor = remember { TrailProcessor() }
    val videoRecorder = remember { VideoRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    val annotatedText = remember() {
        buildAnnotatedString {
            append("Sponsored by ")
            withStyle(
                style = SpanStyle(
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append("OpenPixelPoi")
            }
        }
    }

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
        return (minIso * (maxIso / minIso).pow(position.toDouble() / 100.0)).toInt()
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
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.CAMERA] ?: false
        if (!hasPermission) {
            error = "Camera permission required"
        }
        
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!audioGranted) {
            audioEnabled = false
        }
    }

    fun startRecording() {
        val isRotated = cameraController.sensorOrientation == 90 || cameraController.sensorOrientation == 270
        val w = if (isRotated) currentResolution.second else currentResolution.first
        val h = if (isRotated) currentResolution.first else currentResolution.second
        val currentFps = if (fpsOptions.isNotEmpty()) fpsOptions[fpsSelectedIndex] else 30
        if (videoRecorder.start(w, h, currentFps, audioEnabled)) {
            isRecording = true
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
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

    LaunchedEffect(audioEnabled) {
        prefs.edit().putBoolean(KEY_AUDIO_ENABLED, audioEnabled).apply()
    }

    LaunchedEffect(hideBanner) {
        prefs.edit().putBoolean(KEY_HIDE_BANNER, hideBanner).apply()
    }

    LaunchedEffect(fadePercent) {
        trailProcessor.fadePercent = fadePercent / 100f
        prefs.edit().putFloat(KEY_FADE_PERCENT, fadePercent).apply()
    }

    LaunchedEffect(fpsSelectedIndex) {
        if (fpsOptions.isEmpty()) return@LaunchedEffect
        val fps = fpsOptions[fpsSelectedIndex]
        cameraController.updateFps(fps)
        prefs.edit().putInt(KEY_FPS, fps).apply()
    }

    LaunchedEffect(resolutionSelectedIndex) {
        if (resolutionOptions.isEmpty()) return@LaunchedEffect
        val (w, h) = resolutionOptions[resolutionSelectedIndex]
        prefs.edit().putInt(KEY_RESOLUTION_INDEX, resolutionSelectedIndex).apply()
        if (resolutionInitialized) {
            currentResolution = Pair(w, h)
            cameraController.close()
            isoRangeReported = false
            cameraRestartNonce++
        } else {
            currentResolution = Pair(w, h)
            resolutionInitialized = true
            if (w != 640 || h != 360) {
                cameraController.close()
                isoRangeReported = false
                cameraRestartNonce++
            }
        }
    }

    var showCameraPanel by remember { mutableStateOf(false) }
    var showOutputPanel by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Color.Black)
    ) {
        if (hasPermission) {
            val isRotated = cameraController.sensorOrientation == 90 || cameraController.sensorOrientation == 270
            val previewW = if (isRotated) currentResolution.second else currentResolution.first
            val previewH = if (isRotated) currentResolution.first else currentResolution.second

            CameraPreview(
                cameraController = cameraController,
                trailProcessor = trailProcessor,
                cameraActive = cameraActive,
                videoRecorder = videoRecorder,
                captureWidth = currentResolution.first,
                captureHeight = currentResolution.second,
                isoRangeReported = isoRangeReported,
                onIsoRangeReported = { isoRangeReported = true },
                cameraRestartNonce = cameraRestartNonce,
                onFrameUpdate = { },
                onIsoRangeReady = { lower, upper ->
                        isoRange = lower.toFloat()..upper.toFloat()
                        isoPosition = isoToPosition(isoFromPosition(isoPosition))
                        cameraController.updateIso(isoFromPosition(isoPosition))

                        val available = cameraController.availableFpsOptions
                        if (available.isNotEmpty()) {
                            fpsOptions = available
                            val savedFps = prefs.getInt(KEY_FPS, available.last())
                            val savedIndex = available.indexOf(savedFps).coerceAtLeast(0)
                            fpsSelectedIndex = savedIndex
                            cameraController.updateFps(available[savedIndex])
                        }

                        val resolutions = cameraController.availableResolutions
                            .map { Pair(it.width, it.height) }
                        if (resolutions.isNotEmpty()) {
                            resolutionOptions = resolutions
                            val savedResIndex = if (prefs.contains(KEY_RESOLUTION_INDEX)) {
                                prefs.getInt(KEY_RESOLUTION_INDEX, 0).coerceIn(0, resolutions.size - 1)
                            } else {
                                resolutions.indices.minByOrNull { i ->
                                    val (w, h) = resolutions[i]
                                    val dw = w - 640
                                    val dh = h - 480
                                    dw * dw + dh * dh
                                } ?: 0
                            }
                            resolutionSelectedIndex = savedResIndex
                            currentResolution = resolutions[savedResIndex]
                        }
                    },
                initialIso = isoFromPosition(isoPosition),
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(previewW.toFloat() / previewH.toFloat())
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                if(!hideBanner){
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(16.dp, 8.dp)
                            .clickable {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.openpixelpoi.com")
                                    )
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,

                        ) {
                        // Icon on the left
                        Image(
                            painter = painterResource(id = R.drawable.opp_logo),
                            contentDescription = null, // Set to null if it's purely decorative
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = {
                                        hideBanner = true
                                    }
                                )
                            }
                        )

                        // Space between icon and text
                        Spacer(modifier = Modifier.width(16.dp))

                        // Text on the right
                        Text(
                            text = annotatedText,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                }


                if (showCameraPanel) {
                    SettingsPanel(
                        title = "Light Trail Settings",
                        enabled = !isRecording,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onClose = { showCameraPanel = false }
                    ) {
                        ControlSlider(
                            label = "Trail Sensitivity",
                            value = threshold,
                            onValueChange = { threshold = it },
                            valueRange = 0f..75f,
                            steps = 0
                        )
                        ControlSlider(
                            label = "Trail Length (Frames)",
                            value = trailLength.toFloat(),
                            onValueChange = { trailLength = it.toInt() },
                            valueRange = 1f..20f,
                            steps = 18
                        )
                        ControlSlider(
                            label = "Fade",
                            value = fadePercent,
                            onValueChange = { fadePercent = it },
                            valueRange = 0f..100f,
                            steps = 0
                        )
                    }
                }

                if (showOutputPanel) {
                    SettingsPanel(
                        title = "Camera Settings",
                        enabled = !isRecording,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onClose = { showOutputPanel = false }
                    ) {
                        Text(
                            text = "Higher frame rates and resolutions may cause dropped frames on some devices. If the preview stutters or trails look choppy, try lowering these settings.",
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        ControlSlider(
                            label = "Camera Brightness (ISO)",
                            value = isoPosition,
                            onValueChange = { isoPosition = it },
                            valueRange = 0f..100f,
                            displayValue = null,
                            steps = 0
                        )
                        if (fpsOptions.size >= 2) {
                            var dragIndex by remember { mutableFloatStateOf(fpsSelectedIndex.toFloat()) }
                            LaunchedEffect(fpsSelectedIndex) { dragIndex = fpsSelectedIndex.toFloat() }
                            ControlSlider(
                                label = "Frame Rate (FPS)",
                                value = dragIndex,
                                onValueChange = { dragIndex = Math.round(it).toFloat().coerceIn(0f, (fpsOptions.size - 1).toFloat()) },
                                valueRange = 0f..(fpsOptions.size - 1).toFloat(),
                                steps = fpsOptions.size - 2,
                                displayValue = "${fpsOptions[dragIndex.toInt()]}",
                                enabled = !isRecording,
                                onValueChangeFinished = { fpsSelectedIndex = dragIndex.toInt() }
                            )
                        }
                        if (resolutionOptions.size >= 2) {
                            var dragIndex by remember { mutableFloatStateOf(resolutionSelectedIndex.toFloat()) }
                            LaunchedEffect(resolutionSelectedIndex) { dragIndex = resolutionSelectedIndex.toFloat() }
                            val (w, h) = resolutionOptions[dragIndex.toInt().coerceIn(0, resolutionOptions.size - 1)]
                            ControlSlider(
                                label = "Resolution",
                                value = dragIndex,
                                onValueChange = { dragIndex = Math.round(it).toFloat().coerceIn(0f, (resolutionOptions.size - 1).toFloat()) },
                                valueRange = 0f..(resolutionOptions.size - 1).toFloat(),
                                steps = resolutionOptions.size - 2,
                                displayValue = "${w}×${h}",
                                enabled = !isRecording,
                                onValueChangeFinished = { resolutionSelectedIndex = dragIndex.toInt() }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { audioEnabled = !audioEnabled },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = audioEnabled,
                                onCheckedChange = { audioEnabled = it },
                                enabled = !isRecording,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color.White,
                                    uncheckedColor = Color.White.copy(alpha = 0.6f),
                                    checkmarkColor = Color.Black
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Record Audio",
                                color = Color.White.copy(alpha = if (isRecording) 0.5f else 0.9f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .height(64.dp),
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
                                startRecording()
                            }
                        }
                    )

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelToggleButton(
                                isActive = showCameraPanel,
                                onClick = { showCameraPanel = !showCameraPanel; if (showCameraPanel) showOutputPanel = false },
                                iconType = PanelIconType.CAMERA
                            )
                            PanelToggleButton(
                                isActive = showOutputPanel,
                                onClick = { showOutputPanel = !showOutputPanel; if (showOutputPanel) showCameraPanel = false },
                                iconType = PanelIconType.OUTPUT
                            )
                        }
                    }
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
    captureWidth: Int,
    captureHeight: Int,
    isoRangeReported: Boolean,
    onIsoRangeReported: () -> Unit,
    cameraRestartNonce: Int,
    onFrameUpdate: (Bitmap) -> Unit,
    onIsoRangeReady: (lower: Int, upper: Int) -> Unit,
    initialIso: Int = 200,
    modifier: Modifier = Modifier
) {
    var surfaceHolder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var lastBufW by remember { mutableIntStateOf(0) }
    var lastBufH by remember { mutableIntStateOf(0) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    setZOrderMediaOverlay(true)
                    val isRotated = cameraController.sensorOrientation == 90 || cameraController.sensorOrientation == 270
                    val bufW = if (isRotated) captureHeight else captureWidth
                    val bufH = if (isRotated) captureWidth else captureHeight
                    holder.setFixedSize(bufW, bufH)
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
            update = { view ->
                val isRotated = cameraController.sensorOrientation == 90 || cameraController.sensorOrientation == 270
                val bufW = if (isRotated) captureHeight else captureWidth
                val bufH = if (isRotated) captureWidth else captureHeight
                if (bufW != lastBufW || bufH != lastBufH) {
                    lastBufW = bufW
                    lastBufH = bufH
                    view.holder.setFixedSize(bufW, bufH)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    LaunchedEffect(cameraActive, surfaceHolder, cameraRestartNonce) {
        if (!cameraActive || surfaceHolder == null) return@LaunchedEffect
        cameraController.onFrameAvailable = { frame ->
            if (!isoRangeReported) {
                onIsoRangeReported()
                val range = cameraController.isoRange
                onIsoRangeReady(range.lower, range.upper)
            }
            val processed = trailProcessor.processFrame(frame)

            val holder = surfaceHolder
            if (holder != null) {
                val canvas: AwtCanvas? = try { holder.lockCanvas() } catch (_: Exception) { null }
                if (canvas != null) {
                    try {
                        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        canvas.drawBitmap(processed, 0f, 0f, null)
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
        cameraController.openCamera(width = captureWidth, height = captureHeight, iso = initialIso)
    }
}

private enum class PanelIconType { CAMERA, OUTPUT }

@Composable
private fun PanelToggleButton(
    isActive: Boolean,
    onClick: () -> Unit,
    iconType: PanelIconType,
    modifier: Modifier = Modifier
) {
    val iconRes = when (iconType) {
        PanelIconType.CAMERA -> R.drawable.ic_gesture
        PanelIconType.OUTPUT -> R.drawable.ic_settings
    }
    val bgColor = if (isActive) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.65f)
    val borderColor = Color.White.copy(alpha = if (isActive) 0.6f else 0.35f)
    Box(
        modifier = modifier
            .size(36.dp)
            .border(1.dp, borderColor, CircleShape)
            .background(bgColor, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = if (isActive) 1f else 0.8f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsPanel(
    title: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(Color.White, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
            content()
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
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
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
            if (displayValue != null) {
                Text(
                    text = displayValue,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White
            )
        )
    }
}
