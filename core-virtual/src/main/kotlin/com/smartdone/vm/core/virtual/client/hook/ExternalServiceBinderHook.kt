package com.smartdone.vm.core.virtual.client.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object ExternalServiceBinderHook {
    private const val TAG = "ExternalSvcHook"

    @Volatile
    private var guestPackageName: String? = null

    @Volatile
    private var hostPackageName: String? = null

    private val wrappedConnections = ConcurrentHashMap<ServiceConnection, ServiceConnection>()

    fun install(guestPackageName: String, hostPackageName: String) {
        this.guestPackageName = guestPackageName
        this.hostPackageName = hostPackageName
    }

    fun wrapConnectionIfNeeded(intent: Intent, connection: ServiceConnection): ServiceConnection {
        val targetPackageName = resolveTargetPackageName(intent) ?: return connection
        if (!shouldRewriteRemoteService(intent, targetPackageName)) return connection
        return wrappedConnections.getOrPut(connection) {
            Log.d(
                TAG,
                "Wrapping bound service connection target=$targetPackageName action=${intent.action} component=${intent.component}"
            )
            InterceptingServiceConnection(
                delegate = connection,
                targetPackageName = targetPackageName
            )
        }
    }

    fun unwrapConnection(connection: ServiceConnection): ServiceConnection =
        wrappedConnections.remove(connection) ?: connection

    private fun resolveTargetPackageName(intent: Intent): String? =
        intent.component?.packageName ?: intent.`package`

    private fun shouldRewriteRemoteService(intent: Intent, targetPackageName: String): Boolean {
        if (!EXTERNAL_IDENTITY_SENSITIVE_PACKAGE_PREFIXES.any(targetPackageName::startsWith)) {
            return false
        }
        val action = intent.action.orEmpty()
        val componentClassName = intent.component?.className.orEmpty()
        val dataString = intent.dataString.orEmpty()
        return IDENTITY_REWRITE_ACTION_MARKERS.any { marker ->
            action.contains(marker, ignoreCase = true) ||
                componentClassName.contains(marker, ignoreCase = true) ||
                dataString.contains(marker, ignoreCase = true)
        }
    }

    private class InterceptingServiceConnection(
        private val delegate: ServiceConnection,
        private val targetPackageName: String
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            delegate.onServiceConnected(name, service?.let { wrapBinderIfNeeded(it, targetPackageName) })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            delegate.onServiceDisconnected(name)
        }

        override fun onBindingDied(name: ComponentName?) {
            delegate.onBindingDied(name)
        }

        override fun onNullBinding(name: ComponentName?) {
            delegate.onNullBinding(name)
        }
    }

    private fun wrapBinderIfNeeded(binder: IBinder, targetPackageName: String): IBinder {
        val guestPackageName = guestPackageName ?: return binder
        val hostPackageName = hostPackageName ?: return binder
        if (guestPackageName == hostPackageName) return binder
        val interfaces = buildSet {
            add(IBinder::class.java)
            binder.javaClass.interfaces.forEach(::add)
        }.toTypedArray()
        val handler = BinderInvocationHandler(
            delegate = binder,
            targetPackageName = targetPackageName,
            guestPackageName = guestPackageName,
            hostPackageName = hostPackageName
        )
        return Proxy.newProxyInstance(
            binder.javaClass.classLoader ?: IBinder::class.java.classLoader,
            interfaces,
            handler
        ) as IBinder
    }

    private class BinderInvocationHandler(
        private val delegate: IBinder,
        private val targetPackageName: String,
        private val guestPackageName: String,
        private val hostPackageName: String
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "queryLocalInterface") {
                val localInterface = method.invoke(delegate, *(args ?: emptyArray())) as? IInterface
                return localInterface
            }
            if (method.name != "transact" || args == null || args.size < 4) {
                return method.invoke(delegate, *(args ?: emptyArray()))
            }
            val data = args[1] as? Parcel ?: return method.invoke(delegate, *args)
            val code = args[0] as? Int ?: -1
            val patchedData = rewriteOutgoingParcel(data, code, targetPackageName, guestPackageName, hostPackageName)
            return try {
                val patchedArgs = Array<Any?>(args.size) { index -> args[index] }
                if (patchedData != null) {
                    patchedArgs[1] = patchedData
                }
                method.invoke(delegate, *patchedArgs)
            } finally {
                patchedData?.recycle()
            }
        }
    }

    private fun rewriteOutgoingParcel(
        original: Parcel,
        transactionCode: Int,
        targetPackageName: String,
        guestPackageName: String,
        hostPackageName: String
    ): Parcel? {
        if (hostPackageName.length > guestPackageName.length) {
            Log.w(
                TAG,
                "Skipping binder parcel rewrite for $targetPackageName because host package is longer " +
                    "guest=$guestPackageName host=$hostPackageName"
            )
            return null
        }
        val marshalled = original.marshall()
        val rewrittenCount = rewriteUtf16ParcelStringsInPlace(
            bytes = marshalled,
            source = guestPackageName,
            replacement = hostPackageName
        )
        if (rewrittenCount == 0) {
            return null
        }
        return Parcel.obtain().apply {
            unmarshall(marshalled, 0, marshalled.size)
            setDataPosition(original.dataPosition())
            Log.d(
                TAG,
                "Rewrote $rewrittenCount outbound parcel package value(s) for $targetPackageName transact=$transactionCode"
            )
        }
    }

    private fun rewriteUtf16ParcelStringsInPlace(
        bytes: ByteArray,
        source: String,
        replacement: String
    ): Int {
        val sourcePayload = parcelStringPayload(source)
        val replacementPayload = parcelStringPayload(replacement)
        var index = 0
        var rewrittenCount = 0
        while (index <= bytes.size - sourcePayload.size) {
            val matchIndex = indexOf(bytes, sourcePayload, index)
            if (matchIndex == -1) break
            val lengthOffset = matchIndex - Int.SIZE_BYTES
            if (lengthOffset >= 0 && readIntLe(bytes, lengthOffset) == source.length) {
                writeIntLe(bytes, lengthOffset, replacement.length)
                java.util.Arrays.fill(bytes, matchIndex, matchIndex + sourcePayload.size, 0)
                replacementPayload.copyInto(bytes, destinationOffset = matchIndex)
                rewrittenCount += 1
                index = matchIndex + sourcePayload.size
            } else {
                index = matchIndex + 1
            }
        }
        return rewrittenCount
    }

    private fun parcelStringPayload(value: String): ByteArray =
        (value + "\u0000").toByteArray(StandardCharsets.UTF_16LE)

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, startIndex: Int): Int {
        var index = startIndex
        while (index <= bytes.size - pattern.size) {
            var offset = 0
            while (offset < pattern.size && bytes[index + offset] == pattern[offset]) {
                offset += 1
            }
            if (offset == pattern.size) {
                return index
            }
            index += 1
        }
        return -1
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private val EXTERNAL_IDENTITY_SENSITIVE_PACKAGE_PREFIXES = listOf(
        "com.google.android.gms",
        "com.google.firebase",
        "com.google.android.datatransport",
        "com.google.android.play"
    )

    private val IDENTITY_REWRITE_ACTION_MARKERS = listOf(
        "measurement",
        "appmeasurement",
        "analytics"
    )
}
