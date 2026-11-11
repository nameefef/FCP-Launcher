// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fcp.core.auth.AccountStore
import fcp.core.download.GameRepository
import fcp.core.download.InstallOptions
import fcp.core.download.RemoteVersion
import fcp.core.download.VersionManifest
import fcp.core.launch.GamePaths
import fcp.core.launch.LaunchLog
import fcp.launcher.R
import fcp.launcher.databinding.FragmentVersionsBinding
import fcp.launcher.databinding.ItemVersionBinding
import fcp.launcher.launch.AndroidGameLaunch
import fcp.launcher.launch.LaunchOutcome
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VersionsFragment : Fragment() {
    private var binding: FragmentVersionsBinding? = null
    private var manifest: VersionManifest? = null
    private var busy = false
    private val repository = GameRepository()
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val adapter = VersionAdapter(
        onDownload = { version -> confirmDownload(version) },
        onLaunch = { version -> startLaunch(version) },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val views = FragmentVersionsBinding.inflate(inflater, container, false)
        binding = views
        views.versions.layoutManager = LinearLayoutManager(requireContext())
        views.versions.adapter = adapter
        views.filters.check(R.id.filter_releases)
        views.filters.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) showVersions()
        }
        views.search.doOnTextChanged { _, _, _, _ -> showVersions() }
        views.reload.setOnClickListener { loadManifest() }
        views.launchLog.setOnClickListener { showLog() }
        return views.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshAccount()
        loadManifest()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshAccount()
            showVersions()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun gameDir(): File = File(requireContext().filesDir, "minecraft")

    private fun refreshAccount() {
        val views = binding ?: return
        val file = AccountStore(File(requireContext().filesDir, "accounts.json")).load()
        val selected = file.accounts.firstOrNull { it.id == file.selectedId }
        views.accountLine.text = if (selected == null) {
            getString(R.string.account_line_empty)
        } else {
            getString(R.string.account_line, selected.profileName, selected.kindLabel)
        }
    }

    private fun loadManifest() {
        val views = binding ?: return
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = true
        views.status.setText(R.string.loading_manifest)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.fetchManifest() }
            }
            val current = binding ?: return@launch
            current.progress.visibility = View.GONE
            result.onSuccess { loaded ->
                manifest = loaded
                current.status.text = getString(R.string.manifest_ready, loaded.versions.size)
                showVersions()
            }.onFailure { error ->
                current.status.text = getString(R.string.manifest_failed, error.message ?: error.javaClass.simpleName)
                showVersions()
            }
        }
    }

    private fun showVersions() {
        val views = binding ?: return
        val loaded = manifest
        val installed = GamePaths.installedVersionIds(gameDir())
        if (loaded == null) {
            adapter.submit(installed.sorted().map { id ->
                VersionRow(RemoteVersion(id, "local", "", null, null), installed = true)
            })
            return
        }
        val query = views.search.text?.toString()?.trim().orEmpty()
        val type = when (views.filters.checkedButtonId) {
            R.id.filter_snapshots -> "snapshot"
            R.id.filter_old -> "old"
            else -> "release"
        }
        val shown = loaded.versions.filter { version ->
            val typeMatches = when (type) {
                "snapshot" -> version.type == "snapshot"
                "old" -> version.type == "old_alpha" || version.type == "old_beta"
                else -> version.type == "release"
            }
            typeMatches && (query.isEmpty() || version.id.contains(query, ignoreCase = true))
        }.map { version -> VersionRow(version, installed = version.id in installed) }
        adapter.submit(shown)
    }

    private fun confirmDownload(version: RemoteVersion) {
        if (busy || version.url.isBlank()) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.download_confirm, version.id))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download) { _, _ -> startDownload(version) }
            .show()
    }

    private fun startDownload(version: RemoteVersion) {
        val views = binding ?: return
        busy = true
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = true
        val gameDir = gameDir()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        repository.install(version, gameDir, InstallOptions()) { event ->
                            activity?.runOnUiThread {
                                val current = binding ?: return@runOnUiThread
                                current.status.text = event.label
                                if (event.total > 0) {
                                    current.progress.isIndeterminate = false
                                    current.progress.max = 1000
                                    current.progress.progress =
                                        ((event.completed * 1000) / event.total).toInt().coerceIn(0, 1000)
                                }
                            }
                        }
                    }
                }
                val current = binding ?: return@launch
                result.onSuccess { report ->
                    current.status.text = getString(
                        R.string.download_done,
                        report.versionId,
                        report.librariesDownloaded,
                        report.assetObjectsDownloaded,
                    )
                    showVersions()
                }.onFailure { error ->
                    current.status.text = getString(R.string.download_failed, error.message ?: error.javaClass.simpleName)
                }
            } finally {
                busy = false
                binding?.progress?.visibility = View.GONE
            }
        }
    }

    private fun startLaunch(version: RemoteVersion) {
        if (busy || binding == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val views = binding ?: return
        busy = true
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = true
        views.status.setText(R.string.launch_checking)
        val launcher = AndroidGameLaunch(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    launcher.launch(version.id) { status ->
                        activity?.runOnUiThread { binding?.status?.text = status }
                    }
                }
                binding?.status?.text = when (outcome) {
                    is LaunchOutcome.Running -> getString(R.string.launch_running_title)
                    is LaunchOutcome.Exited -> getString(R.string.launch_exited_title)
                    is LaunchOutcome.Failed -> getString(R.string.launch_failed_title)
                }
                if (binding != null) showOutcome(outcome)
            } finally {
                busy = false
                binding?.progress?.visibility = View.GONE
            }
        }
    }

    private fun showOutcome(outcome: LaunchOutcome) {
        val title: Int
        val message: String
        when (outcome) {
            is LaunchOutcome.Running -> {
                title = R.string.launch_running_title
                message = getString(R.string.launch_running_body, outcome.versionId, outcome.accountName)
            }
            is LaunchOutcome.Exited -> {
                title = R.string.launch_exited_title
                message = getString(R.string.launch_exited_body, outcome.versionId, outcome.exitCode, outcome.logTail)
            }
            is LaunchOutcome.Failed -> {
                title = R.string.launch_failed_title
                message = outcome.message
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.view_log) { _, _ -> showLog() }
            .show()
    }

    private fun showLog() {
        val log = GamePaths.logFile(gameDir())
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                if (!log.isFile) null else LaunchLog.tail(log)
            }
            if (binding == null) return@launch
            if (text.isNullOrBlank()) {
                binding?.status?.setText(R.string.no_log)
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.view_log)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}

