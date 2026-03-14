package com.smartdone.vm.core.nativeengine

import android.util.Log

object NativeEngine {
    private const val tag = "NativeEngine"
    private var loaded = false

    init {
        runCatching {
            System.loadLibrary("core-native")
            loaded = true
        }.onFailure {
            Log.w(tag, "Unable to load core-native library", it)
        }
    }

    fun preload() = loaded

    fun initGum(): Boolean = if (loaded) nativeInitGum() else false

    fun installHooks(hostDataDir: String, vappPkgName: String, userId: Int): Boolean =
        if (loaded) nativeInstallIoHooks(hostDataDir, vappPkgName, userId) else false

    fun removeHooks() {
        if (loaded) {
            nativeRemoveIoHooks()
        }
    }

    fun setCurrentVApp(pkgName: String, userId: Int) {
        if (loaded) {
            nativeSetCurrentVApp(pkgName, userId)
        }
    }

    private external fun nativeInitGum(): Boolean
    private external fun nativeInstallIoHooks(hostDataDir: String, vappPkgName: String, userId: Int): Boolean
    private external fun nativeRemoveIoHooks()
    private external fun nativeSetCurrentVApp(pkgName: String, userId: Int)
}
