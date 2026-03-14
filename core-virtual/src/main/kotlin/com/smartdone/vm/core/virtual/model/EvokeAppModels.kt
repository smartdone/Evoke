package com.smartdone.vm.core.virtual.model

import android.graphics.Bitmap
import android.net.Uri

data class ApkMetadata(
    val packageName: String,
    val label: String,
    val applicationClassName: String?,
    val versionCode: Long,
    val requestedPermissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val providers: List<String>,
    val receivers: List<String>,
    val iconBitmap: Bitmap?,
    val launcherActivity: String? = null,
    val activityComponents: List<ActivityComponentInfo> = emptyList(),
    val receiverComponents: List<ReceiverComponentInfo> = emptyList(),
    val providerComponents: List<ProviderComponentInfo> = emptyList()
)

data class ActivityComponentInfo(
    val className: String,
    val intentFilters: List<IntentFilterSpec> = emptyList(),
    val isLauncher: Boolean = false
)

data class ReceiverComponentInfo(
    val className: String,
    val intentFilters: List<IntentFilterSpec> = emptyList()
)

data class ProviderComponentInfo(
    val className: String,
    val authority: String
)

data class IntentFilterSpec(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSpecs: List<IntentDataSpec> = emptyList()
)

data class IntentDataSpec(
    val scheme: String? = null,
    val host: String? = null,
    val mimeType: String? = null
)

data class IntentMatchRequest(
    val action: String? = null,
    val categories: Set<String> = emptySet(),
    val scheme: String? = null,
    val host: String? = null,
    val mimeType: String? = null,
    val targetPackage: String? = null
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val nativeLibraryDir: String?
)

data class CopiedAppLayout(
    val packageName: String,
    val appDir: String,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val nativeLibDirs: List<String>
)

sealed interface CopyEvent {
    data class Progress(
        val message: String,
        val fraction: Float
    ) : CopyEvent

    data class Completed(
        val layout: CopiedAppLayout
    ) : CopyEvent
}

data class InstallProgress(
    val stage: String,
    val message: String,
    val fraction: Float
)

data class InstallResult(
    val packageName: String,
    val userId: Int,
    val label: String
)

data class StagedLaunchLayout(
    val packageName: String,
    val label: String,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val launcherActivity: String?,
    val applicationClassName: String?,
    val nativeLibDir: String,
    val optimizedDir: String
)

data class EvokeAppSummary(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val apkPath: String,
    val iconPath: String?,
    val installTime: Long,
    val isRunning: Boolean
)

data class EvokeAppInstanceSummary(
    val packageName: String,
    val userId: Int,
    val displayName: String,
    val createdTime: Long
)

data class EvokeAppPermissionSummary(
    val packageName: String,
    val permissionName: String,
    val isGranted: Boolean
)

data class EvokeAppDetails(
    val app: EvokeAppSummary,
    val instances: List<EvokeAppInstanceSummary>,
    val permissions: List<EvokeAppPermissionSummary>
)

data class EvokeAppGroupSummary(
    val app: EvokeAppSummary,
    val instances: List<EvokeAppInstanceSummary>
)

data class RunningAppRecord(
    val packageName: String,
    val userId: Int,
    val processName: String,
    val pid: Int,
    val startedAt: Long = 0L,
    val lastActiveAt: Long = 0L
)

data class StorageStats(
    val userId: Int,
    val dataBytes: Long,
    val cacheBytes: Long
)

data class InstallRequest(
    val installedPackageName: String? = null,
    val apkUri: Uri? = null
)
