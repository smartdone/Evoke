package com.smartdone.vm.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    viewModel: AppDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<AppDetailInstanceUiState?>(null) }
    var renameText by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state?.label ?: "应用详情") }
            )
        }
    ) { padding ->
        state?.let { details ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(details.packageName, style = MaterialTheme.typography.titleMedium)
                    Text("Version ${details.versionCode}")
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(onClick = { viewModel.launch() }) {
                            Text("启动默认实例")
                        }
                        OutlinedButton(onClick = { viewModel.stop() }) {
                            Text("停止默认实例")
                        }
                        OutlinedButton(onClick = viewModel::createInstance) {
                            Text("创建分身")
                        }
                    }
                }
                item {
                    Text("权限", style = MaterialTheme.typography.titleMedium)
                }
                items(details.permissions) { (permission, granted) ->
                    AssistChip(
                        onClick = { if (!granted) viewModel.requestPermission(permission) },
                        label = { Text(if (granted) "$permission 已授权" else "$permission 未授权") }
                    )
                }
                item {
                    Text("实例", style = MaterialTheme.typography.titleMedium)
                }
                items(details.instances) { instance ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${instance.displayName} · user ${instance.userId}")
                        Text(
                            "数据 ${formatBytes(instance.dataBytes)} · 缓存 ${formatBytes(instance.cacheBytes)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { viewModel.launch(instance.userId) }) {
                                Text(if (instance.isRunning) "重新打开" else "启动")
                            }
                            OutlinedButton(onClick = { viewModel.stop(instance.userId) }) {
                                Text("停止")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                renameTarget = instance
                                renameText = instance.displayName
                            }) {
                                Text("重命名")
                            }
                            OutlinedButton(onClick = { viewModel.clearCache(instance.userId) }) {
                                Text("清缓存")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.clearData(instance.userId) }) {
                                Text("清数据")
                            }
                            OutlinedButton(onClick = { viewModel.deleteInstance(instance.userId) }) {
                                Text("删除实例")
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名实例") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("实例名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameInstance(target.userId, renameText)
                        renameTarget = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
