package com.smartdone.vm.core.virtual.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EvokeAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApp(entity: EvokeAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstances(instances: List<EvokeAppInstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermissions(permissions: List<EvokeAppPermissionEntity>)

    @Query("DELETE FROM vapps WHERE packageName = :packageName")
    suspend fun deleteApp(packageName: String)

    @Query("DELETE FROM vapp_instances WHERE packageName = :packageName")
    suspend fun deleteInstances(packageName: String)

    @Query("DELETE FROM vapp_instances WHERE packageName = :packageName AND userId = :userId")
    suspend fun deleteInstance(packageName: String, userId: Int)

    @Query("DELETE FROM vapp_permissions WHERE packageName = :packageName")
    suspend fun deletePermissions(packageName: String)

    @Query("SELECT * FROM vapps ORDER BY installTime DESC")
    fun observeApps(): Flow<List<EvokeAppEntity>>

    @Query("SELECT * FROM vapps WHERE packageName = :packageName LIMIT 1")
    fun observeApp(packageName: String): Flow<EvokeAppEntity?>

    @Query("SELECT * FROM vapp_instances WHERE packageName = :packageName ORDER BY userId ASC")
    fun observeInstances(packageName: String): Flow<List<EvokeAppInstanceEntity>>

    @Query("SELECT * FROM vapp_instances ORDER BY packageName ASC, userId ASC")
    fun observeAllInstances(): Flow<List<EvokeAppInstanceEntity>>

    @Query("SELECT * FROM vapp_permissions WHERE packageName = :packageName ORDER BY permissionName ASC")
    fun observePermissions(packageName: String): Flow<List<EvokeAppPermissionEntity>>

    @Query("SELECT * FROM vapps")
    suspend fun getApps(): List<EvokeAppEntity>

    @Query("SELECT * FROM vapps WHERE packageName = :packageName LIMIT 1")
    suspend fun getApp(packageName: String): EvokeAppEntity?

    @Query("SELECT * FROM vapp_instances")
    suspend fun getInstances(): List<EvokeAppInstanceEntity>

    @Query("SELECT * FROM vapp_instances WHERE packageName = :packageName ORDER BY userId ASC")
    suspend fun getInstances(packageName: String): List<EvokeAppInstanceEntity>

    @Query("SELECT * FROM vapp_permissions WHERE packageName = :packageName ORDER BY permissionName ASC")
    suspend fun getPermissions(packageName: String): List<EvokeAppPermissionEntity>

    @Query("UPDATE vapps SET isRunning = :isRunning WHERE packageName = :packageName")
    suspend fun updateRunning(packageName: String, isRunning: Boolean)

    @Query("UPDATE vapp_instances SET displayName = :displayName WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateInstanceName(packageName: String, userId: Int, displayName: String)
}
