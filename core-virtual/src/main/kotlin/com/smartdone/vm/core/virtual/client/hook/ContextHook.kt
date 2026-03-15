package com.smartdone.vm.core.virtual.client.hook

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextHook @Inject constructor() {
    private var evokePackageName: String? = null
    private var hostPackageName: String? = null

    fun install(evokePackageName: String, hostPackageName: String) {
        this.evokePackageName = evokePackageName
        this.hostPackageName = hostPackageName
        Log.d("ContextHook", "Context hook installed for $evokePackageName")
    }

    fun getPackageName(): String? =
        if (shouldExposeHostPackageName()) hostPackageName ?: evokePackageName else evokePackageName

    fun getOpPackageName(): String? = hostPackageName ?: evokePackageName

    fun getBasePackageName(): String? = hostPackageName ?: evokePackageName

    private fun shouldExposeHostPackageName(): Boolean =
        Thread.currentThread().stackTrace.any { frame ->
            HOST_IDENTITY_CALLER_PREFIXES.any(frame.className::startsWith)
        }

    companion object {
        private val HOST_IDENTITY_CALLER_PREFIXES = listOf(
            "com.google.android.gms.",
            "com.google.firebase.",
            "com.google.android.datatransport.",
            "com.google.android.play."
        )
    }
}
