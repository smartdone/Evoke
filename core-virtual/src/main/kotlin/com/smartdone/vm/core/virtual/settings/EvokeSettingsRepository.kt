package com.smartdone.vm.core.virtual.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeModePreference {
    SYSTEM,
    LIGHT,
    DARK
}

data class EvokeSettings(
    val themeMode: ThemeModePreference = ThemeModePreference.SYSTEM,
    val useDynamicColors: Boolean = true,
    val showRunningAppsNotification: Boolean = true,
    val logNativeCompatibilityOnStartup: Boolean = true,
    val openRunningAppsOnLaunch: Boolean = false
)

@Singleton
class EvokeSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsState = MutableStateFlow(readSettings())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        settingsState.value = readSettings()
    }

    val settings: StateFlow<EvokeSettings> = settingsState.asStateFlow()

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun currentSettings(): EvokeSettings = settingsState.value

    fun setThemeMode(themeMode: ThemeModePreference) {
        preferences.edit().putString(KEY_THEME_MODE, themeMode.name).apply()
    }

    fun setDynamicColorsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DYNAMIC_COLORS, enabled).apply()
    }

    fun setRunningAppsNotificationEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RUNNING_NOTIFICATION, enabled).apply()
    }

    fun setNativeCompatibilityLoggingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_NATIVE_LOGGING, enabled).apply()
    }

    fun setOpenRunningAppsOnLaunch(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_OPEN_RUNNING_ON_LAUNCH, enabled).apply()
    }

    @VisibleForTesting
    internal fun readSettings(): EvokeSettings = EvokeSettings(
        themeMode = preferences.getString(KEY_THEME_MODE, ThemeModePreference.SYSTEM.name)
            ?.let { stored ->
                ThemeModePreference.entries.firstOrNull { it.name == stored }
            }
            ?: ThemeModePreference.SYSTEM,
        useDynamicColors = preferences.getBoolean(KEY_DYNAMIC_COLORS, true),
        showRunningAppsNotification = preferences.getBoolean(KEY_RUNNING_NOTIFICATION, true),
        logNativeCompatibilityOnStartup = preferences.getBoolean(KEY_NATIVE_LOGGING, true),
        openRunningAppsOnLaunch = preferences.getBoolean(KEY_OPEN_RUNNING_ON_LAUNCH, false)
    )

    private companion object {
        const val PREFS_NAME = "evoke-settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        const val KEY_RUNNING_NOTIFICATION = "running_notification"
        const val KEY_NATIVE_LOGGING = "native_logging"
        const val KEY_OPEN_RUNNING_ON_LAUNCH = "open_running_on_launch"
    }
}
