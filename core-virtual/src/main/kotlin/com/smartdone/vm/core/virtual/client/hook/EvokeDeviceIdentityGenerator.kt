package com.smartdone.vm.core.virtual.client.hook

import java.security.MessageDigest

object EvokeDeviceIdentityGenerator {
    fun generate(packageName: String, userId: Int): EvokeDeviceIdentity {
        val digest = sha256("$packageName:$userId")
        return EvokeDeviceIdentity(
            androidId = digest.take(16),
            deviceId = digest.filter(Char::isDigit).padEnd(15, '0').take(15)
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(byte)) }
        }
    }
}