private data class VersionRow(val version: RemoteVersion, val installed: Boolean)

private class VersionAdapter(
    private val onDownload: (RemoteVersion) -> Unit,
    private val onLaunch: (RemoteVersion) -> Unit,
) : RecyclerView.Adapter<VersionAdapter.Holder>() {
    private var items: List<VersionRow> = emptyList()

    fun submit(next: List<VersionRow>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVersionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.id.text = item.version.id
        val typeLabel = versionTypeLabel(context, item.version.type)
        holder.type.text = if (item.installed && item.version.type != "local") {
            context.getString(R.string.installed) + " · " + typeLabel
        } else if (item.installed) {
            context.getString(R.string.installed)
        } else {
            typeLabel
        }
        holder.launch.visibility = if (item.installed) View.VISIBLE else View.GONE
        holder.download.visibility = if (item.version.url.isBlank()) View.GONE else View.VISIBLE
        holder.launch.setOnClickListener { onLaunch(item.version) }
        holder.download.setOnClickListener { onDownload(item.version) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(binding: ItemVersionBinding) : RecyclerView.ViewHolder(binding.root) {
        val id: TextView = binding.versionId
        val type: TextView = binding.versionType
        val launch = binding.launch
        val download = binding.download
    }
}

/** Mojang manifest `type` values, shown with the same labels as the version filters. */
private fun versionTypeLabel(context: Context, type: String): String {
    val res = when (type) {
        "release" -> R.string.filter_releases
        "snapshot" -> R.string.filter_snapshots
        "old_alpha", "old_beta" -> R.string.filter_old
        else -> return type
    }
    return context.getString(res)
}
