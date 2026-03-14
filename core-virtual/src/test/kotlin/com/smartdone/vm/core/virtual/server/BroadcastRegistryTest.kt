package com.smartdone.vm.core.virtual.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastRegistryTest {
    private val registry = BroadcastRegistry()

    @Test
    fun dynamicRegistration_isDeduplicated() {
        registry.registerDynamicReceiver("pkg.a", "ACTION_SYNC")
        registry.registerDynamicReceiver("pkg.a", "ACTION_SYNC")

        assertEquals(listOf("ACTION_SYNC"), registry.getReceivers("pkg.a"))
    }

    @Test
    fun dispatchBroadcast_targetsPackageSpecificReceiversByAction() {
        registry.registerStaticReceivers("pkg.a", listOf("ACTION_BOOT"))
        registry.registerDynamicReceiver("pkg.a", "receiver.Sync", setOf("custom.action.SYNC"))
        registry.registerStaticReceivers("pkg.b", listOf("ACTION_BOOT_B"))

        val targets = registry.dispatchBroadcast("custom.action.SYNC", "pkg.a")

        assertEquals(listOf("ACTION_BOOT", "receiver.Sync"), targets)
    }

    @Test
    fun dispatchBroadcast_withoutTargetReturnsOnlyMatchingGlobalReceivers() {
        registry.registerStaticReceivers("pkg.a", listOf("ACTION_ONE", "ACTION_TWO"))
        registry.registerDynamicReceiver("pkg.b", "receiver.Match", setOf("custom.action.GLOBAL"))
        registry.registerDynamicReceiver("pkg.b", "receiver.Skip", setOf("custom.action.SKIP"))

        val targets = registry.dispatchBroadcast("custom.action.GLOBAL", null)

        assertEquals(listOf("ACTION_ONE", "ACTION_TWO", "receiver.Match"), targets)
        assertFalse("receiver.Skip" in targets)
        assertTrue(registry.dispatchLog().last().contains("custom.action.GLOBAL"))
    }
}
