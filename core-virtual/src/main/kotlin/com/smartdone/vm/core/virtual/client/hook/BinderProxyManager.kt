package com.smartdone.vm.core.virtual.client.hook

import android.os.IBinder
import android.util.Log
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderProxyManager @Inject constructor(
    private val serviceFetcher: EvokeServiceFetcher
) {
    private var installed = false
    private var hostPackageName: String? = null
    private val cachedBinders = mutableMapOf<String, IBinder?>()
    private val replacedTargets = mutableMapOf<String, Any>()

    fun install(hostPackageName: String) {
        this.hostPackageName = hostPackageName
        if (installed) return
        HiddenApiCompat.exemptAll()
        cachedBinders["pm"] = serviceFetcher.packageManager()?.asBinder()
        cachedBinders["am"] = serviceFetcher.activityManager()?.asBinder()
        cachedBinders["cp"] = serviceFetcher.contentProviderManager()?.asBinder()
        cachedBinders["broadcast"] = serviceFetcher.broadcastManager()?.asBinder()
        cachedBinders["perm"] = serviceFetcher.permissionDelegate()?.asBinder()
        replaceActivityManagerSingleton()
        replaceActivityTaskManagerSingleton()
        replacePackageManagerSingleton()
        installed = true
        Log.d("BinderProxyManager", "Initialized proxy cache for ${cachedBinders.keys} and replaced ${replacedTargets.keys}")
    }

    fun isInstalled(): Boolean = installed

    fun binder(name: String): IBinder? = cachedBinders[name]

    private fun replaceActivityManagerSingleton() {
        replaceSingletonField(
            ownerClassName = "android.app.ActivityManager",
            singletonFieldName = "IActivityManagerSingleton",
            proxyLabel = "activity_manager"
        )
    }

    private fun replaceActivityTaskManagerSingleton() {
        replaceSingletonField(
            ownerClassName = "android.app.ActivityTaskManager",
            singletonFieldName = "IActivityTaskManagerSingleton",
            proxyLabel = "activity_task_manager"
        )
    }

    private fun replacePackageManagerSingleton() {
        replaceStaticField(
            ownerClassName = "android.app.ActivityThread",
            fieldName = "sPackageManager",
            proxyLabel = "package_manager"
        )
    }

    private fun replaceSingletonField(
        ownerClassName: String,
        singletonFieldName: String,
        proxyLabel: String
    ) {
        runCatching {
            val ownerClass = Class.forName(ownerClassName)
            val singletonField = ownerClass.getDeclaredField(singletonFieldName).apply {
                isAccessible = true
            }
            val singleton = singletonField.get(null) ?: return@runCatching
            val singletonClass = Class.forName("android.util.Singleton")
            val instanceField = singletonClass.getDeclaredField("mInstance").apply {
                isAccessible = true
            }
            val original = instanceField.get(singleton) ?: return@runCatching
            val proxy = createProxy(original, proxyLabel)
            instanceField.set(singleton, proxy)
            replacedTargets[proxyLabel] = original
        }.onFailure {
            Log.w("BinderProxyManager", "Unable to replace singleton $proxyLabel", it)
        }
    }

    private fun replaceStaticField(
        ownerClassName: String,
        fieldName: String,
        proxyLabel: String
    ) {
        runCatching {
            val ownerClass = Class.forName(ownerClassName)
            val field = ownerClass.getDeclaredField(fieldName).apply {
                isAccessible = true
            }
            val original = field.get(null) ?: return@runCatching
            val proxy = createProxy(original, proxyLabel)
            field.set(null, proxy)
            replacedTargets[proxyLabel] = original
        }.onFailure {
            Log.w("BinderProxyManager", "Unable to replace static field $proxyLabel", it)
        }
    }

    private fun createProxy(target: Any, proxyLabel: String): Any {
        val interfaces = buildSet {
            target.javaClass.interfaces.forEach(::add)
            if (target.javaClass.isInterface) add(target.javaClass)
        }.toTypedArray()
        if (interfaces.isEmpty()) return target
        val handler = DelegatingHandler(target, proxyLabel) { hostPackageName }
        return Proxy.newProxyInstance(target.javaClass.classLoader, interfaces, handler)
    }

    private class DelegatingHandler(
        private val target: Any,
        private val proxyLabel: String,
        private val hostPackageNameProvider: () -> String?
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val rewrittenArgs = args
                ?.let { original -> Array<Any?>(original.size) { index -> original[index] } }
                ?: emptyArray()
            rewriteCallingPackage(method, rewrittenArgs)
            return runCatching {
                method.invoke(target, *rewrittenArgs)
            }.onFailure {
                Log.w("BinderProxyManager", "Proxy invocation failed for $proxyLabel.${method.name}", it)
            }.getOrThrow()
        }

        private fun rewriteCallingPackage(method: Method, args: Array<Any?>) {
            val hostPackageName = hostPackageNameProvider() ?: return
            if (proxyLabel != "activity_manager" && proxyLabel != "activity_task_manager") return
            if (!method.name.startsWith("startActivity")) return
            val firstStringIndex = args.indexOfFirst { it is String }
            if (firstStringIndex == -1) return
            if (args[firstStringIndex] == hostPackageName) return
            Log.d(
                "BinderProxyManager",
                "Rewriting ${proxyLabel}.${method.name} callingPackage=${args[firstStringIndex]} -> $hostPackageName"
            )
            args[firstStringIndex] = hostPackageName
        }
    }
}
