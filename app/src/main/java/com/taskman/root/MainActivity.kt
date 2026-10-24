package com.taskman.root

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.taskman.root.ui.AppRoot
import com.taskman.root.ui.MainViewModel
import com.taskman.root.ui.TaskManTheme
import com.taskman.root.ui.isDark
import com.taskman.root.ui.windowBackgroundArgb

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by vm.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = mode.isDark(systemDark)

            // The status / navigation bar icons and the window behind Compose follow the in-app theme,
            // which can differ from the phone's own dark mode setting.
            DisposableEffect(mode, systemDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark }
                )
                window.setBackgroundDrawable(ColorDrawable(windowBackgroundArgb(mode, systemDark)))
                onDispose { }
            }

            TaskManTheme(mode) {
                AppRoot(vm)
            }
        }
    }

    // Sampling is paused while the app is not visible: no battery drain in the background.
    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onStop() {
        vm.setForeground(false)
        super.onStop()
    }

    private companion object {
        // the scrims of the 3-button navigation bar (same values as the platform's edge-to-edge defaults)
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
