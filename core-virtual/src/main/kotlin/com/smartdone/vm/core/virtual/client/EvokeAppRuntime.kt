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

@Singleton
class EvokeAppRuntime @Inject constructor(
    private val repository: EvokeAppRepository,
    private val sandboxPath: SandboxPath,
    private val apkParser: ApkParser
) {
    private var bootstrappedApp: BootstrappedApp? = null
    private val contextSingletonWarmupCache = ConcurrentHashMap<String, List<String>>()

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
            "com.smartdone.vm.core.virtual.client.EvokeAppClient",
            "lh.f",
            "ph.c0"
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

    private fun primeApplicationLikeStatics(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val classLoader = session.evokeAppClassLoader
        val candidates = listOf(
            "com.tencent.crabshell.b",
            "com.apkpure.aegon.application.RealApplicationLike"
        )
        candidates.forEach { className ->
            runCatching {
                val clazz = classLoader.loadClass(className)
                updateStaticFieldIfPresent(clazz, "application", application)
                updateStaticFieldIfPresent(clazz, "mApplication", application)
                updateStaticFieldIfPresent(clazz, "mContext", application)
                updateStaticFieldIfPresent(clazz, "context", application)
                if (className == "com.apkpure.aegon.application.RealApplicationLike") {
                    updateStaticFieldIfPresent(clazz, "channelConfig", null)
                }
                val instance = runCatching {
                    clazz.getDeclaredMethod("getInstance").invoke(null)
                }.getOrNull() ?: return@runCatching
                val attachMethods = listOf(
                    clazz.methods.firstOrNull {
                        it.name == "attachBaseContext" &&
                            it.parameterTypes.contentEquals(arrayOf(Application::class.java))
                    },
                    clazz.methods.firstOrNull {
                        it.name == "attachBaseContext" &&
                            it.parameterTypes.contentEquals(arrayOf(Context::class.java))
                    }
                )
                attachMethods.firstOrNull()?.let { method ->
                    method.isAccessible = true
                    val arg = if (method.parameterTypes.first() == Application::class.java) {
                        application
                    } else {
                        application.applicationContext
                    }
                    method.invoke(instance, arg)
                    Log.i(TAG, "Primed application-like context via ${clazz.name}.${method.name}")
                }
            }.onFailure {
                Log.d(TAG, "Skipping application-like priming for $className", it)
            }
        }
    }

    private fun initializeApplicationLikeRuntime(
        application: Application,
        session: EvokeAppRuntimeSession
    ) {
        val classLoader = session.evokeAppClassLoader
        val applicationLikeCreated = runCatching {
            val realApplicationLikeClass =
                classLoader.loadClass("com.apkpure.aegon.application.RealApplicationLike")
            val instance = realApplicationLikeClass.getDeclaredMethod("getInstance").invoke(null)
                ?: return@runCatching false
            realApplicationLikeClass.getDeclaredMethod("onCreate", Application::class.java).apply {
                isAccessible = true
                invoke(instance, application)
            }
            Log.i(TAG, "Invoked RealApplicationLike.onCreate for ${session.packageName}")
            true
        }.getOrElse {
            Log.d(TAG, "Skipping application-like onCreate bridge for ${session.packageName}", it)
            false
        }
        runCatching {
            val networkRuntimeClass = classLoader.loadClass("com.apkpure.aegon.network.h")
            networkRuntimeClass.getDeclaredMethod("a", Context::class.java).apply {
                isAccessible = true
                invoke(null, application)
            }
            Log.i(TAG, "Initialized application-like network runtime for ${session.packageName}")
        }.onFailure {
            Log.d(TAG, "Skipping application-like network init for ${session.packageName}", it)
        }
        ensureApkPureClientChannelReady(
            classLoader = classLoader,
            packageName = session.packageName,
            forceInit = !applicationLikeCreated
        )
        warmContextBackedSingletons(
            application = application,
            session = session
        )
        runCatching {
            val clientChannelClass = classLoader.loadClass("bd.h\$b")
            val ready = clientChannelClass.getDeclaredMethod("c").invoke(null) as? Boolean ?: false
            Log.i(TAG, "Client channel ready=$ready for ${session.packageName}")
        }.onFailure {
            Log.d(TAG, "Unable to inspect client channel readiness for ${session.packageName}", it)
        }
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
                "Application-like runtime ready package=${session.packageName} " +
                    "application=${application.javaClass.name} instrumentation=${instrumentation?.javaClass?.name}"
            )
        }.onFailure {
            Log.d(TAG, "Unable to inspect application-like runtime for ${session.packageName}", it)
        }
    }

    private fun ensureApkPureClientChannelReady(
        classLoader: ClassLoader,
        packageName: String,
        forceInit: Boolean
    ) {
        runCatching {
            val clientChannelCompanionClass = classLoader.loadClass("bd.h\$b")
            val ready = clientChannelCompanionClass.getDeclaredMethod("c").invoke(null) as? Boolean ?: false
            if (ready && !forceInit) {
                Log.i(TAG, "Client channel already ready for $packageName")
                return
            }
            val realApplicationLikeClass =
                classLoader.loadClass("com.apkpure.aegon.application.RealApplicationLike")
            realApplicationLikeClass.getDeclaredMethod("initClientChannel").apply {
                isAccessible = true
                invoke(null)
            }
            Log.i(
                TAG,
                "Initialized application-like client channel for $packageName forceInit=$forceInit"
            )
        }.onFailure {
            Log.d(TAG, "Skipping application-like client channel init for $packageName", it)
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

    private fun updateStaticFieldIfPresent(
        clazz: Class<*>,
        fieldName: String,
        value: Any?
    ) {
        runCatching {
            clazz.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(null, value)
            }
            Log.d(TAG, "Updated ${clazz.name}.$fieldName")
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
        private const val TAG = "EvokeAppRuntime"
        private const val APKPURE_PACKAGE_NAME = "com.apkpure.aegon"
        private val CONTEXT_SINGLETON_ARGUMENT_TYPES = listOf(
            Context::class.java,
            Application::class.java,
            ContextWrapper::class.java
        )
        private val CONTEXT_SINGLETON_OBFUSCATED_CLASS_NAME =
            Regex("^[a-z][a-z0-9]{0,2}(\\.[A-Za-z][A-Za-z0-9_$]{0,2}){1,3}$")
        private val APKPURE_SKIPPED_PROVIDER_CLASSES = setOf(
            "androidx.core.content.FileProvider",
            "com.apkmatrix.components.downloader.utils.DownloaderFileProvider",
            "com.just.agentweb.AgentWebFileProvider",
            "com.luck.picture.lib.basic.PictureFileProvider",
            "com.apicfast.sdk.ad.bridge.BridgeProvider",
            "com.apicfast.sdk.ad.provider.AdProvider"
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
        val application = session.applicationClassName?.let { applicationClassName ->
            runCatching {
                val appClass = session.evokeAppClassLoader.loadClass(applicationClassName)
                val created = appClass.getDeclaredConstructor().newInstance() as Application
                attachApplication(created, applicationContext)
                registerApplicationWithActivityThread(created)
                applicationContext.setVirtualApplicationContext(created)
                primeApplicationLikeStatics(created, session)
                installContentProvidersIfNeeded(applicationContext, session)
                initializeFirebaseIfPresent(created, session)
                created.onCreate()
                initializeApplicationLikeRuntime(created, session)
                created to "created:${created::class.java.name}"
            }.getOrElse {
                Log.w(TAG, "Unable to create virtual application $applicationClassName", it)
                val created = Application()
                attachApplication(created, applicationContext)
                registerApplicationWithActivityThread(created)
                applicationContext.setVirtualApplicationContext(created)
                primeApplicationLikeStatics(created, session)
                installContentProvidersIfNeeded(applicationContext, session)
                initializeFirebaseIfPresent(created, session)
                created.onCreate()
                initializeApplicationLikeRuntime(created, session)
                created to "failed:${it.javaClass.simpleName}"
            }
        } ?: run {
            val created = Application()
            attachApplication(created, applicationContext)
            registerApplicationWithActivityThread(created)
            applicationContext.setVirtualApplicationContext(created)
            primeApplicationLikeStatics(created, session)
            installContentProvidersIfNeeded(applicationContext, session)
            initializeFirebaseIfPresent(created, session)
            created.onCreate()
            initializeApplicationLikeRuntime(created, session)
            created to "created:${created::class.java.name}"
        }
        return BootstrappedApp(
            packageName = session.packageName,
            userId = session.userId,
            apkPath = session.apkPath,
            application = application.first,
            applicationContext = applicationContext,
            applicationStatus = application.second
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

    private fun installContentProvidersIfNeeded(
        context: Context,
        session: EvokeAppRuntimeSession
    ) {
        session.providerComponents.forEach { component ->
            if (shouldSkipProviderInstallation(session, component)) {
                Log.i(TAG, "Skipping virtual provider ${component.className} for ${session.packageName}")
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

    private fun shouldSkipProviderInstallation(
        session: EvokeAppRuntimeSession,
        component: ProviderComponentInfo
    ): Boolean {
        if (session.packageName != APKPURE_PACKAGE_NAME) return false
        return component.className in APKPURE_SKIPPED_PROVIDER_CLASSES
    }

    private fun initializeFirebaseIfPresent(
        context: Context,
        session: EvokeAppRuntimeSession
    ) {
        if (session.packageName == APKPURE_PACKAGE_NAME) {
            Log.i(TAG, "Skipping eager Firebase bootstrap for ${session.packageName}")
            return
        }
        runCatching {
            val crashlyticsClass = session.evokeAppClassLoader.loadClass("lh.f")
            val coreClass = session.evokeAppClassLoader.loadClass("ph.c0")
            Log.i(
                TAG,
                "Crashlytics class bridge lh.f loader=${crashlyticsClass.classLoader} " +
                    "source=${crashlyticsClass.protectionDomain?.codeSource?.location} " +
                    "ph.c0 loader=${coreClass.classLoader}"
            )
        }.onFailure {
            Log.w(TAG, "Unable to inspect Crashlytics bridge classes for ${session.packageName}", it)
        }
        val initialized = runCatching {
            val firebaseAppClass = session.evokeAppClassLoader.loadClass("com.google.firebase.FirebaseApp")
            val initializeMethod = firebaseAppClass.getMethod("initializeApp", Context::class.java)
            initializeMethod.invoke(null, context)
            Log.i(TAG, "Manually initialized FirebaseApp for ${session.packageName} via official class")
            true
        }.getOrElse {
            Log.d(TAG, "Official FirebaseApp initialization path unavailable for ${session.packageName}", it)
            false
        }
        if (initialized) return
        runCatching {
            val firebaseOptionsClass = session.evokeAppClassLoader.loadClass("eh.k")
            val firebaseAppClass = session.evokeAppClassLoader.loadClass("eh.f")
            val options = firebaseOptionsClass
                .getMethod("a", Context::class.java)
                .invoke(null, context)
                ?: return@runCatching
            firebaseAppClass
                .getMethod("f", Context::class.java, firebaseOptionsClass)
                .invoke(null, context, options)
            Log.i(TAG, "Manually initialized FirebaseApp for ${session.packageName} via obfuscated classes")
        }.onFailure {
            Log.d(TAG, "Obfuscated FirebaseApp initialization skipped for ${session.packageName}", it)
        }
        repairFirebaseComponentRuntime(context, session)
    }

    private fun repairFirebaseComponentRuntime(
        context: Context,
        session: EvokeAppRuntimeSession
    ) {
        runCatching {
            val classLoader = session.evokeAppClassLoader
            val firebaseAppClass = classLoader.loadClass("eh.f")
            val crashlyticsClass = classLoader.loadClass("lh.f")
            val firebaseApp = firebaseAppClass.getMethod("c").invoke(null) ?: return@runCatching
            val existingCrashlytics = firebaseAppClass
                .getMethod("b", Class::class.java)
                .invoke(firebaseApp, crashlyticsClass)
            if (existingCrashlytics != null) {
                Log.i(TAG, "Firebase component runtime already exposes Crashlytics for ${session.packageName}")
                return@runCatching
            }
            val registrarClassNames = resolveFirebaseRegistrarClassNames(context, session)
            if (registrarClassNames.isEmpty()) {
                Log.w(TAG, "No Firebase registrars discovered for ${session.packageName}")
                return@runCatching
            }
            val hiBClass = classLoader.loadClass("hi.b")
            val providers = arrayListOf<Any>()
            registrarClassNames.forEach { registrarClassName ->
                providers += newProviderProxy(classLoader, hiBClass, registrarClassName)
            }
            providers += newProviderProxy(classLoader, hiBClass, "com.google.firebase.FirebaseCommonRegistrar")
            providers += newProviderProxy(classLoader, hiBClass, "com.google.firebase.concurrent.ExecutorsRegistrar")
            val components = arrayListOf<Any>()
            val componentClass = classLoader.loadClass("jh.b")
            val addComponent = componentClass.methods.first {
                it.name == "c" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Any::class.java &&
                    it.parameterTypes[1] == Class::class.java &&
                    it.parameterTypes[2].isArray
            }
            val emptyInterfaces = emptyArray<Class<*>>()
            val firebaseOptions = firebaseAppClass.getDeclaredField("f25570c").apply {
                isAccessible = true
            }.get(firebaseApp)
            components += addComponent.invoke(null, context, Context::class.java, emptyInterfaces)
            components += addComponent.invoke(null, firebaseApp, firebaseAppClass, emptyInterfaces)
            components += addComponent.invoke(
                null,
                firebaseOptions,
                classLoader.loadClass("eh.k"),
                emptyInterfaces
            )
            runCatching {
                val firebaseInitProviderClass = classLoader.loadClass("com.google.firebase.provider.FirebaseInitProvider")
                val directBootSupport = firebaseInitProviderClass.getDeclaredField("f18952b").apply {
                    isAccessible = true
                }.get(null)
                components += addComponent.invoke(
                    null,
                    directBootSupport,
                    classLoader.loadClass("eh.l"),
                    emptyInterfaces
                )
            }.onFailure {
                Log.d(TAG, "Skipping Firebase direct-boot component for ${session.packageName}", it)
            }
            val executor = classLoader.loadClass("kh.z").getDeclaredField("f29706b").apply {
                isAccessible = true
            }.get(null) as Executor
            val componentMonitor = classLoader.loadClass("ri.b").getDeclaredConstructor().newInstance()
            val runtimeClass = classLoader.loadClass("jh.l")
            val componentRuntime = runtimeClass.constructors.first {
                it.parameterTypes.size == 4
            }.newInstance(executor, ArrayList(providers), ArrayList(components), componentMonitor)
            firebaseAppClass.getDeclaredField("f25571d").apply {
                isAccessible = true
                set(firebaseApp, componentRuntime)
            }
            val heartbeatProvider = runtimeClass
                .getMethod("b", Class::class.java)
                .invoke(componentRuntime, classLoader.loadClass("ei.f"))
            firebaseAppClass.getDeclaredField("f25575h").apply {
                isAccessible = true
                set(firebaseApp, heartbeatProvider)
            }
            firebaseAppClass.getMethod("e").invoke(firebaseApp)
            val repairedCrashlytics = firebaseAppClass
                .getMethod("b", Class::class.java)
                .invoke(firebaseApp, crashlyticsClass)
            Log.i(
                TAG,
                "Firebase component runtime repaired for ${session.packageName} " +
                    "registrars=${registrarClassNames.size} crashlyticsReady=${repairedCrashlytics != null}"
            )
        }.onFailure {
            Log.w(TAG, "Unable to repair Firebase component runtime for ${session.packageName}", it)
        }
    }

    private fun newProviderProxy(
        classLoader: ClassLoader,
        hiBClass: Class<*>,
        registrarClassName: String
    ): Any =
        Proxy.newProxyInstance(classLoader, arrayOf(hiBClass)) { _, method, _ ->
            when (method.name) {
                "get" -> classLoader.loadClass(registrarClassName).getDeclaredConstructor().newInstance()
                "toString" -> "RegistrarProvider($registrarClassName)"
                "hashCode" -> registrarClassName.hashCode()
                "equals" -> false
                else -> null
            }
        }

    private fun resolveFirebaseRegistrarClassNames(
        context: Context,
        session: EvokeAppRuntimeSession
    ): List<String> {
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_SERVICES.toLong() or PackageManager.GET_META_DATA.toLong()
        )
        val archiveInfo = context.packageManager.getPackageArchiveInfo(session.apkPath, flags)
        val discoveryService = archiveInfo
            ?.services
            ?.firstOrNull { it.name == "com.google.firebase.components.ComponentDiscoveryService" }
        return discoveryService
            ?.metaData
            ?.keySet()
            ?.filter { key ->
                key.startsWith("com.google.firebase.components:") &&
                    discoveryService.metaData?.get(key) == "com.google.firebase.components.ComponentRegistrar"
            }
            ?.map { it.removePrefix("com.google.firebase.components:") }
            .orEmpty()
            .distinct()
            .also {
                Log.i(
                    TAG,
                    "Resolved Firebase registrars for ${session.packageName}: ${it.joinToString()}"
                )
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
