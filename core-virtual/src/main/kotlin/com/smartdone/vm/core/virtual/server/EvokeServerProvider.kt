package com.smartdone.vm.core.virtual.server

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class EvokeServerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        METHOD_PACKAGE_MANAGER -> bundleWithBinder(serverEntryPoint().packageManagerService().asBinder())
        METHOD_ACTIVITY_MANAGER -> bundleWithBinder(serverEntryPoint().activityManagerService().asBinder())
        METHOD_CONTENT_PROVIDER -> bundleWithBinder(serverEntryPoint().contentProviderManager().asBinder())
        METHOD_BROADCAST -> bundleWithBinder(serverEntryPoint().broadcastManager().asBinder())
        METHOD_PERMISSION -> bundleWithBinder(serverEntryPoint().permissionDelegateService().asBinder())
        else -> Bundle.EMPTY
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServerProviderEntryPoint {
        fun packageManagerService(): EvokePackageManagerService
        fun activityManagerService(): EvokeActivityManagerService
        fun contentProviderManager(): EvokeContentProviderManager
        fun broadcastManager(): EvokeBroadcastManager
        fun permissionDelegateService(): PermissionDelegateService
    }

    companion object {
        const val METHOD_PACKAGE_MANAGER = "package_manager"
        const val METHOD_ACTIVITY_MANAGER = "activity_manager"
        const val METHOD_CONTENT_PROVIDER = "content_provider"
        const val METHOD_BROADCAST = "broadcast"
        const val METHOD_PERMISSION = "permission"
        const val KEY_BINDER = "binder"

        private fun bundleWithBinder(binder: IBinder): Bundle =
            Bundle().apply { putBinder(KEY_BINDER, binder) }
    }

    private fun serverEntryPoint(): ServerProviderEntryPoint =
        EntryPointAccessors.fromApplication(
            context?.applicationContext ?: error("Application context missing"),
            ServerProviderEntryPoint::class.java
        )
}
