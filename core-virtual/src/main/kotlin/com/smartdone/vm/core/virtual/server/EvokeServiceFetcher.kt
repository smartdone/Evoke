package com.smartdone.vm.core.virtual.server

import android.content.Context
import android.os.IBinder
import com.smartdone.vm.core.virtual.aidl.IPermissionDelegate
import com.smartdone.vm.core.virtual.aidl.IEvokeActivityManager
import com.smartdone.vm.core.virtual.aidl.IEvokeBroadcastManager
import com.smartdone.vm.core.virtual.aidl.IEvokeContentProviderManager
import com.smartdone.vm.core.virtual.aidl.IEvokePackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EvokeServiceFetcher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val providerAuthority = "${context.packageName}.server"
    private val binderCache = mutableMapOf<String, IBinder?>()

    fun packageManager(): IEvokePackageManager? =
        fetchBinder(EvokeServerProvider.METHOD_PACKAGE_MANAGER)?.let(IEvokePackageManager.Stub::asInterface)

    fun activityManager(): IEvokeActivityManager? =
        fetchBinder(EvokeServerProvider.METHOD_ACTIVITY_MANAGER)?.let(IEvokeActivityManager.Stub::asInterface)

    fun contentProviderManager(): IEvokeContentProviderManager? =
        fetchBinder(EvokeServerProvider.METHOD_CONTENT_PROVIDER)
            ?.let(IEvokeContentProviderManager.Stub::asInterface)

    fun broadcastManager(): IEvokeBroadcastManager? =
        fetchBinder(EvokeServerProvider.METHOD_BROADCAST)
            ?.let(IEvokeBroadcastManager.Stub::asInterface)

    fun permissionDelegate(): IPermissionDelegate? =
        fetchBinder(EvokeServerProvider.METHOD_PERMISSION)?.let(IPermissionDelegate.Stub::asInterface)

    fun invalidateCache() {
        binderCache.clear()
    }

    private fun fetchBinder(method: String): IBinder? {
        binderCache[method]?.let { return it }
        val bundle = context.contentResolver.call("content://$providerAuthority".toUri(), method, null, null)
        return bundle?.getBinder(EvokeServerProvider.KEY_BINDER)?.also { binderCache[method] = it }
    }
}

private fun String.toUri() = android.net.Uri.parse(this)
