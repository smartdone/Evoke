package lh

import android.util.Log
import ph.c0

class f @JvmOverloads constructor(
    @JvmField val a: c0 = c0()
) {
    // APKPure touches both the original field name and JADX-renamed variants.
    @JvmField
    val f31095a: c0 = a

    fun b(throwable: Throwable) {
        a.c(throwable)
    }

    fun c(key: String, value: String) {
        a.f(key, value)
    }

    companion object {
        private const val TAG = "VirtualCrashlytics"
        private val INSTANCE = f()

        @JvmStatic
        fun a(): f {
            Log.d(TAG, "Using host Crashlytics shim")
            return INSTANCE
        }
    }
}
