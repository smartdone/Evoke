package com.smartdone.vm.core.virtual.client.hook

import android.os.Looper
import android.util.Log

object GuestRuntimeCrashShield {
    private const val TAG = "GuestCrashShield"

    @Volatile
    private var installedPackageName: String? = null

    fun install(packageName: String) {
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (currentHandler is ShieldHandler && currentHandler.packageName == packageName) {
            installedPackageName = packageName
            return
        }
        Thread.setDefaultUncaughtExceptionHandler(ShieldHandler(packageName, currentHandler))
        installedPackageName = packageName
        Log.d(TAG, "Installed guest crash shield for $packageName")
    }

    private class ShieldHandler(
        val packageName: String,
        private val delegate: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            if (shouldSuppress(thread, throwable)) {
                Log.w(
                    TAG,
                    "Suppressing background guest crash for $packageName thread=${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    throwable
                )
                return
            }
            delegate?.uncaughtException(thread, throwable)
        }
    }

    private fun shouldSuppress(thread: Thread, throwable: Throwable): Boolean {
        if (thread === Looper.getMainLooper().thread) {
            return false
        }
        val causes = generateSequence(throwable as Throwable?) { cause: Throwable? -> cause?.cause }
            .filterNotNull()
        val messages = causes
            .mapNotNull { cause -> cause.message }
            .toList()
        if (messages.none(::isSuppressibleMessage)) {
            return false
        }
        val stackFrames = buildList {
            causes.forEach { cause ->
                addAll(cause.stackTrace.map { it.className })
            }
        }
        return stackFrames.any(::isLikelyPlayServicesFrame) || messages.any(::isSuppressibleMessage)
    }

    private fun isSuppressibleMessage(message: String): Boolean =
        message.contains("Unknown calling package name", ignoreCase = true) ||
            message.contains("Binder invocation to an incorrect interface", ignoreCase = true)

    private fun isLikelyPlayServicesFrame(className: String): Boolean =
        className.startsWith("com.google.android.gms.") ||
            className.startsWith("com.google.firebase.") ||
            className.startsWith("m4.") ||
            className.startsWith("m7.")
}
