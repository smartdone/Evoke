package com.smartdone.vm.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartdone.vm.core.virtual.EvokeCore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RunningAppsActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != EvokeCore.ACTION_STOP_APP) return
        val pendingResult = goAsync()
        val packageName = intent.getStringExtra(EvokeCore.EXTRA_PACKAGE_NAME).orEmpty()
        val userId = intent.getIntExtra(EvokeCore.EXTRA_USER_ID, 0)
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                entryPoint(context).evokeCore().stopApp(packageName, userId)
            }
            pendingResult.finish()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RunningAppsReceiverEntryPoint {
        fun evokeCore(): EvokeCore
    }

    private fun entryPoint(context: Context): RunningAppsReceiverEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, RunningAppsReceiverEntryPoint::class.java)
}
