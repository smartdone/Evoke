package com.smartdone.vm.core.virtual.client

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.install.ApkParser
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import java.io.File
import java.lang.reflect.Method
import android.app.Activity
import android.content.pm.ApplicationInfo
import dalvik.system.DexClassLoader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

data class EvokeAppRuntimeSession(
    val packageName: String,
    val userId: Int,
    val apkPath: String,
    val launcherActivity: String?,
    val applicationClassName: String?,
    val resources: Resources,
    val applicationInfo: ApplicationInfo,
    val bridgeClassLoader: BridgeClassLoader,
    val evokeAppClassLoader: DexClassLoader
)

@Singleton
class EvokeAppRuntime @Inject constructor(
    private val repository: EvokeAppRepository,
    private val sandboxPath: SandboxPath,
    private val apkParser: ApkParser
) {
    fun createSession(
        context: Context,
        packageName: String,
        userId: Int,
        apkPathOverride: String? = null,
        launcherActivityOverride: String? = null,
        applicationClassNameOverride: String? = null
    ): EvokeAppRuntimeSession? = runBlocking {
        val app = if (apkPathOverride == null) repository.getApp(packageName) else null
        val apkPath = apkPathOverride ?: app?.apkPath ?: return@runBlocking null
        val metadata = apkParser.parseArchive(apkPath)
        val hookClasses = setOf(
            "com.smartdone.vm.core.virtual.client.hook.ActivityManagerHook",
            "com.smartdone.vm.core.virtual.client.hook.PackageManagerHook",
            "com.smartdone.vm.core.virtual.client.hook.ContentProviderHook",
            "com.smartdone.vm.core.virtual.client.hook.BroadcastHook",
            "com.smartdone.vm.core.virtual.client.hook.PermissionHook",
            "com.smartdone.vm.core.virtual.client.hook.DeviceInfoHook",
            "com.smartdone.vm.core.virtual.client.hook.ContextHook",
            "com.smartdone.vm.core.virtual.client.EvokeAppClient"
        )
        sandboxPath.ensureAppStructure(packageName, userId)
        sandboxPath.sealArchiveIfManaged(apkPath)
        val bridgeClassLoader = BridgeClassLoader(
            hostClassLoader = context.classLoader,
            hookClassWhitelist = hookClasses
        )
        val apkFile = File(apkPath)
        Log.i(
            TAG,
            "createSession package=$packageName userId=$userId apk=$apkPath " +
                "exists=${apkFile.exists()} canWrite=${apkFile.canWrite()} " +
                "optimizedDir=${sandboxPath.optimizedDir(packageName).absolutePath}"
        )
        val resources = createResources(context, apkPath)
        val applicationInfo = context.packageManager
            .getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(0))
            ?.applicationInfo
            ?.apply {
                sourceDir = apkPath
                publicSourceDir = apkPath
            }
            ?: context.applicationInfo
        val evokeAppClassLoader = DexClassLoader(
            apkPath,
            sandboxPath.optimizedDir(packageName).absolutePath,
            sandboxPath.nativeLibDir(packageName).absolutePath,
            bridgeClassLoader
        )
        EvokeAppRuntimeSession(
            packageName = packageName,
            userId = userId,
            apkPath = apkPath,
            launcherActivity = launcherActivityOverride ?: metadata?.launcherActivity ?: metadata?.activities?.firstOrNull(),
            applicationClassName = applicationClassNameOverride ?: metadata?.applicationClassName,
            resources = resources,
            applicationInfo = applicationInfo,
            bridgeClassLoader = bridgeClassLoader,
            evokeAppClassLoader = evokeAppClassLoader
        )
    }

    fun bootstrapApplication(
        context: Context,
        session: EvokeAppRuntimeSession
    ): RuntimeBootstrapResult {
        val evokeContext = EvokeAppContext(
            base = context,
            evokePackageName = session.packageName,
            evokeClassLoader = session.evokeAppClassLoader,
            evokeResources = session.resources,
            evokeApplicationInfo = session.applicationInfo
        )
        val loadedLauncher = session.launcherActivity?.let { launcherActivity ->
            runCatching { session.evokeAppClassLoader.loadClass(launcherActivity).name }.getOrNull()
        }
        val applicationStatus = session.applicationClassName?.let { applicationClassName ->
            runCatching {
                val appClass = session.evokeAppClassLoader.loadClass(applicationClassName)
                val application = appClass.getDeclaredConstructor().newInstance() as Application
                attachBaseContext(application, evokeContext)
                application.onCreate()
                application::class.java.name
            }.fold(
                onSuccess = { "created:$it" },
                onFailure = { "failed:${it.javaClass.simpleName}" }
            )
        } ?: "default"
        val activityStatus = session.launcherActivity?.let { launcherActivity ->
            runCatching {
                val activityClass = session.evokeAppClassLoader.loadClass(launcherActivity)
                val activity = activityClass.getDeclaredConstructor().newInstance()
                if (activity is Activity || activity is ContextWrapper) {
                    attachBaseContext(activity, evokeContext)
                }
                if (activity is Activity) {
                    activity.intent = Intent().setClassName(session.packageName, launcherActivity)
                }
                "attached:${activityClass.name}"
            }.fold(
                onSuccess = { it },
                onFailure = { "failed:${it.javaClass.simpleName}" }
            )
        } ?: "missing"
        return RuntimeBootstrapResult(
            applicationStatus = applicationStatus,
            launcherStatus = loadedLauncher ?: "missing",
            activityStatus = activityStatus,
            evokeContext = evokeContext
        )
    }

    private fun attachBaseContext(target: Any, context: Context) {
        val attachMethod: Method = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { clazz ->
                runCatching {
                    clazz.getDeclaredMethod("attachBaseContext", Context::class.java)
                }.getOrNull()
            }
            .firstOrNull()
            ?: Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)
        attachMethod.isAccessible = true
        attachMethod.invoke(target, context)
    }

    private fun createResources(context: Context, apkPath: String): Resources {
        val hostResources = context.resources
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assetManager, apkPath) as? Int ?: 0
        if (cookie == 0) {
            return hostResources
        }
        return Resources(assetManager, hostResources.displayMetrics, hostResources.configuration)
    }

    companion object {
        private const val TAG = "EvokeAppRuntime"
    }
}

data class RuntimeBootstrapResult(
    val applicationStatus: String,
    val launcherStatus: String,
    val activityStatus: String,
    val evokeContext: Context
)
