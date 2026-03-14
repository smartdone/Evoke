package com.smartdone.vm.core.virtual.install

import android.content.Context
import android.content.pm.ApplicationInfo
import com.smartdone.vm.core.virtual.model.InstalledAppInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun scan(includeSystemApps: Boolean = false): List<InstalledAppInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .asSequence()
            .filterNot { !includeSystemApps && it.isSystemApp() }
            .filterNot { it.packageName == context.packageName }
            .map { info ->
                InstalledAppInfo(
                    packageName = info.packageName,
                    label = info.loadLabel(pm)?.toString().orEmpty(),
                    isSystemApp = info.isSystemApp(),
                    sourceDir = info.sourceDir.orEmpty(),
                    splitSourceDirs = info.splitSourceDirs?.toList().orEmpty(),
                    nativeLibraryDir = info.nativeLibraryDir
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

private fun ApplicationInfo.isSystemApp(): Boolean =
    (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
