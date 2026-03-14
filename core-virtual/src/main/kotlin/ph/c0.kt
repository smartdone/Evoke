package ph

import android.util.Log

class c0 {
    fun b(message: String) {
        Log.w(TAG, message)
    }

    fun c(throwable: Throwable) {
        Log.w(TAG, "Captured virtual throwable", throwable)
    }

    fun f(key: String, value: String) {
        Log.d(TAG, "Custom key $key=$value")
    }

    fun g(value: String) {
        Log.d(TAG, "Qimei=$value")
    }

    companion object {
        private const val TAG = "VirtualCrashlytics"
    }
}
