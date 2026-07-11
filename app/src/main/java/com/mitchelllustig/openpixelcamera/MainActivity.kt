package com.mitchelllustig.openpixelcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mitchelllustig.openpixelcamera.ui.CameraScreen
import com.mitchelllustig.openpixelcamera.ui.theme.OpenPixelCameraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenPixelCameraTheme {
                CameraScreen()
            }
        }
    }
}
