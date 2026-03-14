package com.smartdone.vm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdone.vm.core.virtual.settings.EvokeSettingsRepository
import com.smartdone.vm.ui.navigation.Destination
import com.smartdone.vm.ui.navigation.VmApp
import com.smartdone.vm.ui.theme.VmTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: EvokeSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = settingsRepository.settings.collectAsStateWithLifecycle().value
            VmTheme(
                themeMode = settings.themeMode,
                useDynamicColors = settings.useDynamicColors
            ) {
                VmApp(
                    startDestination = if (settings.openRunningAppsOnLaunch) {
                        Destination.Running.route
                    } else {
                        Destination.Home.route
                    }
                )
            }
        }
    }
}
