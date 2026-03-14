package com.smartdone.vm.core.virtual.sandbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SandboxPath @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val rootDir: File
        get() = File(context.dataDir, "VirtualEnv")

    fun root(): File = rootDir.ensure()

    fun appsDir(): File = File(root(), "apps").ensure()

    fun systemDir(): File = File(root(), "system").ensure()

    fun appDir(packageName: String): File = File(appsDir(), packageName).ensure()

    fun apkPath(packageName: String): File = File(appDir(packageName), "base.apk")

    fun splitDir(packageName: String): File = File(appDir(packageName), "splits").ensure()

    fun iconPath(packageName: String): File = File(appDir(packageName), "icon.png")

    fun nativeLibDir(packageName: String): File = File(appDir(packageName), "libs").ensure()

    fun optimizedDir(packageName: String): File = File(appDir(packageName), "oat").ensure()

    fun sealPackageArtifacts(packageName: String) {
        sealArchive(apkPath(packageName))
        splitDir(packageName).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "apk" }
            .forEach(::sealArchive)
    }

    fun sealArchiveIfManaged(apkPath: String) {
        val managedRoot = appsDir().absolutePath + File.separator
        val target = File(apkPath)
        if (!target.absolutePath.startsWith(managedRoot)) return
        sealArchive(target)
    }

    fun dataRoot(): File = File(root(), "data").ensure()

    fun userDir(userId: Int): File = File(dataRoot(), "user/$userId").ensure()

    fun dataDir(userId: Int, packageName: String): File = File(userDir(userId), packageName).ensure()

    fun deleteDataDir(userId: Int, packageName: String): Boolean =
        File(userDir(userId), packageName).deleteRecursively()

    fun deleteAllDataDirs(packageName: String): Boolean {
        val usersRoot = File(dataRoot(), "user")
        var deletedAny = false
        usersRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { userDir ->
                val appDir = File(userDir, packageName)
                if (appDir.exists()) {
                    deletedAny = appDir.deleteRecursively() || deletedAny
                }
            }
        return deletedAny
    }

    fun deleteAppDir(packageName: String): Boolean =
        File(appsDir(), packageName).deleteRecursively()

    fun cacheDir(userId: Int, packageName: String): File = File(dataDir(userId, packageName), "cache").ensure()

    fun clearCache(userId: Int, packageName: String): Boolean {
        val cacheDir = cacheDir(userId, packageName)
        val cleared = cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        return cleared
    }

    fun clearData(userId: Int, packageName: String): Boolean {
        val dataDir = File(userDir(userId), packageName)
        val cleared = dataDir.deleteRecursively()
        ensureAppStructure(packageName, userId)
        return cleared
    }

    fun dataSize(userId: Int, packageName: String): Long = File(userDir(userId), packageName).directorySize()

    fun cacheSize(userId: Int, packageName: String): Long = cacheDir(userId, packageName).directorySize()

    fun ensureAppStructure(packageName: String, userId: Int = 0) {
        appDir(packageName)
        splitDir(packageName)
        nativeLibDir(packageName)
        optimizedDir(packageName)
        listOf("files", "cache", "databases", "shared_prefs").forEach { name ->
            File(dataDir(userId, packageName), name).ensure()
        }
        systemDir()
    }

    fun tempImportDir(): File = File(root(), "tmp").ensure()

    fun stagedLaunchesDir(): File = File(root(), "launches").ensure()

    fun stagedLaunchDir(packageName: String, launchId: String): File =
        File(stagedLaunchesDir(), "${packageName}_$launchId").ensure()

    fun stagedLaunchApkPath(packageName: String, launchId: String): File =
        File(stagedLaunchDir(packageName, launchId), "base.apk")

    fun stagedLaunchSplitDir(packageName: String, launchId: String): File =
        File(stagedLaunchDir(packageName, launchId), "splits").ensure()

    fun stagedLaunchNativeLibDir(packageName: String, launchId: String): File =
        File(stagedLaunchDir(packageName, launchId), "libs").ensure()

    fun stagedLaunchOptimizedDir(packageName: String, launchId: String): File =
        File(stagedLaunchDir(packageName, launchId), "oat").ensure()

    fun managedSplitApkPaths(packageName: String): List<String> =
        splitDir(packageName).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "apk" }
            .sortedBy { it.name }
            .map(File::getAbsolutePath)

    fun sealStandaloneArchive(target: File) {
        sealArchive(target)
    }

    private fun sealArchive(target: File) {
        if (!target.exists() || !target.isFile) return
        target.setReadable(true, false)
        target.setWritable(false, false)
        if (target.canWrite()) {
            target.setReadOnly()
        }
    }
}

private fun File.ensure(): File = apply { mkdirs() }

private fun File.directorySize(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    return walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
}
