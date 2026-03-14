package com.smartdone.vm.core.virtual.client.hook

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.smartdone.vm.core.virtual.server.EvokePackageManagerService
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageManagerHook @Inject constructor(
    private val serviceFetcher: EvokeServiceFetcher
) {
    private var installed = false
    private var evokePackageName: String? = null

    fun install(packageName: String) {
        installed = true
        evokePackageName = packageName
        Log.d("PackageManagerHook", "Package manager hook installed")
    }

    fun evokePackageInfo(packageName: String): Bundle? =
        serviceFetcher.packageManager()?.getPackageInfo(packageName)

    fun evokeApplicationInfo(packageName: String = evokePackageName.orEmpty()): Bundle? =
        serviceFetcher.packageManager()?.getApplicationInfo(packageName)

    fun installedPackages(): List<String> =
        serviceFetcher.packageManager()?.installedPackages?.toList().orEmpty()

    fun resolveActivity(action: String?, packageName: String = evokePackageName.orEmpty()): Bundle? =
        serviceFetcher.packageManager()?.resolveIntent(action, packageName)

    fun resolveActivity(intent: Intent, packageName: String = evokePackageName.orEmpty()): Bundle? =
        serviceFetcher.packageManager()?.resolveIntentRoute(
            Bundle().apply {
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

    fun isInstalled(): Boolean = installed
}
