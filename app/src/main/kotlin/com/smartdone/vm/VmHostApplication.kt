package com.smartdone.vm

import android.app.Application
import com.smartdone.vm.core.nativeengine.NativeEngine
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import com.smartdone.vm.runtime.NativeCompatibilityLogger
import com.smartdone.vm.runtime.SystemBroadcastRelay
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class VmHostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeCompatibilityLogger.log(this)
        NativeEngine.preload()
        if (!isMainProcess()) {
            return
        }
        SystemBroadcastRelay(this, entryPoint().evokeServiceFetcher()).register()
        entryPoint().evokeServiceFetcher().broadcastManager()?.dispatchBroadcast("host.app.started", null)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun evokeServiceFetcher(): EvokeServiceFetcher
    }

    private fun entryPoint(): AppEntryPoint =
        EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)

    private fun isMainProcess(): Boolean = packageName == getProcessName()
}
