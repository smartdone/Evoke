package com.smartdone.vm.core.virtual.client.hook

import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoHook @Inject constructor(
    private val deviceIdentityStore: DeviceIdentityStore
) {
    private var evokePackageName: String? = null
    private var userId: Int = 0

    fun install(packageName: String, userId: Int) {
        evokePackageName = packageName
        this.userId = userId
    }

    fun androidId(packageName: String = currentPackageName()): String =
        deviceIdentityStore.identity(packageName, userId).androidId

    fun deviceId(packageName: String = currentPackageName()): String =
        deviceIdentityStore.identity(packageName, userId).deviceId

    fun secureSetting(name: String, packageName: String = currentPackageName()): String? =
        when (name) {
            Settings.Secure.ANDROID_ID -> androidId(packageName)
            else -> null
        }

    private fun currentPackageName(): String = evokePackageName.orEmpty()
}
