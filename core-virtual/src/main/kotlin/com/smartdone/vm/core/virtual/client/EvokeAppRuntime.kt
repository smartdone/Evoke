package com.smartdone.vm.core.virtual.client

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.install.ApkParser
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import java.io.File
import java.lang.reflect.Method
import android.app.Activity
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import android.content.ContentProvider
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import javax.inject.Inject
import javax.inject.Singleton
import com.smartdone.vm.core.virtual.model.ProviderComponentInfo
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import android.os.Process
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap

data class EvokeAppRuntimeSession(
    val packageName: String,
    val userId: Int,
    val apkPath: String,
    val splitApkPaths: List<String>,
    val launcherActivity: String?,
    val applicationClassName: String?,
    val resources: Resources,
    val applicationInfo: ApplicationInfo,
    val activityInfos: Map<String, ActivityInfo>,
    val providerComponents: List<ProviderComponentInfo>,
    val bridgeClassLoader: BridgeClassLoader,
    val evokeAppClassLoader: DexClassLoader,
    val nativeLibDir: String,
    val optimizedDir: String
)

data class PreferredThemeResolution(
    val themeResId: Int,
    val themeContext: Context? = null,
    val sourceClassName: String? = null,
    val sourceMethodName: String? = null
)

@Singleton
class EvokeAppRuntime @Inject constructor(
    private val repository: EvokeAppRepository,
    private val sandboxPath: SandboxPath,
    private val apkParser: ApkParser
) {
    private var bootstrappedApp: BootstrappedApp? = null
    private val guestBridgeClassCache = ConcurrentHashMap<String, List<String>>()
    private val contextSingletonWarmupCache = ConcurrentHashMap<String, List<String>>()
    private val startupBridgeClassCache = ConcurrentHashMap<String, List<String>>()
    private val singletonRepairClassCache = ConcurrentHashMap<String, List<String>>()
    private val themeWrapperRepairClassCache = ConcurrentHashMap<String, List<String>>()
    private val interfaceInjectorRepairClassCache = ConcurrentHashMap<String, List<String>>()
    private val staticRuntimeInitializerClassCache = ConcurrentHashMap<String, List<String>>()

    fun createSession(
        context: Context,
        packageName: String,
        userId: Int,
        apkPathOverride: String? = null,
        launcherActivityOverride: String? = null,
        applicationClassNameOverride: String? = null,
        nativeLibDirOverride: String? = null,
        optimizedDirOverride: String? = null
    ): EvokeAppRuntimeSession? = runBlocking {
        val app = if (apkPathOverride == null) repository.getApp(packageName) else null
        val apkPath = apkPathOverride ?: app?.apkPath ?: return@runBlocking null
        val metadata = apkParser.parseArchive(apkPath)
        val hookClasses = setOf(
            "com.smartdone.vm.core.virtual.client.hook.ActivityManagerHook",
            "com.smartdone.vm.core.virtual.client.hook.PackageManagerHook",
            "com.smartdone.vm.core.virtual.client.hook.ContentProviderHook",
            "com.smartdone.vm.core.virtual.client.hook.BroadcastHook",
            "com.smartdone.vm.core.virtual.client.hook.PermissionHook",
            "com.smartdone.vm.core.virtual.client.hook.DeviceInfoHook",
            "com.smartdone.vm.core.virtual.client.hook.ContextHook",
            "com.smartdone.vm.core.virtual.client.EvokeAppClient"
        )
        sandboxPath.ensureAppStructure(packageName, userId)
        sandboxPath.sealArchiveIfManaged(apkPath)
        val bridgeClassLoader = BridgeClassLoader(
            hostClassLoader = context.classLoader,
            hookClassWhitelist = hookClasses
        )
        val apkFile = File(apkPath)
        val splitApkPaths = resolveSplitApkPaths(packageName, apkPathOverride)
        val optimizedDir = optimizedDirOverride ?: sandboxPath.optimizedDir(packageName).absolutePath
        val nativeLibDir = nativeLibDirOverride ?: sandboxPath.nativeLibDir(packageName).absolutePath
        Log.i(
            TAG,
            "createSession package=$packageName userId=$userId apk=$apkPath " +
            "exists=${apkFile.exists()} canWrite=${apkFile.canWrite()} " +
                "splitCount=${splitApkPaths.size} " +
                "optimizedDir=$optimizedDir nativeLibDir=$nativeLibDir"
        )
        val resources = createResources(context, apkPath, splitApkPaths)
        val archiveInfo = context.packageManager.getPackageArchiveInfo(
            apkPath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
        )
        val applicationInfo = archiveInfo
            ?.applicationInfo
            ?.apply {
                sourceDir = apkPath
                publicSourceDir = apkPath
                splitSourceDirs = splitApkPaths.toTypedArray()
                splitPublicSourceDirs = splitApkPaths.toTypedArray()
                dataDir = sandboxPath.dataDir(userId, packageName).absolutePath
                nativeLibraryDir = nativeLibDir
                processName = processName ?: packageName
                uid = Process.myUid()
                enabled = true
            }
            ?: context.applicationInfo
        val activityInfos = archiveInfo
            ?.activities
            .orEmpty()
            .map { info ->
                info.apply {
                    this.applicationInfo = applicationInfo
                }
            }
            .associateBy { it.name }
        val evokeAppClassLoader = DexClassLoader(
            buildClassPath(apkPath, splitApkPaths),
            optimizedDir,
            nativeLibDir,
            bridgeClassLoader
        )
        EvokeAppRuntimeSession(
            packageName = packageName,
            userId = userId,
            apkPath = apkPath,
            splitApkPaths = splitApkPaths,
            launcherActivity = launcherActivityOverride ?: metadata?.launcherActivity ?: metadata?.activities?.firstOrNull(),
            applicationClassName = applicationClassNameOverride ?: metadata?.applicationClassName,
            resources = resources,
            applicationInfo = applicationInfo,
            activityInfos = activityInfos,
            providerComponents = metadata?.providerComponents.orEmpty(),
            bridgeClassLoader = bridgeClassLoader,
            evokeAppClassLoader = evokeAppClassLoader,
            nativeLibDir = nativeLibDir,
            optimizedDir = optimizedDir
        )
    }

    fun bootstrapApplication(
        context: Context,
        session: EvokeAppRuntimeSession
    ): RuntimeBootstrapResult {
        val bootstrapped = bootstrappedApp
            ?.takeIf {
                it.packageName == session.packageName &&
                    it.userId == session.userId &&
                    it.apkPath == session.apkPath
            }
            ?: createBootstrappedApp(context, session).also { bootstrappedApp = it }
        val loadedLauncher = session.launcherActivity?.let { launcherActivity ->
            runCatching { session.evokeAppClassLoader.loadClass(launcherActivity).name }.getOrNull()
        }
        val activityStatus = session.launcherActivity?.let { launcherActivity ->
            runCatching {
                val activityClass = session.evokeAppClassLoader.loadClass(launcherActivity)
                "loadable:${activityClass.name}"
            }.fold(
                onSuccess = { it },
                onFailure = { "failed:${it.javaClass.simpleName}" }
            )
        } ?: "missing"
        return RuntimeBootstrapResult(
            applicationStatus = bootstrapped.applicationStatus,
            launcherStatus = loadedLauncher ?: "missing",
            activityStatus = activityStatus,
            application = bootstrapped.application,
            applicationContext = bootstrapped.applicationContext
        )
    }

    fun createActivityContext(
        base: Context,
        session: EvokeAppRuntimeSession,
        applicationContextOverride: Context? = null,
        themeResIdOverride: Int? = null,
        reportedPackageNameOverride: String? = null
    ): EvokeAppContext =
        EvokeAppContext(
            base = base,
            evokePackageName = session.packageName,
            reportedPackageName = reportedPackageNameOverride ?: session.packageName,
            evokeClassLoader = session.evokeAppClassLoader,
            evokeResources = session.resources,
            evokeApplicationInfo = session.applicationInfo,
            initialThemeResId = themeResIdOverride ?: session.applicationInfo.theme
        ).also { appContext ->
            applicationContextOverride?.let(appContext::setVirtualApplicationContext)
        }

    fun resolvePreferredTheme(
        session: EvokeAppRuntimeSession,
        fallbackThemeResId: Int
    ): PreferredThemeResolution {
        val primaryPackagePrefixes = resolvePrimaryGuestRepairPackagePrefixes(session)
        resolveThemeWrapperRepairClassNames(session)
            .asSequence()
            .filter { className ->
                shouldConsiderPrimaryGuestRepairClass(
                    className = className,
                    primaryPackagePrefixes = primaryPackagePrefixes
                )
            }
            .forEach { className ->
                val resolution = runCatching {
                    val clazz = session.evokeAppClassLoader.loadClass(className)
                    val wrapperResolution = resolvePreferredThemeWrapper(clazz)
                        ?: return@runCatching null
                    val resolvedThemeResId = resolveThemeResId(wrapperResolution.context)
                    val selectedThemeResId = resolvedThemeResId
                        ?.takeIf { it != 0 }
                        ?: fallbackThemeResId
                    Log.i(
                        TAG,
                        "Resolved preferred theme wrapper class=$className method=${wrapperResolution.methodName} " +
                            "wrapper=${wrapperResolution.context.javaClass.name} " +
                            "wrapperTheme=0x${(resolvedThemeResId ?: 0).toString(16)} " +
                            "selectedTheme=0x${selectedThemeResId.toString(16)}"
                    )
                    PreferredThemeResolution(
                        themeResId = selectedThemeResId,
                        themeContext = wrapperResolution.context,
                        sourceClassName = className,
                        sourceMethodName = wrapperResolution.methodName
                    )
                }.onFailure {
                    Log.d(TAG, "Unable to resolve preferred theme wrapper $className", it)
                }.getOrNull()
                if (resolution != null) {
                    return resolution
                }
            }
        Log.i(
            TAG,
            "Falling back to manifest theme for ${session.packageName} " +
                "theme=0x${fallbackThemeResId.toString(16)}"
        )
        return PreferredThemeResolution(themeResId = fallbackThemeResId)
    }

    fun resolvePreferredThemeResId(
        session: EvokeAppRuntimeSession,
        fallbackThemeResId: Int
    ): Int = resolvePreferredTheme(session, fallbackThemeResId).themeResId

    private fun attachBaseContext(target: Any, context: Context) {
        val attachMethod: Method = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { clazz ->
                runCatching {
                    clazz.getDeclaredMethod("attachBaseContext", Context::class.java)
                }.getOrNull()
            }
            .firstOrNull()
            ?: Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)
        attachMethod.isAccessible = true
        attachMethod.invoke(target, context)
    }

    private fun attachApplication(target: Application, context: Context) {
        val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attachMethod.isAccessible = true
        attachMethod.invoke(target, context)
    }

    private fun registerApplicationWithActivityThread(application: Application) {
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null)
                ?: return@runCatching
            activityThreadClass.getDeclaredField("mInitialApplication").apply {
                isAccessible = true
                set(currentActivityThread, application)
            }
            val applicationsField = activityThreadClass.getDeclaredField("mAllApplications").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val applications = applicationsField.get(currentActivityThread) as? MutableList<Application>
            if (applications != null && application !in applications) {
                applications.add(application)
            }
            Log.i(TAG, "Registered virtual application ${application::class.java.name} with ActivityThread")
        }.onFailure {
            Log.w(TAG, "Unable to register virtual application with ActivityThread", it)
        }
    }

    private fun primeGuestBridgeStatics(
        context: Context,
        session: EvokeAppRuntimeSession,
        application: Application? = null
    ) {
        val handles = resolveGuestBridgeHandles(session)
        if (handles.isEmpty()) {
            Log.d(TAG, "No guest bridge candidates found for ${session.packageName}")
            return
        }
        var touchedCount = 0
        handles.forEach { handle ->
            val touched = runCatching {
                val updatedFields = updateGuestBridgeStaticFields(
                    clazz = handle.clazz,
                    application = application,
                    context = context
                )
                val attached = invokeGuestBridgeAttach(
                    handle = handle,
                    application = application,
                    context = context
                )
                if (updatedFields > 0 || attached) {
                    Log.i(
                        TAG,
                        "Primed guest bridge ${handle.className} for ${session.packageName} " +
                            "updatedFields=$updatedFields attached=$attached"
                    )
                    true
                } else {
                    false
                }
            }.onFailure {
                Log.d(TAG, "Skipping guest bridge priming for ${handle.className}", it)
            }.getOrDefault(false)
            if (touched) {
                touchedCount += 1
            }
        }
        if (touchedCount > 0) {
            Log.i(TAG, "Guest bridge priming complete for ${session.packageName} touched=$touchedCount")
        }
    }

    private fun initializeGuestBridgeRuntime(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val handles = resolveGuestBridgeHandles(session)
        var initializedCount = 0
        handles.forEach { handle ->
            val initialized = runCatching {
                val invokedMethods = invokeGuestBridgeLifecycleMethods(
                    handle = handle,
                    application = application
                )
                if (invokedMethods.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "Initialized guest bridge ${handle.className} for ${session.packageName} " +
                            "methods=${invokedMethods.joinToString()}"
                    )
                    true
                } else {
                    false
                }
            }.onFailure {
                Log.d(TAG, "Skipping guest bridge initialization for ${handle.className}", it)
            }.getOrDefault(false)
            if (initialized) {
                initializedCount += 1
            }
        }
        warmContextBackedSingletons(
            application = application,
            session = session
        )
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null)
                ?: return@runCatching
            val instrumentation = activityThreadClass.getDeclaredField("mInstrumentation").apply {
                isAccessible = true
            }.get(currentActivityThread)
            Log.d(
                TAG,
                "Guest bridge runtime ready package=${session.packageName} " +
                    "application=${application.javaClass.name} instrumentation=${instrumentation?.javaClass?.name} " +
                    "bridges=$initializedCount"
            )
        }.onFailure {
            Log.d(TAG, "Unable to inspect guest bridge runtime for ${session.packageName}", it)
        }
    }

    private fun resolveGuestBridgeHandles(
        session: EvokeAppRuntimeSession
    ): List<GuestBridgeHandle> =
        resolveGuestBridgeClassNames(session).mapNotNull { className ->
            runCatching {
                val clazz = session.evokeAppClassLoader.loadClass(className)
                GuestBridgeHandle(
                    className = className,
                    clazz = clazz,
                    instance = if (requiresGuestBridgeInstance(clazz)) {
                        resolveGuestBridgeInstance(clazz)
                    } else {
                        null
                    }
                )
            }.onFailure {
                Log.d(TAG, "Unable to resolve guest bridge handle for $className", it)
            }.getOrNull()
        }

    private fun resolveGuestBridgeClassNames(
        session: EvokeAppRuntimeSession
    ): List<String> =
        guestBridgeClassCache.getOrPut(
            buildString {
                append(session.apkPath)
                session.splitApkPaths.forEach {
                    append('|')
                    append(it)
                }
            }
        ) {
            val primaryPackagePrefixes = resolvePrimaryGuestRepairPackagePrefixes(session)
            sequenceOf(session.apkPath)
                .plus(session.splitApkPaths.asSequence())
                .flatMap(::enumerateArchiveClassNames)
                .filter { className ->
                    shouldConsiderPrimaryGuestRepairClass(
                        className = className,
                        primaryPackagePrefixes = primaryPackagePrefixes
                    )
                }
                .filter { className ->
                    runCatching {
                        val clazz = session.evokeAppClassLoader.loadClass(className)
                        shouldConsiderGuestBridgeClass(clazz)
                    }.getOrDefault(false)
                }
                .sortedWith(
                    compareByDescending<String> { scoreGuestBridgeClassName(it) }
                        .thenBy { it.length }
                        .thenBy { it }
                )
                .toList()
        }

    private fun shouldConsiderGuestBridgeClass(clazz: Class<*>): Boolean {
        if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return false
        if (Application::class.java.isAssignableFrom(clazz)) return false
        if (ContentProvider::class.java.isAssignableFrom(clazz)) return false
        if (Activity::class.java.isAssignableFrom(clazz)) return false
        if (isGuestUiInstantiationType(clazz)) return false
        val hasAttach = clazz.methods.any(::isGuestBridgeAttachMethod)
        val hasOnCreate = clazz.methods.any(::isGuestBridgeOnCreateMethod)
        if (hasAttach || hasOnCreate) {
            return true
        }
        return scoreGuestBridgeClassName(clazz.name) > 0 &&
            clazz.methods.any(::isGuestBridgeLifecycleMethod) &&
            clazz.declaredFields.any(::isGuestBridgeContextField)
    }

    private fun scoreGuestBridgeClassName(className: String): Int =
        buildList {
            if ("Application" in className) add(6)
            if ("App" in className) add(3)
            if ("Like" in className) add(4)
            if ("Shell" in className) add(3)
            if ("Runtime" in className) add(2)
            if ("Client" in className) add(2)
            if ("Channel" in className) add(2)
            if ("Context" in className) add(2)
        }.sum()

    private fun resolveGuestBridgeInstance(clazz: Class<*>): Any? {
        clazz.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                !field.type.isPrimitive &&
                field.name.equals("INSTANCE", ignoreCase = true)
        }?.let { field ->
            field.isAccessible = true
            field.get(null)?.takeIf(clazz::isInstance)?.let { return it }
        }
        clazz.methods
            .filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType != Void.TYPE &&
                    (
                        method.name.equals("getInstance", ignoreCase = true) ||
                            method.name.equals("instance", ignoreCase = true) ||
                            method.name.equals("current", ignoreCase = true) ||
                            method.name.equals("singleton", ignoreCase = true)
                        )
            }
            .sortedWith(
                compareByDescending<Method> { it.name.equals("getInstance", ignoreCase = true) }
                    .thenByDescending { it.name.equals("instance", ignoreCase = true) }
                    .thenBy { it.name.length }
            )
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(null)
                }.getOrNull()?.takeIf(clazz::isInstance)?.let { return it }
            }
        return null
    }

    private fun requiresGuestBridgeInstance(clazz: Class<*>): Boolean =
        clazz.methods.any { method ->
            !Modifier.isStatic(method.modifiers) &&
                (isGuestBridgeAttachMethod(method) || isGuestBridgeLifecycleMethod(method))
        }

    private fun updateGuestBridgeStaticFields(
        clazz: Class<*>,
        application: Application?,
        context: Context
    ): Int {
        var updated = 0
        generateSequence(clazz) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .forEach { field ->
                if (!Modifier.isStatic(field.modifiers) || Modifier.isFinal(field.modifiers)) {
                    return@forEach
                }
                if (!isGuestBridgeContextField(field)) {
                    return@forEach
                }
                val value = when {
                    application != null && field.type.isInstance(application) -> application
                    application != null && field.type.isAssignableFrom(Application::class.java) -> application
                    field.type.isInstance(context) -> context
                    field.type.isAssignableFrom(Context::class.java) -> context
                    field.type.isAssignableFrom(ContextWrapper::class.java) -> context
                    else -> return@forEach
                }
                runCatching {
                    field.isAccessible = true
                    val currentValue = field.get(null)
                    if (currentValue !== value) {
                        field.set(null, value)
                        updated += 1
                    }
                }
            }
        return updated
    }

    private fun isGuestBridgeContextField(field: Field): Boolean {
        if (field.type.isPrimitive) return false
        val supportedType =
            field.type.isAssignableFrom(Application::class.java) ||
                field.type.isAssignableFrom(Context::class.java) ||
                field.type.isAssignableFrom(ContextWrapper::class.java)
        if (!supportedType) return false
        val normalizedName = field.name.lowercase()
        return normalizedName.contains("app") || normalizedName.contains("context")
    }

    private fun invokeGuestBridgeAttach(
        handle: GuestBridgeHandle,
        application: Application?,
        context: Context
    ): Boolean {
        val method = handle.clazz.methods.firstOrNull(::isGuestBridgeAttachMethod) ?: return false
        val args = resolveGuestBridgeArgs(
            method = method,
            application = application,
            context = context
        ) ?: return false
        val receiver = if (Modifier.isStatic(method.modifiers)) null else handle.instance ?: return false
        method.isAccessible = true
        method.invoke(receiver, *args)
        return true
    }

    private fun invokeGuestBridgeLifecycleMethods(
        handle: GuestBridgeHandle,
        application: Application
    ): List<String> {
        val invokedMethods = mutableListOf<String>()
        handle.clazz.methods
            .filter(::isGuestBridgeLifecycleMethod)
            .sortedWith(
                compareByDescending<Method> { it.name == "onCreate" }
                    .thenByDescending { it.name.startsWith("init", ignoreCase = true) }
                    .thenBy { it.name }
                    .thenBy { it.parameterTypes.size }
            )
            .forEach { method ->
                val args = resolveGuestBridgeArgs(
                    method = method,
                    application = application,
                    context = application.applicationContext
                ) ?: return@forEach
                val receiver = if (Modifier.isStatic(method.modifiers)) {
                    null
                } else {
                    handle.instance ?: return@forEach
                }
                runCatching {
                    method.isAccessible = true
                    method.invoke(receiver, *args)
                    invokedMethods += method.name
                }.onFailure {
                    Log.v(
                        TAG,
                        "Guest bridge lifecycle invocation failed ${handle.className}.${method.name}",
                        it
                    )
                }
            }
        return invokedMethods.distinct()
    }

    private fun isGuestBridgeAttachMethod(method: Method): Boolean =
        method.name == "attachBaseContext" &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 1 &&
            (
                method.parameterTypes[0].isAssignableFrom(Context::class.java) ||
                    method.parameterTypes[0].isAssignableFrom(Application::class.java) ||
                    method.parameterTypes[0].isAssignableFrom(ContextWrapper::class.java)
                )

    private fun isGuestBridgeLifecycleMethod(method: Method): Boolean {
        if (method.returnType != Void.TYPE) return false
        if (method.parameterTypes.size > 1) return false
        if (
            method.parameterTypes.singleOrNull()?.let { parameterType ->
                !parameterType.isAssignableFrom(Application::class.java) &&
                    !parameterType.isAssignableFrom(Context::class.java) &&
                    !parameterType.isAssignableFrom(ContextWrapper::class.java)
            } == true
        ) {
            return false
        }
        return method.name == "onCreate" ||
            method.name.startsWith("init", ignoreCase = true) ||
            method.name.startsWith("initialize", ignoreCase = true) ||
            method.name.startsWith("install", ignoreCase = true) ||
            method.name.startsWith("setup", ignoreCase = true) ||
            method.name.startsWith("start", ignoreCase = true) ||
            method.name.startsWith("bootstrap", ignoreCase = true)
    }

    private fun isGuestBridgeOnCreateMethod(method: Method): Boolean =
        method.name == "onCreate" && isGuestBridgeLifecycleMethod(method)

    private fun resolveGuestBridgeArgs(
        method: Method,
        application: Application?,
        context: Context
    ): Array<Any?>? {
        if (method.parameterTypes.isEmpty()) {
            return emptyArray()
        }
        val parameterType = method.parameterTypes.single()
        return when {
            application != null && parameterType.isInstance(application) -> arrayOf(application)
            application != null && parameterType.isAssignableFrom(Application::class.java) -> arrayOf(application)
            parameterType.isInstance(context) -> arrayOf(context)
            parameterType.isAssignableFrom(Context::class.java) -> arrayOf(context)
            parameterType.isAssignableFrom(ContextWrapper::class.java) -> arrayOf(context)
            else -> null
        }
    }

    private fun warmContextBackedSingletons(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val candidateClassNames = resolveContextSingletonWarmupCandidates(session)
        if (candidateClassNames.isEmpty()) {
            Log.d(TAG, "No context-backed singleton warmup candidates for ${session.packageName}")
            return
        }
        var initializedCount = 0
        candidateClassNames.forEach { className ->
            runCatching {
                val clazz = session.evokeAppClassLoader.loadClass(className)
                val singletonField = findContextSingletonField(clazz) ?: return@runCatching
                singletonField.isAccessible = true
                if (singletonField.get(null) != null) {
                    Log.d(TAG, "Context-backed singleton already ready ${clazz.name}")
                    return@runCatching
                }
                val constructor = findContextSingletonConstructor(clazz) ?: return@runCatching
                constructor.isAccessible = true
                val instance = constructor.newInstance(resolveContextSingletonArgument(constructor, application))
                singletonField.set(null, instance)
                initializedCount += 1
                Log.i(TAG, "Warmed context-backed singleton ${clazz.name} for ${session.packageName}")
            }.onFailure {
                Log.d(
                    TAG,
                    "Unable to warm context-backed singleton $className for ${session.packageName}",
                    it
                )
            }
        }
        Log.i(
            TAG,
            "Context-backed singleton warmup complete for ${session.packageName} initialized=$initializedCount candidates=${candidateClassNames.size}"
        )
    }

    private fun initializeStaticRuntimeModulesIfPresent(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val candidateClassNames = resolveStaticRuntimeInitializerClassNames(session)
        if (candidateClassNames.isEmpty()) {
            Log.d(TAG, "No static runtime module initializers for ${session.packageName}")
            return
        }
        var initializedCount = 0
        candidateClassNames.forEach { className ->
            val initialized = runCatching {
                val clazz = session.evokeAppClassLoader.loadClass(className)
                val method = clazz.methods
                    .filter(::isSupportedStaticRuntimeInitializerMethod)
                    .sortedWith(
                        compareByDescending<Method> { scoreStaticRuntimeInitializerMethod(it) }
                            .thenBy { it.name.length }
                            .thenBy { it.name }
                    )
                    .firstOrNull()
                    ?: return@runCatching false
                val args = resolveGuestBridgeArgs(
                    method = method,
                    application = application,
                    context = application.applicationContext
                ) ?: return@runCatching false
                method.isAccessible = true
                method.invoke(null, *args)
                Log.i(
                    TAG,
                    "Initialized static runtime module ${clazz.name}.${method.name} for ${session.packageName}"
                )
                true
            }.onFailure {
                Log.v(TAG, "Skipping static runtime module initializer $className", it)
            }.getOrDefault(false)
            if (initialized) {
                initializedCount += 1
            }
        }
        if (initializedCount > 0) {
            Log.i(
                TAG,
                "Static runtime module initialization complete for ${session.packageName} initialized=$initializedCount"
            )
        }
    }

    private fun resolveStaticRuntimeInitializerClassNames(
        session: EvokeAppRuntimeSession
    ): List<String> =
        staticRuntimeInitializerClassCache.getOrPut(
            buildString {
                append(session.apkPath)
                session.splitApkPaths.forEach {
                    append('|')
                    append(it)
                }
            }
        ) {
            val primaryPackagePrefixes = resolvePrimaryGuestRepairPackagePrefixes(session)
            sequenceOf(session.apkPath)
                .plus(session.splitApkPaths.asSequence())
                .flatMap(::enumerateArchiveClassNames)
                .filter { className ->
                    shouldConsiderStaticRuntimeInitializerClass(
                        className = className,
                        primaryPackagePrefixes = primaryPackagePrefixes
                    )
                }
                .mapNotNull { className ->
                    runCatching {
                        val clazz = session.evokeAppClassLoader.loadClass(className)
                        className.takeIf { shouldInitializeStaticRuntimeModule(clazz) }
                    }.onFailure {
                        Log.v(TAG, "Skipping static runtime module scan for $className", it)
                    }.getOrNull()
                }
                .sortedWith(
                    compareByDescending<String> { scoreStaticRuntimeInitializerClassName(it) }
                        .thenBy { it.length }
                        .thenBy { it }
                )
                .toList()
        }

    private fun shouldConsiderStaticRuntimeInitializerClass(
        className: String,
        primaryPackagePrefixes: Set<String>
    ): Boolean {
        if ('$' in className) return false
        if (className.endsWith(".BuildConfig")) return false
        if (className.endsWith(".R")) return false
        if (className.contains(".R$")) return false
        if (primaryPackagePrefixes.none { prefix -> className == prefix || className.startsWith("$prefix.") }) {
            return false
        }
        val normalizedName = className.lowercase()
        return STATIC_RUNTIME_INITIALIZER_PACKAGE_KEYWORDS.any(normalizedName::contains)
    }

    private fun shouldInitializeStaticRuntimeModule(clazz: Class<*>): Boolean {
        if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return false
        if (Application::class.java.isAssignableFrom(clazz)) return false
        if (ContentProvider::class.java.isAssignableFrom(clazz)) return false
        if (Activity::class.java.isAssignableFrom(clazz)) return false
        if (isGuestUiInstantiationType(clazz)) return false
        if (clazz.declaredFields.any { !Modifier.isStatic(it.modifiers) }) return false
        if (
            clazz.declaredFields.any { field ->
                Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers)
            }
        ) {
            return false
        }
        return clazz.methods.any(::isSupportedStaticRuntimeInitializerMethod)
    }

    private fun isSupportedStaticRuntimeInitializerMethod(method: Method): Boolean {
        if (!Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) {
            return false
        }
        if (method.parameterTypes.size != 1 || method.isSynthetic) {
            return false
        }
        val parameterType = method.parameterTypes.single()
        if (
            !parameterType.isAssignableFrom(Application::class.java) &&
            !parameterType.isAssignableFrom(Context::class.java) &&
            !parameterType.isAssignableFrom(ContextWrapper::class.java)
        ) {
            return false
        }
        return method.name in STATIC_RUNTIME_INITIALIZER_METHOD_NAMES ||
            (
                method.name.length <= 2 &&
                    method.declaringClass.simpleName.length <= 4
                )
    }

    private fun scoreStaticRuntimeInitializerClassName(className: String): Int {
        val normalizedName = className.lowercase()
        return buildList {
            if (".network." in normalizedName) add(8)
            if (".channel." in normalizedName) add(6)
            if (".runtime." in normalizedName) add(5)
            if (".startup." in normalizedName) add(5)
            if (".service." in normalizedName) add(4)
            if (".sdk." in normalizedName) add(3)
            if (".core." in normalizedName) add(2)
        }.sum()
    }

    private fun scoreStaticRuntimeInitializerMethod(method: Method): Int =
        buildList {
            if (method.name in STATIC_RUNTIME_INITIALIZER_METHOD_NAMES) add(6)
            if (method.name.length <= 2) add(3)
            if (method.parameterTypes.singleOrNull()?.isAssignableFrom(Application::class.java) == true) add(2)
        }.sum()

    private fun resolveContextSingletonWarmupCandidates(
        session: EvokeAppRuntimeSession
    ): List<String> =
        contextSingletonWarmupCache.getOrPut(
            buildString {
                append(session.apkPath)
                session.splitApkPaths.forEach {
                    append('|')
                    append(it)
                }
            }
        ) {
            val candidates = mutableListOf<String>()
            val classNames = sequenceOf(session.apkPath)
                .plus(session.splitApkPaths.asSequence())
                .flatMap(::enumerateArchiveClassNames)
            classNames.forEach { className ->
                if (shouldSkipContextSingletonWarmupClass(className, session.packageName)) {
                    return@forEach
                }
                runCatching {
                    val clazz = session.evokeAppClassLoader.loadClass(className)
                    if (findContextSingletonField(clazz) == null) return@runCatching
                    if (findContextSingletonConstructor(clazz) == null) return@runCatching
                    candidates += className
                }.onFailure {
                    Log.v(TAG, "Skipping singleton warmup scan for $className", it)
                }
            }
            candidates
        }

    private fun enumerateArchiveClassNames(archivePath: String): Sequence<String> = sequence {
        var dexFile: DexFile? = null
        try {
            dexFile = DexFile(archivePath)
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                yield(entries.nextElement())
            }
        } catch (throwable: Throwable) {
            Log.d(TAG, "Unable to enumerate dex entries for $archivePath", throwable)
        } finally {
            runCatching {
                dexFile?.close()
            }
        }
    }

    private fun shouldSkipContextSingletonWarmupClass(
        className: String,
        packageName: String
    ): Boolean {
        if ('$' in className) return true
        if (className.endsWith(".BuildConfig")) return true
        if (className.endsWith(".R")) return true
        if (className.contains(".R$")) return true
        if (className == packageName || className.startsWith("$packageName.")) return false
        if (CONTEXT_SINGLETON_OBFUSCATED_CLASS_NAME.matches(className)) return false
        return true
    }

    private fun findContextSingletonField(clazz: Class<*>): Field? =
        clazz.declaredFields.singleOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                !Modifier.isFinal(field.modifiers) &&
                field.type == clazz
        }

    private fun findContextSingletonConstructor(clazz: Class<*>): Constructor<*>? =
        clazz.declaredConstructors.singleOrNull { constructor ->
            constructor.parameterTypes.size == 1 &&
                CONTEXT_SINGLETON_ARGUMENT_TYPES.any { expected ->
                    expected.isAssignableFrom(constructor.parameterTypes[0]) ||
                        constructor.parameterTypes[0].isAssignableFrom(expected)
                }
        }

    private fun resolveContextSingletonArgument(
        constructor: Constructor<*>,
        application: Application
    ): Any {
        val parameterType = constructor.parameterTypes.first()
        return when {
            parameterType.isInstance(application) -> application
            parameterType.isInstance(application.applicationContext) -> application.applicationContext
            parameterType.isAssignableFrom(Application::class.java) -> application
            parameterType.isAssignableFrom(Context::class.java) -> application.applicationContext
            else -> application
        }
    }

    private fun createResources(
        context: Context,
        apkPath: String,
        splitApkPaths: List<String>
    ): Resources {
        val hostResources = context.resources
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assetManager, apkPath) as? Int ?: 0
        if (cookie == 0) {
            return hostResources
        }
        splitApkPaths.forEach { splitPath ->
            addAssetPath.invoke(assetManager, splitPath)
        }
        return Resources(assetManager, hostResources.displayMetrics, hostResources.configuration)
    }

    private fun buildClassPath(apkPath: String, splitApkPaths: List<String>): String =
        (listOf(apkPath) + splitApkPaths)
            .joinToString(File.pathSeparator)

    private fun resolveSplitApkPaths(packageName: String, apkPathOverride: String?): List<String> {
        if (apkPathOverride.isNullOrBlank()) {
            return sandboxPath.managedSplitApkPaths(packageName)
        }
        val overrideFile = File(apkPathOverride)
        val parentSplits = File(overrideFile.parentFile, "splits")
        if (parentSplits.isDirectory) {
            return parentSplits.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "apk" }
                .sortedBy(File::getName)
                .map(File::getAbsolutePath)
        }
        val managedBase = sandboxPath.apkPath(packageName)
        return if (overrideFile.absolutePath == managedBase.absolutePath) {
            sandboxPath.managedSplitApkPaths(packageName)
        } else {
            emptyList()
        }
    }

    companion object {
        private const val ANDROIDX_CONTEXT_THEME_WRAPPER_CLASS_NAME =
            "androidx.appcompat.view.ContextThemeWrapper"
        private const val TAG = "EvokeAppRuntime"
        private val STARTUP_BRIDGE_CREATE_METHODS = setOf(
            "mainCreate",
            "ioCreate",
            "idleCreate",
            "delayCreate"
        )
        private val SINGLETON_REPAIR_ACCESSOR_METHOD_NAMES = setOf(
            "getInstance",
            "instance",
            "f"
        )
        private val SINGLETON_REPAIR_INIT_METHOD_NAMES = setOf(
            "init",
            "initialize",
            "h"
        )
        private val THEME_WRAPPER_REPAIR_INIT_METHOD_NAMES = setOf(
            "a",
            "init",
            "initialize"
        )
        private val GUEST_CONTEXT_INITIALIZER_METHOD_NAMES = setOf(
            "init",
            "initialize",
            "setup",
            "attach",
            "inject",
            "install",
            "setContext",
            "setApplication",
            "setApp",
            "setEnv"
        )
        private val STATIC_RUNTIME_INITIALIZER_METHOD_NAMES = setOf(
            "init",
            "initialize",
            "setup",
            "install",
            "attach",
            "start",
            "bootstrap",
            "register"
        )
        private val STATIC_RUNTIME_INITIALIZER_PACKAGE_KEYWORDS = setOf(
            ".network.",
            ".channel.",
            ".runtime.",
            ".startup.",
            ".service.",
            ".sdk.",
            ".core."
        )
        private val CONTEXT_SINGLETON_ARGUMENT_TYPES = listOf(
            Context::class.java,
            Application::class.java,
            ContextWrapper::class.java
        )
        private val CONTEXT_SINGLETON_OBFUSCATED_CLASS_NAME =
            Regex("^[a-z][a-z0-9]{0,2}(\\.[A-Za-z][A-Za-z0-9_$]{0,2}){1,3}$")
        private val INTERFACE_INJECTOR_OBFUSCATED_CLASS_NAME =
            Regex("^[a-z][a-z0-9]{0,2}(\\.[A-Za-z][A-Za-z0-9_$]{0,5}){1,2}$")
        private val THEME_WRAPPER_OBFUSCATED_CLASS_NAME =
            Regex("^[a-z][a-z0-9]{0,2}\\.[A-Za-z][A-Za-z0-9_$]{0,5}$")
    }

    private fun bootstrapGuestApplication(
        applicationContext: EvokeAppContext,
        session: EvokeAppRuntimeSession
    ): GuestApplicationBootstrapResult {
        primeGuestBridgeStatics(
            context = applicationContext,
            session = session
        )
        val applicationClassName = session.applicationClassName
        if (applicationClassName.isNullOrBlank()) {
            Log.w(TAG, "Manifest application class missing for ${session.packageName}; using base Application")
            return bootstrapFallbackApplication(
                applicationContext = applicationContext,
                session = session,
                status = "missing"
            )
        }
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            val attemptLabel = if (attempt == 0) "initial" else "retry"
            val created = runCatching {
                val appClass = session.evokeAppClassLoader.loadClass(applicationClassName)
                val constructor = appClass.getDeclaredConstructor().apply {
                    isAccessible = true
                }
                constructor.newInstance() as Application
            }.onFailure { throwable ->
                val cause = rootCause(throwable)
                lastFailure = throwable
                Log.w(
                    TAG,
                    "Unable to create virtual application $applicationClassName for ${session.packageName} " +
                        "attempt=$attemptLabel cause=${cause.javaClass.name}: ${cause.message}",
                    throwable
                )
            }.getOrNull()
            if (created != null) {
                return completeGuestApplicationBootstrap(
                    created = created,
                    applicationContext = applicationContext,
                    session = session,
                    status = "created:${created::class.java.name}"
                )
            }
            if (attempt == 0 && shouldRetryGuestApplicationCreation(lastFailure)) {
                primeGuestBridgeStatics(
                    context = applicationContext,
                    session = session
                )
            }
        }
        return bootstrapFallbackApplication(
            applicationContext = applicationContext,
            session = session,
            status = "failed:${rootCause(lastFailure).javaClass.simpleName}"
        )
    }

    private fun completeGuestApplicationBootstrap(
        created: Application,
        applicationContext: EvokeAppContext,
        session: EvokeAppRuntimeSession,
        status: String
    ): GuestApplicationBootstrapResult {
        val applicationClass = created.javaClass
        Log.i(
            TAG,
            "Bootstrapping guest application package=${session.packageName} " +
                "class=${applicationClass.name} " +
                "attachBaseContextOverride=${declaresApplicationAttachBaseContext(applicationClass)} " +
                "onCreateOverride=${declaresApplicationOnCreate(applicationClass)}"
        )
        attachApplication(created, applicationContext)
        Log.i(TAG, "Attached guest application ${applicationClass.name} for ${session.packageName}")
        registerApplicationWithActivityThread(created)
        applicationContext.setVirtualApplicationContext(created)
        primeGuestBridgeStatics(
            context = applicationContext,
            session = session,
            application = created
        )
        installContentProvidersIfNeeded(created, session)
        initializeMmkvIfPresent(created, session)
        Log.i(TAG, "Calling Application.onCreate for ${applicationClass.name} package=${session.packageName}")
        created.onCreate()
        Log.i(TAG, "Completed Application.onCreate for ${applicationClass.name} package=${session.packageName}")
        initializeGuestBridgeRuntime(created, session)
        initializeStaticRuntimeModulesIfPresent(created, session)
        scheduleDeferredGuestRepairs(created, session)
        return GuestApplicationBootstrapResult(
            application = created,
            status = status
        )
    }

    private fun bootstrapFallbackApplication(
        applicationContext: EvokeAppContext,
        session: EvokeAppRuntimeSession,
        status: String
    ): GuestApplicationBootstrapResult =
        completeGuestApplicationBootstrap(
            created = Application(),
            applicationContext = applicationContext,
            session = session,
            status = status
        )

    private fun declaresApplicationAttachBaseContext(applicationClass: Class<*>): Boolean =
        generateSequence(applicationClass) { current ->
            current.superclass?.takeUnless { it == Application::class.java }
        }.any { clazz ->
            runCatching {
                clazz.getDeclaredMethod("attachBaseContext", Context::class.java)
            }.isSuccess
        }

    private fun declaresApplicationOnCreate(applicationClass: Class<*>): Boolean =
        generateSequence(applicationClass) { current ->
            current.superclass?.takeUnless { it == Application::class.java }
        }.any { clazz ->
            runCatching {
                clazz.getDeclaredMethod("onCreate")
            }.isSuccess
        }

    private fun shouldRetryGuestApplicationCreation(throwable: Throwable?): Boolean {
        val cause = rootCause(throwable)
        return cause is ClassNotFoundException ||
            cause is NoClassDefFoundError ||
            cause is UnsatisfiedLinkError ||
            cause is ExceptionInInitializerError
    }

    private fun rootCause(throwable: Throwable?): Throwable =
        generateSequence(throwable) { current ->
            current.cause?.takeUnless { it === current }
        }.lastOrNull() ?: IllegalStateException("unknown")

    private fun scheduleDeferredGuestRepairs(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        Log.i(
            TAG,
            "Skipping deferred guest repair heuristics during launch for ${session.packageName} " +
                "application=${application.javaClass.name}"
        )
    }

    private fun createBootstrappedApp(
        context: Context,
        session: EvokeAppRuntimeSession
    ): BootstrappedApp {
        val applicationContext = createActivityContext(
            base = context.applicationContext,
            session = session,
            reportedPackageNameOverride = context.applicationContext.packageName
        )
        val application = bootstrapGuestApplication(
            applicationContext = applicationContext,
            session = session
        )
        return BootstrappedApp(
            packageName = session.packageName,
            userId = session.userId,
            apkPath = session.apkPath,
            application = application.application,
            applicationContext = applicationContext,
            applicationStatus = application.status
        )
    }

    private data class BootstrappedApp(
        val packageName: String,
        val userId: Int,
        val apkPath: String,
        val application: Application,
        val applicationContext: EvokeAppContext,
        val applicationStatus: String
    )

    private data class GuestApplicationBootstrapResult(
        val application: Application,
        val status: String
    )

    private data class GuestBridgeHandle(
        val className: String,
        val clazz: Class<*>,
        val instance: Any?
    )

    private fun installContentProvidersIfNeeded(
        context: Context,
        session: EvokeAppRuntimeSession
    ) {
        session.providerComponents.forEach { component ->
            if (!shouldInstallProviderEagerly(component)) {
                Log.i(
                    TAG,
                    "Deferring virtual provider ${component.className} for ${session.packageName} authority=${component.authority}"
                )
                return@forEach
            }
            runCatching {
                val providerClass = session.evokeAppClassLoader.loadClass(component.className)
                val provider = providerClass.getDeclaredConstructor().newInstance() as ContentProvider
                provider.attachInfo(
                    context,
                    ProviderInfo().apply {
                        packageName = session.packageName
                        name = component.className
                        authority = component.authority
                        applicationInfo = session.applicationInfo
                        exported = false
                        grantUriPermissions = true
                    }
                )
                Log.i(TAG, "Installed virtual provider ${component.className} for ${session.packageName}")
            }.onFailure {
                Log.w(TAG, "Unable to install virtual provider ${component.className}", it)
            }
        }
    }

    private fun shouldInstallProviderEagerly(component: ProviderComponentInfo): Boolean {
        val className = component.className.lowercase()
        val authority = component.authority.lowercase()
        return className == "androidx.startup.initializationprovider" ||
            className.contains("firebaseinitprovider") ||
            className.contains("initializationprovider") ||
            className.contains("startupprovider") ||
            authority.contains("firebaseinitprovider")
    }

    private fun runStartupBridgesIfPresent(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val startupBaseClass = runCatching {
            session.evokeAppClassLoader.loadClass("com.rousetime.android_startup.AndroidStartup")
        }.getOrElse {
            Log.d(TAG, "Skipping startup bridge for ${session.packageName}: AndroidStartup unavailable")
            return
        }
        val startupClassNames = resolveStartupBridgeClassNames(session)
        if (startupClassNames.isEmpty()) {
            Log.d(TAG, "No guest startup bridge candidates for ${session.packageName}")
            return
        }
        val startupInstances = linkedMapOf<String, Any>()
        val executed = linkedSetOf<String>()
        val visiting = linkedSetOf<String>()
        var dispatchableCount = 0
        startupClassNames.forEach { className ->
            val startup = instantiateStartupBridge(
                className = className,
                application = application,
                session = session
            ) ?: return@forEach
            startupInstances[className] = startup
            if (!shouldDispatchStartupOnLaunch(startup)) {
                return@forEach
            }
            dispatchableCount += 1
            executeStartupBridgeRecursively(
                className = className,
                startup = startup,
                startupBaseClass = startupBaseClass,
                application = application,
                startupContext = application.applicationContext,
                session = session,
                startupInstances = startupInstances,
                visiting = visiting,
                executed = executed
            )
        }
        Log.i(
            TAG,
            "Startup bridge complete for ${session.packageName} " +
                "candidates=${startupClassNames.size} dispatchable=$dispatchableCount executed=${executed.size}"
        )
    }

    private fun resolveStartupBridgeClassNames(
        session: EvokeAppRuntimeSession
    ): List<String> =
        startupBridgeClassCache.getOrPut(
            buildString {
                append(session.apkPath)
                session.splitApkPaths.forEach {
                    append('|')
                    append(it)
                }
            }
        ) {
            sequenceOf(session.apkPath)
                .plus(session.splitApkPaths.asSequence())
                .flatMap(::enumerateArchiveClassNames)
                .filter { className ->
                    shouldConsiderStartupBridgeClass(className, session)
                }
                .toList()
                .also { candidates ->
                    if (candidates.isNotEmpty()) {
                        Log.i(
                            TAG,
                            "Resolved startup bridge candidates for ${session.packageName}: ${candidates.joinToString()}"
                        )
                    }
                }
        }

    private fun shouldConsiderStartupBridgeClass(
        className: String,
        session: EvokeAppRuntimeSession
    ): Boolean {
        if ('$' in className) return false
        if (!className.endsWith("Startup")) return false
        if (!className.contains(".startup.")) return false
        if (className.startsWith("androidx.")) return false
        if (className.startsWith("com.google.firebase.")) return false
        if (className.startsWith("com.rousetime.android_startup.")) return false
        return resolveStartupBridgePackagePrefixes(session).any { prefix ->
            className == prefix || className.startsWith("$prefix.")
        }
    }

    private fun resolveStartupBridgePackagePrefixes(
        session: EvokeAppRuntimeSession
    ): Set<String> =
        buildSet {
            addStartupBridgePackagePrefixes(session.packageName)
            addStartupBridgePackagePrefixes(session.applicationClassName)
            addStartupBridgePackagePrefixes(session.launcherActivity)
            session.providerComponents.forEach { component ->
                addStartupBridgePackagePrefixes(component.className)
            }
        }

    private fun resolvePrimaryGuestRepairPackagePrefixes(
        session: EvokeAppRuntimeSession
    ): Set<String> =
        buildSet {
            addStartupBridgePackagePrefixes(session.packageName)
            addStartupBridgePackagePrefixes(session.applicationClassName)
            addStartupBridgePackagePrefixes(session.launcherActivity)
        }

    private fun shouldConsiderPrimaryGuestRepairClass(
        className: String,
        primaryPackagePrefixes: Set<String>
    ): Boolean =
        INTERFACE_INJECTOR_OBFUSCATED_CLASS_NAME.matches(className) ||
            primaryPackagePrefixes.any { prefix ->
                className == prefix || className.startsWith("$prefix.")
            }

    private fun MutableSet<String>.addStartupBridgePackagePrefixes(className: String?) {
        if (className.isNullOrBlank()) return
        val parts = className.split('.')
        if (parts.size >= 2) {
            add(parts.take(2).joinToString("."))
        }
        if (parts.size >= 3) {
            add(parts.take(3).joinToString("."))
        }
        if (parts.size > 1) {
            add(className.substringBeforeLast('.'))
        }
    }

    private fun repairGuestSingletonManagersIfPresent(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        var repairedCount = 0
        resolveSingletonRepairClassNames(session).forEach { className ->
            val repaired = runCatching {
                val clazz = session.evokeAppClassLoader.loadClass(className)
                val singleton = resolveSingletonRepairInstance(clazz) ?: return@runCatching false
                val getterMethods = clazz.methods.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.isEmpty() &&
                        method.returnType != Void.TYPE &&
                        !method.returnType.isPrimitive &&
                        method.returnType != clazz
                }
                if (getterMethods.isEmpty()) {
                    return@runCatching false
                }
                val nullGetters = getterMethods.filter { method ->
                    runCatching { method.invoke(null) == null }.getOrDefault(false)
                }
                if (nullGetters.isEmpty()) {
                    return@runCatching false
                }
                val initMethod = clazz.methods.firstOrNull { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.name in SINGLETON_REPAIR_INIT_METHOD_NAMES &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 1 &&
                        (
                            method.parameterTypes[0].isInstance(application) ||
                                method.parameterTypes[0].isAssignableFrom(application.javaClass) ||
                                method.parameterTypes[0].isAssignableFrom(Application::class.java) ||
                                method.parameterTypes[0].isAssignableFrom(Context::class.java)
                            )
                } ?: return@runCatching false
                val arg = if (initMethod.parameterTypes[0].isAssignableFrom(Context::class.java)) {
                    application.applicationContext
                } else {
                    application
                }
                initMethod.isAccessible = true
                initMethod.invoke(singleton, arg)
                val repairedGetters = nullGetters.filter { method ->
                    runCatching { method.invoke(null) != null }.getOrDefault(false)
                }
                if (repairedGetters.isEmpty()) {
                    return@runCatching false
                }
                Log.i(
                    TAG,
                    "Repaired guest singleton manager $className for ${session.packageName} " +
                        "getters=${repairedGetters.joinToString { it.name }}"
                )
                true
            }.onFailure {
                Log.d(TAG, "Skipping guest singleton manager repair for $className", it)
            }.getOrDefault(false)
            if (repaired) {
                repairedCount += 1
            }
        }
        if (repairedCount > 0) {
            Log.i(
                TAG,
                "Guest singleton manager repair complete for ${session.packageName} repaired=$repairedCount"
            )
        }
    }

    private fun resolveSingletonRepairClassNames(
        session: EvokeAppRuntimeSession
    ): List<String> =
        singletonRepairClassCache.getOrPut(
            buildString {
                append(session.apkPath)
                session.splitApkPaths.forEach {
                    append('|')
                    append(it)
                }
            }
        ) {
            val packagePrefixes = resolveStartupBridgePackagePrefixes(session)
            sequenceOf(session.apkPath)
                .plus(session.splitApkPaths.asSequence())
                .flatMap(::enumerateArchiveClassNames)
                .filter { className ->
                    '$' !in className &&
                        !className.contains(".startup.") &&
                        packagePrefixes.any { prefix -> className.startsWith("$prefix.") }
                }
                .toList()
        }

    private fun resolveSingletonRepairInstance(clazz: Class<*>): Any? {
        clazz.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                field.name == "INSTANCE" &&
                clazz.isAssignableFrom(field.type)
        }?.let { field ->
            field.isAccessible = true
            field.get(null)?.let { return it }
        }
        val accessor = clazz.methods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == clazz &&
                method.name in SINGLETON_REPAIR_ACCESSOR_METHOD_NAMES
        } ?: return null
        return accessor.invoke(null)
    }

    private fun repairGuestThemeWrappersIfPresent(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        var repairedCount = 0
        resolveThemeWrapperRepairClassNames(session).forEach { className ->
            val repaired = runCatching {
                val clazz = session.evokeAppClassLoader.loadClass(className)
                val hasThemeField = clazz.declaredFields.any { field ->
                    Modifier.isStatic(field.modifiers) &&
                        field.type.name == ANDROIDX_CONTEXT_THEME_WRAPPER_CLASS_NAME
                }
                if (!hasThemeField) {
                    return@runCatching false
                }
                val getterMethods = clazz.methods.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType.name == ANDROIDX_CONTEXT_THEME_WRAPPER_CLASS_NAME &&
                        (
                            method.parameterTypes.isEmpty() ||
                                (
                                    method.parameterTypes.size == 1 &&
                                        (
                                            method.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                                                method.parameterTypes[0] == Boolean::class.javaObjectType
                                            )
                                    )
                            )
                }
                if (getterMethods.isEmpty()) {
                    return@runCatching false
                }
                val nullGetters = getterMethods.filter { method ->
                    val args = when (method.parameterTypes.size) {
                        0 -> emptyArray()
                        else -> arrayOf(false)
                    }
                    runCatching { method.invoke(null, *args) == null }.getOrDefault(false)
                }
                if (nullGetters.isEmpty()) {
                    return@runCatching false
                }
                val initMethod = clazz.methods.firstOrNull { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.name in THEME_WRAPPER_REPAIR_INIT_METHOD_NAMES &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.firstOrNull()?.isAssignableFrom(Context::class.java) == true &&
                        (
                            method.parameterTypes.size == 1 ||
                                (
                                    method.parameterTypes.size == 2 &&
                                        method.parameterTypes[1] == String::class.java
                                    )
                            )
                } ?: return@runCatching false
                val args = when (initMethod.parameterTypes.size) {
                    1 -> arrayOf(application.applicationContext)
                    2 -> arrayOf(application.applicationContext, "0")
                    else -> return@runCatching false
                }
                initMethod.isAccessible = true
                initMethod.invoke(null, *args)
                val repairedGetters = nullGetters.filter { method ->
                    val getterArgs = when (method.parameterTypes.size) {
                        0 -> emptyArray()
                        else -> arrayOf(false)
                    }
                    runCatching { method.invoke(null, *getterArgs) != null }.getOrDefault(false)
                }
                if (repairedGetters.isEmpty()) {
                    return@runCatching false
                }
                Log.i(
                    TAG,
                    "Repaired guest theme wrappers $className for ${session.packageName} " +
                        "getters=${repairedGetters.joinToString { it.name }}"
                )
                true
            }.onFailure {
                Log.d(TAG, "Skipping guest theme wrapper repair for $className", it)
            }.getOrDefault(false)
            if (repaired) {
                repairedCount += 1
            }
        }
        if (repairedCount > 0) {
            Log.i(TAG, "Guest theme wrapper repair complete for ${session.packageName} repaired=$repairedCount")
        }
    }

    private data class ThemeWrapperResolution(
        val context: Context,
        val methodName: String
    )

    private fun resolvePreferredThemeWrapper(clazz: Class<*>): ThemeWrapperResolution? {
        val getterMethods = clazz.methods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType.name == ANDROIDX_CONTEXT_THEME_WRAPPER_CLASS_NAME &&
                (
                    method.parameterTypes.isEmpty() ||
                        (
                            method.parameterTypes.size == 1 &&
                                (
                                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                                        method.parameterTypes[0] == Boolean::class.javaObjectType
                                    )
                            )
                    )
        }
        getterMethods.forEach { method ->
            val candidates = when (method.parameterTypes.size) {
                0 -> listOf(emptyArray())
                else -> listOf(arrayOf(false), arrayOf(true))
            }
            candidates.forEach { args ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(null, *args)
                }.onFailure {
                    Log.d(
                        TAG,
                        "Theme wrapper getter failed class=${clazz.name} method=${method.name} " +
                            "args=${args.joinToString(prefix = "[", postfix = "]")}",
                        it
                    )
                }.getOrNull()?.let { wrapper ->
                    if (wrapper is Context) {
                        Log.d(
                            TAG,
                            "Theme wrapper getter succeeded class=${clazz.name} method=${method.name} " +
                                "args=${args.joinToString(prefix = "[", postfix = "]")} " +
                                "wrapper=${wrapper.javaClass.name}"
                        )
                        return ThemeWrapperResolution(
                            context = wrapper,
                            methodName = method.name
                        )
                    }
                }
            }
        }
        return null
    }

    private fun resolveThemeResId(context: Context): Int? {
        runCatching {
            return generateSequence<Class<*>>(context.javaClass) { current ->
                current.superclass
            }.flatMap { clazz ->
                clazz.methods
                    .asSequence()
                    .filter { method ->
                        method.name == "getThemeResId" &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType == Int::class.javaPrimitiveType
                    }
            }.firstOrNull()?.let { method ->
                method.isAccessible = true
                method.invoke(context) as? Int
            }
        }.getOrElse { throwable ->
            Log.d(TAG, "Method themeResId lookup failed for ${context.javaClass.name}", throwable)
        }?.let { resolved ->
            return resolved
        }
        return generateSequence<Class<*>>(context.javaClass) { current ->
            current.superclass
        }.mapNotNull { clazz ->
            runCatching { clazz.getDeclaredField("mThemeResource") }.getOrNull()
        }.firstNotNullOfOrNull { field ->
            field.isAccessible = true
            field.get(context) as? Int
        }
    }

    private fun repairGuestContextInitializersIfPresent(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val primaryPackagePrefixes = resolvePrimaryGuestRepairPackagePrefixes(session)
        val candidateClassNames = resolveInterfaceInjectorRepairClassNames(session)
            .filter { className ->
                shouldConsiderPrimaryGuestRepairClass(
                    className = className,
                    primaryPackagePrefixes = primaryPackagePrefixes
                )
            }
        if (candidateClassNames.isEmpty()) {
            return
        }
        val loadedClasses = loadInterfaceInjectorRepairClasses(candidateClassNames, session)
        val implementationsByInterface = buildInterfaceInjectorImplementationsByName(
            loadedClasses = loadedClasses,
            application = application
        )
        var repairedCount = 0
        candidateClassNames
            .sortedWith(
                compareBy<String> { !INTERFACE_INJECTOR_OBFUSCATED_CLASS_NAME.matches(it) }
                    .thenBy { it.length }
                    .thenBy { it }
            )
            .forEach { className ->
            val repaired = runCatching {
                val clazz = session.evokeAppClassLoader.loadClass(className)
                if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) {
                    return@runCatching false
                }
                if (isGuestUiInstantiationType(clazz)) {
                    return@runCatching false
                }
                val initMethods = clazz.methods
                    .filter(::isSupportedGuestContextInitializer)
                    .filter { method ->
                        resolveGuestContextInitializerArgs(
                            method = method,
                            application = application,
                            implementationsByInterface = implementationsByInterface
                        ) != null
                    }
                    .sortedWith(
                        compareByDescending<Method> { it.parameterTypes.any(::isGuestContextLikeType) }
                            .thenByDescending { it.parameterTypes.any(Class<*>::isInterface) }
                            .thenBy { it.parameterTypes.size }
                            .thenBy { it.name }
                    )
                if (initMethods.isEmpty()) {
                    return@runCatching false
                }
                val instance = resolveGuestContextInitializerInstance(clazz) ?: return@runCatching false
                val nullFieldsBefore = countGuestRepairableNullFields(clazz, instance)
                if (nullFieldsBefore == 0) {
                    return@runCatching false
                }
                val repairedMethods = mutableListOf<String>()
                var remainingNullFields = nullFieldsBefore
                initMethods.forEach { method ->
                    val args = resolveGuestContextInitializerArgs(
                        method = method,
                        application = application,
                        implementationsByInterface = implementationsByInterface
                    ) ?: return@forEach
                    if (remainingNullFields == 0) {
                        return@forEach
                    }
                    runCatching {
                        method.isAccessible = true
                        method.invoke(instance, *args)
                    }.onFailure {
                        Log.v(
                            TAG,
                            "Guest context initializer invocation failed ${clazz.name}.${method.name}",
                            it
                        )
                    }.getOrNull() ?: return@forEach
                    val updatedNullFields = countGuestRepairableNullFields(clazz, instance)
                    if (updatedNullFields < remainingNullFields) {
                        repairedMethods += method.name
                        remainingNullFields = updatedNullFields
                    }
                }
                if (remainingNullFields >= nullFieldsBefore) {
                    return@runCatching false
                }
                Log.i(
                    TAG,
                    "Repaired guest context initializer $className for ${session.packageName} " +
                        "methods=${repairedMethods.distinct().joinToString()} " +
                        "nullFields=$nullFieldsBefore->$remainingNullFields"
                )
                true
            }.onFailure {
                Log.d(TAG, "Skipping guest context initializer repair for $className", it)
            }.getOrDefault(false)
            if (repaired) {
                repairedCount += 1
            }
            }
        if (repairedCount > 0) {
            Log.i(
                TAG,
                "Guest context initializer repair complete for ${session.packageName} repaired=$repairedCount"
            )
        }
    }

    private fun repairGuestInterfaceInjectorsIfPresent(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val candidateClassNames = resolveInterfaceInjectorRepairClassNames(session)
        if (candidateClassNames.isEmpty()) {
            return
        }
        val loadedClasses = loadInterfaceInjectorRepairClasses(candidateClassNames, session)
        val implementationsByInterface = buildInterfaceInjectorImplementationsByName(
            loadedClasses = loadedClasses,
            application = application
        )
        var repairedCount = 0
        loadedClasses.forEach { holderClass ->
            val repaired = runCatching {
                if (holderClass.isInterface || Modifier.isAbstract(holderClass.modifiers)) {
                    return@runCatching false
                }
                val targetField = holderClass.declaredFields.firstOrNull { field ->
                    Modifier.isStatic(field.modifiers) &&
                        !Modifier.isFinal(field.modifiers) &&
                        field.type.isInterface &&
                        shouldConsiderInterfaceInjectorType(field.type)
                } ?: return@runCatching false
                targetField.isAccessible = true
                if (targetField.get(null) != null) {
                    return@runCatching false
                }
                val interfaceType = targetField.type
                val implementationClasses = implementationsByInterface[interfaceType.name]
                    .orEmpty()
                    .filter { implementation ->
                        implementation != holderClass &&
                            findInterfaceInjectorConstructor(implementation, application) != null
                    }
                if (implementationClasses.size != 1) {
                    return@runCatching false
                }
                val implementationClass = implementationClasses.single()
                val implementation = instantiateInterfaceInjector(implementationClass, application)
                    ?: return@runCatching false
                holderClass.methods.firstOrNull { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == interfaceType
                }?.let { setter ->
                    setter.isAccessible = true
                    setter.invoke(null, implementation)
                } ?: targetField.set(null, implementation)
                if (targetField.get(null) == null) {
                    return@runCatching false
                }
                Log.i(
                    TAG,
                    "Repaired guest interface injector ${holderClass.name} for ${session.packageName} " +
                        "interface=${interfaceType.name} impl=${implementationClass.name}"
                )
                true
            }.onFailure {
                Log.d(TAG, "Skipping guest interface injector repair for ${holderClass.name}", it)
            }.getOrDefault(false)
            if (repaired) {
                repairedCount += 1
            }
        }
        if (repairedCount > 0) {
            Log.i(TAG, "Guest interface injector repair complete for ${session.packageName} repaired=$repairedCount")
        }
    }

    private fun resolveAssignableInterfaces(clazz: Class<*>): Set<Class<*>> =
        buildSet {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.interfaces.forEach { interfaceClass ->
                    add(interfaceClass)
                    addAll(resolveAssignableInterfaces(interfaceClass))
                }
                current = current.superclass
            }
        }

    private fun loadInterfaceInjectorRepairClasses(
        candidateClassNames: List<String>,
        session: EvokeAppRuntimeSession
    ): List<Class<*>> =
        candidateClassNames.mapNotNull { className ->
            runCatching {
                session.evokeAppClassLoader.loadClass(className)
            }.onFailure {
                Log.v(TAG, "Skipping interface injector candidate $className", it)
            }.getOrNull()
        }

    private fun buildInterfaceInjectorImplementationsByName(
        loadedClasses: List<Class<*>>,
        application: Application
    ): Map<String, List<Class<*>>> =
        loadedClasses
            .asSequence()
            .filterNot(Class<*>::isInterface)
            .filter { !Modifier.isAbstract(it.modifiers) }
            .flatMap { implementation ->
                resolveAssignableInterfaces(implementation).asSequence()
                    .filter(::shouldConsiderInterfaceInjectorType)
                    .filter { findInterfaceInjectorConstructor(implementation, application) != null }
                    .map { it.name to implementation }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second }
            )
            .mapValues { (_, implementations) ->
                implementations.distinctBy(Class<*>::getName)
            }

    private fun shouldConsiderInterfaceInjectorType(clazz: Class<*>): Boolean {
        val name = clazz.name
        return !name.startsWith("android.") &&
            !name.startsWith("androidx.") &&
            !name.startsWith("java.") &&
            !name.startsWith("javax.") &&
            !name.startsWith("kotlin.") &&
            !isGuestUiInstantiationType(clazz)
    }

    private fun findInterfaceInjectorConstructor(
        clazz: Class<*>,
        application: Application
    ): Constructor<*>? =
        clazz.takeUnless(::isGuestUiInstantiationType)
            ?.declaredConstructors
            ?.sortedBy { it.parameterTypes.size }
            ?.firstOrNull { constructor ->
                resolveInterfaceInjectorArgs(constructor, application) != null
            }

    private fun instantiateInterfaceInjector(
        clazz: Class<*>,
        application: Application
    ): Any? {
        if (isGuestUiInstantiationType(clazz)) {
            return null
        }
        val constructor = findInterfaceInjectorConstructor(clazz, application) ?: return null
        constructor.isAccessible = true
        val args = resolveInterfaceInjectorArgs(constructor, application) ?: return null
        return constructor.newInstance(*args)
    }

    private fun isGuestUiInstantiationType(clazz: Class<*>): Boolean {
        val name = clazz.name
        if (
            listOf(
                "android.view.View",
                "android.app.Activity",
                "android.app.Dialog",
                "android.app.Service",
                "android.content.ContentProvider",
                "android.app.Fragment",
                "androidx.fragment.app.Fragment",
                "androidx.appcompat.app.AppCompatActivity",
                "androidx.appcompat.app.AppCompatDialog"
            ).any { parentName ->
                generateSequence<Class<*>>(clazz) { current ->
                    current.superclass
                }.any { current ->
                    current.name == parentName
                }
            }
        ) {
            return true
        }
        if (
            clazz.declaredConstructors.any { constructor ->
                constructor.parameterTypes.any { parameterType ->
                    parameterType.name == "android.util.AttributeSet"
                }
            }
        ) {
            return true
        }
        return listOf("view", "dialog", "activity", "fragment", "toolbar")
            .any(name.lowercase()::contains)
    }

    private fun resolveInterfaceInjectorArgs(
        constructor: Constructor<*>,
        application: Application
    ): Array<Any?>? {
        val args = constructor.parameterTypes.map { parameterType ->
            when {
                parameterType.isInstance(application) -> application
                parameterType.isInstance(application.applicationContext) -> application.applicationContext
                parameterType.isAssignableFrom(application.javaClass) -> application
                parameterType.isAssignableFrom(Application::class.java) -> application
                parameterType.isAssignableFrom(Context::class.java) -> application.applicationContext
                parameterType.isAssignableFrom(ContextWrapper::class.java) -> application.applicationContext
                else -> return null
            }
        }
        return args.toTypedArray()
    }

    private fun isSupportedGuestContextInitializer(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) {
            return false
        }
        if (method.parameterTypes.isEmpty() || method.parameterTypes.size > 2) {
            return false
        }
        val ownerName = method.declaringClass.name
        val methodName = method.name
        val ownerLooksObfuscated =
            INTERFACE_INJECTOR_OBFUSCATED_CLASS_NAME.matches(ownerName) ||
                CONTEXT_SINGLETON_OBFUSCATED_CLASS_NAME.matches(ownerName) ||
                THEME_WRAPPER_OBFUSCATED_CLASS_NAME.matches(ownerName)
        val methodLooksObfuscated =
            methodName.length <= 2 && methodName.all { it.isLetterOrDigit() }
        if (
            methodName !in GUEST_CONTEXT_INITIALIZER_METHOD_NAMES &&
            !(ownerLooksObfuscated && methodLooksObfuscated)
        ) {
            return false
        }
        return method.parameterTypes.none { parameterType ->
            parameterType.isPrimitive &&
                parameterType != Boolean::class.javaPrimitiveType
        }
    }

    private fun resolveGuestContextInitializerArgs(
        method: Method,
        application: Application,
        implementationsByInterface: Map<String, List<Class<*>>>
    ): Array<Any?>? {
        val args = method.parameterTypes.map { parameterType ->
            when {
                parameterType.isInstance(application) -> application
                parameterType.isInstance(application.applicationContext) -> application.applicationContext
                parameterType.isAssignableFrom(application.javaClass) -> application
                parameterType.isAssignableFrom(Application::class.java) -> application
                parameterType.isAssignableFrom(Context::class.java) -> application.applicationContext
                parameterType.isAssignableFrom(ContextWrapper::class.java) -> application.applicationContext
                parameterType.isInterface && shouldConsiderInterfaceInjectorType(parameterType) ->
                    resolveGuestContextInitializerInterfaceArg(
                        interfaceType = parameterType,
                        implementationsByInterface = implementationsByInterface,
                        application = application
                    ) ?: return null
                parameterType.isEnum && parameterType.enumConstants?.size == 1 ->
                    parameterType.enumConstants?.firstOrNull()
                !parameterType.isPrimitive && shouldConsiderGuestRepairableFieldType(parameterType) ->
                    resolveGuestContextInitializerInstance(parameterType)
                        ?: instantiateInterfaceInjector(parameterType, application)
                else -> return null
            }
        }
        return args.toTypedArray()
    }

    private fun resolveGuestContextInitializerInterfaceArg(
        interfaceType: Class<*>,
        implementationsByInterface: Map<String, List<Class<*>>>,
        application: Application
    ): Any? {
        val implementationClass = implementationsByInterface[interfaceType.name]
            .orEmpty()
            .singleOrNull()
            ?: return null
        return instantiateInterfaceInjector(implementationClass, application)
    }

    private fun isGuestContextLikeType(type: Class<*>): Boolean =
        type.isAssignableFrom(Application::class.java) ||
            type.isAssignableFrom(Context::class.java) ||
            type.isAssignableFrom(ContextWrapper::class.java)

    private fun resolveGuestContextInitializerInstance(clazz: Class<*>): Any? {
        resolveSingletonRepairInstance(clazz)?.let { return it }
        clazz.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                !field.type.isPrimitive &&
                (clazz.isAssignableFrom(field.type) || field.type.isInterface)
        }?.let { field ->
            field.isAccessible = true
            field.get(null)?.takeIf(clazz::isInstance)?.let { return it }
        }
        clazz.methods
            .asSequence()
            .filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType != Void.TYPE &&
                    !method.returnType.isPrimitive &&
                    (
                        method.returnType == clazz ||
                            clazz.isAssignableFrom(method.returnType) ||
                            method.returnType.isInterface
                        )
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(null)
                }.getOrNull()?.takeIf(clazz::isInstance)?.let { return it }
            }
        return null
    }

    private fun countGuestRepairableNullFields(instance: Any): Int =
        countGuestRepairableNullFields(instance.javaClass, instance)

    private fun countGuestRepairableNullFields(
        holderClass: Class<*>,
        instance: Any
    ): Int =
        countGuestRepairableStaticNullFields(holderClass) +
        generateSequence(instance.javaClass) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .count { field ->
                !Modifier.isStatic(field.modifiers) &&
                    !field.type.isPrimitive &&
                    shouldConsiderGuestRepairableFieldType(field.type).also {
                        if (it) {
                            field.isAccessible = true
                        }
                    } &&
                    field.get(instance) == null
            }

    private fun countGuestRepairableStaticNullFields(clazz: Class<*>): Int =
        generateSequence(clazz) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .count { field ->
                Modifier.isStatic(field.modifiers) &&
                    !field.type.isPrimitive &&
                    shouldConsiderGuestRepairableFieldType(field.type).also {
                        if (it) {
                            field.isAccessible = true
                        }
                    } &&
                    field.get(null) == null
            }

    private fun shouldConsiderGuestRepairableFieldType(type: Class<*>): Boolean {
        val name = type.name
        return !type.isPrimitive &&
            !name.startsWith("android.") &&
            !name.startsWith("androidx.") &&
            !name.startsWith("java.") &&
            !name.startsWith("javax.") &&
            !name.startsWith("kotlin.") &&
            !isGuestUiInstantiationType(type)
    }

    private fun resolveInterfaceInjectorRepairClassNames(
        session: EvokeAppRuntimeSession
    ): List<String> =
        interfaceInjectorRepairClassCache.getOrPut(
            buildString {
                append(session.apkPath)
                session.splitApkPaths.forEach {
                    append('|')
                    append(it)
                }
            }
        ) {
            val packagePrefixes = resolveStartupBridgePackagePrefixes(session)
            sequenceOf(session.apkPath)
                .plus(session.splitApkPaths.asSequence())
                .flatMap(::enumerateArchiveClassNames)
                .filter { className ->
                    '$' !in className &&
                        (
                            packagePrefixes.any { prefix -> className.startsWith("$prefix.") } ||
                                INTERFACE_INJECTOR_OBFUSCATED_CLASS_NAME.matches(className)
                            )
                }
                .toList()
        }

    private fun resolveThemeWrapperRepairClassNames(
        session: EvokeAppRuntimeSession
    ): List<String> =
        themeWrapperRepairClassCache.getOrPut(
            buildString {
                append(session.apkPath)
                session.splitApkPaths.forEach {
                    append('|')
                    append(it)
                }
            }
        ) {
            val packagePrefixes = resolveStartupBridgePackagePrefixes(session)
            sequenceOf(session.apkPath)
                .plus(session.splitApkPaths.asSequence())
                .flatMap(::enumerateArchiveClassNames)
                .filter { className ->
                    '$' !in className &&
                        (
                            packagePrefixes.any { prefix -> className.startsWith("$prefix.") } ||
                                THEME_WRAPPER_OBFUSCATED_CLASS_NAME.matches(className)
                            )
                }
                .toList()
        }

    private fun instantiateStartupBridge(
        className: String,
        application: Application,
        session: EvokeAppRuntimeSession
    ): Any? =
        runCatching {
            val clazz = session.evokeAppClassLoader.loadClass(className)
            if (Modifier.isAbstract(clazz.modifiers) || clazz.isInterface) {
                return null
            }
            clazz.declaredFields.firstOrNull { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.name == "INSTANCE" &&
                    clazz.isAssignableFrom(field.type)
            }?.let { field ->
                field.isAccessible = true
                field.get(null)?.let { return it }
            }
            clazz.declaredConstructors
                .sortedBy { it.parameterTypes.size }
                .firstNotNullOfOrNull { constructor ->
                    instantiateStartupBridgeViaConstructor(constructor, application)
                }
        }.onFailure {
            Log.d(TAG, "Skipping startup bridge instance $className for ${session.packageName}", it)
        }.getOrNull()

    private fun instantiateStartupBridgeViaConstructor(
        constructor: Constructor<*>,
        application: Application
    ): Any? {
        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { parameterType ->
            when {
                parameterType.isInstance(application) -> application
                parameterType.isInstance(application.applicationContext) -> application.applicationContext
                parameterType.isAssignableFrom(application.javaClass) -> application
                parameterType.isAssignableFrom(Application::class.java) -> application
                parameterType.isAssignableFrom(Context::class.java) -> application.applicationContext
                parameterType.isAssignableFrom(ContextWrapper::class.java) -> application.applicationContext
                else -> return null
            }
        }
        return constructor.newInstance(*args.toTypedArray())
    }

    private fun shouldDispatchStartupOnLaunch(startup: Any): Boolean =
        runCatching {
            val method = startup.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "callCreateOnMainThread" &&
                    candidate.parameterTypes.isEmpty() &&
                    (candidate.returnType == Boolean::class.javaPrimitiveType ||
                        candidate.returnType == Boolean::class.javaObjectType)
            } ?: return@runCatching true
            method.invoke(startup) as? Boolean ?: true
        }.getOrElse {
            Log.d(TAG, "Unable to inspect callCreateOnMainThread for ${startup.javaClass.name}", it)
            true
        }

    private fun executeStartupBridgeRecursively(
        className: String,
        startup: Any,
        startupBaseClass: Class<*>,
        application: Application,
        startupContext: Context,
        session: EvokeAppRuntimeSession,
        startupInstances: MutableMap<String, Any>,
        visiting: MutableSet<String>,
        executed: MutableSet<String>
    ) {
        if (executed.contains(className)) {
            return
        }
        if (!visiting.add(className)) {
            Log.w(TAG, "Detected startup bridge cycle at $className for ${session.packageName}")
            return
        }
        resolveStartupBridgeDependencies(startup).forEach { dependencyClassName ->
            val dependency = startupInstances[dependencyClassName]
                ?: instantiateStartupBridge(
                    className = dependencyClassName,
                    application = application,
                    session = session
                )?.also { startupInstances[dependencyClassName] = it }
            if (dependency == null || !startupBaseClass.isInstance(dependency)) {
                return@forEach
            }
            executeStartupBridgeRecursively(
                className = dependencyClassName,
                startup = dependency,
                startupBaseClass = startupBaseClass,
                application = application,
                startupContext = startupContext,
                session = session,
                startupInstances = startupInstances,
                visiting = visiting,
                executed = executed
            )
        }
        visiting.remove(className)
        runCatching {
            val createMethod = startup.javaClass.methods.firstOrNull { method ->
                method.name == "create" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(Context::class.java)
            } ?: startup.javaClass.methods.firstOrNull { method ->
                method.name in STARTUP_BRIDGE_CREATE_METHODS &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(Context::class.java)
            } ?: return@runCatching
            createMethod.isAccessible = true
            createMethod.invoke(startup, startupContext)
            executed += className
            Log.i(TAG, "Executed startup bridge $className for ${session.packageName}")
        }.onFailure {
            Log.w(TAG, "Unable to execute startup bridge $className for ${session.packageName}", it)
        }
    }

    private fun resolveStartupBridgeDependencies(startup: Any): List<String> {
        val dependenciesByName = runCatching {
            startup.javaClass.methods.firstOrNull { method ->
                method.name == "dependenciesByName" && method.parameterTypes.isEmpty()
            }?.invoke(startup) as? Iterable<*>
        }.getOrNull()
            ?.mapNotNull { it as? String }
            .orEmpty()
        if (dependenciesByName.isNotEmpty()) {
            return dependenciesByName
        }
        return runCatching {
            startup.javaClass.methods.firstOrNull { method ->
                method.name == "dependencies" && method.parameterTypes.isEmpty()
            }?.invoke(startup) as? Iterable<*>
        }.getOrNull()
            ?.mapNotNull { dependency ->
                when (dependency) {
                    is Class<*> -> dependency.name
                    else -> null
                }
            }
            .orEmpty()
    }

    private fun initializeMmkvIfPresent(
        context: Context,
        session: EvokeAppRuntimeSession
    ) {
        runCatching {
            val mmkvClass = session.evokeAppClassLoader.loadClass("com.tencent.mmkv.MMKV")
            val existingRoot = mmkvClass.methods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.name == "rootDir" &&
                    method.parameterTypes.isEmpty()
            }?.invoke(null) as? String
            if (!existingRoot.isNullOrBlank()) {
                Log.i(TAG, "MMKV already initialized for ${session.packageName} root=$existingRoot")
                return@runCatching
            }
            val initMethod = mmkvClass.methods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.name == "initialize" &&
                    method.parameterTypes.firstOrNull()?.let(Context::class.java::isAssignableFrom) == true
            } ?: return@runCatching
            val mmkvDir = File(context.filesDir, "mmkv").apply { mkdirs() }
            val initArgs = when {
                initMethod.parameterTypes.size == 1 -> arrayOf(context)
                initMethod.parameterTypes.size >= 2 &&
                    initMethod.parameterTypes[1] == String::class.java -> {
                    arrayOf(context, mmkvDir.absolutePath)
                }
                else -> return@runCatching
            }
            val rootDir = initMethod.invoke(null, *initArgs) as? String
            Log.i(
                TAG,
                "Initialized MMKV for ${session.packageName} root=${rootDir ?: mmkvDir.absolutePath}"
            )
        }.onFailure {
            Log.d(TAG, "MMKV eager initialization skipped for ${session.packageName}", it)
        }
    }

}

data class RuntimeBootstrapResult(
    val applicationStatus: String,
    val launcherStatus: String,
    val activityStatus: String,
    val application: Application,
    val applicationContext: Context
)
