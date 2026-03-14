package com.smartdone.vm.core.virtual.client.hook

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Singleton
class VirtualPackageArchiveResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: EvokeAppRepository,
    private val sandboxPath: SandboxPath
) {
    private var currentPackageName: String? = null
    private var currentUserId: Int = 0
    private var currentApkPathOverride: String? = null
    private val packageCache = mutableMapOf<String, PackageInfo>()

    fun prepare(packageName: String, userId: Int, apkPathOverride: String? = null) {
        if (
            currentPackageName != packageName ||
            currentUserId != userId ||
            currentApkPathOverride != apkPathOverride
        ) {
            packageCache.clear()
        }
        currentPackageName = packageName
        currentUserId = userId
        currentApkPathOverride = apkPathOverride
        packageInfo(packageName)?.let { info ->
            val discoveryService = info.services
                ?.firstOrNull { it.name == "com.google.firebase.components.ComponentDiscoveryService" }
            Log.d(
                TAG,
                "Prepared archive package=$packageName services=${info.services?.size ?: 0} " +
                    "discoveryMetaKeys=${discoveryService?.metaData?.keySet()?.sorted() ?: emptyList<String>()}"
            )
        }
    }

    fun packageInfo(packageName: String): PackageInfo? {
        if (packageName != currentPackageName) return null
        packageCache[packageName]?.let { return copyParcelable(it, PackageInfo.CREATOR) }
        val loaded = loadPackageInfo(packageName) ?: return null
        packageCache[packageName] = loaded
        return copyParcelable(loaded, PackageInfo.CREATOR)
    }

    fun applicationInfo(packageName: String): ApplicationInfo? =
        packageInfo(packageName)?.applicationInfo?.let { copyParcelable(it, ApplicationInfo.CREATOR) }

    fun activityInfo(componentName: ComponentName): ActivityInfo? =
        packageInfo(componentName.packageName)
            ?.activities
            ?.firstOrNull { it.name == componentName.className }
            ?.let { copyParcelable(it, ActivityInfo.CREATOR) }

    fun serviceInfo(componentName: ComponentName): ServiceInfo? =
        packageInfo(componentName.packageName)
            ?.services
            ?.firstOrNull { it.name == componentName.className }
            ?.let { copyParcelable(it, ServiceInfo.CREATOR) }

    fun providerInfo(componentName: ComponentName): ProviderInfo? =
        packageInfo(componentName.packageName)
            ?.providers
            ?.firstOrNull { it.name == componentName.className }
            ?.let { copyParcelable(it, ProviderInfo.CREATOR) }

    private fun loadPackageInfo(packageName: String): PackageInfo? = runBlocking {
        val apkPath = if (packageName == currentPackageName && !currentApkPathOverride.isNullOrBlank()) {
            currentApkPathOverride
        } else {
            repository.getApp(packageName)?.apkPath
        } ?: return@runBlocking null
        val flags = PackageManager.PackageInfoFlags.of(
            (
                PackageManager.GET_ACTIVITIES.toLong() or
                    PackageManager.GET_SERVICES.toLong() or
                    PackageManager.GET_PROVIDERS.toLong() or
                    PackageManager.GET_RECEIVERS.toLong() or
                    PackageManager.GET_PERMISSIONS.toLong() or
                    PackageManager.GET_META_DATA.toLong()
                )
        )
        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkPath, flags)
            ?: return@runBlocking null
        val installedInfo = runCatching {
            context.packageManager.getPackageInfo(packageName, flags)
        }.getOrNull()
        val result = copyParcelable(archiveInfo, PackageInfo.CREATOR)
        val splitApkPaths = resolveSplitApkPaths(packageName, apkPath)
        val applicationInfo = buildVirtualApplicationInfo(
            packageName = packageName,
            apkPath = apkPath,
            splitApkPaths = splitApkPaths,
            archiveInfo = result.applicationInfo ?: installedInfo?.applicationInfo,
            installedInfo = installedInfo?.applicationInfo
        )
        result.packageName = packageName
        result.applicationInfo = applicationInfo
        result.sharedUserId = installedInfo?.sharedUserId ?: result.sharedUserId
        result.sharedUserLabel = installedInfo?.sharedUserLabel ?: result.sharedUserLabel
        result.firstInstallTime = installedInfo?.firstInstallTime ?: result.firstInstallTime
        result.lastUpdateTime = installedInfo?.lastUpdateTime ?: result.lastUpdateTime
        result.signatures = installedInfo?.signatures ?: result.signatures
        result.signingInfo = installedInfo?.signingInfo ?: result.signingInfo
        result.activities?.forEach { it.applicationInfo = applicationInfo }
        result.services?.forEach { it.applicationInfo = applicationInfo }
        result.providers?.forEach { it.applicationInfo = applicationInfo }
        result.receivers?.forEach { it.applicationInfo = applicationInfo }
        result
    }

    private fun buildVirtualApplicationInfo(
        packageName: String,
        apkPath: String,
        splitApkPaths: List<String>,
        archiveInfo: ApplicationInfo?,
        installedInfo: ApplicationInfo?
    ): ApplicationInfo {
        val info = archiveInfo?.let { copyParcelable(it, ApplicationInfo.CREATOR) }
            ?: installedInfo?.let { copyParcelable(it, ApplicationInfo.CREATOR) }
            ?: ApplicationInfo()
        val dataDir = sandboxPath.dataDir(currentUserId, packageName).absolutePath
        val nativeLibraryDir = resolveNativeLibraryDir(packageName, apkPath, installedInfo)
        info.packageName = packageName
        info.sourceDir = apkPath
        info.publicSourceDir = apkPath
        info.splitSourceDirs = splitApkPaths.toTypedArray()
        info.splitPublicSourceDirs = splitApkPaths.toTypedArray()
        info.dataDir = dataDir
        info.nativeLibraryDir = nativeLibraryDir
        info.processName = archiveInfo?.processName
            ?: installedInfo?.processName
            ?: packageName
        info.className = archiveInfo?.className ?: installedInfo?.className
        info.uid = Process.myUid()
        info.enabled = true
        if (info.name.isNullOrBlank()) {
            info.name = packageName
        }
        return info
    }

    private fun resolveSplitApkPaths(packageName: String, apkPath: String): List<String> {
        val overrideFile = File(apkPath)
        val parentSplits = File(overrideFile.parentFile, "splits")
        if (parentSplits.isDirectory) {
            return parentSplits.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "apk" }
                .sortedBy(File::getName)
                .map(File::getAbsolutePath)
        }
        return sandboxPath.managedSplitApkPaths(packageName)
            .takeIf { overrideFile.absolutePath == sandboxPath.apkPath(packageName).absolutePath }
            .orEmpty()
    }

    private fun resolveNativeLibraryDir(
        packageName: String,
        apkPath: String,
        installedInfo: ApplicationInfo?
    ): String {
        val overrideFile = File(apkPath)
        val siblingLibDir = File(overrideFile.parentFile, "libs")
        if (siblingLibDir.isDirectory) {
            return siblingLibDir.absolutePath
        }
        val managedBase = sandboxPath.apkPath(packageName)
        if (overrideFile.absolutePath == managedBase.absolutePath) {
            return sandboxPath.nativeLibDir(packageName).absolutePath
        }
        return installedInfo?.nativeLibraryDir ?: sandboxPath.nativeLibDir(packageName).absolutePath
    }

    companion object {
        private const val TAG = "VirtualPkgResolver"
    }
}

private fun <T : Parcelable> copyParcelable(value: T, creator: Parcelable.Creator<T>): T {
    val parcel = Parcel.obtain()
    return try {
        value.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        creator.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}
