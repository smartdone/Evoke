package com.smartdone.vm.core.virtual.di

import android.content.Context
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.data.db.AppDatabase
import com.smartdone.vm.core.virtual.data.db.EvokeAppDao
import com.smartdone.vm.core.virtual.install.ApkFileImporter
import com.smartdone.vm.core.virtual.install.ApkInstaller
import com.smartdone.vm.core.virtual.install.ApkParser
import com.smartdone.vm.core.virtual.install.AppCopier
import com.smartdone.vm.core.virtual.install.InstalledAppScanner
import com.smartdone.vm.core.virtual.sandbox.SandboxPath
import com.smartdone.vm.core.virtual.settings.EvokeSettingsRepository
import com.smartdone.vm.core.virtual.server.PermissionDelegateService
import com.smartdone.vm.core.virtual.server.AndroidProcessInspector
import com.smartdone.vm.core.virtual.server.ProcessInspector
import com.smartdone.vm.core.virtual.server.ProcessSlotManager
import com.smartdone.vm.core.virtual.server.EvokeActivityManagerService
import com.smartdone.vm.core.virtual.server.EvokeBroadcastManager
import com.smartdone.vm.core.virtual.server.EvokeContentProviderManager
import com.smartdone.vm.core.virtual.server.EvokePackageManagerService
import com.smartdone.vm.core.virtual.EvokeCore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EvokeCoreModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.get(context)

    @Provides
    fun provideDao(database: AppDatabase): EvokeAppDao = database.evokeAppDao()

    @Provides
    @Singleton
    fun provideRepository(dao: EvokeAppDao): EvokeAppRepository = EvokeAppRepository(dao)

    @Provides
    @Singleton
    fun provideProcessInspector(
        androidProcessInspector: AndroidProcessInspector
    ): ProcessInspector = androidProcessInspector

    @Provides
    @Singleton
    fun provideApkParser(@ApplicationContext context: Context): ApkParser = ApkParser(context)

    @Provides
    @Singleton
    fun provideInstalledAppScanner(@ApplicationContext context: Context): InstalledAppScanner =
        InstalledAppScanner(context)

    @Provides
    @Singleton
    fun provideSandboxPath(@ApplicationContext context: Context): SandboxPath = SandboxPath(context)

    @Provides
    @Singleton
    fun provideAppCopier(sandboxPath: SandboxPath): AppCopier = AppCopier(sandboxPath)

    @Provides
    @Singleton
    fun provideApkFileImporter(
        @ApplicationContext context: Context,
        apkParser: ApkParser,
        sandboxPath: SandboxPath
    ): ApkFileImporter = ApkFileImporter(context, apkParser, sandboxPath)

    @Provides
    @Singleton
    fun provideApkInstaller(
        installedAppScanner: InstalledAppScanner,
        appCopier: AppCopier,
        apkFileImporter: ApkFileImporter,
        apkParser: ApkParser,
        repository: EvokeAppRepository,
        sandboxPath: SandboxPath
    ): ApkInstaller = ApkInstaller(
        installedAppScanner = installedAppScanner,
        appCopier = appCopier,
        apkFileImporter = apkFileImporter,
        apkParser = apkParser,
        repository = repository,
        sandboxPath = sandboxPath
    )

    @Provides
    @Singleton
    fun provideEvokeCore(
        @ApplicationContext context: Context,
        repository: EvokeAppRepository,
        apkFileImporter: ApkFileImporter,
        packageManagerService: EvokePackageManagerService,
        activityManagerService: EvokeActivityManagerService,
        permissionDelegateService: PermissionDelegateService,
        broadcastManager: EvokeBroadcastManager,
        contentProviderManager: EvokeContentProviderManager,
        processSlotManager: ProcessSlotManager,
        sandboxPath: SandboxPath,
        settingsRepository: EvokeSettingsRepository
    ): EvokeCore = EvokeCore(
        context = context,
        repository = repository,
        apkFileImporter = apkFileImporter,
        packageManagerService = packageManagerService,
        activityManagerService = activityManagerService,
        permissionDelegateService = permissionDelegateService,
        broadcastManager = broadcastManager,
        contentProviderManager = contentProviderManager,
        processSlotManager = processSlotManager,
        sandboxPath = sandboxPath,
        settingsRepository = settingsRepository
    )
}
