package com.smartdone.vm.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdone.vm.core.virtual.settings.ThemeModePreference

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var lastLaunchReport by remember { mutableStateOf(viewModel.lastLaunchReport()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Evoke 设置", style = MaterialTheme.typography.headlineSmall)
            Text(
                "管理宿主题、运行时通知、启动日志和诊断信息。",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        ElevatedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionTitle("外观")
                Text("主题模式", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeModePreference.entries.forEach { themeMode ->
                        FilterChip(
                            selected = settings.themeMode == themeMode,
                            onClick = { viewModel.setThemeMode(themeMode) },
                            label = {
                                Text(
                                    when (themeMode) {
                                        ThemeModePreference.SYSTEM -> "跟随系统"
                                        ThemeModePreference.LIGHT -> "浅色"
                                        ThemeModePreference.DARK -> "深色"
                                    }
                                )
                            }
                        )
                    }
                }
                SwitchSettingRow(
                    title = "启用动态取色",
                    summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "在 Android 12 及以上使用系统动态色板。"
                    } else {
                        "当前系统版本不支持动态取色。"
                    },
                    checked = settings.useDynamicColors,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    onCheckedChange = viewModel::setDynamicColorsEnabled
                )
            }
        }

        ElevatedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionTitle("运行时")
                SwitchSettingRow(
                    title = "显示运行中通知",
                    summary = "在宿主侧显示当前正在运行的 Evoke 应用，并提供快速停止入口。",
                    checked = settings.showRunningAppsNotification,
                    onCheckedChange = viewModel::setRunningAppsNotificationEnabled
                )
                HorizontalDivider()
                SwitchSettingRow(
                    title = "启动时默认打开运行页",
                    summary = "下次冷启动宿主应用时直接进入“运行中”页面。",
                    checked = settings.openRunningAppsOnLaunch,
                    onCheckedChange = viewModel::setOpenRunningAppsOnLaunch
                )
                HorizontalDivider()
                SwitchSettingRow(
                    title = "启动时记录 Native 兼容日志",
                    summary = "在宿主启动时打印 ABI、so 打包方式和页面大小等兼容性信息。",
                    checked = settings.logNativeCompatibilityOnStartup,
                    onCheckedChange = viewModel::setNativeCompatibilityLoggingEnabled
                )
            }
        }

        ElevatedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionTitle("诊断")
                Text(
                    "系统版本：Android ${Build.VERSION.RELEASE_OR_CODENAME} (API ${Build.VERSION.SDK_INT})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "设备 ABI：${Build.SUPPORTED_ABIS.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.logNativeCompatibilityNow()
                            Toast.makeText(context, "已写入 Native 兼容日志", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("立即记录日志")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            )
                        }
                    ) {
                        Text("通知权限设置")
                    }
                }
            }
        }

        ElevatedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("最近一次启动诊断")
                if (lastLaunchReport == null) {
                    Text("当前没有可用的启动报告。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val report = lastLaunchReport ?: return@Column
                    DiagnosticLine("包名", report.packageName)
                    DiagnosticLine("用户", report.userId.toString())
                    DiagnosticLine("目标 Activity", report.realActivity)
                    DiagnosticLine("Application", report.applicationStatus)
                    DiagnosticLine("Launcher", report.launcherStatus)
                    DiagnosticLine("Activity", report.activityStatus)
                    DiagnosticLine("失败阶段", report.failureStage.ifBlank { "无" })
                    DiagnosticLine("失败信息", report.failureMessage.ifBlank { "无" })
                    OutlinedButton(
                        onClick = {
                            viewModel.clearLastLaunchReport()
                            lastLaunchReport = null
                        }
                    ) {
                        Text("清除启动报告")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SwitchSettingRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
