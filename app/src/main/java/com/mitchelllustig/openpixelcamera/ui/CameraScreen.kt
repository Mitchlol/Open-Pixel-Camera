package com.mitchelllustig.openpixelcamera.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.net.Uri
import android.provider.MediaStore
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.mitchelllustig.openpixelcamera.processing.ColorOverrideMode
import com.mitchelllustig.openpixelcamera.recording.VideoRecorder
import android.graphics.Canvas as AwtCanvas
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.pow

private const val PREFS_NAME = "open_pixel_camera"
private const val KEY_ISO_POSITION = "iso_position"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_TRAIL_LENGTH = "trail_length"
private const val KEY_FPS = "fps"
private const val KEY_RESOLUTION_INDEX = "resolution_index"
private const val KEY_AUDIO_ENABLED = "audio_enabled"
private const val KEY_FADE_PERCENT = "fade_percent"
private const val KEY_COLOR_OVERRIDE_MODE = "color_override_mode"
private const val KEY_SOLID_COLOR_HUE = "solid_color_hue"
private const val KEY_BLUR_AMOUNT = "blur_amount"
private const val KEY_MIRROR_HORIZONTAL = "mirror_horizontal"
private const val KEY_MIRROR_VERTICAL = "mirror_vertical"
private const val KEY_ROTATION_SYMMETRY = "rotation_symmetry"
private const val KEY_SHIMMER = "shimmer"
private const val KEY_HIDE_BANNER = "hide_banner"
private const val KEY_THRESHOLD_PREVIEW = "threshold_preview"

