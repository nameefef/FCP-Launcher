// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import fcp.launcher.BuildConfig
import fcp.launcher.R
import fcp.launcher.databinding.FragmentAboutBinding
import fcp.shared.PRODUCT_FULL_NAME
import fcp.shared.PRODUCT_NAME

class AboutFragment : Fragment() {
    private var taps = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentAboutBinding.inflate(inflater, container, false)
        binding.productName.text = PRODUCT_NAME
        binding.version.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.productName.setOnClickListener {
            taps += 1
            if (taps >= 7) {
                binding.easterEgg.visibility = View.VISIBLE
                binding.easterEgg.text = PRODUCT_FULL_NAME
            }
        }
        return binding.root
    }
}
