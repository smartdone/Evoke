package com.smartdone.vm.runtime

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.client.EvokeAppRuntime
import com.smartdone.vm.core.virtual.client.EvokeAppRuntimeSession
import com.smartdone.vm.core.virtual.client.PreferredThemeResolution
import com.smartdone.vm.core.virtual.client.RuntimeBootstrapResult
import com.smartdone.vm.core.virtual.util.StubActivityRecord
import com.smartdone.vm.core.virtual.util.StubActivityRouter
import kotlinx.coroutines.runBlocking

internal class EmbeddedVirtualActivityController(
    private val hostActivity: ComponentActivity,
    private val appRuntime: EvokeAppRuntime,
    private val runtimeSession: EvokeAppRuntimeSession,
    private val bootstrapResult: RuntimeBootstrapResult,
    private val launchRecord: StubActivityRecord,
    private val evokeCore: EvokeCore
) {
    private var embeddedActivity: Activity? = null
    private var instrumentation: Instrumentation? = null
    private var started = false
    private var resumed = false

    fun launch(): LaunchResult {
        val requestedClassName =
            launchRecord.realIntent.component?.className
            ?: runtimeSession.launcherActivity
            ?: return LaunchResult.Failure("Missing target activity")
        val candidates = buildLaunchCandidates(requestedClassName)
        var lastFailure: Throwable? = null
        candidates.forEach { targetClassName ->
            val result = runCatching {
                val targetInfo = runtimeSession.activityInfos[targetClassName]
                    ?: buildFallbackActivityInfo(targetClassName)
                val preferredTheme = resolveTheme(targetInfo)
                val themeResId = preferredTheme.themeResId
                val activityContext = appRuntime.createActivityContext(
                    base = hostActivity,
                    session = runtimeSession,
                    applicationContextOverride = bootstrapResult.application,
                    themeResIdOverride = themeResId,
                    reportedPackageNameOverride = hostActivity.applicationContext.packageName
                )
                synchronizeContextTheme(
                    target = activityContext,
                    source = preferredTheme.themeContext,
                    themeResId = themeResId,
                    label = "activityContext"
                )
                Log.i(
                    TAG,
                    "Attempting embedded launch activity=$targetClassName theme=0x${themeResId.toString(16)} " +
                        "activityTheme=0x${targetInfo.theme.toString(16)} appTheme=0x${runtimeSession.applicationInfo.theme.toString(16)} " +
                        "contextPkg=${activityContext.packageName} appCtx=${activityContext.applicationContext.javaClass.name} " +
                        "preferredSource=${preferredTheme.sourceClassName ?: "manifest"}#" +
                        "${preferredTheme.sourceMethodName ?: "theme"}"
                )
                val launchIntent = Intent(launchRecord.realIntent).apply {
                    setClassName(runtimeSession.packageName, targetClassName)
                    removeExtra(EvokeCore.EXTRA_PACKAGE_NAME)
                    removeExtra(EvokeCore.EXTRA_USER_ID)
                }
                val baseInstrumentation = hostActivity.readField<Instrumentation>("mInstrumentation")
                    ?: error("Host instrumentation unavailable")
                val proxyInstrumentation = EvokeInstrumentationProxy(
                    base = baseInstrumentation,
                    evokeCore = evokeCore,
                    userId = launchRecord.userId,
                    hostPackageName = hostActivity.packageName,
                    guestPackageName = runtimeSession.packageName,
                    guestActivityNames = runtimeSession.activityInfos.keys,
                    fallbackGuestLauncher = ::launchViaCurrentStub
                )
                val activity = runtimeSession.evokeAppClassLoader
                    .loadClass(targetClassName)
                    .getDeclaredConstructor()
                    .newInstance() as Activity
                attachEmbeddedActivity(
                    activity = activity,
                    activityContext = activityContext,
                    instrumentation = proxyInstrumentation,
                    launchIntent = launchIntent,
                    activityInfo = targetInfo
                )
                synchronizeAttachedActivity(activity, activityContext, preferredTheme)
                logEmbeddedEnvironment(activity, activityContext)
                applyEmbeddedTheme(activity, activityContext, preferredTheme)
                proxyInstrumentation.callActivityOnCreate(activity, null)
                bindDecorView(activity)
                instrumentation = proxyInstrumentation
                embeddedActivity = activity
                LaunchResult.Success(activity)
            }
            if (result.isSuccess) {
                return result.getOrThrow()
            }
            lastFailure = result.exceptionOrNull()
            Log.e(TAG, "Unable to launch embedded activity $targetClassName", lastFailure)
        }
        return LaunchResult.Failure(buildFailureMessage(lastFailure ?: IllegalStateException("Unknown launch failure")))
    }

    fun dispatchStart() {
        val activity = embeddedActivity ?: return
        val instrumentation = instrumentation ?: return
        if (started) return
        instrumentation.callActivityOnStart(activity)
        started = true
    }

    fun dispatchResume() {
        val activity = embeddedActivity ?: return
        val instrumentation = instrumentation ?: return
        if (resumed) return
        instrumentation.callActivityOnResume(activity)
        resumed = true
    }

    fun dispatchPause() {
        val activity = embeddedActivity ?: return
        val instrumentation = instrumentation ?: return
        if (!resumed) return
        instrumentation.callActivityOnPause(activity)
        resumed = false
    }

    fun dispatchStop() {
        val activity = embeddedActivity ?: return
        val instrumentation = instrumentation ?: return
        if (!started) return
        instrumentation.callActivityOnStop(activity)
        started = false
    }

    fun dispatchDestroy() {
        val activity = embeddedActivity ?: return
        val instrumentation = instrumentation ?: return
        if (resumed) {
            instrumentation.callActivityOnPause(activity)
            resumed = false
        }
        if (started) {
            instrumentation.callActivityOnStop(activity)
            started = false
        }
        instrumentation.callActivityOnDestroy(activity)
        embeddedActivity = null
    }

    fun dispatchBackPressed(): Boolean {
        val activity = embeddedActivity ?: return false
        return runCatching {
            when (activity) {
                is ComponentActivity -> activity.onBackPressedDispatcher.onBackPressed()
                else -> activity.onBackPressed()
            }
            true
        }.onFailure {
            Log.w(TAG, "Embedded activity back handling failed", it)
        }.getOrDefault(false)
    }

    private fun attachEmbeddedActivity(
        activity: Activity,
        activityContext: Context,
        instrumentation: Instrumentation,
        launchIntent: Intent,
        activityInfo: ActivityInfo
    ) {
        val attachMethod = Activity::class.java.declaredMethods.firstOrNull {
            it.name == "attach" && it.parameterTypes.size == 19
        } ?: error("Activity.attach not found")
        attachMethod.isAccessible = true
        attachMethod.invoke(
            activity,
            activityContext,
            hostActivity.readField<Any>("mMainThread"),
            instrumentation,
            hostActivity.readField<IBinder>("mToken"),
            hostActivity.readField<Int>("mIdent") ?: 0,
            bootstrapResult.application,
            launchIntent,
            activityInfo,
            activityInfo.loadLabel(hostActivity.packageManager),
            null,
            null,
            null,
            activityContext.resources.configuration,
            hostActivity.readField<String>("mReferrer"),
            null,
            null,
            null,
            hostActivity.readField<IBinder>("mAssistToken"),
            hostActivity.readField<IBinder>("mShareableActivityToken")
        )
    }

    private fun bindDecorView(activity: Activity) {
        val decorView = activity.window?.decorView ?: error("Embedded activity decor view missing")
        (decorView.parent as? ViewGroup)?.removeView(decorView)
        val content = hostActivity.findViewById<ViewGroup>(android.R.id.content)
        content.removeAllViews()
        WindowCompat.setDecorFitsSystemWindows(hostActivity.window, true)
        content.addView(createInsetAwareContainer(decorView))
        hostActivity.title = activity.title
    }

    private fun createInsetAwareContainer(embeddedDecorView: View): ViewGroup =
        FrameLayout(hostActivity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipToPadding = false
            addView(
                embeddedDecorView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP or Gravity.START
                )
            )
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            requestApplyInsets()
        }

    private fun applyEmbeddedTheme(
        activity: Activity,
        activityContext: Context,
        preferredTheme: PreferredThemeResolution
    ) {
        val themeResId = preferredTheme.themeResId
        if (themeResId != 0) {
            activity.setTheme(themeResId)
        }
        synchronizeContextTheme(
            target = activity,
            source = preferredTheme.themeContext ?: activityContext,
            themeResId = themeResId,
            label = "activity"
        )
    }

    private fun synchronizeAttachedActivity(
        activity: Activity,
        activityContext: Context,
        preferredTheme: PreferredThemeResolution
    ) {
        runCatching {
            activity.writeField("mResources", activityContext.resources)
            activity.writeField("mInflater", LayoutInflater.from(activityContext))
            synchronizeContextTheme(
                target = activity,
                source = preferredTheme.themeContext ?: activityContext,
                themeResId = preferredTheme.themeResId,
                label = "attachedActivity"
            )
            activity.window?.let { window ->
                window.writeField("mContext", activityContext)
                window.writeField("mDecorContext", activityContext)
                window.writeField("mLayoutInflater", LayoutInflater.from(activityContext))
                synchronizeContextTheme(
                    target = window.context,
                    source = preferredTheme.themeContext ?: activityContext,
                    themeResId = preferredTheme.themeResId,
                    label = "windowContext"
                )
            }
            Log.d(
                TAG,
                "Synchronized attached activity resources activity=${activity.javaClass.name} " +
                    "resources=${activity.resources} base=${describeContextChain(activity.baseContext)} " +
                    "activityTheme=0x${readThemeResId(activity).toString(16)} " +
                    "contextTheme=0x${readThemeResId(activityContext).toString(16)}"
            )
        }.onFailure {
            Log.w(TAG, "Unable to synchronize attached activity internals", it)
        }
    }

    private fun logEmbeddedEnvironment(activity: Activity, activityContext: Context) {
        runCatching {
            val activityLoader = requireNotNull(activity.javaClass.classLoader) {
                "Activity class loader missing for ${activity.javaClass.name}"
            }
            val appCompatDrawableClass = runCatching {
                activityLoader.loadClass("androidx.appcompat.R\$drawable")
            }.getOrElse {
                hostActivity.classLoader.loadClass("androidx.appcompat.R\$drawable")
            }
            val appCompatDelegateClass = runCatching {
                activityLoader.loadClass("androidx.appcompat.app.AppCompatDelegateImpl")
            }.getOrElse {
                hostActivity.classLoader.loadClass("androidx.appcompat.app.AppCompatDelegateImpl")
            }
            val vectorTestId = runCatching {
                appCompatDrawableClass.getDeclaredField("abc_vector_test").getInt(null)
            }.getOrDefault(0)
            val resourceName = vectorTestId.takeIf { it != 0 }?.let { resId ->
                runCatching { activity.resources.getResourceName(resId) }
                    .getOrElse { throwable -> "unresolved:${throwable.javaClass.simpleName}" }
            } ?: "missing"
            Log.i(
                TAG,
                "Embedded environment activity=${activity.javaClass.name} " +
                    "activityLoader=$activityLoader appCompatDrawableLoader=${appCompatDrawableClass.classLoader} " +
                    "appCompatDelegateLoader=${appCompatDelegateClass.classLoader} " +
                    "vectorTestId=0x${vectorTestId.toString(16)} vectorTestName=$resourceName " +
                    "activityBase=${describeContextChain(activity.baseContext)} " +
                    "launchContext=${describeContextChain(activityContext)}"
            )
        }.onFailure {
            Log.w(TAG, "Unable to inspect embedded AppCompat environment", it)
        }
    }

    private fun resolveTheme(activityInfo: ActivityInfo): PreferredThemeResolution =
        appRuntime.resolvePreferredTheme(
            session = runtimeSession,
            fallbackThemeResId = activityInfo.theme
                .takeIf { it != 0 }
                ?: activityInfo.applicationInfo?.theme
                ?: runtimeSession.applicationInfo.theme
        )

    private fun synchronizeContextTheme(
        target: Context,
        source: Context?,
        themeResId: Int,
        label: String
    ) {
        runCatching {
            if (themeResId != 0) {
                target.setTheme(themeResId)
            }
            source?.let { sourceContext ->
                target.theme.setTo(sourceContext.theme)
                if (themeResId != 0) {
                    target.theme.applyStyle(themeResId, true)
                }
            }
            Log.i(
                TAG,
                "Synchronized theme label=$label target=${target.javaClass.name} " +
                    "theme=0x${readThemeResId(target).toString(16)} " +
                    "source=${source?.javaClass?.name ?: "none"} " +
                    "sourceTheme=0x${readThemeResId(source).toString(16)}"
            )
        }.onFailure {
            Log.w(
                TAG,
                "Unable to synchronize theme label=$label target=${target.javaClass.name} " +
                    "theme=0x${themeResId.toString(16)} source=${source?.javaClass?.name ?: "none"}",
                it
            )
        }
    }

    private fun buildFallbackActivityInfo(targetClassName: String) = ActivityInfo().apply {
        packageName = runtimeSession.packageName
        name = targetClassName
        applicationInfo = runtimeSession.applicationInfo
        theme = runtimeSession.applicationInfo.theme
    }

    private fun buildLaunchCandidates(requestedClassName: String): List<String> {
        val rankedFallbacks = runtimeSession.activityInfos.keys
            .filter { it != requestedClassName }
            .sortedByDescending(::primaryEntryScore)
            .filter(::looksLikePrimaryEntry)
        val preferred = if (looksLikeTransientEntry(requestedClassName)) {
            rankedFallbacks.firstOrNull()?.also {
                Log.i(TAG, "Detected preferred fallback activity $it for transient entry $requestedClassName")
            }
        } else {
            null
        }
        return buildList {
            if (preferred != null) {
                addAll(rankedFallbacks.take(5))
                add(requestedClassName)
            } else {
                add(requestedClassName)
                addAll(rankedFallbacks.take(5))
            }
        }.distinct()
    }

    private fun buildFailureMessage(throwable: Throwable): String =
        buildString {
            append(throwable.javaClass.simpleName)
            throwable.message?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        }

    private fun launchViaCurrentStub(intent: Intent): Boolean {
        val targetClassName = intent.component?.className ?: return false
        val record = StubActivityRecord(
            stubClassName = launchRecord.stubClassName,
            packageName = runtimeSession.packageName,
            userId = launchRecord.userId,
            realIntent = Intent(intent).apply {
                setClassName(runtimeSession.packageName, targetClassName)
                putExtra(EvokeCore.EXTRA_PACKAGE_NAME, runtimeSession.packageName)
                putExtra(EvokeCore.EXTRA_USER_ID, launchRecord.userId)
            },
            apkPath = runtimeSession.apkPath,
            launcherActivity = targetClassName,
            applicationClassName = runtimeSession.applicationClassName,
            nativeLibDir = runtimeSession.nativeLibDir,
            optimizedDir = runtimeSession.optimizedDir
        )
        val stubIntent = StubActivityRouter.buildLaunchIntent(
            hostPackage = hostActivity.packageName,
            stubClassName = launchRecord.stubClassName,
            record = record,
            label = hostActivity.intent.getStringExtra(EvokeCore.EXTRA_LABEL)
                ?: hostActivity.title?.toString().orEmpty().ifBlank { runtimeSession.packageName }
        )
        hostActivity.startActivity(stubIntent)
        Log.i(TAG, "Fallback stub launch routed activity=$targetClassName via=${launchRecord.stubClassName}")
        return true
    }

    private class EvokeInstrumentationProxy(
        private val base: Instrumentation,
        private val evokeCore: EvokeCore,
        private val userId: Int,
        private val hostPackageName: String,
        private val guestPackageName: String,
        private val guestActivityNames: Set<String>,
        private val fallbackGuestLauncher: (Intent) -> Boolean
    ) : Instrumentation() {
        override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity =
            base.newActivity(cl, className, intent)

        override fun callActivityOnCreate(activity: Activity?, icicle: Bundle?) {
            base.callActivityOnCreate(activity, icicle)
        }

        override fun callActivityOnStart(activity: Activity?) {
            base.callActivityOnStart(activity)
        }

        override fun callActivityOnResume(activity: Activity?) {
            base.callActivityOnResume(activity)
        }

        override fun callActivityOnPause(activity: Activity?) {
            base.callActivityOnPause(activity)
        }

        override fun callActivityOnStop(activity: Activity?) {
            base.callActivityOnStop(activity)
        }

        override fun callActivityOnDestroy(activity: Activity?) {
            base.callActivityOnDestroy(activity)
        }

        @Suppress("unused")
        fun execStartActivity(
            who: Context,
            contextThread: IBinder,
            token: IBinder,
            target: Activity,
            intent: Intent,
            requestCode: Int,
            options: Bundle?
        ): ActivityResult? {
            Log.i(TAG, "Proxy execStartActivity(Activity) intent=$intent requestCode=$requestCode")
            if (handleVirtualStart(intent, requestCode)) {
                return null
            }
            return invokeBaseHidden(
                "execStartActivity",
                arrayOf<Class<*>>(
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    Activity::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType!!,
                    Bundle::class.java
                ),
                who,
                contextThread,
                token,
                target,
                intent,
                requestCode,
                options
            ) as? ActivityResult
        }

        @Suppress("unused")
        fun execStartActivity(
            who: Context,
            contextThread: IBinder,
            token: IBinder,
            target: String,
            intent: Intent,
            requestCode: Int,
            options: Bundle?
        ): ActivityResult? {
            Log.i(TAG, "Proxy execStartActivity(String) intent=$intent requestCode=$requestCode")
            if (handleVirtualStart(intent, requestCode)) {
                return null
            }
            return invokeBaseHidden(
                "execStartActivity",
                arrayOf<Class<*>>(
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType!!,
                    Bundle::class.java
                ),
                who,
                contextThread,
                token,
                target,
                intent,
                requestCode,
                options
            ) as? ActivityResult
        }

        private fun handleVirtualStart(intent: Intent, requestCode: Int): Boolean {
            val rewrittenIntent = normalizeGuestIntent(intent)
            val routed = runBlocking { evokeCore.launchIntent(rewrittenIntent, userId) } ||
                fallbackGuestLauncher(rewrittenIntent)
            Log.i(
                TAG,
                "Proxy routed=$routed requestCode=$requestCode " +
                    "originalIntent=$intent rewrittenIntent=$rewrittenIntent"
            )
            if (routed && requestCode >= 0) {
                Log.w(TAG, "Activity result delivery is not implemented for embedded virtual activities")
            }
            return routed
        }

        private fun normalizeGuestIntent(intent: Intent): Intent =
            Intent(intent).apply {
                val originalComponent = component
                val originalClassName = originalComponent?.className
                if (
                    originalClassName != null &&
                    !originalClassName.startsWith(hostPackageName) &&
                    (
                        originalComponent.packageName == hostPackageName ||
                            originalComponent.packageName != guestPackageName
                        )
                ) {
                    component = ComponentName(guestPackageName, originalClassName)
                }
                if (
                    (
                        `package` == hostPackageName ||
                            component?.packageName == guestPackageName
                        ) &&
                    component?.className?.let { className ->
                        className in guestActivityNames || !className.startsWith(hostPackageName)
                    } == true
                ) {
                    `package` = guestPackageName
                }
                if (component?.packageName == guestPackageName) {
                    putExtra(EvokeCore.EXTRA_PACKAGE_NAME, guestPackageName)
                }
            }

        private fun invokeBaseHidden(
            name: String,
            parameterTypes: Array<Class<*>>,
            vararg args: Any?
        ): Any? {
            val method = base.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.contentEquals(parameterTypes)
            } ?: base.javaClass.getDeclaredMethod(name, *parameterTypes).apply {
                isAccessible = true
            }
            return method.invoke(base, *args)
        }
    }

    sealed interface LaunchResult {
        data class Success(val activity: Activity) : LaunchResult
        data class Failure(val message: String) : LaunchResult
    }

    companion object {
        private const val TAG = "EmbeddedActivity"
    }
}

