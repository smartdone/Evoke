package com.smartdone.vm.core.virtual.install

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.smartdone.vm.core.virtual.model.CopiedAppLayout
import com.smartdone.vm.core.virtual.model.CopyEvent
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
        sandboxPath.sealArchiveIfManaged(target.absolutePath)
        tempFile.delete()
        emit(
            CopyEvent.Completed(
                CopiedAppLayout(
                    packageName = metadata.packageName,
                    appDir = sandboxPath.appDir(metadata.packageName).absolutePath,
                    baseApkPath = target.absolutePath,
                    splitApkPaths = emptyList(),
                    nativeLibDirs = emptyList()
                )
            )
        )
    }
}
