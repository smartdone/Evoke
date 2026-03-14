package com.smartdone.vm.core.virtual.client.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartdone.vm.core.virtual.permission.PermissionRequestContract
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionHook @Inject constructor(
    private val serviceFetcher: EvokeServiceFetcher
) {
    private var installed = false
    private var appContext: Context? = null
    private var evokePackageName: String? = null

    fun install(context: Context, packageName: String) {
        installed = true
        appContext = context.applicationContext
        evokePackageName = packageName
        Log.d("PermissionHook", "Permission hook installed")
    }

    fun checkPermission(packageName: String, permissionName: String): Boolean =
        serviceFetcher.permissionDelegate()?.checkPermission(packageName, permissionName) ?: false

    fun checkSelfPermission(permissionName: String): Boolean =
        checkPermission(evokePackageName.orEmpty(), permissionName)

    fun requestPermission(
        packageName: String = evokePackageName.orEmpty(),
        permissionName: String
    ): Boolean {
        val alreadyGranted =
            serviceFetcher.permissionDelegate()?.requestPermission(packageName, permissionName) ?: false
        if (!alreadyGranted && packageName.isNotBlank()) {
            launchPermissionRequest(packageName, permissionName)
        }
        return alreadyGranted
    }

    fun requestPermissions(vararg permissionNames: String): Map<String, Boolean> =
        permissionNames.associateWith { requestPermission(permissionName = it) }

    fun grantedPermissions(packageName: String = evokePackageName.orEmpty()): List<String> =
        serviceFetcher.permissionDelegate()?.getGrantedPermissions(packageName)?.toList().orEmpty()

    fun onPermissionResult(
        permissionName: String,
        granted: Boolean,
        packageName: String = evokePackageName.orEmpty()
    ) {
        if (packageName.isBlank()) return
        serviceFetcher.permissionDelegate()?.let {
            if (granted || permissionName in it.getGrantedPermissions(packageName)) {
                return
            }
        }
        launchPermissionRequest(packageName, permissionName)
    }

    private fun launchPermissionRequest(packageName: String, permissionName: String) {
        val context = appContext ?: return
        val intent = Intent().apply {
            component = ComponentName(context.packageName, PermissionRequestContract.ACTIVITY_CLASS_NAME)
            putExtra(PermissionRequestContract.EXTRA_PACKAGE_NAME, packageName)
            putExtra(PermissionRequestContract.EXTRA_PERMISSION_NAME, permissionName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
