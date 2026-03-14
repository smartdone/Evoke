package com.smartdone.vm.runtime

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object NativeCompatibilityLogger {
    private const val TAG = "NativeCompat"

    fun log(context: Context) {
        val applicationInfo = context.applicationInfo
        val processName = Application.getProcessName()
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
        val extractNativeLibs = (applicationInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0
        Log.i(
            TAG,
            "process=$processName pageSize=$pageSize extractNativeLibs=$extractNativeLibs " +
                "nativeLibraryDir=${applicationInfo.nativeLibraryDir} apk=${applicationInfo.sourceDir} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString()}"
        )

        runCatching {
            ZipFile(applicationInfo.sourceDir).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".so") }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        val method = when (entry.method) {
                            ZipEntry.STORED -> "STORED"
                            ZipEntry.DEFLATED -> "DEFLATED"
                            else -> entry.method.toString()
                        }
                        Log.i(
                            TAG,
                            "apk-lib name=${entry.name} method=$method size=${entry.size} compressed=${entry.compressedSize}"
                        )
                    }
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to inspect packaged native libraries", error)
        }
    }
}
