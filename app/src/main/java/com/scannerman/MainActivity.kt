package com.scannerman

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.scannerman.ui.KnownDevicesScreen
import com.scannerman.ui.MainScreen
import com.scannerman.ui.MainViewModel
import com.scannerman.ui.SettingsScreen

enum class Screen { MAIN, SETTINGS, KNOWN }

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
            BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
            MaterialTheme(colorScheme = colors) {
                when (screen) {
                    Screen.MAIN -> MainScreen(vm, navigate = { screen = it })
                    Screen.SETTINGS -> SettingsScreen(vm, onBack = { screen = Screen.MAIN })
                    Screen.KNOWN -> KnownDevicesScreen(vm, onBack = { screen = Screen.MAIN })
                }
            }
        }
    }
}