private fun describeContextChain(context: Context?): String =
    buildString {
        var current = context
        var depth = 0
        while (current != null && depth < 8) {
            if (depth > 0) append(" -> ")
            append(current.javaClass.name)
            append('(')
            append(current.packageName)
            append(')')
            current = (current as? ContextWrapper)?.baseContext
            depth += 1
        }
        if (current != null) {
            append(" -> ...")
        }
    }

private fun looksLikeTransientEntry(className: String): Boolean {
    val lower = className.lowercase()
    return listOf("splash", "guide", "welcome", "intro", "startup", "launch", "first")
        .any(lower::contains)
}

private fun readThemeResId(context: Context?): Int {
    if (context == null) {
        return 0
    }
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
        } ?: 0
    }.getOrElse {
        return generateSequence<Class<*>>(context.javaClass) { current ->
            current.superclass
        }.mapNotNull { clazz ->
            runCatching { clazz.getDeclaredField("mThemeResource") }.getOrNull()
        }.firstNotNullOfOrNull { field ->
            field.isAccessible = true
            field.get(context) as? Int
        } ?: 0
    }
}

private fun Any.writeField(fieldName: String, value: Any?) {
    generateSequence(javaClass) { it.superclass }
        .mapNotNull { clazz ->
            runCatching { clazz.getDeclaredField(fieldName) }.getOrNull()
        }
        .firstOrNull()
        ?.apply {
            isAccessible = true
            set(this@writeField, value)
        }
}

private fun looksLikePrimaryEntry(className: String): Boolean {
    val lower = className.lowercase()
    return listOf("main", "home", "tab").any(lower::contains)
}

private fun primaryEntryScore(className: String): Int {
    val lower = className.lowercase()
    var score = 0
    if ("maintabactivity" in lower) score += 100
    if ("mainactivity" in lower) score += 90
    if ("homeactivity" in lower) score += 80
    if ("frameactivity" in lower) score += 60
    if ("container" in lower) score += 50
    if ("main" in lower) score += 40
    if ("home" in lower) score += 30
    if ("tab" in lower) score += 20
    if ("cms" in lower) score -= 10
    if ("splash" in lower || "first" in lower || "guide" in lower || "welcome" in lower) score -= 100
    return score
}

private fun ActivityInfo.loadLabel(context: Context): CharSequence =
    runCatching { loadLabel(context.packageManager) }.getOrDefault(name ?: packageName.orEmpty())

private fun <T> Any.readField(name: String): T? {
    var current: Class<*>? = javaClass
    while (current != null) {
        val field = runCatching { current.getDeclaredField(name) }.getOrNull()
        if (field != null) {
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(this) as? T
        }
        current = current.superclass
    }
    return null
}
