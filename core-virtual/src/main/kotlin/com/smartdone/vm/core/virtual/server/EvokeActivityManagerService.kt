package com.smartdone.vm.core.virtual.server

import android.os.Bundle
import android.os.Process
import com.smartdone.vm.core.virtual.aidl.IEvokeActivityManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class EvokeActivityManagerService @Inject constructor(
    private val processSlotManager: ProcessSlotManager
) : IEvokeActivityManager.Stub() {
    private val stateLock = Mutex()

    override fun startActivity(packageName: String, userId: Int, activityName: String?): Bundle {
        val slot = processSlotManager.acquireSlot(packageName, userId, activityName)
        return Bundle().apply {
            putInt("slotId", slot.slotId)
            putString("processName", slot.processName)
            putString("activityName", activityName)
            putString("packageName", packageName)
            putInt("userId", userId)
        }
    }

    override fun startService(packageName: String, userId: Int, serviceName: String?): Bundle =
        startActivity(packageName, userId, serviceName)

    override fun bindService(packageName: String, userId: Int, serviceName: String?): Bundle =
        startActivity(packageName, userId, serviceName)

    override fun reportAppLaunch(packageName: String, userId: Int, pid: Int) {
        processSlotManager.reportPid(packageName, userId, pid)
    }

    suspend fun stopApp(packageName: String, userId: Int) {
        stateLock.withLock {
            processSlotManager.snapshot()
                .firstOrNull { it.packageName == packageName && it.userId == userId }
                ?.pid
                ?.takeIf { it > 0 }
                ?.let(Process::killProcess)
            processSlotManager.release(packageName, userId)
        }
    }

    fun getRunningProcesses(): List<ProcessSlot> = processSlotManager.snapshot()
}
