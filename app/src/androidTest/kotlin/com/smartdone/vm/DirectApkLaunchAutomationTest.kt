package com.smartdone.vm

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectApkLaunchAutomationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        assumeTrue("A launchable APKPure source APK is required", launchSourceFile()?.exists() == true)
        execShell("logcat -c")
        bringHostToForeground()
    }

    @Test
    fun launchApkUri_withoutImport_launchesInsideEvoke() {
        val source = requireNotNull(launchSourceFile())
        val launched = runBlocking {
            entryPoint().evokeCore().launchApkUri(Uri.fromFile(source))
        }
        val embedded = waitForEmbeddedUi()
        val logs = captureRelevantLogs()

        assertTrue("Direct APK launch request was rejected", launched)
        assertTrue("Expected direct APK launch to reach embedded UI. Logs:\n$logs", embedded)
        assertTrue(
            "Expected direct launch to use staged launch storage. Logs:\n$logs",
            logs.contains("/VirtualEnv/launches/")
        )
    }

    private fun bringHostToForeground() {
        targetContext.startActivity(
            Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        device.wait(Until.hasObject(By.pkg(targetContext.packageName).depth(0)), 10_000)
        SystemClock.sleep(1_000)
    }

    private fun launchSourceFile(): File? {
        val installedSource = runCatching {
            @Suppress("DEPRECATION")
            targetContext.packageManager.getApplicationInfo(VIRTUAL_APKPURE_PACKAGE, 0).sourceDir
        }.getOrNull()
        if (!installedSource.isNullOrBlank()) {
            return File(installedSource)
        }
        return File(targetContext.getExternalFilesDir(null), "apkpure-base.apk")
            .takeIf(File::exists)
    }

    private fun waitForEmbeddedUi(timeoutMs: Long = 10_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val logs = execShell("logcat -d -s StubActivity:I EmbeddedActivity:I AndroidRuntime:E *:S")
            if (
                "Embedded virtual activity launched" in logs &&
                "launchFailed=" !in logs &&
                "Embedded virtual activity launch failed" !in logs
            ) {
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
                    it.contains("EmbeddedActivity") ||
                    it.contains("StubActivity") ||
                    it.contains(VIRTUAL_APKPURE_PACKAGE) ||
                    it.contains("/VirtualEnv/launches/")
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
