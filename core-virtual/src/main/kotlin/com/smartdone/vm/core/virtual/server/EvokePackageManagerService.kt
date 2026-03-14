package com.smartdone.vm.core.virtual.server

import android.os.Bundle
import com.smartdone.vm.core.virtual.aidl.IEvokePackageManager
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.install.ApkParser
import com.smartdone.vm.core.virtual.model.ApkMetadata
import com.smartdone.vm.core.virtual.model.IntentMatchRequest
import com.smartdone.vm.core.virtual.util.ManifestIntentMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@Singleton
class EvokePackageManagerService @Inject constructor(
    private val repository: EvokeAppRepository,
    private val apkParser: ApkParser
) : IEvokePackageManager.Stub() {
    private val packageCache = mutableMapOf<String, ApkMetadata>()

    override fun getPackageInfo(packageName: String): Bundle = runBlocking {
        val app = repository.getApp(packageName) ?: return@runBlocking Bundle.EMPTY
        val metadata = loadMetadata(app.packageName, app.apkPath)
        Bundle().apply {
            putString("packageName", app.packageName)
            putString("label", app.label)
            putLong("versionCode", app.versionCode)
            putString("apkPath", app.apkPath)
            putStringArrayList("activities", ArrayList(metadata?.activities.orEmpty()))
            putStringArrayList("services", ArrayList(metadata?.services.orEmpty()))
            putStringArrayList("providers", ArrayList(metadata?.providers.orEmpty()))
            putStringArrayList("receivers", ArrayList(metadata?.receivers.orEmpty()))
        }
    }

    override fun getInstalledPackages(): MutableList<String> = runBlocking {
        repository.getApps().mapTo(mutableListOf()) { it.packageName }
    }

    override fun getApplicationInfo(packageName: String): Bundle = runBlocking {
        val app = repository.getApp(packageName) ?: return@runBlocking Bundle.EMPTY
        val metadata = loadMetadata(app.packageName, app.apkPath)
        Bundle().apply {
            putString("packageName", app.packageName)
            putString("label", app.label)
            putString("iconPath", app.iconPath)
            putString("applicationClassName", metadata?.applicationClassName)
            putStringArrayList(
                "requestedPermissions",
                ArrayList(metadata?.requestedPermissions.orEmpty())
            )
        }
    }

    override fun resolveIntent(action: String?, packageName: String?): Bundle = runBlocking {
        resolveIntentRoute(
            Bundle().apply {
                putString(KEY_ACTION, action)
                putString(KEY_PACKAGE_NAME, packageName)
            }
        )
    }

    override fun resolveIntentRoute(request: Bundle): Bundle = runBlocking {
        val matchRequest = IntentMatchRequest(
            action = request.getString(KEY_ACTION),
            categories = request.getStringArrayList(KEY_CATEGORIES)?.toSet().orEmpty(),
            scheme = request.getString(KEY_SCHEME),
            host = request.getString(KEY_HOST),
            mimeType = request.getString(KEY_MIME_TYPE),
            targetPackage = request.getString(KEY_PACKAGE_NAME)
        )
        val apps = if (matchRequest.targetPackage.isNullOrBlank()) {
            repository.getApps()
        } else {
            listOfNotNull(repository.getApp(matchRequest.targetPackage.orEmpty()))
        }
        apps.mapNotNull { app ->
            val metadata = loadMetadata(app.packageName, app.apkPath) ?: return@mapNotNull null
            val resolution = ManifestIntentMatcher.resolveActivityResolution(metadata, matchRequest)
                ?: return@mapNotNull null
            app to resolution
        }.maxWithOrNull(
            compareBy<Pair<com.smartdone.vm.core.virtual.model.EvokeAppSummary, ManifestIntentMatcher.ActivityResolution>>(
                { it.second.score },
                { if (it.second.component.isLauncher) 1 else 0 }
            )
        )?.let { (app, resolution) ->
            Bundle().apply {
                putString("packageName", app.packageName)
                putString("activityName", resolution.component.className)
                putString("action", matchRequest.action)
                putBoolean("isLauncher", resolution.component.isLauncher)
            }
        } ?: Bundle.EMPTY
    }

    suspend fun warmUpPackageCache() {
        repository.getApps().forEach { app ->
            loadMetadata(app.packageName, app.apkPath)
        }
    }

    private suspend fun loadMetadata(packageName: String, apkPath: String): ApkMetadata? {
        packageCache[packageName]?.let { return it }
        return withContext(Dispatchers.Default) { apkParser.parseArchive(apkPath) }
            ?.also { packageCache[packageName] = it }
    }

    companion object {
        const val KEY_ACTION = "action"
        const val KEY_CATEGORIES = "categories"
        const val KEY_SCHEME = "scheme"
        const val KEY_HOST = "host"
        const val KEY_MIME_TYPE = "mimeType"
        const val KEY_PACKAGE_NAME = "packageName"
    }
}
