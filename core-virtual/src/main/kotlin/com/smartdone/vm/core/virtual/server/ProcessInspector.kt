package com.smartdone.vm.core.virtual.server

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun interface ProcessInspector {
    fun runningPids(): Set<Int>
}

@Singleton
class AndroidProcessInspector @Inject constructor(
    @ApplicationContext private val context: Context
) : ProcessInspector {
    override fun runningPids(): Set<Int> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptySet()
        val hostUid = android.os.Process.myUid()
        return activityManager.runningAppProcesses
            ?.asSequence()
            ?.filter { it.uid == hostUid }
            ?.map { it.pid }
            ?.toSet()
            .orEmpty()
    }
}
