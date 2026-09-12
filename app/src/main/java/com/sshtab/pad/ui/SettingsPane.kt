package com.sshtab.pad.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sshtab.pad.service.KeepAliveOem

@Composable
fun SettingsPane(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val theme by AppSettings.theme.collectAsState()
    val font by AppSettings.fontSize.collectAsState()
    val extra by AppSettings.extraKeys.collectAsState()
    val screen by AppSettings.keepScreenOn.collectAsState()
    val reconnect by AppSettings.autoReconnect.collectAsState()
    val statusSec by AppSettings.statusSec.collectAsState()
    val savePass by AppSettings.savePassword.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Text("高亮使用 GitHub Primer 配色。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = theme == "dark", onClick = { AppSettings.setTheme("dark") }, label = { Text("夜间") })
            FilterChip(selected = theme == "light", onClick = { AppSettings.setTheme("light") }, label = { Text("白天") })
            FilterChip(selected = theme == "system", onClick = { AppSettings.setTheme("system") }, label = { Text("跟随系统") })
        }
        Text("终端字号 $font")
        Slider(
            value = font.toFloat(),
            onValueChange = { AppSettings.setFontSize(it.toInt()) },
            valueRange = 12f..22f,
            steps = 9,
        )
        SettingSwitch("显示额外按键", extra) { AppSettings.setExtraKeys(it) }
        SettingSwitch("保持屏幕常亮", screen) { AppSettings.setKeepScreenOn(it) }

        HorizontalDivider()
        Text("会话", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("断线自动重连", reconnect) { AppSettings.setAutoReconnect(it) }
        SettingSwitch("保存节点密码/密钥", savePass) { AppSettings.setSavePassword(it) }
        Text("状态刷新 ${statusSec}s（CPU / 内存 / 网络）")
        Slider(
            value = statusSec.toFloat(),
            onValueChange = { AppSettings.setStatusSec(it.toInt()) },
            valueRange = 3f..15f,
            steps = 11,
        )

        HorizontalDivider()
        Text("保活（国行）", style = MaterialTheme.typography.titleMedium)
        Text(
            "参考 ServerBox：关闭电池优化、允许自启动/后台运行、允许悬浮窗。后台用静音音频 + 前台服务，无需音乐通知。首次启动会引导一次。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { KeepAliveOem.requestOverlay(ctx) }) {
            Text(if (KeepAliveOem.overlayGranted(ctx)) "悬浮窗已允许" else "去允许悬浮窗")
        }
        TextButton(onClick = { KeepAliveOem.requestBattery(ctx) }) {
            Text(if (KeepAliveOem.batteryGranted(ctx)) "电池无限制已开" else "关闭电池优化")
        }
        TextButton(onClick = { KeepAliveOem.openVendorKeepAlive(ctx) }) { Text("厂商自启动 / 后台运行") }
        TextButton(onClick = { AppSettings.setOnboarded(false) }) { Text("重新显示首次引导") }
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun OnboardDialog() {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val titles = listOf("后台保活", "悬浮窗", "电池与自启动")
    val bodies = listOf(
        "国行系统会在切走后冻结 SSH。接下来按 ServerBox 的做法打开保活项，只需一次。",
        "允许悬浮窗后，切到其他 App 时右上角会有 SSH 绿点，系统不容易冻进程。",
        "电池策略设为无限制，并在安全中心允许自启动 / 后台运行。之后可在「设置」里改。",
    )
    AlertDialog(
        onDismissRequest = { AppSettings.setOnboarded(true) },
        title = { Text("${titles[step]}  (${step + 1}/3)") },
        text = { Text(bodies[step]) },
        confirmButton = {
            TextButton(onClick = {
                when (step) {
                    0 -> step = 1
                    1 -> {
                        KeepAliveOem.requestOverlay(ctx)
                        step = 2
                    }
                    else -> {
                        KeepAliveOem.requestBattery(ctx)
                        KeepAliveOem.openVendorKeepAlive(ctx)
                        AppSettings.setOnboarded(true)
                    }
                }
            }) { Text(if (step < 2) "下一步" else "去设置") }
        },
        dismissButton = {
            TextButton(onClick = { AppSettings.setOnboarded(true) }) { Text("跳过") }
        },
    )
}