private val rotationSymmetryOptions = listOf(0, 3, 5, 6)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var isoPosition by remember { mutableFloatStateOf(prefs.getFloat(KEY_ISO_POSITION, 25f)) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat(KEY_THRESHOLD, 25f)) }
    var fpsOptions by remember { mutableStateOf<List<Int>>(emptyList()) }
    var fpsSelectedIndex by remember { mutableIntStateOf(0) }
    val currentFps = if (fpsOptions.isNotEmpty()) fpsOptions[fpsSelectedIndex] else 30
    var trailLength by remember { mutableIntStateOf(prefs.getInt(KEY_TRAIL_LENGTH, 8)) }
    var trailLengthPos by remember {
        mutableFloatStateOf(
            run {
                val duration = trailLength.toDouble() / currentFps
                (kotlin.math.sqrt(duration.coerceAtLeast(0.0) / 5.0) * 100.0).toFloat().coerceIn(0f, 100f)
            }
        )
    }
    var hasPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isoRange by remember { mutableStateOf(100f..6400f) }
    var resolutionOptions by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var resolutionSelectedIndex by remember { mutableIntStateOf(0) }
    var currentResolution by remember { mutableStateOf(Pair(640, 360)) }
    var isoRangeReported by remember { mutableStateOf(false) }
    var resolutionInitialized by remember { mutableStateOf(false) }
    var cameraRestartNonce by remember { mutableIntStateOf(0) }
    var audioEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_AUDIO_ENABLED, true)) }
    var fadePercent by remember { mutableFloatStateOf(prefs.getFloat(KEY_FADE_PERCENT, 25f)) }
    var colorOverrideModeIndex by remember { mutableIntStateOf(prefs.getInt(KEY_COLOR_OVERRIDE_MODE, 0)) }
    var solidColorHue by remember { mutableFloatStateOf(prefs.getFloat(KEY_SOLID_COLOR_HUE, 0f)) }
    var blurAmount by remember { mutableFloatStateOf(prefs.getFloat(KEY_BLUR_AMOUNT, 0f)) }
    var mirrorHorizontal by remember { mutableStateOf(prefs.getBoolean(KEY_MIRROR_HORIZONTAL, false)) }
    var mirrorVertical by remember { mutableStateOf(prefs.getBoolean(KEY_MIRROR_VERTICAL, false)) }
    var rotationalSymmetry by remember { mutableIntStateOf(prefs.getInt(KEY_ROTATION_SYMMETRY, 0)) }
    var shimmerEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_SHIMMER, false)) }
    var thresholdPreview by remember { mutableStateOf(prefs.getBoolean(KEY_THRESHOLD_PREVIEW, false)) }
    var settingsTab by remember { mutableIntStateOf(0) }
    var hideBanner by remember { mutableStateOf(prefs.getBoolean(KEY_HIDE_BANNER, false)) }
    var settingsRestored by remember { mutableStateOf(false) }

    val cameraController = remember {
        CameraController(context).apply {
            onActualResolutionChanged = { w, h -> currentResolution = Pair(w, h) }
        }
    }
    val trailProcessor = remember { TrailProcessor(context) }
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

    LaunchedEffect(colorOverrideModeIndex) {
        trailProcessor.colorOverrideMode = ColorOverrideMode.entries[colorOverrideModeIndex]
        prefs.edit().putInt(KEY_COLOR_OVERRIDE_MODE, colorOverrideModeIndex).apply()
    }

    LaunchedEffect(solidColorHue, colorOverrideModeIndex) {
        if (ColorOverrideMode.entries[colorOverrideModeIndex] == ColorOverrideMode.COLOR) {
            trailProcessor.solidColor = hsvToArgb(solidColorHue, 1f, 1f)
        }
        prefs.edit().putFloat(KEY_SOLID_COLOR_HUE, solidColorHue).apply()
    }

    LaunchedEffect(blurAmount) {
        trailProcessor.blurAmount = blurAmount / 100f
        prefs.edit().putFloat(KEY_BLUR_AMOUNT, blurAmount).apply()
    }

    LaunchedEffect(mirrorHorizontal) {
        trailProcessor.mirrorHorizontal = mirrorHorizontal
        prefs.edit().putBoolean(KEY_MIRROR_HORIZONTAL, mirrorHorizontal).apply()
    }

    LaunchedEffect(mirrorVertical) {
        trailProcessor.mirrorVertical = mirrorVertical
        prefs.edit().putBoolean(KEY_MIRROR_VERTICAL, mirrorVertical).apply()
    }

    LaunchedEffect(rotationalSymmetry) {
        trailProcessor.rotationalSymmetry = rotationalSymmetry
        prefs.edit().putInt(KEY_ROTATION_SYMMETRY, rotationalSymmetry).apply()
    }

    LaunchedEffect(shimmerEnabled) {
        trailProcessor.shimmerEnabled = shimmerEnabled
        prefs.edit().putBoolean(KEY_SHIMMER, shimmerEnabled).apply()
    }

    LaunchedEffect(thresholdPreview) {
        trailProcessor.thresholdPreviewEnabled = thresholdPreview
        prefs.edit().putBoolean(KEY_THRESHOLD_PREVIEW, thresholdPreview).apply()
    }

    LaunchedEffect(fpsSelectedIndex) {
        if (fpsOptions.isEmpty()) return@LaunchedEffect
        val fps = fpsOptions[fpsSelectedIndex]
        cameraController.updateFps(fps)
        prefs.edit().putInt(KEY_FPS, fps).apply()
        val t = trailLengthPos / 100.0
                                val duration = t * t * 5.0
                                trailLength = (duration * fps).toInt().coerceAtLeast(0)
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

    var showSettingsPanel by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

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
                thresholdPreview = thresholdPreview,
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

                        val available = cameraController.availableFpsOptions
                        if (available.isNotEmpty()) {
                            fpsOptions = available
                        }

                        val resolutions = cameraController.availableResolutions
                            .map { Pair(it.width, it.height) }
                        if (resolutions.isNotEmpty()) {
                            resolutionOptions = resolutions
                        }

                        if (!settingsRestored) {
                            settingsRestored = true
                            if (available.isNotEmpty()) {
                                val savedFps = prefs.getInt(KEY_FPS, available.last())
                                val savedIndex = available.indexOf(savedFps).coerceAtLeast(0)
                                fpsSelectedIndex = savedIndex
                            }
                            if (resolutions.isNotEmpty()) {
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
                    .align(Alignment.TopCenter)
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.opp_logo),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = {
                                        hideBanner = true
                                    }
                                )
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = annotatedText,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                if (showSettingsPanel) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Settings",
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
                                        ) { showSettingsPanel = false },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.size(12.dp)) {
                                        drawLine(Color.White, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                                        drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("Trails", "Effects", "Camera").forEachIndexed { index, label ->
                                    val active = settingsTab == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (active) Color.White else Color.White.copy(alpha = 0.15f))
                                            .clickable { settingsTab = index }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (active) Color.Black else Color.White.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            if (settingsTab == 0) {
                                ControlSlider(
                                    label = "Camera Brightness (ISO)",
                                    value = isoPosition,
                                    onValueChange = { isoPosition = it },
                                    valueRange = 0f..100f,
                                    displayValue = null,
                                    steps = 0
                                )
                                ControlSlider(
                                    label = "Trail Sensitivity",
                                    value = threshold,
                                    onValueChange = { threshold = it },
                                    valueRange = 0f..75f,
                                    steps = 0
                                )
                                ControlSlider(
                                    label = "Trail Length",
                                    value = trailLengthPos,
                                    onValueChange = {
                                        trailLengthPos = it
                                        val t = it / 100.0
                                        val duration = t * t * 5.0
                                        trailLength = (duration * currentFps).toInt().coerceAtLeast(0)
                                    },
                                    valueRange = 0f..100f,
                                    steps = 0
                                )
                            } else if (settingsTab == 1) {
                                ControlSlider(
                                    label = "Fade",
                                    value = fadePercent,
                                    onValueChange = { fadePercent = it },
                                    valueRange = 0f..100f,
                                    steps = 0
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                ControlSlider(
                                    label = "Blur (Causes lag)",
                                    value = blurAmount,
                                    onValueChange = { blurAmount = it },
                                    valueRange = 0f..100f,
                                    steps = 0
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Color Override",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("Off", "Fade", "RGB", "Color", "White", "Black").forEachIndexed { index, label ->
                                        val active = colorOverrideModeIndex == index
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (active) Color.White else Color.White.copy(alpha = 0.15f))
                                                .clickable { colorOverrideModeIndex = index }
                                                .padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (active) Color.Black else Color.White.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                                if (ColorOverrideMode.entries[colorOverrideModeIndex] == ColorOverrideMode.COLOR) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Color",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(16.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        ) {
                                            val colors = (0..size.width.toInt()).map { i ->
                                                hsvToArgb(i.toFloat() / size.width * 360f, 1f, 1f)
                                            }
                                            for (i in 0 until colors.size - 1) {
                                                drawRect(
                                                    color = Color(colors[i]),
                                                    topLeft = Offset(i.toFloat(), 0f),
                                                    size = Size(2f, size.height)
                                                )
                                            }
                                        }
                                        Slider(
                                            value = solidColorHue,
                                            onValueChange = { solidColorHue = it },
                                            valueRange = 0f..360f,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color.White,
                                                activeTrackColor = Color.Transparent,
                                                inactiveTrackColor = Color.Transparent
                                            )
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Mirror",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    CheckboxRow(
                                        label = "Horizontal",
                                        checked = mirrorHorizontal,
                                        onCheckedChange = { mirrorHorizontal = it },
                                        modifier = Modifier.weight(1f)
                                    )
                                    CheckboxRow(
                                        label = "Vertical",
                                        checked = mirrorVertical,
                                        onCheckedChange = { mirrorVertical = it },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                var rotIndex by remember {
                                    mutableFloatStateOf(
                                        rotationSymmetryOptions.indexOf(rotationalSymmetry).coerceAtLeast(0).toFloat()
                                    )
                                }
                                LaunchedEffect(rotationalSymmetry) {
                                    rotIndex = rotationSymmetryOptions.indexOf(rotationalSymmetry).coerceAtLeast(0).toFloat()
                                }
                                ControlSlider(
                                    label = "Kaleidoscope",
                                    value = rotIndex,
                                    onValueChange = {
                                        rotIndex = Math.round(it).toFloat()
                                            .coerceIn(0f, (rotationSymmetryOptions.size - 1).toFloat())
                                    },
                                    valueRange = 0f..(rotationSymmetryOptions.size - 1).toFloat(),
                                    steps = rotationSymmetryOptions.size - 2,
                                    displayValue = "${rotationSymmetryOptions[rotIndex.toInt()]}",
                                    onValueChangeFinished = {
                                        rotationalSymmetry = rotationSymmetryOptions[rotIndex.toInt()]
                                    }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                CheckboxRow(
                                    label = "Shimmer",
                                    checked = shimmerEnabled,
                                    onCheckedChange = { shimmerEnabled = it }
                                )
                            } else {
                                Text(
                                    text = "Higher frame rates and resolutions may cause dropped frames on some devices. If the preview stutters or trails look choppy, try lowering these settings.",
                                    color = Color.White.copy(alpha = 0.45f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(4.dp))
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
                                Spacer(modifier = Modifier.height(4.dp))
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
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
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
                        } else {
                            val lastFrame = videoRecorder.lastFrame
                            val context = LocalContext.current
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black)
                                    .border(2.dp, Color.White, RoundedCornerShape(6.dp))
                                    .clickable {
                                        val uri = videoRecorder.lastVideoUri
                                        if (uri != null) {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "video/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        } else {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                            )
                                            context.startActivity(intent)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                lastFrame?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Last recording",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize()
                                    )
                                }
                            }
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
                                isActive = torchOn,
                                onClick = {
                                    torchOn = !torchOn
                                    cameraController.setTorchEnabled(torchOn)
                                },
                                iconType = PanelIconType.TORCH
                            )
                            PanelToggleButton(
                                isActive = thresholdPreview,
                                onClick = { thresholdPreview = !thresholdPreview },
                                iconType = PanelIconType.EYE,
                                iconTint = Color.Red
                            )
                            PanelToggleButton(
                                isActive = showSettingsPanel,
                                onClick = { showSettingsPanel = !showSettingsPanel },
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
    thresholdPreview: Boolean,
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

        val trailThread = android.os.HandlerThread("TrailThread").apply { start() }
        val trailHandler = android.os.Handler(trailThread.looper)
        val drawThread = android.os.HandlerThread("DrawThread").apply { start() }
        val drawHandler = android.os.Handler(drawThread.looper)

        cameraController.onFrameAvailable = { buf, w, h ->
            trailHandler.post {
                if (!isoRangeReported) {
                    onIsoRangeReported()
                    val range = cameraController.isoRange
                    onIsoRangeReady(range.lower, range.upper)
                }
                val processed = trailProcessor.processFrame(buf, w, h)

                drawHandler.post {
                    val holder = surfaceHolder
                    if (holder != null) {
                        val canvas: AwtCanvas? = try { holder.lockCanvas() } catch (_: Exception) { null }
                        if (canvas != null) {
                            try {
                                canvas.drawBitmap(processed, 0f, 0f, null)
                                val overlay = trailProcessor.thresholdPreview
                                if (thresholdPreview && overlay != null) {
                                    canvas.drawBitmap(overlay, 0f, 0f, null)
                                }
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
            }
        }
        cameraController.openCamera(width = captureWidth, height = captureHeight, iso = initialIso)

        try {
            kotlinx.coroutines.suspendCancellableCoroutine {}
        } finally {
            trailHandler.removeCallbacksAndMessages(null)
            trailThread.quitSafely()
            trailThread.join()
            drawHandler.removeCallbacksAndMessages(null)
            drawThread.quitSafely()
            drawThread.join()
        }
    }
}

private enum class PanelIconType { OUTPUT, TORCH, EYE }

@Composable
private fun PanelToggleButton(
    isActive: Boolean,
    onClick: () -> Unit,
    iconType: PanelIconType,
    iconTint: Color = Color.White.copy(alpha = if (isActive) 1f else 0.8f),
    modifier: Modifier = Modifier
) {
    val iconRes = when (iconType) {
        PanelIconType.OUTPUT -> R.drawable.ic_settings
        PanelIconType.TORCH -> if (isActive) R.drawable.ic_flashlight else R.drawable.ic_flashlight_off
        PanelIconType.EYE -> if (isActive) R.drawable.ic_eye else R.drawable.ic_eye_off
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
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color.White,
                uncheckedColor = Color.White.copy(alpha = 0.6f),
                checkmarkColor = Color.Black
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodyMedium
        )
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

private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
    val h = ((hue / 60f) % 6f).coerceIn(0f, 6f)
    val f = h - kotlin.math.floor(h)
    val p = (value * (1f - saturation) * 255f).toInt()
    val q = (value * (1f - f * saturation) * 255f).toInt()
    val t = (value * (1f - (1f - f) * saturation) * 255f).toInt()
    val v = (value * 255f).toInt()
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
