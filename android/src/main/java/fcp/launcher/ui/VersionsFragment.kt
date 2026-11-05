// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fcp.core.download.GameRepository
import fcp.core.download.InstallOptions
import fcp.core.download.RemoteVersion
import fcp.core.download.VersionManifest
import fcp.launcher.R
import fcp.launcher.databinding.FragmentVersionsBinding
import fcp.launcher.databinding.ItemVersionBinding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VersionsFragment : Fragment() {
    private var binding: FragmentVersionsBinding? = null
    private var manifest: VersionManifest? = null
    private var busy = false
    private val repository = GameRepository()
    private val adapter = VersionAdapter { version -> confirmDownload(version) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val views = FragmentVersionsBinding.inflate(inflater, container, false)
        binding = views
        views.versions.layoutManager = LinearLayoutManager(requireContext())
        views.versions.adapter = adapter
        views.filters.check(R.id.filter_releases)
        views.filters.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) applyFilter()
        }
        views.search.doOnTextChanged { _, _, _, _ -> applyFilter() }
        views.reload.setOnClickListener { loadManifest() }
        return views.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadManifest()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
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
                applyFilter()
            }.onFailure { error ->
                current.status.text = getString(R.string.manifest_failed, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun applyFilter() {
        val views = binding ?: return
        val loaded = manifest ?: return
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
        }
        adapter.submit(shown)
    }

    private fun confirmDownload(version: RemoteVersion) {
        if (busy) return
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
        val gameDir = File(requireContext().filesDir, "minecraft")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.install(version, gameDir, InstallOptions()) { event ->
                        activity?.runOnUiThread {
                            val current = binding ?: return@runOnUiThread
                            current.status.text = event.label
                            if (event.total > 0) {
                                current.progress.isIndeterminate = false
                                current.progress.max = 1000
                                current.progress.progress = ((event.completed * 1000) / event.total).toInt().coerceIn(0, 1000)
                            }
                        }
                    }
                }
            }
            busy = false
            val current = binding ?: return@launch
            current.progress.visibility = View.GONE
            result.onSuccess { report ->
                current.status.text = getString(
                    R.string.download_done,
                    report.versionId,
                    report.librariesDownloaded,
                    report.assetObjectsDownloaded,
                )
            }.onFailure { error ->
                current.status.text = getString(R.string.download_failed, error.message ?: error.javaClass.simpleName)
            }
        }
    }
}

private class VersionAdapter(
    private val onDownload: (RemoteVersion) -> Unit,
) : RecyclerView.Adapter<VersionAdapter.Holder>() {
    private var items: List<RemoteVersion> = emptyList()

    fun submit(next: List<RemoteVersion>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVersionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.id.text = item.id
        holder.type.text = item.type
        holder.download.setOnClickListener { onDownload(item) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(binding: ItemVersionBinding) : RecyclerView.ViewHolder(binding.root) {
        val id: TextView = binding.versionId
        val type: TextView = binding.versionType
        val download = binding.download
    }
}
