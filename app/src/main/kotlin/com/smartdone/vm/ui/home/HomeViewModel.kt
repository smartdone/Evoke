package com.smartdone.vm.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.data.EvokeAppRepository
import com.smartdone.vm.core.virtual.model.EvokeAppGroupSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: EvokeAppRepository,
    private val evokeCore: EvokeCore
) : ViewModel() {
    val appGroups: StateFlow<List<EvokeAppGroupSummary>> = repository.observeAppGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun launch(packageName: String, userId: Int = 0) {
        viewModelScope.launch {
            evokeCore.launchApp(packageName, userId)
        }
    }

    fun stop(packageName: String, userId: Int = 0) {
        viewModelScope.launch {
            evokeCore.stopApp(packageName, userId)
        }
    }

    fun createInstance(packageName: String, label: String, existingCount: Int) {
        viewModelScope.launch {
            evokeCore.createInstance(packageName, "$label #$existingCount")
        }
    }

    fun uninstall(packageName: String) {
        viewModelScope.launch {
            evokeCore.uninstallApp(packageName)
        }
    }
}
