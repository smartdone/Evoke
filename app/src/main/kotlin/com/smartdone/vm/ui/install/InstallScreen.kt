package com.smartdone.vm.ui.install

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallScreen(
    initialTab: Int = 0,
    viewModel: InstallViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.confirmUri(uri)
        }
    }

    LaunchedEffect(state.lastNotice) {
        state.lastNotice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedTab == 1) {
                FloatingActionButton(onClick = { launcher.launch("application/vnd.android.package-archive") }) {
                    Icon(Icons.Outlined.Add, contentDescription = "选取 APK")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                listOf("已安装应用", "本地 APK").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            progress?.let {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .animateContentSize()
                ) {
                    Text(it.message)
                    LinearProgressIndicator(progress = { it.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            when (selectedTab) {
                0 -> InstalledAppTab(state = state, viewModel = viewModel)
                else -> LocalApkTab()
            }
        }
    }

    when (val pendingInstall = state.pendingInstall) {
        is PendingInstall.InstalledApp -> AlertDialog(
            onDismissRequest = viewModel::dismissPendingInstall,
            title = { Text("导入已安装应用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pendingInstall.app.label)
                    Text(pendingInstall.app.packageName)
                    Text("将复制 base.apk、split APK 和 native libs 到 Evoke 沙盒。")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.installFromInstalledApp(pendingInstall.app.packageName) }) {
                    Text("开始导入")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingInstall) {
                    Text("取消")
                }
            }
        )

        is PendingInstall.FileUri -> AlertDialog(
            onDismissRequest = viewModel::dismissPendingInstall,
            title = { Text("导入本地 APK") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("可以直接在 Evoke 内临时启动，或导入后长期保留。")
                    Text(pendingInstall.uri.toString())
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.launchFromUri(pendingInstall.uri) }) {
                        Text("直接启动")
                    }
                    TextButton(onClick = { viewModel.installFromUri(pendingInstall.uri) }) {
                        Text("导入保存")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingInstall) {
                    Text("取消")
                }
            }
        )

        null -> Unit
    }
}

@Composable
private fun InstalledAppTab(
    state: InstallUiState,
    viewModel: InstallViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text("搜索已安装应用") },
            singleLine = true
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("显示系统应用")
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = state.includeSystemApps,
                onCheckedChange = viewModel::setIncludeSystemApps
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.visibleApps, key = { it.packageName }) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.confirmInstalledApp(app) },
                    colors = CardDefaults.cardColors()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Android, contentDescription = null)
                        Column(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f)
                        ) {
                            Text(app.label)
                            Text(app.packageName)
                        }
                        Text(if (app.isSystemApp) "系统" else "用户")
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalApkTab() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("选择本地 APK 后可以直接临时启动，也可以导入到 Evoke 空间。")
        Text("直接启动不会安装到 Android 系统，也不会写入 Evoke 应用列表。")
    }
}
