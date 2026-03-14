package com.smartdone.vm.core.virtual.install

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.data.db.AppDatabase
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import kotlinx.coroutines.flow.collect

class InstallEvokeAppWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sandboxPath = SandboxPath(applicationContext)
        val repository = EvokeAppRepository(AppDatabase.get(applicationContext).evokeAppDao())
        val apkParser = ApkParser(applicationContext)
        val installer = ApkInstaller(
            installedAppScanner = InstalledAppScanner(applicationContext),
            appCopier = AppCopier(sandboxPath),
            apkFileImporter = ApkFileImporter(applicationContext, apkParser, sandboxPath),
            apkParser = apkParser,
            repository = repository,
            sandboxPath = sandboxPath
        )
        val installedPackage = inputData.getString(KEY_PACKAGE_NAME)
        val uri = inputData.getString(KEY_APK_URI)?.let(Uri::parse)
        return runCatching {
            when {
                installedPackage != null -> installer.installFromInstalledApp(installedPackage).collect { }
                uri != null -> installer.installFromFile(uri).collect { }
                else -> error("Missing install source")
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.failure(workDataOf(KEY_ERROR to (it.message ?: "Unknown error"))) }
        )
    }

    companion object {
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_APK_URI = "apk_uri"
        const val KEY_ERROR = "error"

        fun enqueueInstalledApp(context: Context, packageName: String) =
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<InstallEvokeAppWorker>()
                    .setInputData(Data.Builder().putString(KEY_PACKAGE_NAME, packageName).build())
                    .build()
            )

        fun enqueueApkUri(context: Context, uri: Uri) =
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<InstallEvokeAppWorker>()
                    .setInputData(Data.Builder().putString(KEY_APK_URI, uri.toString()).build())
                    .build()
            )
    }
}
