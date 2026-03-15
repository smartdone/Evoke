package com.smartdone.vm.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil.compose.AsyncImage

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun HomeScreen(
    onAddAppClick: (Int) -> Unit,
    onAppClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val appGroups by viewModel.appGroups.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<com.smartdone.vm.core.virtual.model.EvokeAppGroupSummary?>(null) }
    if (appGroups.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Evoke 空间为空", style = MaterialTheme.typography.headlineSmall)
                Text("从已安装应用复制，或直接导入 APK。")
                FilledTonalButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = null)
                    Text("添加应用")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FilledTonalButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = null)
                    Text("添加应用")
                }
            }
            itemsIndexed(appGroups, key = { _, item -> item.app.packageName }) { index, group ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onAppClick(group.app.packageName) },
                                onLongClick = { manageTarget = group }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            AsyncImage(
                                model = group.app.iconPath,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                contentScale = ContentScale.Fit
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(group.app.label, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        group.app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "v${group.app.versionCode}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "${group.instances.size} 个实例",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(onClick = { viewModel.launch(group.app.packageName) }) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                        Text("启动")
                                    }
                                    if (group.app.isRunning) {
                                        OutlinedButton(onClick = { viewModel.stop(group.app.packageName) }) {
                                            Icon(Icons.Outlined.Stop, contentDescription = null)
                                            Text("停止")
                                        }
                                    }
                                    group.instances.forEach { instance ->
                                        OutlinedButton(
                                            onClick = { viewModel.launch(group.app.packageName, instance.userId) },
                                        ) {
                                            Text(instanceDisplayName(group, instance))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (index != appGroups.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("添加应用", style = MaterialTheme.typography.titleLarge)
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showAddSheet = false
                        onAddAppClick(0)
                    }
                ) {
                    Text("从已安装应用导入")
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showAddSheet = false
                        onAddAppClick(1)
                    }
                ) {
                    Text("从本地 APK 导入")
                }
            }
        }
    }

    manageTarget?.let { group ->
        ModalBottomSheet(
            onDismissRequest = { manageTarget = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(group.app.label, style = MaterialTheme.typography.titleLarge)
                Text(group.app.packageName, style = MaterialTheme.typography.bodySmall)
                ListItem(
                    headlineContent = { Text("打开详情") },
                    supportingContent = { Text("查看权限、存储和实例管理") },
                    modifier = Modifier.clickable {
                        manageTarget = null
                        onAppClick(group.app.packageName)
                    }
                )
                ListItem(
                    headlineContent = { Text("创建分身") },
                    supportingContent = { Text("为该应用新增独立 userId 实例") },
                    modifier = Modifier.clickable {
                        viewModel.createInstance(
                            packageName = group.app.packageName,
                            label = group.app.label,
                            existingCount = group.instances.size
                        )
                        manageTarget = null
                    }
                )
                ListItem(
                    headlineContent = { Text("启动默认实例") },
                    supportingContent = { Text("快速启动 user 0") },
                    modifier = Modifier.clickable {
                        viewModel.launch(group.app.packageName)
                        manageTarget = null
                    }
                )
                if (group.app.isRunning) {
                    ListItem(
                        headlineContent = { Text("停止默认实例") },
                        supportingContent = { Text("停止当前默认实例进程") },
                        modifier = Modifier.clickable {
                            viewModel.stop(group.app.packageName)
                            manageTarget = null
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text("删除 Evoke 应用") },
                    supportingContent = { Text("移除 APK、实例数据和数据库记录") },
                    modifier = Modifier.clickable {
                        viewModel.uninstall(group.app.packageName)
                        manageTarget = null
                    }
                )
            }
        }
    }
}

private fun instanceDisplayName(
    group: com.smartdone.vm.core.virtual.model.EvokeAppGroupSummary,
    instance: com.smartdone.vm.core.virtual.model.EvokeAppInstanceSummary
): String {
    val baseName = instance.displayName.ifBlank { group.app.label }
    return if (baseName.endsWith(".user${instance.userId}")) {
        baseName
    } else {
        "$baseName.user${instance.userId}"
    }
}
