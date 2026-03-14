package com.smartdone.vm.core.virtual.data.db

import androidx.room.Entity

@Entity(
    tableName = "vapp_instances",
    primaryKeys = ["packageName", "userId"]
)
data class EvokeAppInstanceEntity(
    val packageName: String,
    val userId: Int,
    val displayName: String,
    val createdTime: Long
)
