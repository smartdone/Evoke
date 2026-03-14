package com.smartdone.vm.ui.running

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RunningAppsScreen(viewModel: RunningAppsViewModel = hiltViewModel()) {
    val runningApps by viewModel.runningApps.collectAsStateWithLifecycle()
    if (runningApps.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("当前没有运行中的 Evoke 应用", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(runningApps) { app ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${app.packageName} · user ${app.userId} · ${app.processName}")
                Text("pid=${app.pid}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { viewModel.stop(app.packageName, app.userId) }) {
                    Text("停止")
                }
            }
        }
    }
}
