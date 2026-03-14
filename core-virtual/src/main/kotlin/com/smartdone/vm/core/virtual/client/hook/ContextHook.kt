package com.smartdone.vm.core.virtual.client.hook

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextHook @Inject constructor() {
    private var evokePackageName: String? = null

    fun install(evokePackageName: String) {
        this.evokePackageName = evokePackageName
        Log.d("ContextHook", "Context hook installed for $evokePackageName")
    }

    fun getPackageName(): String? = evokePackageName

    fun getOpPackageName(): String? = evokePackageName

    fun getBasePackageName(): String? = evokePackageName
}
