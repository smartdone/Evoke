package com.smartdone.vm.core.virtual.client.hook

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class EvokeDeviceIdentity(
    val androidId: String,
    val deviceId: String
)

@Singleton
class DeviceIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun identity(packageName: String, userId: Int): EvokeDeviceIdentity {
        val key = "$packageName#$userId"
        val androidIdKey = "android_id:$key"
        val deviceIdKey = "device_id:$key"
        val cachedAndroidId = preferences.getString(androidIdKey, null)
        val cachedDeviceId = preferences.getString(deviceIdKey, null)
        if (cachedAndroidId != null && cachedDeviceId != null) {
            return EvokeDeviceIdentity(
                androidId = cachedAndroidId,
                deviceId = cachedDeviceId
            )
        }
        val identity = EvokeDeviceIdentityGenerator.generate(packageName, userId)
        preferences.edit()
            .putString(androidIdKey, identity.androidId)
            .putString(deviceIdKey, identity.deviceId)
            .apply()
        return identity
    }

    private companion object {
        const val PREFS_NAME = "virtual-device-identities"
    }
}
