package com.smartdone.vm.core.virtual.client.hook

import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartdone.vm.core.virtual.server.EvokePackageManagerService
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityManagerHook @Inject constructor(
    private val serviceFetcher: EvokeServiceFetcher
) {
    private var installed = false
    private var hostPackageName: String? = null
    private var evokePackageName: String? = null
    private var evokeUserId: Int = 0

    fun install(context: Context, packageName: String, userId: Int) {
        installed = true
        hostPackageName = context.packageName
        evokePackageName = packageName
        evokeUserId = userId
        Log.d("ActivityManagerHook", "Activity manager hook installed")
    }

    fun rewriteForStub(intent: Intent, stubPackage: String, stubClassName: String): Intent =
        Intent(intent).apply {
            setClassName(stubPackage, stubClassName)
        }

    fun startActivity(intent: Intent, userId: Int): Intent? {
        val packageName = intent.component?.packageName ?: evokePackageName ?: return null
        val launch = serviceFetcher.activityManager()
            ?.startActivity(packageName, userId, intent.component?.className)
            ?: return null
        val stubClassName = "com.smartdone.vm.stub.StubActivity_P${launch.getInt("slotId")}_A0"
        return rewriteForStub(
            intent = intent,
            stubPackage = hostPackageName ?: packageName,
            stubClassName = stubClassName
        )
    }

    fun startService(serviceName: String, userId: Int) =
        evokePackageName?.let { serviceFetcher.activityManager()?.startService(it, userId, serviceName) }

    fun bindService(serviceName: String, userId: Int) =
        evokePackageName?.let { serviceFetcher.activityManager()?.bindService(it, userId, serviceName) }

    fun rewriteStartServiceIntent(intent: Intent): Intent? {
        val targetPackage = intent.component?.packageName ?: intent.`package` ?: evokePackageName ?: return null
        if (targetPackage != evokePackageName) return null
        val targetService = intent.component?.className ?: return null
        val route = startService(targetService, evokeUserId) ?: return null
        val stubClassName = "com.smartdone.vm.stub.StubService_P${route.getInt("slotId")}"
        return rewriteForStub(
            intent = intent,
            stubPackage = hostPackageName ?: targetPackage,
            stubClassName = stubClassName
        ).apply {
            putExtra(EXTRA_ORIGINAL_SERVICE, targetService)
        }
    }

    fun rewriteBindServiceIntent(intent: Intent): Intent? {
        val targetPackage = intent.component?.packageName ?: intent.`package` ?: evokePackageName ?: return null
        if (targetPackage != evokePackageName) return null
        val targetService = intent.component?.className ?: return null
        if (!shouldRewriteBindService(targetService)) {
            Log.i("ActivityManagerHook", "Skipping bindService rewrite for $targetService")
            return null
        }
        val route = bindService(targetService, evokeUserId) ?: return null
        val stubClassName = "com.smartdone.vm.stub.StubService_P${route.getInt("slotId")}"
        return rewriteForStub(
            intent = intent,
            stubPackage = hostPackageName ?: targetPackage,
            stubClassName = stubClassName
        ).apply {
            putExtra(EXTRA_ORIGINAL_SERVICE, targetService)
        }
    }

    fun rewriteActivityIntent(intent: Intent): Intent? {
        val targetPackage = intent.component?.packageName
            ?: intent.`package`
            ?: serviceFetcher.packageManager()?.resolveIntentRoute(
                android.os.Bundle().apply {
                    putString(EvokePackageManagerService.KEY_ACTION, intent.action)
                    putStringArrayList(
                        EvokePackageManagerService.KEY_CATEGORIES,
                        ArrayList(intent.categories.orEmpty())
                    )
                    putString(EvokePackageManagerService.KEY_SCHEME, intent.data?.scheme)
                    putString(EvokePackageManagerService.KEY_HOST, intent.data?.host)
                    putString(EvokePackageManagerService.KEY_MIME_TYPE, intent.type)
                    putString(EvokePackageManagerService.KEY_PACKAGE_NAME, evokePackageName)
                }
            )?.getString("packageName")
            ?: return null
        if (targetPackage != evokePackageName) return null
        return startActivity(intent, evokeUserId)
    }

    fun isInstalled(): Boolean = installed

    fun shouldSilentlyDenyBind(intent: Intent): Boolean {
        return false
    }

    private fun shouldRewriteBindService(serviceName: String): Boolean {
        return true
    }

    companion object {
        private const val EXTRA_ORIGINAL_SERVICE = "com.smartdone.vm.extra.ORIGINAL_SERVICE"
    }
}
