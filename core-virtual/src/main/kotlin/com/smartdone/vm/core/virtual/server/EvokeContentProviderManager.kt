package com.smartdone.vm.core.virtual.server

import android.content.Context
import android.os.Bundle
import com.smartdone.vm.core.virtual.aidl.IEvokeContentProviderManager
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.install.ApkParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Singleton
class EvokeContentProviderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: EvokeAppRepository,
    private val apkParser: ApkParser
) : IEvokeContentProviderManager.Stub() {
    private val providerAuthorityMap = linkedMapOf<String, ProviderRoute>()

    override fun acquireProvider(authority: String): Bundle = Bundle().apply {
        ensureMappings()
        putString("authority", authority)
        providerAuthorityMap[authority]?.let { route ->
            putString("hostAuthority", route.stubAuthority)
            putString("packageName", route.packageName)
            putInt("slotId", route.slotId)
        }
    }

    override fun getProviderInfo(authority: String): Bundle = Bundle().apply {
        ensureMappings()
        putString("authority", authority)
        providerAuthorityMap[authority]?.let { route ->
            putString("hostAuthority", route.stubAuthority)
            putString("packageName", route.packageName)
            putInt("slotId", route.slotId)
        }
    }

    fun providerRoute(authority: String): ProviderRoute? {
        ensureMappings()
        return providerAuthorityMap[authority]
    }

    fun refreshMappings() {
        providerAuthorityMap.clear()
        runBlocking {
            repository.getApps().forEachIndexed { index, app ->
                apkParser.parseArchive(app.apkPath)?.providers.orEmpty().forEach { providerAuthority ->
                    providerAuthorityMap[providerAuthority] = AuthorityMapper.createRoute(
                        hostPackage = context.packageName,
                        packageName = app.packageName,
                        originalAuthority = providerAuthority,
                        slotId = index % 10
                    )
                }
            }
        }
    }

    private fun ensureMappings() {
        if (providerAuthorityMap.isEmpty()) {
            refreshMappings()
        }
    }
}
