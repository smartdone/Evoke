package com.smartdone.vm.core.virtual.data.db

import androidx.room.Entity

@Entity(
    tableName = "vapp_permissions",
    primaryKeys = ["packageName", "permissionName"]
)
data class EvokeAppPermissionEntity(
    val packageName: String,
    val permissionName: String,
    val isGranted: Boolean
)
