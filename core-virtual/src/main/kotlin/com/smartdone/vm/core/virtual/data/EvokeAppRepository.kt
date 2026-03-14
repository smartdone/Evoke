package com.smartdone.vm.core.virtual.data

import com.smartdone.vm.core.virtual.data.db.EvokeAppDao
import com.smartdone.vm.core.virtual.data.db.EvokeAppEntity
import com.smartdone.vm.core.virtual.data.db.EvokeAppInstanceEntity
import com.smartdone.vm.core.virtual.data.db.EvokeAppPermissionEntity
import com.smartdone.vm.core.virtual.model.EvokeAppDetails
import com.smartdone.vm.core.virtual.model.EvokeAppGroupSummary
import com.smartdone.vm.core.virtual.model.EvokeAppInstanceSummary
import com.smartdone.vm.core.virtual.model.EvokeAppPermissionSummary
import com.smartdone.vm.core.virtual.model.EvokeAppSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EvokeAppRepository @Inject constructor(
    private val dao: EvokeAppDao
) {
    fun observeApps(): Flow<List<EvokeAppSummary>> =
        dao.observeApps().map { apps -> apps.map(EvokeAppEntity::toSummary) }

    fun observeAppGroups(): Flow<List<EvokeAppGroupSummary>> =
        combine(
            dao.observeApps(),
            dao.observeAllInstances()
        ) { apps, instances ->
            val instancesByPackage = instances.groupBy { it.packageName }
            apps.map { app ->
                EvokeAppGroupSummary(
                    app = app.toSummary(),
                    instances = instancesByPackage[app.packageName].orEmpty().map(EvokeAppInstanceEntity::toSummary)
                )
            }
        }

    fun observeAppDetails(packageName: String): Flow<EvokeAppDetails?> =
        combine(
            dao.observeApp(packageName),
            dao.observeInstances(packageName),
            dao.observePermissions(packageName)
        ) { app, instances, permissions ->
            app?.let {
                EvokeAppDetails(
                    app = it.toSummary(),
                    instances = instances.map(EvokeAppInstanceEntity::toSummary),
                    permissions = permissions.map(EvokeAppPermissionEntity::toSummary)
                )
            }
        }

    suspend fun getApp(packageName: String): EvokeAppSummary? =
        dao.getApp(packageName)?.toSummary()

    suspend fun getApps(): List<EvokeAppSummary> =
        dao.getApps().map(EvokeAppEntity::toSummary)

    suspend fun getPermissions(packageName: String): List<EvokeAppPermissionSummary> =
        dao.getPermissions(packageName).map(EvokeAppPermissionEntity::toSummary)

    suspend fun getInstances(packageName: String): List<EvokeAppInstanceSummary> =
        dao.getInstances(packageName).map(EvokeAppInstanceEntity::toSummary)

    suspend fun upsertInstalledApp(
        app: EvokeAppEntity,
        instances: List<EvokeAppInstanceEntity>,
        permissions: List<EvokeAppPermissionEntity>
    ) {
        dao.upsertApp(app)
        dao.upsertInstances(instances)
        dao.upsertPermissions(permissions)
    }

    suspend fun setRunning(packageName: String, isRunning: Boolean) {
        dao.updateRunning(packageName, isRunning)
    }

    suspend fun deleteApp(packageName: String) {
        dao.deletePermissions(packageName)
        dao.deleteInstances(packageName)
        dao.deleteApp(packageName)
    }

    suspend fun deleteInstance(packageName: String, userId: Int) {
        dao.deleteInstance(packageName, userId)
    }

    suspend fun renameInstance(packageName: String, userId: Int, displayName: String) {
        dao.updateInstanceName(packageName, userId, displayName)
    }
}

private fun EvokeAppEntity.toSummary() = EvokeAppSummary(
    packageName = packageName,
    label = label,
    versionCode = versionCode,
    apkPath = apkPath,
    iconPath = iconPath,
    installTime = installTime,
    isRunning = isRunning
)

private fun EvokeAppInstanceEntity.toSummary() = EvokeAppInstanceSummary(
    packageName = packageName,
    userId = userId,
    displayName = displayName,
    createdTime = createdTime
)

private fun EvokeAppPermissionEntity.toSummary() = EvokeAppPermissionSummary(
    packageName = packageName,
    permissionName = permissionName,
    isGranted = isGranted
)
