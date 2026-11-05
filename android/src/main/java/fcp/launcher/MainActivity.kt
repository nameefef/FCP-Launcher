// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import fcp.launcher.databinding.ActivityMainBinding
import fcp.launcher.ui.AboutFragment
import fcp.launcher.ui.AccountsFragment
import fcp.launcher.ui.VersionsFragment
import fcp.shared.PRODUCT_NAME

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = PRODUCT_NAME
        if (savedInstanceState == null) {
            showTab("versions")
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_versions -> showTab("versions")
                R.id.nav_accounts -> showTab("accounts")
                R.id.nav_about -> showTab("about")
                else -> false
            }
        }
    }

    private fun showTab(tag: String): Boolean {
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        for (existing in listOf("versions", "accounts", "about")) {
            manager.findFragmentByTag(existing)?.let { transaction.hide(it) }
        }
        val current = manager.findFragmentByTag(tag)
        if (current == null) {
            transaction.add(R.id.container, fragmentFor(tag), tag)
        } else {
            transaction.show(current)
        }
        transaction.commit()
        return true
    }

    private fun fragmentFor(tag: String): Fragment = when (tag) {
        "accounts" -> AccountsFragment()
        "about" -> AboutFragment()
        else -> VersionsFragment()
    }
}
