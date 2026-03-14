package com.smartdone.vm.ui.running

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartdone.vm.core.virtual.EvokeCore
import com.smartdone.vm.core.virtual.model.RunningAppRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RunningAppsViewModel @Inject constructor(
    private val evokeCore: EvokeCore
) : ViewModel() {
    val runningApps: StateFlow<List<RunningAppRecord>> = evokeCore.runningApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun stop(packageName: String, userId: Int) {
        viewModelScope.launch {
            evokeCore.stopApp(packageName, userId)
        }
    }
}
