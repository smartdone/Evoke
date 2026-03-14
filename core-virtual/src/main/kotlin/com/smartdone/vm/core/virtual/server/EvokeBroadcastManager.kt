package com.smartdone.vm.core.virtual.server

import com.smartdone.vm.core.virtual.aidl.IEvokeBroadcastManager
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.install.ApkParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

@Singleton
class EvokeBroadcastManager @Inject constructor(
    private val repository: EvokeAppRepository,
    private val apkParser: ApkParser
) : IEvokeBroadcastManager.Stub() {
    private val registry = BroadcastRegistry()
    private val dispatchLog = MutableStateFlow<List<String>>(emptyList())
    private var staticReceiversLoaded = false

    override fun registerStaticReceivers(packageName: String, receivers: MutableList<String>) {
        registry.registerStaticReceivers(packageName, receivers)
    }

    override fun registerDynamicReceiver(packageName: String, receiverClassName: String) {
        registry.registerDynamicReceiver(packageName, receiverClassName)
    }

    override fun getReceivers(packageName: String): MutableList<String> =
        registry.getReceivers(packageName).toMutableList()

    override fun dispatchBroadcast(action: String, targetPackage: String?): MutableList<String> {
        ensureStaticReceiversLoaded()
        val targets = registry.dispatchBroadcast(action, targetPackage)
        dispatchLog.value = registry.dispatchLog()
        return targets.toMutableList()
    }

    fun observeDispatchLog(): StateFlow<List<String>> = dispatchLog.asStateFlow()

    fun warmUpStaticReceivers() {
        ensureStaticReceiversLoaded()
    }

    private fun ensureStaticReceiversLoaded() {
        if (staticReceiversLoaded) return
        runBlocking {
            repository.getApps().forEach { app ->
                val receivers = apkParser.parseArchive(app.apkPath)?.receiverComponents.orEmpty()
                if (receivers.isNotEmpty()) {
                    registry.registerManifestReceivers(app.packageName, receivers)
                }
            }
        }
        staticReceiversLoaded = true
    }
}
