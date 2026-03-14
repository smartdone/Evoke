package com.smartdone.vm.core.virtual.install

import com.smartdone.vm.core.virtual.model.CopiedAppLayout
import com.smartdone.vm.core.virtual.model.CopyEvent
import com.smartdone.vm.core.virtual.model.InstalledAppInfo
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class AppCopier @Inject constructor(
    private val sandboxPath: SandboxPath
) {
    fun copyInstalledApp(appInfo: InstalledAppInfo): Flow<CopyEvent> = flow {
        sandboxPath.ensureAppStructure(appInfo.packageName)
        val appDir = sandboxPath.appDir(appInfo.packageName)
        val splitDir = sandboxPath.splitDir(appInfo.packageName)
        val nativeDir = sandboxPath.nativeLibDir(appInfo.packageName)
        emit(CopyEvent.Progress("Copying base APK", 0.15f))
        val baseApk = sandboxPath.apkPath(appInfo.packageName)
        File(appInfo.sourceDir).copyTo(baseApk, overwrite = true)
        sandboxPath.sealArchiveIfManaged(baseApk.absolutePath)

        val splitTargets = mutableListOf<String>()
        appInfo.splitSourceDirs.forEachIndexed { index, splitSource ->
            emit(
                CopyEvent.Progress(
                    message = "Copying split APK ${index + 1}/${appInfo.splitSourceDirs.size}",
                    fraction = 0.25f + ((index + 1).toFloat() / (appInfo.splitSourceDirs.size.coerceAtLeast(1)) * 0.35f)
                )
            )
            val target = File(splitDir, File(splitSource).name)
            File(splitSource).copyTo(target, overwrite = true)
            sandboxPath.sealArchiveIfManaged(target.absolutePath)
            splitTargets += target.absolutePath
        }

        val copiedLibs = mutableListOf<String>()
        appInfo.nativeLibraryDir?.let { dir ->
            val sourceDir = File(dir)
            if (sourceDir.exists()) {
                emit(CopyEvent.Progress("Copying native libraries", 0.8f))
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.extension == "so" }
                    .forEach { file ->
                        val target = File(nativeDir, file.name)
                        file.copyTo(target, overwrite = true)
                        copiedLibs += target.absolutePath
                    }
            }
        }

        emit(
            CopyEvent.Completed(
                CopiedAppLayout(
                    packageName = appInfo.packageName,
                    appDir = appDir.absolutePath,
                    baseApkPath = baseApk.absolutePath,
                    splitApkPaths = splitTargets,
                    nativeLibDirs = copiedLibs
                )
            )
        )
    }
}
