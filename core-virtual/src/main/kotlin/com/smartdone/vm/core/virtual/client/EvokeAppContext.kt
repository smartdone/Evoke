package com.smartdone.vm.core.virtual.client

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources

class EvokeAppContext(
    base: Context,
    private val evokePackageName: String,
    private val evokeClassLoader: ClassLoader,
    private val evokeResources: Resources,
    private val evokeApplicationInfo: ApplicationInfo
) : ContextWrapper(base) {
    override fun getPackageName(): String = evokePackageName

    override fun getOpPackageName(): String = evokePackageName

    override fun getResources(): Resources = evokeResources

    override fun getAssets(): AssetManager = evokeResources.assets

    override fun getClassLoader(): ClassLoader = evokeClassLoader

    override fun getApplicationInfo(): ApplicationInfo = evokeApplicationInfo
}
