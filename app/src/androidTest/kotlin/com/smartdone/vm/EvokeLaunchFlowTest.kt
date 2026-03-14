package com.smartdone.vm

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvokeLaunchFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val targetPackageName = targetContext.packageName

    @Before
    fun setUp() {
        cleanUpEvokePackage()
        bringHostToForeground()
    }

    @After
    fun tearDown() {
        cleanUpEvokePackage()
    }

    private fun cleanUpEvokePackage() {
        runBlocking {
            runCatching { entryPoint().evokeCore().stopApp(targetPackageName, 0) }
            runCatching { entryPoint().evokeCore().uninstallApp(targetPackageName) }
        }
    }

    private fun bringHostToForeground() {
        targetContext.startActivity(
            android.content.Intent(targetContext, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        SystemClock.sleep(1000)
    }

    @Test
    fun launchApp_bootstrapsStubProcessAndMarksPackageRunning() = runBlocking {
        installTargetPackage()

        val launched = entryPoint().evokeCore().launchApp(targetPackageName, userId = 0)
        assertTrue(launched)

        assertTrue(waitForRunningApp(targetPackageName, userId = 0))
    }

    @Test
    fun launchIntent_routesDeepLinkToEvokeActivity() = runBlocking {
        installTargetPackage()

        val resolved = entryPoint().packageManagerService().resolveIntentRoute(
            android.os.Bundle().apply {
                putString(com.smartdone.vm.core.virtual.server.EvokePackageManagerService.KEY_ACTION, android.content.Intent.ACTION_VIEW)
                putStringArrayList(
                    com.smartdone.vm.core.virtual.server.EvokePackageManagerService.KEY_CATEGORIES,
                    arrayListOf(
                        android.content.Intent.CATEGORY_DEFAULT,
                        android.content.Intent.CATEGORY_BROWSABLE
                    )
                )
                putString(com.smartdone.vm.core.virtual.server.EvokePackageManagerService.KEY_SCHEME, "https")
                putString(com.smartdone.vm.core.virtual.server.EvokePackageManagerService.KEY_HOST, "vm.smartdone.app")
            }
        )

        assertTrue(resolved.getString("packageName") == targetPackageName)
        assertTrue(resolved.getString("activityName") == "com.smartdone.vm.runtime.IntentProxyActivity")
    }

    private suspend fun installTargetPackage() {
        entryPoint().apkInstaller()
            .installFromFile(Uri.fromFile(File(targetContext.packageCodePath)))
            .collect()
    }

    private fun waitForRunningApp(packageName: String, userId: Int, timeoutMs: Long = 10_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (entryPoint().evokeCore().getRunningApps().any { it.packageName == packageName && it.userId == userId }) {
                return true
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
        }
        return false
    }

    private fun entryPoint(): VmAppEntryPoint =
        EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            VmAppEntryPoint::class.java
        )
}
