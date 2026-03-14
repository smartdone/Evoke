package com.smartdone.vm.core.virtual.client.hook

import android.content.Context
import android.content.Intent
import android.util.Log
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

    fun install(context: Context, packageName: String) {
        installed = true
        hostPackageName = context.packageName
        evokePackageName = packageName
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

    fun isInstalled(): Boolean = installed
}
