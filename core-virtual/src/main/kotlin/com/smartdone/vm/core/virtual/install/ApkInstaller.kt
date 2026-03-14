package com.smartdone.vm.core.virtual.install

import android.graphics.Bitmap
import android.net.Uri
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.data.db.EvokeAppEntity
import com.smartdone.vm.core.virtual.data.db.EvokeAppInstanceEntity
import com.smartdone.vm.core.virtual.data.db.EvokeAppPermissionEntity
import com.smartdone.vm.core.virtual.model.CopyEvent
import com.smartdone.vm.core.virtual.model.InstallProgress
import com.smartdone.vm.core.virtual.model.InstallResult
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

@Singleton
class ApkInstaller @Inject constructor(
    private val installedAppScanner: InstalledAppScanner,
    private val appCopier: AppCopier,
    private val apkFileImporter: ApkFileImporter,
    private val apkParser: ApkParser,
    private val repository: EvokeAppRepository,
    private val sandboxPath: SandboxPath
) {
    fun installFromInstalledApp(packageName: String): Flow<InstallProgress> = flow {
        val appInfo = installedAppScanner.scan(includeSystemApps = true)
            .firstOrNull { it.packageName == packageName }
            ?: error("Installed app not found: $packageName")
        var result: InstallResult? = null
        appCopier.copyInstalledApp(appInfo).collect { event ->
            when (event) {
                is CopyEvent.Progress -> emit(InstallProgress("copy", event.message, event.fraction))
                is CopyEvent.Completed -> {
                    emit(InstallProgress("parse", "Parsing copied APK", 0.88f))
                    result = finalizeInstall(event.layout.packageName, event.layout.baseApkPath)
                    emit(InstallProgress("done", "Installed ${event.layout.packageName}", 1f))
                }
            }
        }
        checkNotNull(result)
    }

    fun installFromFile(uri: Uri): Flow<InstallProgress> = flow {
        var result: InstallResult? = null
        apkFileImporter.importFromUri(uri).collect { event ->
            when (event) {
                is CopyEvent.Progress -> emit(InstallProgress("copy", event.message, event.fraction))
                is CopyEvent.Completed -> {
                    emit(InstallProgress("parse", "Saving package metadata", 0.9f))
                    result = finalizeInstall(event.layout.packageName, event.layout.baseApkPath)
                    emit(InstallProgress("done", "Installed ${event.layout.packageName}", 1f))
                }
            }
        }
        checkNotNull(result)
    }

    private suspend fun finalizeInstall(packageName: String, apkPath: String): InstallResult {
        val metadata = apkParser.parseArchive(apkPath) ?: error("Unable to parse copied APK: $apkPath")
        sandboxPath.ensureAppStructure(metadata.packageName)
        sandboxPath.sealPackageArtifacts(metadata.packageName)
        val iconPath = metadata.iconBitmap?.let { saveIcon(packageName, it) }
        val appEntity = EvokeAppEntity(
            packageName = metadata.packageName,
            label = metadata.label.ifBlank { metadata.packageName },
            versionCode = metadata.versionCode,
            apkPath = apkPath,
            iconPath = iconPath,
            installTime = System.currentTimeMillis(),
            isRunning = false
        )
        val instances = listOf(
            EvokeAppInstanceEntity(
                packageName = metadata.packageName,
                userId = 0,
                displayName = metadata.label.ifBlank { metadata.packageName },
                createdTime = System.currentTimeMillis()
            )
        )
        val permissions = metadata.requestedPermissions.map { permission ->
            EvokeAppPermissionEntity(
                packageName = metadata.packageName,
                permissionName = permission,
                isGranted = false
            )
        }
        repository.upsertInstalledApp(appEntity, instances, permissions)
        return InstallResult(
            packageName = metadata.packageName,
            userId = 0,
            label = metadata.label
        )
    }

    private fun saveIcon(packageName: String, bitmap: Bitmap): String {
        val target = sandboxPath.iconPath(packageName)
        FileOutputStream(target).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return target.absolutePath
    }
}
