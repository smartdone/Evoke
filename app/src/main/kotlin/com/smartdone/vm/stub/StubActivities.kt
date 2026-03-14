package com.smartdone.vm.stub

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.client.RuntimeBootstrapResult
import com.smartdone.vm.core.virtual.client.EvokeAppClient
import com.smartdone.vm.core.virtual.client.EvokeAppRuntime
import com.smartdone.vm.core.virtual.client.EvokeAppRuntimeSession
import com.smartdone.vm.core.virtual.client.hook.DeviceInfoHook
import com.smartdone.vm.runtime.StubLaunchReporter
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import com.smartdone.vm.core.virtual.util.StubActivityRouter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

open class BaseStubActivity : ComponentActivity() {
    private var runtimeSession: EvokeAppRuntimeSession? = null
    private var bootstrapResult: RuntimeBootstrapResult? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        val launchRecord = intent?.let { StubActivityRouter.read(it, javaClass.name) } ?: return
        val evokeAppClient = entryPoint().evokeAppClient()
        if (evokeAppClient.currentPackageName() != launchRecord.packageName) {
            evokeAppClient.initialize(newBase, launchRecord.packageName, launchRecord.userId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchRecord = StubActivityRouter.read(intent, javaClass.name)
        val evokeAppClient = entryPoint().evokeAppClient()
        if (evokeAppClient.currentPackageName() != launchRecord.packageName) {
            evokeAppClient.initialize(this, launchRecord.packageName, launchRecord.userId)
        }
        entryPoint().serviceFetcher().activityManager()
            ?.reportAppLaunch(launchRecord.packageName, launchRecord.userId, Process.myPid())
        var failureStage = "createSession"
        val bootstrapFailure = runCatching {
            runtimeSession = entryPoint().evokeAppRuntime().createSession(
                context = this,
                packageName = launchRecord.packageName,
                userId = launchRecord.userId,
                apkPathOverride = launchRecord.apkPath,
                launcherActivityOverride = launchRecord.launcherActivity,
                applicationClassNameOverride = launchRecord.applicationClassName
            )
            failureStage = "bootstrapApplication"
            bootstrapResult = runtimeSession?.let { session ->
                entryPoint().evokeAppRuntime().bootstrapApplication(this, session)
            }
        }.exceptionOrNull()
        if (bootstrapFailure != null) {
            val failureMessage = buildFailureMessage(bootstrapFailure)
            Log.e(TAG, "Evoke launch failed for ${launchRecord.packageName}", bootstrapFailure)
            StubLaunchReporter.report(
                context = this,
                packageName = launchRecord.packageName,
                userId = launchRecord.userId,
                realActivity = launchRecord.realIntent.component?.className ?: "unknown",
                applicationStatus = "failed",
                launcherStatus = "failed",
                activityStatus = "failed",
                failureStage = failureStage,
                failureMessage = failureMessage
            )
            setContentView(
                TextView(this).apply {
                    text =
                        "Evoke launch failed\n" +
                            "package=${launchRecord.packageName}\n" +
                            "userId=${launchRecord.userId}\n" +
                            "real=${launchRecord.realIntent.component?.className ?: "unknown"}\n" +
                            "launchFailed=$failureMessage"
                }
            )
            return
        }
        val label = intent.getStringExtra(EvokeCore.EXTRA_LABEL) ?: "Stub"
        val realActivity = launchRecord.realIntent.component?.className ?: "unknown"
        val applicationClass = runtimeSession?.applicationClassName ?: "default"
        val classLoaderName = runtimeSession?.evokeAppClassLoader?.javaClass?.simpleName ?: "unavailable"
        val deviceInfo = entryPoint().deviceInfoHook()
        StubLaunchReporter.report(
            context = this,
            packageName = launchRecord.packageName,
            userId = launchRecord.userId,
            realActivity = realActivity,
            applicationStatus = bootstrapResult?.applicationStatus ?: "skipped",
            launcherStatus = bootstrapResult?.launcherStatus ?: "skipped",
            activityStatus = bootstrapResult?.activityStatus ?: "skipped"
        )
        if (launchInstalledActivity(launchRecord.realIntent, launchRecord.packageName)) {
            finish()
            return
        }
        setContentView(
            TextView(this).apply {
                text =
                    "Launching $label in stub process\n" +
                    "package=${launchRecord.packageName}\n" +
                    "userId=${launchRecord.userId}\n" +
                    "real=$realActivity\n" +
                    "appClass=$applicationClass\n" +
                    "loader=$classLoaderName\n" +
                    "appBootstrap=${bootstrapResult?.applicationStatus ?: "skipped"}\n" +
                    "launcherLoad=${bootstrapResult?.launcherStatus ?: "skipped"}\n" +
                    "activityBootstrap=${bootstrapResult?.activityStatus ?: "skipped"}\n" +
                    "androidId=${deviceInfo.androidId(launchRecord.packageName)}\n" +
                    "deviceId=${deviceInfo.deviceId(launchRecord.packageName)}"
            }
        )
    }

    override fun getResources() = runtimeSession?.resources ?: super.getResources()

    override fun getAssets() = runtimeSession?.resources?.assets ?: super.getAssets()

    override fun getClassLoader() = runtimeSession?.evokeAppClassLoader ?: super.getClassLoader()

    override fun getPackageName() = runtimeSession?.packageName ?: super.getPackageName()

    override fun getOpPackageName() = runtimeSession?.packageName ?: super.getOpPackageName()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StubActivityEntryPoint {
        fun evokeAppClient(): EvokeAppClient
        fun evokeAppRuntime(): EvokeAppRuntime
        fun serviceFetcher(): EvokeServiceFetcher
        fun deviceInfoHook(): DeviceInfoHook
    }

    private fun entryPoint(): StubActivityEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, StubActivityEntryPoint::class.java)

