// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fcp.core.auth.AccountStore
import fcp.core.auth.GameProfile
import fcp.core.auth.LittleSkin
import fcp.core.auth.LoginOutcome
import fcp.core.auth.OfflineAccounts
import fcp.core.auth.OfflineCreateResult
import fcp.core.auth.StoredAccount
import fcp.core.auth.YggdrasilClient
import fcp.core.auth.YggdrasilSession
import fcp.core.auth.storedYggdrasil
import fcp.core.download.RemoteVersion
import fcp.core.download.VersionManifest
import fcp.core.download.GameRepository
import fcp.core.launch.GamePaths
import fcp.core.launch.JavaInstallation
import fcp.core.launch.JavaRuntimes
import fcp.core.launch.LaunchLog
import fcp.core.launch.RunningGame
import fcp.core.net.UrlHttpClient
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class Tab { Versions, Accounts, Settings, About }

sealed class AppDialog {
    data class Message(val title: String, val body: String) : AppDialog()
    data class ConfirmDownload(val version: RemoteVersion) : AppDialog()
    data class ChooseProfile(val names: List<String>) : AppDialog()
    data class Log(val text: String) : AppDialog()
}

data class VersionRow(val version: RemoteVersion, val installed: Boolean)

class LauncherModel {
    var tab by mutableStateOf(Tab.Versions)
    var settings by mutableStateOf(DesktopSettings.load())
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)
    var accounts by mutableStateOf<List<StoredAccount>>(emptyList())
    var selectedAccountId by mutableStateOf<String?>(null)
    var detectedJava by mutableStateOf<List<JavaInstallation>>(emptyList())
    var dialog by mutableStateOf<AppDialog?>(null)
    var filter by mutableStateOf("release")
    var query by mutableStateOf("")

    private var manifest by mutableStateOf<VersionManifest?>(null)
    private var installed by mutableStateOf<Set<String>>(emptySet())
    private var pendingSession: YggdrasilSession? = null
    private var pendingPreset: String = "custom"
    private val running = mutableListOf<RunningGame>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val yggdrasil = YggdrasilClient(UrlHttpClient())

    init {
        refreshAccounts()
        refreshInstalled()
        detectJava()
        loadManifest()
    }

    fun visibleVersions(): List<VersionRow> {
        val loaded = manifest
        val have = installed
        if (loaded == null) {
            return have.sorted().map { id ->
                VersionRow(RemoteVersion(id, "local", "", null, null), installed = true)
            }
        }
        val text = query.trim()
        return loaded.versions.filter { version ->
            val typeMatches = when (filter) {
                "snapshot" -> version.type == "snapshot"
                "old" -> version.type == "old_alpha" || version.type == "old_beta"
                else -> version.type == "release"
            }
            typeMatches && (text.isEmpty() || version.id.contains(text, ignoreCase = true))
        }.map { version -> VersionRow(version, installed = version.id in have) }
    }

    fun accountLine(): String {
        val selected = accounts.firstOrNull { it.id == selectedAccountId }
        return if (selected == null) {
            tr("还没有选中账号。请先到「账号」添加离线或 LittleSkin 账号。", "No account selected. Add an offline or LittleSkin account first.")
        } else {
            tr(
                "当前账号：${selected.profileName}（${kindOf(selected)}）",
                "Current account: ${selected.profileName} (${kindOf(selected)})",
            )
        }
    }

    fun kindOf(account: StoredAccount): String = when (account) {
        is StoredAccount.Offline -> tr("离线", "Offline")
        is StoredAccount.Yggdrasil ->
            if (account.preset == LittleSkin.PRESET_ID) "LittleSkin" else tr("第三方", "Third-party")
    }

    fun loadManifest() {
        if (busy) return
        ui { status = tr("正在从 BMCLAPI 获取版本清单…", "Fetching the version list from BMCLAPI…") }
        scope.launch {
            val result = runCatching { GameRepository(mirrors = settings.mirrors()).fetchManifest() }
            ui {
                result.onSuccess { loaded ->
                    manifest = loaded
                    status = tr(
                        "共 ${loaded.versions.size} 个版本。默认镜像 BMCLAPI，失败时回退 Mojang。",
                        "${loaded.versions.size} versions. BMCLAPI first, Mojang if that fails.",
                    )
                    refreshInstalled()
                }.onFailure { error ->
                    status = tr(
                        "版本清单获取失败：${error.message}",
                        "Could not load the version list: ${error.message}",
                    )
                }
            }
        }
    }

    fun requestDownload(version: RemoteVersion) {
        if (busy || version.url.isBlank()) return
        dialog = AppDialog.ConfirmDownload(version)
    }

    fun startDownload(version: RemoteVersion) {
        dialog = null
        if (busy) return
        busy = true
        progress = null
        status = tr("正在下载 ${version.id}…", "Downloading ${version.id}…")
        val current = settings
        scope.launch {
            val result = runCatching {
                installVersion(current, version) { label -> ui { status = label } }
            }
            ui {
                busy = false
                progress = null
                result.onSuccess { report ->
                    refreshInstalled()
                    status = tr(
                        "已安装 ${report.versionId}。客户端、${report.librariesDownloaded} 个依赖库、${report.assetObjectsDownloaded} 个资源文件已写入游戏目录。",
                        "Installed ${report.versionId}. Client, ${report.librariesDownloaded} libraries, and ${report.assetObjectsDownloaded} asset objects are in the game directory.",
                    )
                }.onFailure { error ->
                    status = tr("下载失败：${error.message}", "Download failed: ${error.message}")
                }
            }
        }
    }

    fun launch(version: RemoteVersion) {
        if (busy) return
        if (accounts.none { it.id == selectedAccountId }) {
            dialog = AppDialog.Message(
                tr("无法启动", "Could not launch"),
                tr("请先在「账号」里添加并选中一个账号，然后再启动。", "Add an account and select it before launching."),
            )
            return
        }
        busy = true
        progress = null
        status = tr("正在检查游戏文件、账号和 Java…", "Checking game files, the account, and Java…")
        val current = settings
        scope.launch {
            val outcome = DesktopGameLaunch(current).launch(version.id) { label -> ui { status = label } }
            ui {
                busy = false
                when (outcome) {
                    is DesktopLaunchResult.Started -> {
                        running += outcome.game
                        status = tr("游戏进程已启动", "Game process started")
                        dialog = AppDialog.Message(
                            tr("游戏进程已启动", "Game process started"),
                            tr(
                                "已在独立进程里启动 ${outcome.versionId}（账号 ${outcome.accountName}）。\nJava：${outcome.javaPath}\n日志：${outcome.logFile.path}",
                                "Started ${outcome.versionId} (account ${outcome.accountName}) in its own process.\nJava: ${outcome.javaPath}\nLog: ${outcome.logFile.path}",
                            ),
                        )
                    }
                    is DesktopLaunchResult.Failed -> {
                        status = tr("无法启动", "Could not launch")
                        dialog = AppDialog.Message(tr("无法启动", "Could not launch"), outcome.message)
                    }
                }
            }
        }
    }

    fun showLog() {
        scope.launch {
            val log = GamePaths.logFile(settings.gameDir())
            val text = if (log.isFile) LaunchLog.tail(log) else ""
            ui {
                if (text.isBlank()) {
                    status = tr("还没有启动日志。", "No launch log yet.")
                } else {
                    dialog = AppDialog.Log(text)
                }
            }
        }
    }

    fun addOffline(name: String) {
        when (val result = OfflineAccounts.create(name)) {
            is OfflineCreateResult.Invalid -> status = result.message
            is OfflineCreateResult.Ok -> {
                store().upsert(result.account)
                refreshAccounts()
                status = tr("已保存 ${result.account.profileName}", "Saved ${result.account.profileName}")
            }
        }
    }

    fun loginLittleSkin(username: String, password: String) {
        login(LittleSkin.API_ROOT, username, password, LittleSkin.PRESET_ID, resolveFirst = false)
    }

    fun loginCustom(url: String, username: String, password: String) {
        login(url, username, password, "custom", resolveFirst = true)
    }

    fun chooseProfile(index: Int) {
        val session = pendingSession ?: return
        val profile = session.profiles.getOrNull(index) ?: return
        dialog = null
        pendingSession = null
        saveYggdrasil(pendingPreset, session, profile)
    }

    fun selectAccount(id: String) {
        store().select(id)
        refreshAccounts()
    }

    fun deleteAccount(id: String) {
        store().delete(id)
        refreshAccounts()
    }

    fun saveSettings(next: DesktopSettings) {
        val memory = next.maxMemoryMb.coerceIn(512, 16384)
        val source = if (next.downloadSource == DesktopSettings.SOURCE_MOJANG) {
            DesktopSettings.SOURCE_MOJANG
        } else {
            DesktopSettings.SOURCE_BMCLAPI
        }
        val saved = next.copy(maxMemoryMb = memory, downloadSource = source, gameDirectory = next.gameDirectory.trim())
        if (saved.gameDirectory.isEmpty()) {
            status = tr("请填写游戏目录。", "Enter a game directory.")
            return
        }
        DesktopSettings.save(saved)
        settings = saved
        refreshInstalled()
        detectJava()
        status = tr("设置已保存。", "Settings saved.")
    }

    fun dismissDialog() {
        dialog = null
        pendingSession = null
    }

    private fun login(apiRoot: String, username: String, password: String, preset: String, resolveFirst: Boolean) {
        if (busy) return
        busy = true
        status = tr("正在登录…", "Signing in…")
        scope.launch {
            val result = runCatching {
                if (resolveFirst) yggdrasil.loginResolved(apiRoot, username, password)
                else yggdrasil.login(apiRoot, username, password)
            }
            ui {
                busy = false
                result.onSuccess { outcome ->
                    when (outcome) {
                        is LoginOutcome.Ready -> saveYggdrasil(preset, outcome.session, outcome.profile)
                        is LoginOutcome.ChooseProfile -> {
                            pendingSession = outcome.session
                            pendingPreset = preset
                            dialog = AppDialog.ChooseProfile(outcome.session.profiles.map { it.name })
                        }
                    }
                }.onFailure { error ->
                    status = error.message ?: error.javaClass.simpleName
                }
            }
        }
    }

    private fun saveYggdrasil(preset: String, session: YggdrasilSession, profile: GameProfile) {
        val account = storedYggdrasil(preset, session, profile)
        store().upsert(account)
        refreshAccounts()
        status = tr("已保存 ${account.profileName}", "Saved ${account.profileName}")
    }

    private fun refreshAccounts() {
        val file = store().load()
        accounts = file.accounts
        selectedAccountId = file.selectedId
    }

    private fun refreshInstalled() {
        installed = GamePaths.installedVersionIds(settings.gameDir())
    }

    private fun detectJava() {
        val extra = settings.javaPathFile()
        scope.launch {
            val found = runCatching { JavaRuntimes.detect(listOfNotNull(extra)) }.getOrDefault(emptyList())
            ui { detectedJava = found }
        }
    }

    private fun store(): AccountStore = AccountStore(DesktopSettings.accountsFile())

    private fun ui(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}
