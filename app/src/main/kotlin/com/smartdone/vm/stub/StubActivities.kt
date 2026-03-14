package com.smartdone.vm.stub

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.client.RuntimeBootstrapResult
import com.smartdone.vm.core.virtual.client.EvokeAppClient
import com.smartdone.vm.core.virtual.client.EvokeAppRuntime
import com.smartdone.vm.core.virtual.client.EvokeAppRuntimeSession
import com.smartdone.vm.runtime.StubLaunchReporter
import com.smartdone.vm.runtime.EmbeddedVirtualActivityController
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import com.smartdone.vm.core.virtual.util.StubActivityRouter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

open class BaseStubActivity : ComponentActivity() {
    private var runtimeSession: EvokeAppRuntimeSession? = null
    private var bootstrapResult: RuntimeBootstrapResult? = null
    private var embeddedController: EmbeddedVirtualActivityController? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        val launchRecord = intent?.let { StubActivityRouter.read(it, javaClass.name) } ?: return
        val evokeAppClient = entryPoint().evokeAppClient()
        if (evokeAppClient.currentPackageName() != launchRecord.packageName) {
            evokeAppClient.initialize(
                newBase,
                launchRecord.packageName,
                launchRecord.userId,
                launchRecord.apkPath
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureHostWindow()
        val launchRecord = StubActivityRouter.read(intent, javaClass.name)
        val evokeAppClient = entryPoint().evokeAppClient()
        if (evokeAppClient.currentPackageName() != launchRecord.packageName) {
            evokeAppClient.initialize(
                this,
                launchRecord.packageName,
                launchRecord.userId,
                launchRecord.apkPath
            )
        }
        runCatching {
            entryPoint().serviceFetcher().activityManager()
                ?.reportAppLaunch(launchRecord.packageName, launchRecord.userId, Process.myPid())
        }.onFailure {
            Log.w(TAG, "Unable to report app launch for ${launchRecord.packageName}", it)
        }
        var failureStage = "createSession"
        val bootstrapFailure = runCatching {
            runtimeSession = entryPoint().evokeAppRuntime().createSession(
                context = this,
                packageName = launchRecord.packageName,
                userId = launchRecord.userId,
                apkPathOverride = launchRecord.apkPath,
                launcherActivityOverride = launchRecord.launcherActivity,
                applicationClassNameOverride = launchRecord.applicationClassName,
                nativeLibDirOverride = launchRecord.nativeLibDir,
                optimizedDirOverride = launchRecord.optimizedDir
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
        val realActivity = launchRecord.realIntent.component?.className ?: "unknown"
        StubLaunchReporter.report(
            context = this,
            packageName = launchRecord.packageName,
            userId = launchRecord.userId,
            realActivity = realActivity,
            applicationStatus = bootstrapResult?.applicationStatus ?: "skipped",
            launcherStatus = bootstrapResult?.launcherStatus ?: "skipped",
            activityStatus = bootstrapResult?.activityStatus ?: "skipped"
        )
        val controller = EmbeddedVirtualActivityController(
            hostActivity = this,
            appRuntime = entryPoint().evokeAppRuntime(),
            runtimeSession = runtimeSession ?: run {
                setFailureContent(
                    launchRecord = launchRecord,
                    failureMessage = "IllegalStateException: Runtime session missing"
                )
                return
            },
            bootstrapResult = bootstrapResult ?: run {
                setFailureContent(
                    launchRecord = launchRecord,
                    failureMessage = "IllegalStateException: Bootstrap result missing"
                )
                return
            },
            launchRecord = launchRecord,
            evokeCore = entryPoint().evokeCore()
        )
        when (val result = controller.launch()) {
            is EmbeddedVirtualActivityController.LaunchResult.Success -> {
                embeddedController = controller
                Log.i(TAG, "Embedded virtual activity launched: ${result.activity.componentName}")
            }
            is EmbeddedVirtualActivityController.LaunchResult.Failure -> {
                Log.e(TAG, "Embedded virtual activity launch failed: ${result.message}")
                setFailureContent(launchRecord, result.message)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "Stub onStart ${componentName.className}")
        embeddedController?.dispatchStart()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Stub onResume ${componentName.className}")
        embeddedController?.dispatchResume()
    }

    override fun onPause() {
        Log.d(TAG, "Stub onPause ${componentName.className}")
        embeddedController?.dispatchPause()
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "Stub onStop ${componentName.className}")
        embeddedController?.dispatchStop()
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "Stub onDestroy ${componentName.className}")
        embeddedController?.dispatchDestroy()
        super.onDestroy()
    }

    override fun finish() {
        Log.w(TAG, "Stub finish requested for ${componentName.className}", Throwable("finish trace"))
        super.finish()
    }

    override fun onBackPressed() {
        if (embeddedController?.dispatchBackPressed() == true) {
            return
        }
        super.onBackPressed()
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
        fun evokeCore(): EvokeCore
        fun serviceFetcher(): EvokeServiceFetcher
    }

    private fun entryPoint(): StubActivityEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, StubActivityEntryPoint::class.java)

    private fun buildFailureMessage(throwable: Throwable): String =
        buildString {
            append(throwable.javaClass.simpleName)
            val message = throwable.message
            if (!message.isNullOrBlank()) {
                append(": ")
                append(message)
            }
        }

    private fun configureHostWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    private fun setFailureContent(
        launchRecord: com.smartdone.vm.core.virtual.util.StubActivityRecord,
        failureMessage: String
    ) {
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
