package com.smartdone.vm.core.virtual.client.hook

import android.os.IBinder
import android.util.Log
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Intent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderProxyManager @Inject constructor(
    private val serviceFetcher: EvokeServiceFetcher,
    private val activityManagerHook: ActivityManagerHook,
    private val virtualPackageArchiveResolver: VirtualPackageArchiveResolver
) {
    private var installed = false
    private var hostPackageName: String? = null
    private val cachedBinders = mutableMapOf<String, IBinder?>()
    private val replacedTargets = mutableMapOf<String, Any>()

    fun install(hostPackageName: String, packageManager: PackageManager? = null) {
        this.hostPackageName = hostPackageName
        if (installed) {
            packageManager?.let(::replaceActivePackageManager)
            return
        }
        HiddenApiCompat.exemptAll()
        cachedBinders["pm"] = serviceFetcher.packageManager()?.asBinder()
        cachedBinders["am"] = serviceFetcher.activityManager()?.asBinder()
        cachedBinders["cp"] = serviceFetcher.contentProviderManager()?.asBinder()
        cachedBinders["broadcast"] = serviceFetcher.broadcastManager()?.asBinder()
        cachedBinders["perm"] = serviceFetcher.permissionDelegate()?.asBinder()
        replaceActivityManagerSingleton()
        replaceActivityTaskManagerSingleton()
        replacePackageManagerSingleton(packageManager)
        replaceStorageManagerSingleton()
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

    private fun replacePackageManagerSingleton(packageManager: PackageManager?) {
        replaceStaticField(
            ownerClassName = "android.app.ActivityThread",
            fieldName = "sPackageManager",
            proxyLabel = "package_manager",
            onReplaced = { proxy -> packageManager?.let { replaceActivePackageManager(it, proxy) } }
        )
    }

    private fun replaceStorageManagerSingleton() {
        runCatching {
            val storageManagerClass = Class.forName("android.os.storage.StorageManager")
            val field = storageManagerClass.getDeclaredField("sStorageManager").apply {
                isAccessible = true
            }
            val original = field.get(null) ?: run {
                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                val mountBinder = serviceManagerClass
                    .getMethod("getService", String::class.java)
                    .invoke(null, "mount") as? IBinder
                    ?: return@runCatching
                val storageStubClass = Class.forName("android.os.storage.IStorageManager\$Stub")
                storageStubClass.getMethod("asInterface", IBinder::class.java)
                    .invoke(null, mountBinder)
                    ?: return@runCatching
            }
            val proxy = createProxy(original, "storage_manager")
            field.set(null, proxy)
            replacedTargets["storage_manager"] = original
        }.onFailure {
            Log.w("BinderProxyManager", "Unable to replace storage_manager", it)
        }
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
        proxyLabel: String,
        onReplaced: ((Any) -> Unit)? = null
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
            onReplaced?.invoke(proxy)
        }.onFailure {
            Log.w("BinderProxyManager", "Unable to replace static field $proxyLabel", it)
        }
    }

    private fun replaceActivePackageManager(packageManager: PackageManager, proxy: Any? = null) {
        runCatching {
            val delegateField = generateSequence(packageManager.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { clazz ->
                    runCatching { clazz.getDeclaredField("mPM") }.getOrNull()
                }
                .firstOrNull()
                ?: return@runCatching
            delegateField.isAccessible = true
            delegateField.set(packageManager, proxy ?: replaceTargetsPackageManager())
            Log.d("BinderProxyManager", "Replaced active PackageManager delegate on ${packageManager.javaClass.name}")
        }.onFailure {
            Log.w("BinderProxyManager", "Unable to replace active PackageManager delegate", it)
        }
    }

    private fun replaceTargetsPackageManager(): Any =
        runCatching {
            val ownerClass = Class.forName("android.app.ActivityThread")
            val field = ownerClass.getDeclaredField("sPackageManager").apply { isAccessible = true }
            field.get(null) ?: error("sPackageManager is null")
        }.getOrElse {
            replacedTargets["package_manager"] ?: error("package_manager proxy unavailable")
        }

    private fun createProxy(target: Any, proxyLabel: String): Any {
        val interfaces = buildSet {
            target.javaClass.interfaces.forEach(::add)
            if (target.javaClass.isInterface) add(target.javaClass)
        }.toTypedArray()
        if (interfaces.isEmpty()) return target
        val handler = DelegatingHandler(
            target = target,
            proxyLabel = proxyLabel,
            activityManagerHook = activityManagerHook,
            virtualPackageArchiveResolver = virtualPackageArchiveResolver
        ) { hostPackageName }
        return Proxy.newProxyInstance(target.javaClass.classLoader, interfaces, handler)
    }

    private class DelegatingHandler(
        private val target: Any,
        private val proxyLabel: String,
        private val activityManagerHook: ActivityManagerHook,
        private val virtualPackageArchiveResolver: VirtualPackageArchiveResolver,
        private val hostPackageNameProvider: () -> String?
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val rewrittenArgs = args
                ?.let { original -> Array<Any?>(original.size) { index -> original[index] } }
                ?: emptyArray()
            rewritePackageManagerQuery(method, rewrittenArgs)?.let { return it }
            maybeShortCircuitBindService(method, rewrittenArgs)?.let { return it }
            rewriteCallingPackage(method, rewrittenArgs)
            rewriteStoragePackage(method, rewrittenArgs)
            rewriteActivityIntent(method, rewrittenArgs)
            rewriteServiceIntent(method, rewrittenArgs)
            return runCatching {
                method.invoke(target, *rewrittenArgs)
            }.onFailure {
                Log.w("BinderProxyManager", "Proxy invocation failed for $proxyLabel.${method.name}", it)
            }.getOrThrow()
        }

        private fun rewriteCallingPackage(method: Method, args: Array<Any?>) {
            val hostPackageName = hostPackageNameProvider() ?: return
            if (proxyLabel != "activity_manager" && proxyLabel != "activity_task_manager") return
            if (
                !method.name.startsWith("startActivity") &&
                !method.name.startsWith("getIntentSender")
            ) {
                return
            }
            val firstStringIndex = args.indexOfFirst { it is String }
            if (firstStringIndex == -1) return
            if (args[firstStringIndex] == hostPackageName) return
            Log.d(
                "BinderProxyManager",
                "Rewriting ${proxyLabel}.${method.name} callingPackage=${args[firstStringIndex]} -> $hostPackageName"
            )
            args[firstStringIndex] = hostPackageName
        }

        private fun rewriteActivityIntent(method: Method, args: Array<Any?>) {
            if (proxyLabel != "activity_manager" && proxyLabel != "activity_task_manager") return
            if (!method.name.startsWith("startActivity")) return
            val intentIndex = args.indexOfFirst { it is Intent }
            if (intentIndex == -1) return
            val originalIntent = args[intentIndex] as? Intent ?: return
            val rewrittenIntent = activityManagerHook.rewriteActivityIntent(originalIntent) ?: return
            Log.i(
                "BinderProxyManager",
                "Rewriting ${proxyLabel}.${method.name} intent ${originalIntent.component ?: originalIntent.action} -> ${rewrittenIntent.component}"
            )
            args[intentIndex] = rewrittenIntent
        }

        private fun maybeShortCircuitBindService(method: Method, args: Array<Any?>): Any? {
            if (proxyLabel != "activity_manager" && proxyLabel != "activity_task_manager") return null
            if (!method.name.startsWith("bindService")) return null
            val intent = args.firstOrNull { it is Intent } as? Intent ?: return null
            if (!activityManagerHook.shouldSilentlyDenyBind(intent)) return null
            Log.i(
                "BinderProxyManager",
                "Short-circuiting ${proxyLabel}.${method.name} for service ${intent.component}"
            )
            return defaultReturnValue(method.returnType)
        }

        private fun rewriteStoragePackage(method: Method, args: Array<Any?>) {
            val hostPackageName = hostPackageNameProvider() ?: return
            if (proxyLabel != "storage_manager") return
            if (method.name != "getVolumeList") return
            val packageIndex = args.indexOfFirst { it is String }
            if (packageIndex == -1) return
            if (args[packageIndex] == hostPackageName) return
            Log.d(
                "BinderProxyManager",
                "Rewriting storage package for ${method.name} ${args[packageIndex]} -> $hostPackageName"
            )
            args[packageIndex] = hostPackageName
        }

        private fun rewriteServiceIntent(method: Method, args: Array<Any?>) {
            if (proxyLabel != "activity_manager" && proxyLabel != "activity_task_manager") return
            val intentIndex = args.indexOfFirst { it is Intent }
            if (intentIndex == -1) return
            val originalIntent = args[intentIndex] as? Intent ?: return
            val rewrittenIntent = when {
                method.name.startsWith("bindService") -> activityManagerHook.rewriteBindServiceIntent(originalIntent)
                method.name.startsWith("startService") -> activityManagerHook.rewriteStartServiceIntent(originalIntent)
                else -> null
            } ?: return
            Log.i(
                "BinderProxyManager",
                "Rewriting ${proxyLabel}.${method.name} service ${originalIntent.component ?: originalIntent.action} -> ${rewrittenIntent.component}"
            )
            args[intentIndex] = rewrittenIntent
        }

        private fun rewritePackageManagerQuery(method: Method, args: Array<Any?>): Any? {
            if (proxyLabel != "package_manager") return null
            return when (method.name) {
                "getApplicationInfo" -> {
                    val packageName = args.firstOrNull() as? String ?: return null
                    virtualPackageArchiveResolver.applicationInfo(packageName)?.also {
                        Log.d("BinderProxyManager", "Resolved virtual getApplicationInfo($packageName)")
                    }
                }
                "getPackageInfo", "getPackageInfoVersioned" -> {
                    val packageName = when (val targetArg = args.firstOrNull()) {
                        is String -> targetArg
                        is android.content.pm.VersionedPackage -> targetArg.packageName
                        else -> null
                    } ?: return null
                    virtualPackageArchiveResolver.packageInfo(packageName)?.also {
                        Log.d("BinderProxyManager", "Resolved virtual ${method.name}($packageName)")
                    }
                }
                "getActivityInfo" -> {
                    val componentName = args.firstOrNull() as? ComponentName ?: return null
                    virtualPackageArchiveResolver.activityInfo(componentName)?.also {
                        Log.d("BinderProxyManager", "Resolved virtual getActivityInfo($componentName)")
                    }
                }
                "getServiceInfo" -> {
                    val componentName = args.firstOrNull() as? ComponentName ?: return null
                    virtualPackageArchiveResolver.serviceInfo(componentName)?.also {
                        Log.d(
                            "BinderProxyManager",
                            "Resolved virtual getServiceInfo($componentName) metaKeys=${it.metaData?.keySet()?.sorted() ?: emptyList<String>()}"
                        )
                    } ?: run {
                        Log.d("BinderProxyManager", "Missed virtual getServiceInfo($componentName)")
                        null
                    }
                }
                "getProviderInfo" -> {
                    val componentName = args.firstOrNull() as? ComponentName ?: return null
                    virtualPackageArchiveResolver.providerInfo(componentName)?.also {
                        Log.d("BinderProxyManager", "Resolved virtual getProviderInfo($componentName)")
                    }
                }
                else -> null
            }
        }

        private fun defaultReturnValue(returnType: Class<*>): Any? = when (returnType) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
            java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
            else -> null
        }
    }
}
