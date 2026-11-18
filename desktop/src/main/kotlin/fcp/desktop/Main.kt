// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fcp.desktop.ui.AccountsPage
import fcp.desktop.ui.AboutPage
import fcp.desktop.ui.SettingsPage
import fcp.desktop.ui.VersionsPage
import fcp.shared.PRODUCT_NAME

private val Green = Color(0xFF1B7F4E)
private val PageBackground = Color(0xFFF3F7F4)

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    val model = remember { LauncherModel() }
    Window(
        onCloseRequest = ::exitApplication,
        title = PRODUCT_NAME,
        icon = painterResource("fcp-icon.png"),
        state = rememberWindowState(width = 1080.dp, height = 760.dp),
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Green,
                onPrimary = Color.White,
                background = PageBackground,
                surface = Color.White,
            ),
        ) {
            Scaffold(
                containerColor = PageBackground,
                topBar = {
                    TopAppBar(
                        title = { Text(PRODUCT_NAME) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Green,
                            titleContentColor = Color.White,
                        ),
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        NavItem(tr("版本", "Versions"), model.tab == Tab.Versions) { model.tab = Tab.Versions }
                        NavItem(tr("账号", "Accounts"), model.tab == Tab.Accounts) { model.tab = Tab.Accounts }
                        NavItem(tr("设置", "Settings"), model.tab == Tab.Settings) { model.tab = Tab.Settings }
                        NavItem(tr("关于", "About"), model.tab == Tab.About) { model.tab = Tab.About }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (model.tab) {
                        Tab.Versions -> VersionsPage(model)
                        Tab.Accounts -> AccountsPage(model)
                        Tab.Settings -> SettingsPage(model)
                        Tab.About -> AboutPage()
                    }
                }
            }
            Dialogs(model)
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Text(label.take(1)) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = Green,
            indicatorColor = Green,
        ),
    )
}

@Composable
private fun Dialogs(model: LauncherModel) {
    when (val dialog = model.dialog) {
        null -> Unit
        is AppDialog.Message -> AlertDialog(
            onDismissRequest = { model.dismissDialog() },
            title = { Text(dialog.title) },
            text = { Text(dialog.body) },
            confirmButton = {
                TextButton(onClick = { model.dismissDialog() }) { Text(tr("确定", "OK")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    model.dismissDialog()
                    model.showLog()
                }) { Text(tr("查看日志", "View log")) }
            },
        )
        is AppDialog.ConfirmDownload -> AlertDialog(
            onDismissRequest = { model.dismissDialog() },
            title = { Text(tr("下载 ${dialog.version.id}", "Download ${dialog.version.id}")) },
            text = {
                Text(
                    tr(
                        "下载 ${dialog.version.id} 的客户端、依赖库和资源。资源文件可能较大。先使用 BMCLAPI，失败再使用 Mojang 官方源。桌面原生库会按当前系统从版本 JSON 解压。",
                        "Download the client, libraries, and assets for ${dialog.version.id}. Assets can be large. BMCLAPI is tried first, then Mojang. Desktop natives are extracted from the version JSON for this OS.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { model.startDownload(dialog.version) }) { Text(tr("下载", "Download")) }
            },
            dismissButton = {
                TextButton(onClick = { model.dismissDialog() }) { Text(tr("取消", "Cancel")) }
            },
        )
        is AppDialog.ChooseProfile -> AlertDialog(
            onDismissRequest = { model.dismissDialog() },
            title = { Text(tr("选择角色", "Choose a profile")) },
            text = {
                Column {
                    dialog.names.forEachIndexed { index, name ->
                        TextButton(onClick = { model.chooseProfile(index) }) { Text(name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { model.dismissDialog() }) { Text(tr("取消", "Cancel")) }
            },
        )
        is AppDialog.Log -> AlertDialog(
            onDismissRequest = { model.dismissDialog() },
            title = { Text(tr("启动日志", "Launch log")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(dialog.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { model.dismissDialog() }) { Text(tr("确定", "OK")) }
            },
        )
    }
}
