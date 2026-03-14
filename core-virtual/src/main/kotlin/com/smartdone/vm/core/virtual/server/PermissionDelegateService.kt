package com.smartdone.vm.core.virtual.server

import com.smartdone.vm.core.virtual.aidl.IPermissionDelegate
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.data.db.EvokeAppEntity
import com.smartdone.vm.core.virtual.data.db.EvokeAppInstanceEntity
import com.smartdone.vm.core.virtual.data.db.EvokeAppPermissionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Singleton
class PermissionDelegateService @Inject constructor(
    private val repository: EvokeAppRepository
) : IPermissionDelegate.Stub() {
    private val pendingRequests = mutableMapOf<String, MutableSet<String>>()

    override fun requestPermission(packageName: String, permissionName: String): Boolean {
        val granted = checkPermission(packageName, permissionName)
        if (!granted) {
            pendingRequests.getOrPut(packageName) { linkedSetOf() }.add(permissionName)
        }
        return granted
    }

    override fun checkPermission(packageName: String, permissionName: String): Boolean = runBlocking {
        repository.getPermissions(packageName)
            .firstOrNull { it.permissionName == permissionName }
            ?.isGranted
            ?: false
    }

    override fun getGrantedPermissions(packageName: String): MutableList<String> = runBlocking {
        repository.getPermissions(packageName)
            .filter { it.isGranted }
            .mapTo(mutableListOf()) { it.permissionName }
    }

    fun consumePendingRequests(packageName: String): List<String> =
        pendingRequests.remove(packageName)?.toList().orEmpty()

    fun updatePermission(packageName: String, permissionName: String, granted: Boolean) = runBlocking {
        val app = repository.getApp(packageName) ?: return@runBlocking
        val permissions = repository.getPermissions(packageName).map {
            EvokeAppPermissionEntity(
                packageName = it.packageName,
                permissionName = it.permissionName,
                isGranted = if (it.permissionName == permissionName) granted else it.isGranted
            )
        }.ifEmpty {
            listOf(
                EvokeAppPermissionEntity(
                    packageName = packageName,
                    permissionName = permissionName,
                    isGranted = granted
                )
            )
        }
        repository.upsertInstalledApp(
            app = EvokeAppEntity(
                packageName = app.packageName,
                label = app.label,
                versionCode = app.versionCode,
                apkPath = app.apkPath,
                iconPath = app.iconPath,
                installTime = app.installTime,
                isRunning = app.isRunning
            ),
            instances = repository.getInstances(packageName).map {
                EvokeAppInstanceEntity(
                    packageName = it.packageName,
                    userId = it.userId,
                    displayName = it.displayName,
                    createdTime = it.createdTime
                )
            },
            permissions = permissions
        )
    }
}
