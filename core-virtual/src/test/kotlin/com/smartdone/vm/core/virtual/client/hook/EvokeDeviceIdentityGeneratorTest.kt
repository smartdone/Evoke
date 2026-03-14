package com.smartdone.vm.core.virtual.client.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EvokeDeviceIdentityGeneratorTest {
    @Test
    fun identity_isStableForSamePackageAndUser() {
        val first = EvokeDeviceIdentityGenerator.generate("pkg.demo", 0)
        val second = EvokeDeviceIdentityGenerator.generate("pkg.demo", 0)

        assertEquals(first, second)
    }

    @Test
    fun identity_isolatedAcrossUsersAndPackages() {
        val user0 = EvokeDeviceIdentityGenerator.generate("pkg.demo", 0)
        val user1 = EvokeDeviceIdentityGenerator.generate("pkg.demo", 1)
        val otherPackage = EvokeDeviceIdentityGenerator.generate("pkg.other", 0)

        assertNotEquals(user0.androidId, user1.androidId)
        assertNotEquals(user0.deviceId, user1.deviceId)
        assertNotEquals(user0.androidId, otherPackage.androidId)
    }
}
