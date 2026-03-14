package com.smartdone.vm.core.virtual.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vapps")
data class EvokeAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val versionCode: Long,
    val apkPath: String,
    val iconPath: String?,
    val installTime: Long,
    val isRunning: Boolean
)
