package com.smartdone.vm.core.virtual.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper

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

    override fun getPackageName(): String = reportedPackageName

    override fun getOpPackageName(): String = reportedPackageName

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

    fun setVirtualApplicationContext(context: Context) {
        virtualApplicationContext = context
    }
}
