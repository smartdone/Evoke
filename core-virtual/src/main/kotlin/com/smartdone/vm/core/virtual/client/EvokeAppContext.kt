package com.smartdone.vm.core.virtual.client

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper
import com.smartdone.vm.core.virtual.client.hook.ExternalServiceBinderHook
import java.util.concurrent.Executor

class EvokeAppContext(
    base: Context,
    private val evokePackageName: String,
    private val reportedPackageName: String,
    private val evokeClassLoader: ClassLoader,
    private val evokeResources: Resources,
    private val evokeApplicationInfo: ApplicationInfo,
    initialThemeResId: Int = evokeApplicationInfo.theme
) : ContextThemeWrapper(base, initialThemeResId) {
    private var virtualApplicationContext: Context? = null
    private var themeResId: Int = initialThemeResId
    private val hostPackageName: String = base.packageName

    override fun getPackageName(): String =
        if (shouldExposeHostPackageName()) hostPackageName else reportedPackageName

    override fun getOpPackageName(): String = hostPackageName

    override fun getResources(): Resources = evokeResources

    override fun getAssets(): AssetManager = evokeResources.assets

    override fun getClassLoader(): ClassLoader = evokeClassLoader

    override fun getApplicationInfo(): ApplicationInfo = evokeApplicationInfo

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
        val configuredBase = baseContext.createConfigurationContext(overrideConfiguration)
        return EvokeAppContext(
            base = configuredBase,
            evokePackageName = evokePackageName,
            reportedPackageName = reportedPackageName,
            evokeClassLoader = evokeClassLoader,
            evokeResources = Resources(
                evokeResources.assets,
                evokeResources.displayMetrics,
                Configuration(overrideConfiguration)
            ),
            evokeApplicationInfo = evokeApplicationInfo,
            initialThemeResId = themeResId
        ).also { derived ->
            virtualApplicationContext?.let(derived::setVirtualApplicationContext)
        }
    }

    override fun getTheme(): Resources.Theme {
        return super.getTheme().apply {
            if (themeResId != 0) {
                applyStyle(themeResId, true)
            }
        }
    }

    override fun setTheme(resid: Int) {
        themeResId = resid
        super.setTheme(resid)
    }

    override fun getApplicationContext(): Context = virtualApplicationContext ?: this

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
        super.bindService(service, ExternalServiceBinderHook.wrapConnectionIfNeeded(service, conn), flags)

    override fun bindService(
        service: Intent,
        conn: ServiceConnection,
        flags: Context.BindServiceFlags
    ): Boolean = super.bindService(
        service,
        ExternalServiceBinderHook.wrapConnectionIfNeeded(service, conn),
        flags
    )

    override fun bindService(
        service: Intent,
        flags: Int,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean = super.bindService(
        service,
        flags,
        executor,
        ExternalServiceBinderHook.wrapConnectionIfNeeded(service, conn)
    )

    override fun bindService(
        service: Intent,
        flags: Context.BindServiceFlags,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean = super.bindService(
        service,
        flags,
        executor,
        ExternalServiceBinderHook.wrapConnectionIfNeeded(service, conn)
    )

    override fun bindIsolatedService(
        service: Intent,
        flags: Int,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean = super.bindIsolatedService(
        service,
        flags,
        instanceName,
        executor,
        ExternalServiceBinderHook.wrapConnectionIfNeeded(service, conn)
    )

    override fun bindIsolatedService(
        service: Intent,
        flags: Context.BindServiceFlags,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean = super.bindIsolatedService(
        service,
        flags,
        instanceName,
        executor,
        ExternalServiceBinderHook.wrapConnectionIfNeeded(service, conn)
    )

    override fun unbindService(conn: ServiceConnection) {
        super.unbindService(ExternalServiceBinderHook.unwrapConnection(conn))
    }

    fun setVirtualApplicationContext(context: Context) {
        virtualApplicationContext = context
    }

    private fun shouldExposeHostPackageName(): Boolean =
        Thread.currentThread().stackTrace.any { frame ->
            HOST_IDENTITY_CALLER_PREFIXES.any(frame.className::startsWith)
        }

    companion object {
        private val HOST_IDENTITY_CALLER_PREFIXES = listOf(
            "com.google.android.gms.",
            "com.google.firebase.",
            "com.google.android.datatransport.",
            "com.google.android.play."
        )
    }
}
