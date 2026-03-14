package com.smartdone.vm.core.virtual.client.hook

import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

object HiddenApiCompat {
    private const val tag = "HiddenApiCompat"
    private var initialized = false

    fun exemptAll() {
        if (initialized) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
            initialized = true
        }.onFailure {
            Log.w(tag, "Unable to exempt hidden APIs", it)
        }
    }
}
