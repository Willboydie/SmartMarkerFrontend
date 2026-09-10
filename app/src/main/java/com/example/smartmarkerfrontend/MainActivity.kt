package com.example.smartmarkerfrontend

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartmarkerfrontend.ui.SmartMarkerViewModel
import com.example.smartmarkerfrontend.ui.theme.SmartMarkerFrontendTheme
import com.example.smartmarkerfrontend.ui.theme.SmColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bar = SmColors.bg.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(bar, bar),
            navigationBarStyle = SystemBarStyle.light(bar, bar)
        )
        setContent {
            SmartMarkerFrontendTheme {
                val vm: SmartMarkerViewModel = viewModel()
                SmartMarkerApp(vm)
            }
        }
    }
}
