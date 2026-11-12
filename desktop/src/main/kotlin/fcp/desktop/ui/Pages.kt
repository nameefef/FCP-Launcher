// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fcp.core.auth.LittleSkin
import fcp.core.auth.StoredAccount
import fcp.desktop.DesktopSettings
import fcp.desktop.LauncherModel
import fcp.desktop.tr
import fcp.shared.PRODUCT_FULL_NAME
import fcp.shared.PRODUCT_NAME
import java.io.File
import javax.swing.JFileChooser

@Composable
fun VersionsPage(model: LauncherModel) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = model.query,
            onValueChange = { model.query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(tr("搜索版本，例如 1.7.10", "Search, for example 1.7.10")) },
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterButton(tr("正式版", "Releases"), model.filter == "release") { model.filter = "release" }
            FilterButton(tr("快照", "Snapshots"), model.filter == "snapshot") { model.filter = "snapshot" }
            FilterButton(tr("远古版", "Ancient"), model.filter == "old") { model.filter = "old" }
        }
        if (model.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Text(model.status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Text(model.accountLine(), style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = { model.loadManifest() }, enabled = !model.busy) {
                Text(tr("刷新清单", "Reload list"))
            }
            TextButton(onClick = { model.showLog() }) { Text(tr("启动日志", "Launch log")) }
        }
        val rows = model.visibleVersions()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.version.id + ":" + it.version.type }) { row ->
                VersionItem(model, row)
            }
        }
    }
}

@Composable
private fun VersionItem(model: LauncherModel, row: fcp.desktop.VersionRow) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.version.id, style = MaterialTheme.typography.titleMedium)
            val installedLabel = if (row.installed && row.version.type != "local") tr("已安装", "Installed") + " · " else ""
            val typeLabel = if (row.version.type == "local") tr("已安装", "Installed") else versionTypeLabel(row.version.type)
            Text(installedLabel + typeLabel, style = MaterialTheme.typography.bodySmall)
        }
        if (row.installed) {
            Button(
                onClick = { model.launch(row.version) },
                enabled = !model.busy,
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(tr("启动", "Launch")) }
        }
        if (row.version.url.isNotBlank()) {
            TextButton(
                onClick = { model.requestDownload(row.version) },
                enabled = !model.busy,
            ) { Text(tr("下载", "Download")) }
        }
    }
}

@Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

private fun versionTypeLabel(type: String): String = when (type) {
    "release" -> tr("正式版", "Releases")
    "snapshot" -> tr("快照", "Snapshots")
    "old_alpha", "old_beta" -> tr("远古版", "Ancient")
    "local" -> tr("已安装", "Installed")
    else -> type
}

@Composable
fun AccountsPage(model: LauncherModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            if (model.accounts.isEmpty()) tr("还没有账号", "No accounts yet") else model.accountLine(),
            style = MaterialTheme.typography.bodyLarge,
        )
        model.accounts.forEach { account ->
            AccountRow(model, account)
        }
        Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("离线登录", "Offline login"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr(
                        "只在本机保存角色名，不连接验证服务器。与 HMCL / Fold Craft Launcher 的离线账号相同。",
                        "Stores a player name on this computer only. Same model as HMCL / Fold Craft Launcher offline accounts.",
                    ),
                    Modifier.padding(top = 4.dp),
                )
                var name by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text(tr("角色名", "Player name")) },
                )
                Button(
                    onClick = {
                        model.addOffline(name)
                        name = ""
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    enabled = !model.busy,
                ) { Text(tr("添加离线账号", "Add offline account")) }
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("LittleSkin 登录", "LittleSkin login"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr(
                        "LittleSkin 是第三方皮肤站，不是 Microsoft / Mojang 正版登录。API 地址按用户手册预填。",
                        "LittleSkin is a third-party skin site, not Microsoft / Mojang login. The API root is the one from their manual.",
                    ),
                    Modifier.padding(top = 4.dp),
                )
                Text(LittleSkin.API_ROOT, Modifier.padding(top = 8.dp))
                var user by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text(tr("邮箱或角色名", "Email or player name")) },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(tr("密码", "Password")) },
                )
                Button(
                    onClick = {
                        val secret = password
                        password = ""
                        model.loginLittleSkin(user, secret)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    enabled = !model.busy,
                ) { Text(tr("登录 LittleSkin", "Sign in to LittleSkin")) }
            }
        }
        var advanced by remember { mutableStateOf(false) }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(top = 8.dp)) {
            Text(tr("高级：自定义 Yggdrasil 服务器", "Advanced: custom Yggdrasil server"))
        }
        if (advanced) {
            Text(
                tr(
                    "可填写 authlib-injector 地址。密码只发给这个服务器。支持 API Location Indication（例如直接填 littleskin.cn）。这仍然不是官方 Microsoft 登录。",
                    "Paste an authlib-injector address. The password is sent only to that server. API Location Indication is followed. This is still not official Microsoft login.",
                ),
            )
            var url by remember { mutableStateOf("") }
            var user by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text("https://example.com/api/yggdrasil") },
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text(tr("邮箱或角色名", "Email or player name")) },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(tr("密码", "Password")) },
            )
            OutlinedButton(
                onClick = {
                    val secret = password
                    password = ""
                    model.loginCustom(url, user, secret)
                },
                modifier = Modifier.padding(top = 8.dp),
                enabled = !model.busy,
            ) { Text(tr("登录该服务器", "Sign in to this server")) }
        }
        if (model.status.isNotBlank() && model.tab.name == "Accounts") {
            Text(model.status, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun AccountRow(model: LauncherModel, account: StoredAccount) {
    val current = account.id == model.selectedAccountId
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(account.profileName, style = MaterialTheme.typography.titleMedium)
            val prefix = if (current) tr("当前", "Current") + " · " else ""
            Text(prefix + model.kindOf(account), style = MaterialTheme.typography.bodySmall)
        }
        if (!current) {
            TextButton(onClick = { model.selectAccount(account.id) }) { Text(tr("使用", "Use")) }
        }
        TextButton(onClick = { model.deleteAccount(account.id) }) { Text(tr("删除", "Delete")) }
    }
}

