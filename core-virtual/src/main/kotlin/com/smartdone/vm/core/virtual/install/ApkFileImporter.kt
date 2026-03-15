package com.smartdone.vm.core.virtual.install

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.smartdone.vm.core.virtual.model.CopiedAppLayout
import com.smartdone.vm.core.virtual.model.CopyEvent
import com.smartdone.vm.core.virtual.model.StagedLaunchLayout
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class ApkFileImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apkParser: ApkParser,
    private val sandboxPath: SandboxPath
) {
    fun importFromUri(uri: Uri): Flow<CopyEvent> = flow {
        emit(CopyEvent.Progress("Reading APK file", 0.1f))
        val tempFile = File(sandboxPath.tempImportDir(), "${System.currentTimeMillis()}.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open APK uri: $uri")

        emit(CopyEvent.Progress("Parsing APK", 0.45f))
        val metadata = apkParser.parseArchive(tempFile.absolutePath)
            ?: error("Unable to parse APK: $uri")
        sandboxPath.ensureAppStructure(metadata.packageName)
        val target = sandboxPath.apkPath(metadata.packageName)
        tempFile.copyTo(target, overwrite = true)
        val splitTargets = copyRelatedSplitApks(
            sourceUri = uri,
            packageName = metadata.packageName,
            splitTargetDir = sandboxPath.splitDir(metadata.packageName)
        )
        sandboxPath.sealArchiveIfManaged(target.absolutePath)
        extractNativeLibraries(target, sandboxPath.nativeLibDir(metadata.packageName))
        tempFile.delete()
        emit(
            CopyEvent.Completed(
                CopiedAppLayout(
                    packageName = metadata.packageName,
                    appDir = sandboxPath.appDir(metadata.packageName).absolutePath,
                    baseApkPath = target.absolutePath,
                    splitApkPaths = splitTargets,
                    nativeLibDirs = emptyList()
                )
            )
        )
    }

    suspend fun stageForLaunch(uri: Uri): StagedLaunchLayout {
        val tempFile = File(sandboxPath.tempImportDir(), "launch_${System.currentTimeMillis()}.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open APK uri: $uri")

        val metadata = apkParser.parseArchive(tempFile.absolutePath)
            ?: error("Unable to parse APK: $uri")
        sandboxPath.ensureAppStructure(metadata.packageName)
        val launchId = System.currentTimeMillis().toString()
        val target = sandboxPath.stagedLaunchApkPath(metadata.packageName, launchId)
        tempFile.copyTo(target, overwrite = true)
        val splitTargets = copyRelatedSplitApks(
            sourceUri = uri,
            packageName = metadata.packageName,
            splitTargetDir = sandboxPath.stagedLaunchSplitDir(metadata.packageName, launchId)
        )
        sandboxPath.sealStandaloneArchive(target)
        val nativeLibDir = sandboxPath.stagedLaunchNativeLibDir(metadata.packageName, launchId)
        extractNativeLibraries(target, nativeLibDir)
        tempFile.delete()
        Log.i(
            TAG,
            "Prepared staged launch package=${metadata.packageName} " +
                "launcher=${metadata.launcherActivity ?: metadata.activities.firstOrNull()} " +
                "baseApk=${target.absolutePath} splitCount=${splitTargets.size}"
        )
        return StagedLaunchLayout(
            packageName = metadata.packageName,
            label = metadata.label.ifBlank { metadata.packageName },
            baseApkPath = target.absolutePath,
            splitApkPaths = splitTargets,
            launcherActivity = metadata.launcherActivity ?: metadata.activities.firstOrNull(),
            applicationClassName = metadata.applicationClassName,
            nativeLibDir = nativeLibDir.absolutePath,
            optimizedDir = sandboxPath.stagedLaunchOptimizedDir(metadata.packageName, launchId).absolutePath
        )
    }

    private fun extractNativeLibraries(apkFile: File, targetDir: File) {
        ZipFile(apkFile).use { zip ->
            val nativeEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .mapNotNull { entry ->
                    val segments = entry.name.split('/')
                    val abi = segments.getOrNull(1) ?: return@mapNotNull null
                    abi to entry
                }
                .toList()
            if (nativeEntries.isEmpty()) {
                return
            }
            val availableAbis = nativeEntries.map { it.first }.distinct()
            val selectedAbi = Build.SUPPORTED_ABIS.firstOrNull(availableAbis::contains)
                ?: availableAbis.first()
            nativeEntries.asSequence()
                .filter { it.first == selectedAbi }
                .forEach { (_, entry) ->
                    val target = File(targetDir, File(entry.name).name)
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            Log.i(
                TAG,
                "Extracted native libraries abi=$selectedAbi count=${nativeEntries.count { it.first == selectedAbi }} apk=${apkFile.name}"
            )
        }
    }

    private fun copyRelatedSplitApks(
        sourceUri: Uri,
        packageName: String,
        splitTargetDir: File
    ): List<String> {
        val sourceFile = sourceUri.path
            ?.takeIf { sourceUri.scheme == ContentResolver.SCHEME_FILE }
            ?.let(::File)
            ?.takeIf(File::exists)
            ?: return emptyList()
        return sourceFile.parentFile
            ?.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "apk" && it.name != sourceFile.name }
            .filter { candidate ->
                apkParser.parseArchive(candidate.absolutePath)?.packageName == packageName
            }
            .sortedBy(File::getName)
            .map { splitFile ->
                val target = File(splitTargetDir, splitFile.name)
                splitFile.copyTo(target, overwrite = true)
                sandboxPath.sealStandaloneArchive(target)
                target.absolutePath
            }
            .toList()
    }

    companion object {
        private const val TAG = "ApkFileImporter"
    }
}
