// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fcp.core.auth.AccountStore
import fcp.core.auth.LittleSkin
import fcp.core.auth.LoginOutcome
import fcp.core.auth.OfflineAccounts
import fcp.core.auth.OfflineCreateResult
import fcp.core.auth.StoredAccount
import fcp.core.auth.YggdrasilClient
import fcp.core.auth.storedYggdrasil
import fcp.core.net.UrlHttpClient
import fcp.launcher.R
import fcp.launcher.databinding.FragmentAccountsBinding
import fcp.launcher.databinding.ItemAccountBinding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountsFragment : Fragment() {
    private var binding: FragmentAccountsBinding? = null
    private val yggdrasil = YggdrasilClient(UrlHttpClient())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val views = FragmentAccountsBinding.inflate(inflater, container, false)
        binding = views
        views.littleskinUrl.text = LittleSkin.API_ROOT
        views.addOffline.setOnClickListener { addOffline() }
        views.littleskinLogin.setOnClickListener {
            login(LittleSkin.API_ROOT, views.littleskinUser, views.littleskinPassword, LittleSkin.PRESET_ID, views.littleskinLogin)
        }
        views.toggleAdvanced.setOnClickListener {
            views.advanced.visibility = if (views.advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        views.customLogin.setOnClickListener {
            login(
                views.customUrl.text?.toString().orEmpty(),
                views.customUser,
                views.customPassword,
                "custom",
                views.customLogin,
            )
        }
        renderAccounts()
        return views.root
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun store(): AccountStore = AccountStore(File(requireContext().filesDir, "accounts.json"))

    private fun addOffline() {
        val views = binding ?: return
        when (val result = OfflineAccounts.create(views.offlineName.text?.toString().orEmpty())) {
            is OfflineCreateResult.Invalid -> views.accountStatus.text = result.message
            is OfflineCreateResult.Ok -> {
                store().upsert(result.account)
                views.offlineName.text = null
                views.accountStatus.text = getString(R.string.account_added, result.account.profileName)
                renderAccounts()
            }
        }
    }

    private fun login(apiRoot: String, userField: EditText, passwordField: EditText, preset: String, button: View) {
        val views = binding ?: return
        val username = userField.text?.toString().orEmpty()
        val password = passwordField.text?.toString().orEmpty()
        button.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { yggdrasil.login(apiRoot, username, password) }
            }
            button.isEnabled = true
            val current = binding ?: return@launch
            result.onSuccess { outcome ->
                passwordField.text = null
                when (outcome) {
                    is LoginOutcome.Ready -> saveYggdrasil(preset, outcome.session, outcome.profile)
                    is LoginOutcome.ChooseProfile -> chooseProfile(preset, outcome)
                }
            }.onFailure { error ->
                current.accountStatus.text = error.message ?: error.javaClass.simpleName
            }
        }
    }

    private fun chooseProfile(preset: String, outcome: LoginOutcome.ChooseProfile) {
        val names = outcome.session.profiles.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.choose_profile)
            .setItems(names) { _, which ->
                val profile = outcome.session.profiles[which]
                saveYggdrasil(preset, outcome.session, profile)
            }
            .show()
    }

    private fun saveYggdrasil(
        preset: String,
        session: fcp.core.auth.YggdrasilSession,
        profile: fcp.core.auth.GameProfile,
    ) {
        val account = storedYggdrasil(preset, session, profile)
        store().upsert(account)
        binding?.accountStatus?.text = getString(R.string.account_added, "${account.kindLabel} · ${account.profileName}")
        renderAccounts()
    }

    private fun renderAccounts() {
        val views = binding ?: return
        val data = store().load()
        views.accountList.removeAllViews()
        if (data.accounts.isEmpty()) {
            views.accountStatus.setText(R.string.no_accounts)
            return
        }
        if (views.accountStatus.text == getString(R.string.no_accounts)) {
            val selected = data.accounts.firstOrNull { it.id == data.selectedId }
            if (selected != null) {
                views.accountStatus.text = selected.kindLabel + " · " + selected.profileName
            }
        }
        val inflater = LayoutInflater.from(requireContext())
        for (account in data.accounts) {
            val row = ItemAccountBinding.inflate(inflater, views.accountList, true)
            val current = if (account.id == data.selectedId) getString(R.string.current_account) + " · " else ""
            row.accountTitle.text = current + account.kindLabel + " · " + account.profileName
            row.accountSubtitle.text = when (account) {
                is StoredAccount.Offline -> account.uuid
                is StoredAccount.Yggdrasil -> account.serverName + " · " + account.apiRoot
            }
            row.use.setOnClickListener {
                store().select(account.id)
                renderAccounts()
            }
            row.delete.setOnClickListener {
                store().delete(account.id)
                renderAccounts()
            }
        }
    }
}
