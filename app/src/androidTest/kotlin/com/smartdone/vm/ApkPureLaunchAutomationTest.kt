package com.smartdone.vm

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ApkPureLaunchAutomationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        assumeTrue("APKPure must be installed on the device", isInstalledOnDevice())
        ensureInstalledInEvokeSpace()
        execShell("logcat -c")
        bringHostToForeground()
    }

    @Test
    fun launchInstalledApkPure_fromHome_doesNotCrashStubProcess() {
        val launchTarget = device.wait(Until.findObject(By.text("APKPure · user 0")), 10_000)
            ?: device.wait(Until.findObject(By.text("启动默认")), 5_000)
        assertTrue("Could not find a launch control for APKPure", launchTarget != null)
        launchTarget!!.click()

        val launched = waitForRealUi()
        val logs = captureRelevantLogs()
        val apkPath = entryPoint().packageManagerService().getPackageInfo(VIRTUAL_APKPURE_PACKAGE)
            .getString("apkPath")
            .orEmpty()
        val apkFile = File(apkPath)

        assertTrue(
            "Evoke APK should be sealed read-only: $apkPath",
            apkPath.isNotBlank() && apkFile.exists() && !apkFile.canWrite()
        )
        assertTrue(
            "Expected APKPure main activity to enter foreground. Logs:\n$logs",
            launched
        )
        assertFalse("Stub reported launch failure.\nLogs:\n$logs", logs.contains("launchFailed="))
        assertFalse("Detected writable dex failure again.\nLogs:\n$logs", logs.contains("Writable dex file"))
        assertFalse("Detected fatal runtime crash.\nLogs:\n$logs", logs.contains("FATAL EXCEPTION"))
    }

    private fun bringHostToForeground() {
        targetContext.startActivity(
            Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        device.wait(Until.hasObject(By.pkg(targetContext.packageName).depth(0)), 10_000)
        device.wait(Until.findObject(By.text("Home")), 5_000)?.click()
        SystemClock.sleep(1_000)
    }

    private fun ensureInstalledInEvokeSpace() {
        if (isInstalledInEvokeSpace()) return
        runBlocking {
            entryPoint().apkInstaller().installFromInstalledApp(VIRTUAL_APKPURE_PACKAGE).collect()
        }
    }

    private fun isInstalledInEvokeSpace(): Boolean =
        entryPoint().packageManagerService().installedPackages.contains(VIRTUAL_APKPURE_PACKAGE)

    private fun isInstalledOnDevice(): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            targetContext.packageManager.getPackageInfo(VIRTUAL_APKPURE_PACKAGE, 0)
        }.isSuccess

    private fun waitForRealUi(timeoutMs: Long = 10_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.hasObject(By.pkg(VIRTUAL_APKPURE_PACKAGE).depth(0))) {
                return true
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
        }
        return false
    }

    private fun captureRelevantLogs(): String {
        val raw = execShell("logcat -d -v brief")
        return raw.lineSequence()
            .filter {
                it.contains("AndroidRuntime") ||
                    it.contains("EvokeAppRuntime") ||
                    it.contains("StubActivity") ||
                    it.contains("NativeCompat") ||
                    it.contains("Displayed com.smartdone.vm/.stub") ||
                    it.contains(VIRTUAL_APKPURE_PACKAGE) ||
                    it.contains("Writable dex file")
            }
            .joinToString("\n")
    }

    private fun execShell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private fun entryPoint(): VmAppEntryPoint =
        EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            VmAppEntryPoint::class.java
        )

    companion object {
        private const val VIRTUAL_APKPURE_PACKAGE = "com.apkpure.aegon"
    }
}