@Composable
fun SettingsPage(model: LauncherModel) {
    var gameDir by remember(model.settings) { mutableStateOf(model.settings.gameDirectory) }
    var memory by remember(model.settings) { mutableStateOf(model.settings.maxMemoryMb.toString()) }
    var javaPath by remember(model.settings) { mutableStateOf(model.settings.javaPath) }
    var source by remember(model.settings) { mutableStateOf(model.settings.downloadSource) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(tr("游戏目录", "Game directory"), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = gameDir,
                onValueChange = { gameDir = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    chooseDirectory(gameDir)?.let { gameDir = it }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(tr("浏览", "Browse")) }
        }
        Text(tr("内存（-Xmx，MB）", "Memory (-Xmx, MB)"), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = memory,
            onValueChange = { memory = it.filter { ch -> ch.isDigit() }.take(5) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            tr("Java 路径（可选）", "Java path (optional)"),
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            tr(
                "留空则自动检测，并按版本要求的 Java 选择（1.16 及更早为 8，1.18–1.20.4 为 17，更新的版本为 21）。填写后，这个路径会和已安装的 Java 一起参与匹配。",
                "Leave this empty to auto-detect and pick by the version's Java (8 through 1.16, 17 for 1.18–1.20.4, 21 for newer). A path here is included in that match.",
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = javaPath,
                onValueChange = { javaPath = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                onClick = { chooseFile(javaPath)?.let { javaPath = it } },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(tr("浏览", "Browse")) }
        }
        Text(tr("已检测到的 Java", "Detected Java"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        if (model.detectedJava.isEmpty()) {
            Text(tr("没有检测到已安装的 Java。", "No installed Java was detected."))
        } else {
            model.detectedJava.forEach { java ->
                Text("Java ${java.major}  ${java.javaBinary.path}")
            }
        }
        Text(tr("下载源", "Download source"), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = source == DesktopSettings.SOURCE_BMCLAPI, onClick = { source = DesktopSettings.SOURCE_BMCLAPI })
            Text(tr("BMCLAPI（优先，失败回退 Mojang）", "BMCLAPI first, then Mojang"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = source == DesktopSettings.SOURCE_MOJANG, onClick = { source = DesktopSettings.SOURCE_MOJANG })
            Text(tr("仅 Mojang 官方", "Mojang only"))
        }
        Button(
            onClick = {
                model.saveSettings(
                    DesktopSettings(
                        gameDirectory = gameDir,
                        maxMemoryMb = memory.toIntOrNull() ?: model.settings.maxMemoryMb,
                        javaPath = javaPath,
                        downloadSource = source,
                    ),
                )
            },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(tr("保存", "Save")) }
        if (model.status.isNotBlank()) {
            Text(model.status, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun AboutPage() {
    var taps by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            PRODUCT_NAME,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.clickable { taps += 1 },
        )
        Text(tr("版本 ${DesktopSettings.VERSION}", "Version ${DesktopSettings.VERSION}"), Modifier.padding(top = 4.dp))
        if (taps >= 7) {
            Text(PRODUCT_FULL_NAME, color = MaterialTheme.colorScheme.primary)
        }
        Section(
            tr("这个版本", "This version"),
            tr(
                "在 Windows、macOS 和 Linux 上管理 Minecraft Java 版账号，从 BMCLAPI 下载历代版本，并用本机 Java 启动游戏。",
                "Manage Minecraft Java accounts on Windows, macOS, and Linux, download versions from BMCLAPI, and launch the game with a local Java.",
            ),
        )
        Section(
            tr("下载源", "Download source"),
            tr(
                "游戏文件默认使用 BMCLAPI（https://bmclapi2.bangbang93.com），由 bangbang93 提供。这是 HMCL 与 Fold Craft Launcher 长期使用的社区镜像。某个文件失败时，会改用 Mojang 官方源。本页保留 BMCLAPI 署名。",
                "Game files use BMCLAPI (https://bmclapi2.bangbang93.com), operated by bangbang93, the mirror HMCL and Fold Craft Launcher have used for a long time. A failed file falls back to Mojang. This screen keeps the BMCLAPI credit.",
            ),
        )
        Section(
            tr("账号", "Accounts"),
            tr(
                "支持离线账号和 LittleSkin（Yggdrasil / authlib-injector）。LittleSkin 启动会附带 authlib-injector。高级选项可以填写其他 Yggdrasil 服务器。官方 Microsoft 登录不在此版本中：没有 Mojang Client ID 白名单，也不使用其他启动器的 Azure 应用。",
                "Offline accounts and LittleSkin (Yggdrasil / authlib-injector) are supported. LittleSkin launches with authlib-injector. Advanced mode accepts another Yggdrasil server. Official Microsoft login is not in this version: there is no Mojang client-id whitelist, and other launchers' Azure applications are not used.",
            ),
        )
        Section(
            tr("启动", "Launch"),
            tr(
                "游戏是单独的进程，使用系统里的 Java，或设置中填写的 Java 路径。会按版本 JSON 的 javaVersion 选择：1.16 及更早为 Java 8，1.17 为 16 或 17，1.18 到 1.20.4 为 Java 17，更新的版本为 Java 21。找不到时会说明原因。原生库来自版本 JSON 的 LWJGL 分类器（按操作系统），不使用 Android 端的 Fold Craft Launcher 库。",
                "The game is a separate process, using system Java or the path in Settings. Java is chosen from the version JSON: Java 8 through 1.16, 16 or 17 for 1.17, Java 17 for 1.18–1.20.4, and Java 21 after that. A missing runtime is reported. Natives come from the version JSON's LWJGL classifiers for this OS, not the Android Fold Craft Launcher libraries.",
            ),
        )
        Section(
            tr("许可与上游", "License and upstream"),
            tr(
                "FCP Launcher 以 GNU GPL-3.0 发布。下载、账号和启动参数对齐 HMCL 与 Fold Craft Launcher（均为 GPL-3.0）。不包含 Plain Craft Launcher 源码。Minecraft 是 Mojang Synergies AB 的商标。本软件与 Mojang、Microsoft、LittleSkin、BMCLAPI 均无隶属关系。",
                "FCP Launcher is released under GNU GPL-3.0. Downloads, accounts, and launch arguments follow HMCL and Fold Craft Launcher (both GPL-3.0). Plain Craft Launcher source is not included. Minecraft is a trademark of Mojang Synergies AB. This project is not affiliated with Mojang, Microsoft, LittleSkin, or BMCLAPI.",
            ),
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text(body, Modifier.padding(top = 4.dp))
}

private fun chooseDirectory(current: String): String? {
    val chooser = JFileChooser(File(current).takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = tr("选择游戏目录", "Choose the game directory")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}

private fun chooseFile(current: String): String? {
    val start = File(current).let { file ->
        when {
            file.isFile -> file.parentFile
            file.isDirectory -> file
            else -> File(System.getProperty("user.home"))
        }
    }
    val chooser = JFileChooser(start).apply {
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        dialogTitle = tr("选择 Java", "Choose Java")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}