    private fun launchInstalledActivity(intent: Intent, packageName: String): Boolean {
        val component = intent.component ?: return false
        val forwardIntent = Intent(intent).apply {
            removeExtra(EvokeCore.EXTRA_PACKAGE_NAME)
            removeExtra(EvokeCore.EXTRA_USER_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Log.i(TAG, "Attempting to forward to installed activity $component for $packageName")
        return runCatching {
            applicationContext.startActivity(forwardIntent)
            Log.i(TAG, "Forwarded to installed activity $component")
            true
        }.onFailure {
            Log.w(TAG, "Unable to forward to installed activity $component", it)
        }.getOrDefault(false)
    }

    private fun buildFailureMessage(throwable: Throwable): String =
        buildString {
            append(throwable.javaClass.simpleName)
            val message = throwable.message
            if (!message.isNullOrBlank()) {
                append(": ")
                append(message)
            }
        }

    companion object {
        private const val TAG = "StubActivity"
    }
}

class StubActivity_P0_A0 : BaseStubActivity()
class StubActivity_P0_A1 : BaseStubActivity()
class StubActivity_P1_A0 : BaseStubActivity()
class StubActivity_P1_A1 : BaseStubActivity()
class StubActivity_P2_A0 : BaseStubActivity()
class StubActivity_P2_A1 : BaseStubActivity()
class StubActivity_P3_A0 : BaseStubActivity()
class StubActivity_P3_A1 : BaseStubActivity()
class StubActivity_P4_A0 : BaseStubActivity()
class StubActivity_P4_A1 : BaseStubActivity()
class StubActivity_P5_A0 : BaseStubActivity()
class StubActivity_P5_A1 : BaseStubActivity()
class StubActivity_P6_A0 : BaseStubActivity()
class StubActivity_P6_A1 : BaseStubActivity()
class StubActivity_P7_A0 : BaseStubActivity()
class StubActivity_P7_A1 : BaseStubActivity()
class StubActivity_P8_A0 : BaseStubActivity()
class StubActivity_P8_A1 : BaseStubActivity()
class StubActivity_P9_A0 : BaseStubActivity()
class StubActivity_P9_A1 : BaseStubActivity()
