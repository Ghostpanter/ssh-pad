package com.sshtab.pad.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sshtab.pad.service.SshSessionService
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.SshSessionManager
import com.sshtab.pad.ssh.TransportKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshPadAppUi() {
    var tab by remember { mutableIntStateOf(0) }
    val connected by SshSessionManager.connected.collectAsState()
    val status by SshSessionManager.status.collectAsState()
    val kind by SshSessionManager.kind.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Pad") },
                actions = {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            val tablet = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (tablet) {
                    NavigationRail {
                        NavigationRailItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Terminal, null) },
                            label = { Text("终端") },
                        )
                        NavigationRailItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Folder, null) },
                            label = { Text("文件") },
                            enabled = kind == TransportKind.SSH,
                        )
                    }
                    if (tab == 0) {
                        ConnectAndTerminal(Modifier.weight(1f), connected)
                    } else {
                        FilePane(Modifier.weight(1f), connected && kind == TransportKind.SSH)
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { tab = 0 },
                                modifier = Modifier.padding(8.dp),
                            ) { Text("终端") }
                            FilledTonalButton(
                                onClick = { tab = 1 },
                                modifier = Modifier.padding(8.dp),
                                enabled = kind == TransportKind.SSH,
                            ) { Text("文件") }
                        }
                        if (tab == 0) ConnectAndTerminal(Modifier.weight(1f), connected)
                        else FilePane(Modifier.weight(1f), connected && kind == TransportKind.SSH)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectAndTerminal(modifier: Modifier, connected: Boolean) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(TransportKind.SSH) }
    var host by remember { mutableStateOf("192.168.1.1") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var pass by remember { mutableStateOf("") }

    Column(modifier.padding(12.dp)) {
        if (!connected) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == TransportKind.SSH,
                            onClick = {
                                kind = TransportKind.SSH
                                if (port == "23") port = "22"
                            },
                            label = { Text("SSH") },
                        )
                        FilterChip(
                            selected = kind == TransportKind.TELNET,
                            onClick = {
                                kind = TransportKind.TELNET
                                if (port == "22") port = "23"
                            },
                            label = { Text("Telnet") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            host, { host = it },
                            label = { Text("主机") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            port, { port = it },
                            label = { Text("端口") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    if (kind == TransportKind.SSH) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                user, { user = it },
                                label = { Text("用户名") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                pass, { pass = it },
                                label = { Text("密码") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                    } else {
                        Text(
                            "Telnet 在终端里交互登录，账号密码直接打在屏幕上。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = {
                        val profile = HostProfile(
                            name = user.trim().ifBlank { host.trim() },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: if (kind == TransportKind.TELNET) 23 else 22,
                            username = user.trim(),
                            password = pass,
                            kind = kind,
                        )
                        when {
                            profile.host.isBlank() -> SshSessionManager.fail("请填写主机")
                            kind == TransportKind.SSH && profile.username.isBlank() ->
                                SshSessionManager.fail("请填写用户名")
                            else -> SshSessionService.startConnect(ctx, profile)
                        }
                    }) { Text("连接") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "连接后像 ServerBox 一样直接在终端里输入。切到其他应用也会保活：请允许通知，并在弹出的电池优化对话框里选「允许」。",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = {
                    ctx.startService(
                        Intent(ctx, SshSessionService::class.java).apply {
                            action = SshSessionService.ACTION_DISCONNECT
                        }
                    )
                }) {
                    Icon(Icons.Default.LinkOff, null)
                    Spacer(Modifier.width(6.dp))
                    Text("断开")
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(Modifier.weight(1f).fillMaxWidth()) {
                XtermView(Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            ExtraKeysBar()
        }
    }
}

@Composable
private fun ExtraKeysBar() {
    val keys = listOf(
        "Esc" to "\u001b",
        "Tab" to "\t",
        "Ctrl-A" to "\u0001",
        "Ctrl-C" to "\u0003",
        "Ctrl-D" to "\u0004",
        "Ctrl-Z" to "\u001a",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "Home" to "\u001b[H",
        "End" to "\u001b[F",
        "PgUp" to "\u001b[5~",
        "PgDn" to "\u001b[6~",
        "~" to "~",
        "/" to "/",
        "-" to "-",
        "|" to "|",
    )
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            keys.forEach { (label, seq) ->
                FilledTonalButton(
                    onClick = { SshSessionManager.writeUtf8(seq) },
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun FilePane(modifier: Modifier, connected: Boolean) {
    val ctx = LocalContext.current
    val files by SshSessionManager.files.collectAsState()
    val path by SshSessionManager.remotePath.collectAsState()
    val kind by SshSessionManager.kind.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingDownload by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val name = pendingDownload ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val tmp = File(ctx.cacheDir, name)
                withContext(Dispatchers.IO) {
                    SshSessionManager.download(name, tmp.toPath())
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                }
                message = "已保存 $name"
            } catch (e: Exception) {
                message = "下载失败: ${e.message}"
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
                val tmp = File(ctx.cacheDir, name)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    SshSessionManager.upload(tmp.toPath(), name)
                }
                message = "已上传 $name"
            } catch (e: Exception) {
                message = "上传失败: ${e.message}"
            }
        }
    }

    Column(modifier.padding(16.dp)) {
        if (kind == TransportKind.TELNET) {
            Text("Telnet 没有 SFTP，文件传输请用 SSH。")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "远程: $path",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { SshSessionManager.listRemote(path) }
                            } catch (e: Exception) {
                                message = e.message ?: "刷新失败"
                            }
                        }
                    },
                    enabled = connected,
                ) { Icon(Icons.Default.Refresh, "刷新") }
                IconButton(
                    onClick = { openLauncher.launch(arrayOf("*/*")) },
                    enabled = connected,
                ) {
                    Icon(Icons.Default.CloudUpload, "上传")
                }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            if (!connected) {
                Text("先在「终端」页连接 SSH，会话会保持在后台服务中。")
            }
            LazyColumn(Modifier.fillMaxHeight()) {
                items(files, key = { it.name }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(if (entry.isDirectory) "目录" else "${entry.size} 字节")
                        },
                        leadingContent = {
                            Icon(
                                if (entry.isDirectory) Icons.Default.Folder
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                null,
                            )
                        },
                        trailingContent = {
                            if (!entry.isDirectory && entry.name != "." && entry.name != "..") {
                                IconButton(onClick = {
                                    pendingDownload = entry.name
                                    saveLauncher.launch(entry.name)
                                }) { Icon(Icons.Default.CloudDownload, "下载") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (entry.isDirectory && entry.name != ".") {
                        TextButton(onClick = {
                            scope.launch {
                                val next = when (entry.name) {
                                    ".." -> path.substringBeforeLast('/', ".")
                                    else -> if (path == "." || path.isBlank()) entry.name else "$path/${entry.name}"
                                }
                                try {
                                    withContext(Dispatchers.IO) { SshSessionManager.listRemote(next) }
                                } catch (e: Exception) {
                                    message = e.message ?: "打开失败"
                                }
                            }
                        }) { Text("打开 ${entry.name}") }
                    }
                }
            }
        }
    }
}
