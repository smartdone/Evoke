package com.smartdone.vm.core.virtual.client

import android.content.Context
import com.smartdone.vm.core.nativeengine.NativeEngine
import com.smartdone.vm.core.virtual.client.hook.PermissionHook
import com.smartdone.vm.core.virtual.client.hook.ActivityManagerHook
import com.smartdone.vm.core.virtual.client.hook.BinderProxyManager
import com.smartdone.vm.core.virtual.client.hook.BroadcastHook
import com.smartdone.vm.core.virtual.client.hook.ContentProviderHook
import com.smartdone.vm.core.virtual.client.hook.ContextHook
import com.smartdone.vm.core.virtual.client.hook.DeviceInfoHook
import com.smartdone.vm.core.virtual.client.hook.ExternalServiceBinderHook
import com.smartdone.vm.core.virtual.client.hook.GuestRuntimeCrashShield
import com.smartdone.vm.core.virtual.client.hook.PackageManagerHook
import com.smartdone.vm.core.virtual.client.hook.VirtualPackageArchiveResolver
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EvokeAppClient @Inject constructor(
    private val binderProxyManager: BinderProxyManager,
    private val activityManagerHook: ActivityManagerHook,
    private val packageManagerHook: PackageManagerHook,
    private val contentProviderHook: ContentProviderHook,
    private val broadcastHook: BroadcastHook,
    private val contextHook: ContextHook,
    private val permissionHook: PermissionHook,
    private val deviceInfoHook: DeviceInfoHook,
    private val virtualPackageArchiveResolver: VirtualPackageArchiveResolver,
    private val serviceFetcher: EvokeServiceFetcher
) {
    private var initializedPackage: String? = null

    fun initialize(
        context: Context,
        packageName: String,
        userId: Int,
        apkPathOverride: String? = null
    ) {
        initializedPackage = packageName
        virtualPackageArchiveResolver.prepare(packageName, userId, apkPathOverride)
        binderProxyManager.install(context.packageName, context.packageManager)
        activityManagerHook.install(context, packageName, userId)
        packageManagerHook.install(packageName)
        contentProviderHook.install(context, userId)
        broadcastHook.install(packageName)
        contextHook.install(packageName, context.packageName)
        ExternalServiceBinderHook.install(packageName, context.packageName)
        GuestRuntimeCrashShield.install(packageName)
        permissionHook.install(context, packageName)
        deviceInfoHook.install(packageName, userId)
        serviceFetcher.packageManager()
        serviceFetcher.contentProviderManager()
        serviceFetcher.broadcastManager()
        serviceFetcher.permissionDelegate()
        NativeEngine.preload()
        NativeEngine.initGum()
        NativeEngine.installHooks(context.dataDir.absolutePath, packageName, userId)
        NativeEngine.setCurrentVApp(packageName, userId)
    }

    fun currentPackageName(): String? = initializedPackage
}
