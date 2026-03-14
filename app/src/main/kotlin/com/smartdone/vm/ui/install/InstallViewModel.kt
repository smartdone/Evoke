package com.smartdone.vm.ui.install

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.install.ApkInstaller
import com.smartdone.vm.core.virtual.install.InstalledAppScanner
import com.smartdone.vm.core.virtual.model.InstallProgress
import com.smartdone.vm.core.virtual.model.InstalledAppInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class InstallViewModel @Inject constructor(
    private val installedAppScanner: InstalledAppScanner,
    private val apkInstaller: ApkInstaller,
    private val evokeCore: EvokeCore
) : ViewModel() {
    private val _state = MutableStateFlow(InstallUiState())
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private var installJob: Job? = null

    init {
        refreshInstalledApps()
    }

    fun refreshInstalledApps() {
        val includeSystemApps = _state.value.includeSystemApps
        val installedApps = installedAppScanner.scan(includeSystemApps)
        _state.update {
            it.copy(
                installedApps = installedApps,
                visibleApps = installedApps.filteredBy(it.searchQuery)
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _state.update {
            it.copy(
                searchQuery = query,
                visibleApps = it.installedApps.filteredBy(query)
            )
        }
    }

    fun setIncludeSystemApps(includeSystemApps: Boolean) {
        _state.update { it.copy(includeSystemApps = includeSystemApps) }
        refreshInstalledApps()
    }

    fun confirmInstalledApp(app: InstalledAppInfo) {
        _state.update { it.copy(pendingInstall = PendingInstall.InstalledApp(app)) }
    }

    fun confirmUri(uri: Uri) {
        _state.update { it.copy(pendingInstall = PendingInstall.FileUri(uri)) }
    }

    fun dismissPendingInstall() {
        _state.update { it.copy(pendingInstall = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(lastNotice = null) }
    }

    fun installFromInstalledApp(packageName: String) {
        installJob?.cancel()
        dismissPendingInstall()
        _progress.value = null
        installJob = viewModelScope.launch {
            runCatching {
                apkInstaller.installFromInstalledApp(packageName).collect { _progress.value = it }
            }.onSuccess {
                _state.update { it.copy(lastNotice = "已导入 $packageName") }
                refreshInstalledApps()
            }.onFailure { throwable ->
                _state.update { it.copy(lastNotice = throwable.message ?: "导入失败") }
            }
        }
    }

    fun installFromUri(uri: Uri) {
        installJob?.cancel()
        dismissPendingInstall()
        _progress.value = null
        installJob = viewModelScope.launch {
            runCatching {
                apkInstaller.installFromFile(uri).collect { _progress.value = it }
            }.onSuccess {
                _state.update { it.copy(lastNotice = "APK 已导入") }
                refreshInstalledApps()
            }.onFailure { throwable ->
                _state.update { it.copy(lastNotice = throwable.message ?: "APK 导入失败") }
            }
        }
    }

    fun launchFromUri(uri: Uri) {
        installJob?.cancel()
        dismissPendingInstall()
        _progress.value = null
        installJob = viewModelScope.launch {
            runCatching {
                evokeCore.launchApkUri(uri)
            }.onSuccess { launched ->
                _state.update {
                    it.copy(lastNotice = if (launched) "APK 已直接启动" else "APK 缺少可启动 Activity")
                }
            }.onFailure { throwable ->
                _state.update { it.copy(lastNotice = throwable.message ?: "APK 直接启动失败") }
            }
        }
    }
}

data class InstallUiState(
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val visibleApps: List<InstalledAppInfo> = emptyList(),
    val searchQuery: String = "",
    val includeSystemApps: Boolean = false,
    val pendingInstall: PendingInstall? = null,
    val lastNotice: String? = null
)

sealed interface PendingInstall {
    data class InstalledApp(val app: InstalledAppInfo) : PendingInstall
    data class FileUri(val uri: Uri) : PendingInstall
}

private fun List<InstalledAppInfo>.filteredBy(query: String): List<InstalledAppInfo> {
    if (query.isBlank()) return this
    val normalized = query.trim().lowercase()
    return filter {
        it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized)
    }
}
