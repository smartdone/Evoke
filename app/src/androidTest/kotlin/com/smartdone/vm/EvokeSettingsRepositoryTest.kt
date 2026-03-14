package com.smartdone.vm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartdone.vm.core.virtual.settings.EvokeSettingsRepository
import com.smartdone.vm.core.virtual.settings.ThemeModePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvokeSettingsRepositoryTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        clearSettings()
    }

    @After
    fun tearDown() {
        clearSettings()
    }

    @Test
    fun defaults_matchExpectedHostBehavior() {
        val repository = EvokeSettingsRepository(targetContext)
        val settings = repository.currentSettings()

        assertEquals(ThemeModePreference.SYSTEM, settings.themeMode)
        assertTrue(settings.useDynamicColors)
        assertTrue(settings.showRunningAppsNotification)
        assertTrue(settings.logNativeCompatibilityOnStartup)
        assertFalse(settings.openRunningAppsOnLaunch)
    }

    @Test
    fun updates_arePersistedAcrossRepositoryInstances() {
        EvokeSettingsRepository(targetContext).apply {
            setThemeMode(ThemeModePreference.DARK)
            setDynamicColorsEnabled(false)
            setRunningAppsNotificationEnabled(false)
            setNativeCompatibilityLoggingEnabled(false)
            setOpenRunningAppsOnLaunch(true)
        }

        val reloaded = EvokeSettingsRepository(targetContext).currentSettings()

        assertEquals(ThemeModePreference.DARK, reloaded.themeMode)
        assertFalse(reloaded.useDynamicColors)
        assertFalse(reloaded.showRunningAppsNotification)
        assertFalse(reloaded.logNativeCompatibilityOnStartup)
        assertTrue(reloaded.openRunningAppsOnLaunch)
    }

    private fun clearSettings() {
        targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "evoke-settings"
    }
}
