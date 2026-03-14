package com.smartdone.vm.core.virtual.client.hook

import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastHook @Inject constructor(
    private val serviceFetcher: EvokeServiceFetcher
) {
    private var installed = false
    private var evokePackageName: String? = null
    private val dynamicRegistrations = mutableMapOf<String, MutableList<String>>()

    fun install(packageName: String) {
        installed = true
        evokePackageName = packageName
        preloadStaticReceivers(packageName)
        Log.d("BroadcastHook", "Broadcast hook installed")
    }

    fun registerReceiver(packageName: String, filter: IntentFilter) {
        val actions = buildList {
            for (index in 0 until filter.countActions()) {
                add(filter.getAction(index))
            }
        }
        val packageRegistrations = dynamicRegistrations.getOrPut(packageName) { mutableListOf() }
        actions.filterNot { it in packageRegistrations }.forEach { action ->
            packageRegistrations += action
            serviceFetcher.broadcastManager()?.registerDynamicReceiver(packageName, action)
        }
    }

    fun registerReceiver(filter: IntentFilter) {
        val packageName = evokePackageName ?: return
        registerReceiver(packageName, filter)
    }

    fun registrations(packageName: String): List<String> = dynamicRegistrations[packageName].orEmpty()

    fun dispatchBroadcast(intent: Intent, sourcePackage: String = evokePackageName.orEmpty()): BroadcastDispatchResult {
        val action = intent.action.orEmpty()
        val targetPackage = intent.`package`
        val targets = serviceFetcher.broadcastManager()
            ?.dispatchBroadcast(action, targetPackage)
            ?.toList()
            .orEmpty()
        val systemFallback = targets.isEmpty() && targetPackage.isNullOrBlank() && sourcePackage.isNotBlank()
        return BroadcastDispatchResult(
            action = action,
            evokeTargets = targets,
            systemFallback = systemFallback
        )
    }

    fun sendBroadcast(intent: Intent): BroadcastDispatchResult =
        dispatchBroadcast(intent)

    fun sendOrderedBroadcast(intent: Intent): BroadcastDispatchResult =
        dispatchBroadcast(intent)

    private fun preloadStaticReceivers(packageName: String) {
        val receivers = serviceFetcher.packageManager()
            ?.getPackageInfo(packageName)
            ?.getStringArrayList("receivers")
            .orEmpty()
        if (receivers.isNotEmpty()) {
            serviceFetcher.broadcastManager()?.registerStaticReceivers(packageName, receivers)
        }
    }
}

data class BroadcastDispatchResult(
    val action: String,
    val evokeTargets: List<String>,
    val systemFallback: Boolean
)
