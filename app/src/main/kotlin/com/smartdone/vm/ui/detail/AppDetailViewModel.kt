package com.smartdone.vm.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: EvokeAppRepository,
    private val evokeCore: EvokeCore
) : ViewModel() {
    private val packageName: String = checkNotNull(savedStateHandle[Destination.Detail.argument])

    val state: StateFlow<AppDetailUiState?> = combine(
        repository.observeAppDetails(packageName),
        evokeCore.runningApps()
    ) { details, runningApps ->
        val storageByUserId = evokeCore.storageStats(packageName).associateBy { it.userId }
        details?.let {
            AppDetailUiState(
                label = it.app.label,
                packageName = it.app.packageName,
                versionCode = it.app.versionCode,
                permissions = it.permissions.map { permission -> permission.permissionName to permission.isGranted },
                instances = it.instances.map { instance ->
                    val storage = storageByUserId[instance.userId]
                    AppDetailInstanceUiState(
                        userId = instance.userId,
                        displayName = instance.displayName,
                        isRunning = runningApps.any { app ->
                            app.packageName == instance.packageName && app.userId == instance.userId
                        },
                        dataBytes = storage?.dataBytes ?: 0L,
                        cacheBytes = storage?.cacheBytes ?: 0L
                    )
                }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun launch(userId: Int = 0) {
        viewModelScope.launch {
            evokeCore.launchApp(packageName, userId)
        }
    }

    fun stop(userId: Int = 0) {
        viewModelScope.launch {
            evokeCore.stopApp(packageName, userId)
        }
    }

    fun createInstance() {
        viewModelScope.launch {
            val stateValue = state.value ?: return@launch
            val nextIndex = stateValue.instances.size
            evokeCore.createInstance(packageName, "${stateValue.label} #$nextIndex")
        }
    }

    fun requestPermission(permission: String) {
        evokeCore.requestPermission(packageName, permission)
    }

    fun renameInstance(userId: Int, displayName: String) {
        viewModelScope.launch {
            evokeCore.renameInstance(packageName, userId, displayName)
        }
    }

    fun deleteInstance(userId: Int) {
        viewModelScope.launch {
            evokeCore.deleteInstance(packageName, userId)
        }
    }

    fun clearCache(userId: Int) {
        viewModelScope.launch {
            evokeCore.clearCache(packageName, userId)
        }
    }

    fun clearData(userId: Int) {
        viewModelScope.launch {
            evokeCore.clearData(packageName, userId)
        }
    }
}

data class AppDetailUiState(
    val label: String,
    val packageName: String,
    val versionCode: Long,
    val permissions: List<Pair<String, Boolean>>,
    val instances: List<AppDetailInstanceUiState>
)

data class AppDetailInstanceUiState(
    val userId: Int,
    val displayName: String,
    val isRunning: Boolean,
    val dataBytes: Long,
    val cacheBytes: Long
)
