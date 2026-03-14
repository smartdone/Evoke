package com.smartdone.vm.core.virtual.server

import com.smartdone.vm.core.virtual.model.RunningAppRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProcessSlot(
    val slotId: Int,
    val processName: String,
    val pid: Int = -1,
    val packageName: String? = null,
    val userId: Int = -1,
    val startedAt: Long = 0L,
    val lastActiveAt: Long = 0L,
    val lastComponent: String? = null
)

@Singleton
class ProcessSlotManager @Inject constructor(
    private val processInspector: ProcessInspector
) {
    private val slots = MutableStateFlow(List(10) { ProcessSlot(it, ":p$it") })
    private val runningApps = MutableStateFlow<List<RunningAppRecord>>(emptyList())

    fun acquireSlot(packageName: String, userId: Int, componentName: String? = null): ProcessSlot {
        pruneDeadProcesses()
        val now = System.currentTimeMillis()
        val existing = slots.value.firstOrNull { it.packageName == packageName && it.userId == userId }
        if (existing != null) {
            val updated = existing.copy(
                lastActiveAt = now,
                lastComponent = componentName ?: existing.lastComponent
            )
            slots.value = slots.value.map { if (it.slotId == existing.slotId) updated else it }
            syncRunningApps()
            return updated
        }
        val next = slots.value.firstOrNull { it.packageName == null }
            ?: slots.value
                .filter { it.packageName != null }
                .minByOrNull { it.lastActiveAt }
            ?: slots.value.first()
        val updated = next.copy(
            pid = -1,
            packageName = packageName,
            userId = userId,
            startedAt = now,
            lastActiveAt = now,
            lastComponent = componentName
        )
        slots.value = slots.value.map { if (it.slotId == next.slotId) updated else it }
        syncRunningApps()
        return updated
    }

    fun reportPid(packageName: String, userId: Int, pid: Int) {
        val now = System.currentTimeMillis()
        slots.value = slots.value.map {
            if (it.packageName == packageName && it.userId == userId) {
                it.copy(
                    pid = pid,
                    startedAt = if (it.startedAt > 0L) it.startedAt else now,
                    lastActiveAt = now
                )
            } else {
                it
            }
        }
        syncRunningApps()
    }

    fun touch(packageName: String, userId: Int, componentName: String? = null) {
        val now = System.currentTimeMillis()
        slots.value = slots.value.map {
            if (it.packageName == packageName && it.userId == userId) {
                it.copy(
                    lastActiveAt = now,
                    lastComponent = componentName ?: it.lastComponent
                )
            } else {
                it
            }
        }
        syncRunningApps()
    }

    fun release(packageName: String, userId: Int) {
        slots.value = slots.value.map {
            if (it.packageName == packageName && it.userId == userId) {
                ProcessSlot(it.slotId, it.processName)
            } else {
                it
            }
        }
        syncRunningApps()
    }

    fun observeRunningApps(): StateFlow<List<RunningAppRecord>> = runningApps.asStateFlow()

    fun snapshotRunningApps(): List<RunningAppRecord> {
        pruneDeadProcesses()
        return runningApps.value
    }

    fun snapshot(): List<ProcessSlot> {
        pruneDeadProcesses()
        return slots.value
    }

    fun lastKnownComponent(packageName: String, userId: Int): String? =
        snapshot()
            .firstOrNull { it.packageName == packageName && it.userId == userId }
            ?.lastComponent

    private fun pruneDeadProcesses() {
        val alivePids = processInspector.runningPids()
        var changed = false
        val updated = slots.value.map { slot ->
            if (slot.packageName != null && slot.pid > 0 && slot.pid !in alivePids) {
                changed = true
                ProcessSlot(slot.slotId, slot.processName)
            } else {
                slot
            }
        }
        if (changed) {
            slots.value = updated
            syncRunningApps()
        }
    }

    private fun syncRunningApps() {
        runningApps.value = slots.value
            .filter { it.packageName != null }
            .map {
                RunningAppRecord(
                    packageName = it.packageName.orEmpty(),
                    userId = it.userId,
                    processName = it.processName,
                    pid = it.pid,
                    startedAt = it.startedAt,
                    lastActiveAt = it.lastActiveAt
                )
            }
    }
}
