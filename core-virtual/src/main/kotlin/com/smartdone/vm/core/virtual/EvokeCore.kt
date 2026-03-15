package com.smartdone.vm.core.virtual

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.install.ApkFileImporter
import com.smartdone.vm.core.virtual.model.RunningAppRecord
import com.smartdone.vm.core.virtual.permission.PermissionRequestContract
import com.smartdone.vm.core.virtual.model.StorageStats
import com.smartdone.vm.core.virtual.server.PermissionDelegateService
import com.smartdone.vm.core.virtual.server.ProcessSlotManager
import com.smartdone.vm.core.virtual.settings.EvokeSettingsRepository
import com.smartdone.vm.core.virtual.server.EvokeActivityManagerService
import com.smartdone.vm.core.virtual.server.EvokeBroadcastManager
import com.smartdone.vm.core.virtual.server.EvokeContentProviderManager
import com.smartdone.vm.core.virtual.server.EvokePackageManagerService
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import com.smartdone.vm.core.virtual.util.StubActivityRecord
import com.smartdone.vm.core.virtual.util.StubActivityRouter
import com.smartdone.vm.core.virtual.util.IntentRouteResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class EvokeCore @Inject constructor(
    private val context: Context,
    private val repository: EvokeAppRepository,
    private val apkFileImporter: ApkFileImporter,
    private val packageManagerService: EvokePackageManagerService,
    private val activityManagerService: EvokeActivityManagerService,
    private val permissionDelegateService: PermissionDelegateService,
    private val broadcastManager: EvokeBroadcastManager,
    private val contentProviderManager: EvokeContentProviderManager,
    private val processSlotManager: ProcessSlotManager,
    private val sandboxPath: SandboxPath,
    private val settingsRepository: EvokeSettingsRepository
) {
    private val runningAppsState = MutableStateFlow<List<RunningAppRecord>>(emptyList())

    fun runningApps(): StateFlow<List<RunningAppRecord>> = runningAppsState.asStateFlow()

    suspend fun launchApp(packageName: String, userId: Int): Boolean {
        val routeIntent = Intent(Intent.ACTION_MAIN).apply {
            setPackage(packageName)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return launchIntent(routeIntent, userId)
    }

    suspend fun launchApkUri(uri: Uri, userId: Int = 0): Boolean {
        val stagedLaunch = apkFileImporter.stageForLaunch(uri)
        val targetActivity = stagedLaunch.launcherActivity ?: return false
        Log.i(
            TAG,
            "Launching staged APK package=${stagedLaunch.packageName} userId=$userId activity=$targetActivity apk=${stagedLaunch.baseApkPath}"
        )
        val launchInfo = activityManagerService.startActivity(
            packageName = stagedLaunch.packageName,
            userId = userId,
            activityName = targetActivity
        )
        val slotId = launchInfo.getInt("slotId")
        val stubClass = "com.smartdone.vm.stub.StubActivity_P${slotId}_A0"
        val record = StubActivityRecord(
            stubClassName = stubClass,
            packageName = stagedLaunch.packageName,
            userId = userId,
            realIntent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(stagedLaunch.packageName, targetActivity)
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra(EXTRA_PACKAGE_NAME, stagedLaunch.packageName)
                putExtra(EXTRA_USER_ID, userId)
            },
            apkPath = stagedLaunch.baseApkPath,
            launcherActivity = targetActivity,
            applicationClassName = stagedLaunch.applicationClassName,
            nativeLibDir = stagedLaunch.nativeLibDir,
            optimizedDir = stagedLaunch.optimizedDir
        )
        val stubIntent = StubActivityRouter.buildLaunchIntent(
            hostPackage = context.packageName,
            stubClassName = stubClass,
            record = record,
            label = stagedLaunch.label
        )
        context.startActivity(stubIntent)
        syncRunningApps()
        syncRunningNotification()
        return true
    }

    suspend fun launchIntent(intent: Intent, userId: Int): Boolean {
        val initialPackage = IntentRouteResolver.resolvePackageName(intent)
        val resolvedRoute = packageManagerService.resolveIntentRoute(
            android.os.Bundle().apply {
                putString(EvokePackageManagerService.KEY_ACTION, intent.action)
                putStringArrayList(
                    EvokePackageManagerService.KEY_CATEGORIES,
                    ArrayList(intent.categories.orEmpty())
                )
                putString(EvokePackageManagerService.KEY_SCHEME, intent.data?.scheme)
                putString(EvokePackageManagerService.KEY_HOST, intent.data?.host)
                putString(EvokePackageManagerService.KEY_MIME_TYPE, intent.type)
                putString(EvokePackageManagerService.KEY_PACKAGE_NAME, initialPackage)
            }
        )
        val packageName = initialPackage
            ?: resolvedRoute.getString("packageName")
            ?: return false
        val app = repository.getApp(packageName) ?: return false
        val resolved =
            if (resolvedRoute.getString("packageName") == packageName) {
                resolvedRoute
            } else {
                packageManagerService.resolveIntentRoute(
                    android.os.Bundle().apply {
                        putString(EvokePackageManagerService.KEY_ACTION, intent.action)
                        putStringArrayList(
                            EvokePackageManagerService.KEY_CATEGORIES,
                            ArrayList(intent.categories.orEmpty())
                        )
                        putString(EvokePackageManagerService.KEY_SCHEME, intent.data?.scheme)
                        putString(EvokePackageManagerService.KEY_HOST, intent.data?.host)
                        putString(EvokePackageManagerService.KEY_MIME_TYPE, intent.type)
                        putString(EvokePackageManagerService.KEY_PACKAGE_NAME, packageName)
                    }
                )
            }
        val packageInfo = packageManagerService.getPackageInfo(packageName)
        val applicationInfo = packageManagerService.getApplicationInfo(packageName)
        val targetActivity = intent.component?.className
            ?: resolved.getString("activityName")
            ?: processSlotManager.lastKnownComponent(packageName, userId)
            ?: packageName
        val realIntent = Intent(intent).apply {
            setClassName(packageName, targetActivity)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_USER_ID, userId)
        }
        val launchInfo = activityManagerService.startActivity(
            packageName = packageName,
            userId = userId,
            activityName = realIntent.component?.className
        )
        val slotId = launchInfo.getInt("slotId")
        val stubClass = "com.smartdone.vm.stub.StubActivity_P${slotId}_A0"
        val record = StubActivityRecord(
            stubClassName = stubClass,
            packageName = packageName,
            userId = userId,
            realIntent = realIntent,
            apkPath = packageInfo.getString("apkPath") ?: app.apkPath,
            launcherActivity = targetActivity,
            applicationClassName = applicationInfo.getString("applicationClassName")
        )
        val stubIntent = StubActivityRouter.buildLaunchIntent(
            hostPackage = context.packageName,
            stubClassName = stubClass,
            record = record,
            label = app.label
        )
        context.startActivity(stubIntent)
        syncRunningApps()
        syncPackageRunningState(packageName)
        syncRunningNotification()
        return true
    }

    suspend fun stopApp(packageName: String, userId: Int) {
        activityManagerService.stopApp(packageName, userId)
        syncRunningApps()
        syncPackageRunningState(packageName)
        syncRunningNotification()
    }

    suspend fun createInstance(packageName: String, displayName: String): Int {
        val nextUserId = (repository.getInstances(packageName).maxOfOrNull { it.userId } ?: -1) + 1
        sandboxPath.ensureAppStructure(packageName, nextUserId)
        repository.upsertInstalledApp(
            app = repository.getApp(packageName)?.let {
                com.smartdone.vm.core.virtual.data.db.EvokeAppEntity(
                    packageName = it.packageName,
                    label = it.label,
                    versionCode = it.versionCode,
                    apkPath = it.apkPath,
                    iconPath = it.iconPath,
                    installTime = it.installTime,
                    isRunning = it.isRunning
                )
            } ?: error("App not found"),
            instances = repository.getInstances(packageName).map {
                com.smartdone.vm.core.virtual.data.db.EvokeAppInstanceEntity(
                    packageName = it.packageName,
                    userId = it.userId,
                    displayName = it.displayName,
                    createdTime = it.createdTime
                )
            } + com.smartdone.vm.core.virtual.data.db.EvokeAppInstanceEntity(
                packageName = packageName,
                userId = nextUserId,
                displayName = displayName,
                createdTime = System.currentTimeMillis()
            ),
            permissions = repository.getPermissions(packageName).map {
                com.smartdone.vm.core.virtual.data.db.EvokeAppPermissionEntity(
                    packageName = it.packageName,
                    permissionName = it.permissionName,
                    isGranted = it.isGranted
                )
            }
        )
        return nextUserId
    }

    suspend fun renameInstance(packageName: String, userId: Int, displayName: String) {
        repository.renameInstance(packageName, userId, displayName)
    }

    suspend fun deleteInstance(packageName: String, userId: Int): Boolean {
        val instances = repository.getInstances(packageName)
        if (instances.size <= 1) return false
        stopApp(packageName, userId)
        sandboxPath.deleteDataDir(userId, packageName)
        repository.deleteInstance(packageName, userId)
        return true
    }

    suspend fun uninstallApp(packageName: String): Boolean {
        val instances = repository.getInstances(packageName)
        if (repository.getApp(packageName) == null) return false
        instances.forEach { stopApp(packageName, it.userId) }
        val removedAppDir = sandboxPath.deleteAppDir(packageName)
        val removedData = sandboxPath.deleteAllDataDirs(packageName)
        repository.deleteApp(packageName)
        syncRunningApps()
        syncRunningNotification()
        return removedAppDir || removedData || instances.isNotEmpty()
    }

    fun storageStats(packageName: String): List<StorageStats> =
        repository.run {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    getInstances(packageName).map { instance ->
                        StorageStats(
                            userId = instance.userId,
                            dataBytes = sandboxPath.dataSize(instance.userId, packageName),
                            cacheBytes = sandboxPath.cacheSize(instance.userId, packageName)
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    suspend fun clearCache(packageName: String, userId: Int): Boolean =
        sandboxPath.clearCache(userId, packageName)

    suspend fun clearData(packageName: String, userId: Int): Boolean {
        stopApp(packageName, userId)
        return sandboxPath.clearData(userId, packageName)
    }

    fun getRunningApps(): List<RunningAppRecord> {
        syncRunningApps()
        return runningAppsState.value
    }

    fun requestPermission(packageName: String, permissionName: String): Boolean {
        val alreadyGranted = permissionDelegateService.requestPermission(packageName, permissionName)
        if (alreadyGranted) return true
        val intent = Intent().apply {
            component = ComponentName(context.packageName, PermissionRequestContract.ACTIVITY_CLASS_NAME)
            putExtra(PermissionRequestContract.EXTRA_PACKAGE_NAME, packageName)
            putExtra(PermissionRequestContract.EXTRA_PERMISSION_NAME, permissionName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return false
    }

    private fun syncRunningApps() {
        runningAppsState.value = processSlotManager.snapshotRunningApps()
    }

    private suspend fun syncPackageRunningState(packageName: String) {
        if (repository.getApp(packageName) == null) return
        val isRunning = runningAppsState.value.any { it.packageName == packageName }
        repository.setRunning(packageName, isRunning)
    }

    private fun syncRunningNotification() {
        val runningApps = runningAppsState.value
        val notificationManager = NotificationManagerCompat.from(context)
        if (!settingsRepository.currentSettings().showRunningAppsNotification || runningApps.isEmpty()) {
            notificationManager.cancel(RUNNING_APPS_NOTIFICATION_ID)
            return
        }
        ensureNotificationChannel()
        val primary = runningApps.first()
        val contentText = runningApps.joinToString(limit = 3) { "${it.packageName} (u${it.userId})" }
        val stopIntent = Intent().apply {
            component = ComponentName(context.packageName, ACTION_RECEIVER_CLASS_NAME)
            action = ACTION_STOP_APP
            putExtra(EXTRA_PACKAGE_NAME, primary.packageName)
            putExtra(EXTRA_USER_ID, primary.userId)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            primary.packageName.hashCode() * 31 + primary.userId,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, RUNNING_APPS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Evoke 应用运行中")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止 ${primary.packageName}", stopPendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    runningApps.joinToString(separator = "\n") {
                        "${it.packageName} · user ${it.userId} · ${it.processName}"
                    }
                )
            )
            .build()
        notificationManager.notify(RUNNING_APPS_NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            RUNNING_APPS_CHANNEL_ID,
            "Evoke Running Apps",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    fun refreshRunningAppsNotification() {
        syncRunningNotification()
    }

    companion object {
        private const val TAG = "EvokeCore"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_LABEL = "extra_label"
        private const val RUNNING_APPS_CHANNEL_ID = "running_apps"
        private const val RUNNING_APPS_NOTIFICATION_ID = 1001
        private const val ACTION_RECEIVER_CLASS_NAME = "com.smartdone.vm.runtime.RunningAppsActionReceiver"
        const val ACTION_STOP_APP = "com.smartdone.vm.action.STOP_RUNNING_APP"
    }
}
