package com.smartdone.vm.core.virtual.server

import com.smartdone.vm.core.virtual.model.ReceiverComponentInfo

class BroadcastRegistry {
    private val staticReceivers = mutableMapOf<String, List<BroadcastRouteEntry>>()
    private val dynamicReceivers = mutableMapOf<String, MutableList<BroadcastRouteEntry>>()
    private var dispatchLog: List<String> = emptyList()

    fun registerStaticReceivers(packageName: String, receivers: List<String>) {
        staticReceivers[packageName] = receivers.map { receiver ->
            BroadcastRouteEntry(
                identifier = receiver,
                actions = emptySet()
            )
        }
    }

    fun registerManifestReceivers(packageName: String, receivers: List<ReceiverComponentInfo>) {
        staticReceivers[packageName] = receivers.map { receiver ->
            BroadcastRouteEntry(
                identifier = receiver.className,
                actions = receiver.intentFilters.flatMap { it.actions }.toSet()
            )
        }
    }

    fun registerDynamicReceiver(packageName: String, receiverClassName: String) {
        registerDynamicReceiver(
            packageName = packageName,
            receiverClassName = receiverClassName,
            actions = setOf(receiverClassName)
        )
    }

    fun registerDynamicReceiver(
        packageName: String,
        receiverClassName: String,
        actions: Set<String>
    ) {
        val registrations = dynamicReceivers.getOrPut(packageName) { mutableListOf() }
        if (registrations.any { it.identifier == receiverClassName && it.actions == actions }) return
        registrations += BroadcastRouteEntry(
            identifier = receiverClassName,
            actions = actions
        )
    }

    fun getReceivers(packageName: String): List<String> =
        (staticReceivers[packageName].orEmpty() + dynamicReceivers[packageName].orEmpty())
            .map(BroadcastRouteEntry::identifier)
            .distinct()

    fun dispatchBroadcast(action: String, targetPackage: String?): List<String> {
        val routes = if (targetPackage != null) {
            staticReceivers[targetPackage].orEmpty() + dynamicReceivers[targetPackage].orEmpty()
        } else {
            staticReceivers.values.flatten() + dynamicReceivers.values.flatten()
        }
        val targets = routes
            .filter { it.actions.isEmpty() || action in it.actions }
            .map(BroadcastRouteEntry::identifier)
            .distinct()
        dispatchLog = (dispatchLog + "$action -> ${targets.joinToString()}").takeLast(20)
        return targets
    }

    fun dispatchLog(): List<String> = dispatchLog
}

private data class BroadcastRouteEntry(
    val identifier: String,
    val actions: Set<String>
)
