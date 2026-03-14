package com.smartdone.vm.core.virtual.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSlotManagerTest {
    private val fakeInspector = FakeProcessInspector()
    private val manager = ProcessSlotManager(fakeInspector)

    @Test
    fun acquireSlot_reusesExistingSlotAndUpdatesLastComponent() {
        val initial = manager.acquireSlot("pkg.a", 0, "pkg.a.MainActivity")

        Thread.sleep(5)

        val reused = manager.acquireSlot("pkg.a", 0, "pkg.a.SettingsActivity")

        assertEquals(initial.slotId, reused.slotId)
        assertEquals("pkg.a.SettingsActivity", reused.lastComponent)
        assertTrue(reused.lastActiveAt >= initial.lastActiveAt)
    }

    @Test
    fun acquireSlot_evictsLeastRecentlyUsedSlotWhenPoolIsFull() {
        repeat(10) { index ->
            manager.acquireSlot("pkg.$index", index, "pkg.$index.MainActivity")
            Thread.sleep(2)
        }

        val replacement = manager.acquireSlot("pkg.new", 99, "pkg.new.MainActivity")
        val snapshot = manager.snapshot()

        assertEquals(0, replacement.slotId)
        assertEquals("pkg.new", snapshot.first { it.slotId == 0 }.packageName)
        assertNull(snapshot.firstOrNull { it.packageName == "pkg.0" })
    }

    @Test
    fun snapshot_prunesDeadProcessesFromRunningSlots() {
        manager.acquireSlot("pkg.dead", 0, "pkg.dead.MainActivity")
        manager.reportPid("pkg.dead", 0, 4242)

        fakeInspector.alivePids = setOf()

        val snapshot = manager.snapshot()

        assertNull(snapshot.first { it.slotId == 0 }.packageName)
        assertTrue(manager.snapshotRunningApps().none { it.packageName == "pkg.dead" })
    }

    private class FakeProcessInspector(
        var alivePids: Set<Int> = emptySet()
    ) : ProcessInspector {
        override fun runningPids(): Set<Int> = alivePids
    }
}
