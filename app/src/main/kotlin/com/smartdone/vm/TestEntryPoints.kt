package com.smartdone.vm

import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.install.ApkInstaller
import com.smartdone.vm.core.virtual.server.EvokePackageManagerService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VmAppEntryPoint {
    fun apkInstaller(): ApkInstaller
    fun evokeCore(): EvokeCore
    fun packageManagerService(): EvokePackageManagerService
}
