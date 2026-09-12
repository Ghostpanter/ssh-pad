package com.sshtab.pad.ui

import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.sshtab.pad.service.KeepAliveOem
import com.sshtab.pad.service.SessionClient
import com.sshtab.pad.ssh.AuthMethod
import com.sshtab.pad.ssh.FileEntry
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.SessionInfo
import com.sshtab.pad.ssh.TransportKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SshPadAppUi() {
    var tab by remember { mutableIntStateOf(0) }
    val sessions by SessionClient.sessions.collectAsState()
    val activeId by SessionClient.activeId.collectAsState()
    val status by SessionClient.status.collectAsState()
    val kind by SessionClient.kind.collectAsState()
    val connected by SessionClient.connected.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val tablet = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (tablet) {
                LeftPanel(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight(),
                    sessions = sessions,
                    activeId = activeId,
                    tab = tab,
                    onTab = { tab = it },
                    filesEnabled = kind == TransportKind.SSH && connected,
                )
            } else {
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
                    NavigationRailItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Description, null) },
                        label = { Text("日志") },
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!tablet) {
                    SessionChipRow(sessions, activeId)
                }
                when (tab) {
                    0 -> TerminalPane(Modifier.fillMaxSize(), sessions.isNotEmpty(), status, showForm = !tablet)
                    1 -> XftpPane(Modifier.fillMaxSize(), connected && kind == TransportKind.SSH)
                    else -> LogPane(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun LeftPanel(
    modifier: Modifier,
    sessions: List<SessionInfo>,
    activeId: String?,
    tab: Int,
    onTab: (Int) -> Unit,
    filesEnabled: Boolean,
) {
    Surface(modifier, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("SSH Pad", style = MaterialTheme.typography.titleLarge)
            Text("多会话 · 终端占满右侧", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NavChip("终端", tab == 0) { onTab(0) }
                NavChip("文件", tab == 1, enabled = filesEnabled) { onTab(1) }
                NavChip("日志", tab == 2) { onTab(2) }
            }
            Spacer(Modifier.height(12.dp))
            Text("会话", style = MaterialTheme.typography.labelLarge)
            if (sessions.isEmpty()) {
                Text("尚未连接", style = MaterialTheme.typography.bodySmall)
            } else {
                sessions.forEach { s ->
                    val selected = s.id == activeId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable { SessionClient.switchSession(s.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                s.status,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { SessionClient.closeSession(s.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("新建连接", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            ConnectForm()
        }
    }
}

@Composable
private fun NavChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}

@Composable
private fun SessionChipRow(sessions: List<SessionInfo>, activeId: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sessions.forEach { s ->
            FilterChip(
                selected = s.id == activeId,
                onClick = { SessionClient.switchSession(s.id) },
                label = { Text(s.title, maxLines = 1) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        "关闭",
                        modifier = Modifier.size(14.dp).clickable { SessionClient.closeSession(s.id) },
                    )
                },
            )
        }
    }
}

@Composable
private fun ConnectForm() {
    val ctx = LocalContext.current
    val formPrefs = remember { ctx.getSharedPreferences("form", 0) }
    var kind by remember {
        mutableStateOf(
            runCatching { TransportKind.valueOf(formPrefs.getString("kind", "SSH")!!) }
                .getOrDefault(TransportKind.SSH),
        )
    }
    var auth by remember {
        mutableStateOf(
            runCatching { AuthMethod.valueOf(formPrefs.getString("auth", "PASSWORD")!!) }
                .getOrDefault(AuthMethod.PASSWORD),
        )
    }
    var host by remember { mutableStateOf(formPrefs.getString("host", "") ?: "") }
    var port by remember { mutableStateOf(formPrefs.getString("port", "22") ?: "22") }
    var user by remember { mutableStateOf(formPrefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var keyPem by remember { mutableStateOf("") }
    var keyName by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var showPhrase by remember { mutableStateOf(false) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                keyPem = input.readBytes().toString(Charsets.UTF_8)
            }
            keyName = uri.lastPathSegment?.substringAfterLast('/') ?: "id_key"
            auth = AuthMethod.KEY
        } catch (e: Exception) {
            SessionClient.fail("读取密钥失败: ${e.message}")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        OutlinedTextField(host, { host = it }, label = { Text("主机") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (kind == TransportKind.SSH) {
            OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = auth == AuthMethod.PASSWORD,
                    onClick = { auth = AuthMethod.PASSWORD },
                    label = { Text("密码") },
                )
                FilterChip(
                    selected = auth == AuthMethod.KEY,
                    onClick = { auth = AuthMethod.KEY },
                    label = { Text("密钥") },
                    leadingIcon = { Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(16.dp)) },
                )
            }
            if (auth == AuthMethod.PASSWORD) {
                OutlinedTextField(
                    pass,
                    { pass = it },
                    label = { Text("密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示密码")
                        }
                    },
                )
            } else {
                TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                    Text(if (keyName.isBlank()) "选择私钥文件" else "密钥: $keyName")
                }
                OutlinedTextField(
                    passphrase,
                    { passphrase = it },
                    label = { Text("密钥口令（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPhrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPhrase = !showPhrase }) {
                            Icon(if (showPhrase) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                )
                Text("也可再填密码作备用认证", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pass,
                    { pass = it },
                    label = { Text("备用密码（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                )
            }
        } else {
            Text("Telnet 在终端里交互登录。", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                val profile = HostProfile(
                    name = user.trim().ifBlank { host.trim() },
                    host = host.trim(),
                    port = port.toIntOrNull() ?: if (kind == TransportKind.TELNET) 23 else 22,
                    username = user.trim(),
                    password = pass,
                    kind = kind,
                    auth = auth,
                    privateKey = keyPem,
                    passphrase = passphrase,
                )
                formPrefs.edit()
                    .putString("host", profile.host)
                    .putString("port", profile.port.toString())
                    .putString("user", profile.username)
                    .putString("kind", kind.name)
                    .putString("auth", auth.name)
                    .apply()
                when {
                    profile.host.isBlank() -> SessionClient.fail("请填写主机")
                    kind == TransportKind.SSH && profile.username.isBlank() -> SessionClient.fail("请填写用户名")
                    kind == TransportKind.SSH && auth == AuthMethod.PASSWORD && profile.password.isBlank() ->
                        SessionClient.fail("请填写密码")
                    kind == TransportKind.SSH && auth == AuthMethod.KEY && profile.privateKey.isBlank() ->
                        SessionClient.fail("请选择私钥")
                    else -> {
                        KeepAliveOem.requestOverlay(ctx)
                        SessionClient.connect(ctx, profile)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("连接 / 新开会话")
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            TextButton(onClick = { KeepAliveOem.requestOverlay(ctx) }) {
                Text("未开悬浮窗，切走易断", color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = { KeepAliveOem.openVendorKeepAlive(ctx) }) { Text("国行保活设置") }
        if (SessionClient.lastDisconnectReason.isNotBlank()) {
            Text(
                SessionClient.lastDisconnectReason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TerminalPane(modifier: Modifier, hasSession: Boolean, status: String, showForm: Boolean) {
    Column(modifier) {
        if (!hasSession) {
            if (showForm) {
                Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    ConnectForm()
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("在左侧填写主机后点「连接 / 新开会话」\n可同时开多个窗口", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Card(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
                XtermView(Modifier.fillMaxSize())
            }
            ExtraKeysBar()
        }
    }
}

@Composable
private fun ExtraKeysBar() {
    val keys = listOf(
        "Esc" to "\u001b",
        "Tab" to "\t",
        "Ctrl-C" to "\u0003",
        "Ctrl-D" to "\u0004",
        "Ctrl-Z" to "\u001a",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "Home" to "\u001b[H",
        "End" to "\u001b[F",
        "~" to "~",
        "/" to "/",
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
                    onClick = { SessionClient.writeUtf8(seq) },
                    modifier = Modifier.height(36.dp),
                ) { Text(label, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun XftpPane(modifier: Modifier, connected: Boolean) {
    val ctx = LocalContext.current
    val files by SessionClient.files.collectAsState()
    val remotePath by SessionClient.remotePath.collectAsState()
    val kind by SessionClient.kind.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    val prefs = remember { ctx.getSharedPreferences("xftp", 0) }
    var treeUri by remember { mutableStateOf(prefs.getString("tree", null)?.let(Uri::parse)) }
    var localRel by remember { mutableStateOf("") }
    var localSel by remember { mutableStateOf<String?>(null) }
    var remoteSel by remember { mutableStateOf<FileEntry?>(null) }
    var mkdirName by remember { mutableStateOf("") }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Throwable) {
        }
        prefs.edit().putString("tree", uri.toString()).apply()
        treeUri = uri
        localRel = ""
    }

    fun localRoot(): DocumentFile? = treeUri?.let { DocumentFile.fromTreeUri(ctx, it) }
    fun localDir(): DocumentFile? {
        var cur = localRoot() ?: return null
        if (localRel.isNotBlank()) {
            localRel.split('/').filter { it.isNotBlank() }.forEach { name ->
                cur = cur.findFile(name) ?: return cur
            }
        }
        return cur
    }
    val localEntries = remember(treeUri, localRel, message) {
        val dir = localDir()
        dir?.listFiles()?.map {
            FileEntry(it.name ?: "?", it.isDirectory, it.length())
        }?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()
    }

    fun refreshRemote() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SessionClient.listRemote(remotePath) }
            } catch (e: Exception) {
                message = e.message ?: "刷新失败"
            }
        }
    }

    LaunchedEffect(connected) {
        if (connected) refreshRemote()
    }

    Column(modifier.padding(8.dp)) {
        if (kind == TransportKind.TELNET) {
            Text("Telnet 没有 SFTP，请用 SSH 连接。")
            return
        }
        if (!connected) {
            Text("先在左侧连接 SSH。")
            return
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本地", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { treePicker.launch(null) }) { Icon(Icons.Default.FolderOpen, "选择本地目录") }
                    }
                    Text(
                        (localDir()?.name ?: "未选择目录") + if (localRel.isNotBlank()) "/$localRel" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (treeUri == null) {
                        TextButton(onClick = { treePicker.launch(null) }) { Text("选择本地文件夹（SAF）") }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            if (localRel.isNotBlank()) {
                                item {
                                    ListRow("..", true, 0, selected = false) {
                                        localRel = localRel.substringBeforeLast('/', "")
                                        localSel = null
                                    }
                                }
                            }
                            items(localEntries, key = { "L${it.name}" }) { e ->
                                ListRow(e.name, e.isDirectory, e.size, localSel == e.name) {
                                    if (e.isDirectory) {
                                        localRel = if (localRel.isBlank()) e.name else "$localRel/${e.name}"
                                        localSel = null
                                    } else localSel = e.name
                                }
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(
                    onClick = {
                        val name = localSel ?: return@IconButton
                        val dir = localDir() ?: return@IconButton
                        val doc = dir.findFile(name) ?: return@IconButton
                        scope.launch {
                            try {
                                val tmp = File(ctx.cacheDir, name)
                                withContext(Dispatchers.IO) {
                                    ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                                        tmp.outputStream().use { input.copyTo(it) }
                                    }
                                    SessionClient.upload(tmp.toPath(), name)
                                }
                                message = "已上传 $name"
                                localSel = null
                            } catch (e: Exception) {
                                message = "上传失败: ${e.message}"
                            }
                        }
                    },
                    enabled = localSel != null,
                ) { Icon(Icons.Default.KeyboardArrowUp, "上传到远程") }
                Text("上传", fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                IconButton(
                    onClick = {
                        val entry = remoteSel ?: return@IconButton
                        if (entry.isDirectory) return@IconButton
                        val dir = localDir()
                        scope.launch {
                            try {
                                val tmp = File(ctx.cacheDir, entry.name)
                                withContext(Dispatchers.IO) {
                                    SessionClient.download(entry.name, tmp.toPath())
                                    if (dir != null) {
                                        val existing = dir.findFile(entry.name)
                                        val dest = existing ?: dir.createFile("application/octet-stream", entry.name)
                                        dest?.uri?.let { uri ->
                                            ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                                tmp.inputStream().use { it.copyTo(out) }
                                            }
                                        }
                                    }
                                }
                                message = "已下载 ${entry.name}"
                                remoteSel = null
                            } catch (e: Exception) {
                                message = "下载失败: ${e.message}"
                            }
                        }
                    },
                    enabled = remoteSel != null && remoteSel?.isDirectory == false && treeUri != null,
                ) { Icon(Icons.Default.KeyboardArrowDown, "下载到本地") }
                Text("下载", fontSize = 11.sp)
            }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("远程", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { refreshRemote() }) { Icon(Icons.Default.Refresh, "刷新") }
                        IconButton(onClick = {
                            if (mkdirName.isNotBlank()) {
                                SessionClient.mkdir(mkdirName)
                                mkdirName = ""
                            }
                        }) { Icon(Icons.Default.CreateNewFolder, "新建目录") }
                        IconButton(
                            onClick = {
                                val e = remoteSel ?: return@IconButton
                                SessionClient.deleteRemote(e.name, e.isDirectory)
                                remoteSel = null
                            },
                            enabled = remoteSel != null && remoteSel?.name != "." && remoteSel?.name != "..",
                        ) { Icon(Icons.Default.Delete, "删除") }
                    }
                    Text(remotePath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(
                        mkdirName,
                        { mkdirName = it },
                        label = { Text("新目录名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(files, key = { "R${it.name}" }) { e ->
                            ListRow(e.name, e.isDirectory, e.size, remoteSel?.name == e.name) {
                                if (e.isDirectory) {
                                    val next = when (e.name) {
                                        ".." -> remotePath.substringBeforeLast('/', ".")
                                        "." -> remotePath
                                        else -> if (remotePath == "." || remotePath.isBlank()) e.name else "$remotePath/${e.name}"
                                    }
                                    remoteSel = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { SessionClient.listRemote(next) }
                                        } catch (ex: Exception) {
                                            message = ex.message ?: "打开失败"
                                        }
                                    }
                                } else remoteSel = e
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(name: String, isDir: Boolean, size: Long, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (!isDir) Text("$size 字节", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogPane(modifier: Modifier) {
    val ctx = LocalContext.current
    val log by SessionClient.log.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { SessionClient.refreshLog() }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(SessionClient.log.value.toByteArray())
                    }
                }
                message = "已保存"
            } catch (e: Exception) {
                message = "保存失败: ${e.message}"
            }
        }
    }
    Column(modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                saveLauncher.launch("ssh-pad-${System.currentTimeMillis()}.log")
            }) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(6.dp))
                Text("保存到本地")
            }
            TextButton(onClick = { SessionClient.refreshLog() }) { Text("刷新") }
            FilledTonalButton(onClick = {
                SessionClient.clearLog()
                message = "已清空"
            }) {
                Icon(Icons.Default.DeleteSweep, null)
                Spacer(Modifier.width(6.dp))
                Text("清理日志")
            }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Text(
                    log.ifBlank { "暂无日志" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(scroll),
                )
            }
        }
    }
}
