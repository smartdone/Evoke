package com.smartdone.vm.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.settings.EvokeSettings
import com.smartdone.vm.core.virtual.settings.EvokeSettingsRepository
import com.smartdone.vm.core.virtual.settings.ThemeModePreference
import com.smartdone.vm.runtime.NativeCompatibilityLogger
import com.smartdone.vm.runtime.StubLaunchReport
import com.smartdone.vm.runtime.StubLaunchReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: EvokeSettingsRepository,
    private val evokeCore: EvokeCore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val settings: StateFlow<EvokeSettings> = settingsRepository.settings

    fun setThemeMode(themeMode: ThemeModePreference) {
        settingsRepository.setThemeMode(themeMode)
    }

    fun setDynamicColorsEnabled(enabled: Boolean) {
        settingsRepository.setDynamicColorsEnabled(enabled)
    }

    fun setRunningAppsNotificationEnabled(enabled: Boolean) {
        settingsRepository.setRunningAppsNotificationEnabled(enabled)
        evokeCore.refreshRunningAppsNotification()
    }

    fun setNativeCompatibilityLoggingEnabled(enabled: Boolean) {
        settingsRepository.setNativeCompatibilityLoggingEnabled(enabled)
    }

    fun setOpenRunningAppsOnLaunch(enabled: Boolean) {
        settingsRepository.setOpenRunningAppsOnLaunch(enabled)
    }

    fun lastLaunchReport(): StubLaunchReport? = StubLaunchReporter.lastReport(context)

    fun clearLastLaunchReport() {
        StubLaunchReporter.clear(context)
    }

    fun logNativeCompatibilityNow() {
        NativeCompatibilityLogger.log(context)
    }
}
