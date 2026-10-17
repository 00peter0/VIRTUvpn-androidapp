/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.ConfigNamingDialogFragmentBinding
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.vcs.VcsManagedClient
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class ConfigNamingDialogFragment : DialogFragment() {
    private var binding: ConfigNamingDialogFragmentBinding? = null
    private var config: Config? = null
    private var creating = false

    private fun createTunnelAndDismiss() {
        val binding = binding ?: return
        val activity = activity ?: return
        if (creating) return
        val name = binding.tunnelNameText.text.toString()
        creating = true
        activity.lifecycleScope.launch {
            try {
                val tunnel = Application.getTunnelManager().create(name, config, independentOnly = true)
                if (requireArguments().getBoolean(KEY_MARK_EXTERNAL_VPN_MESH, false)) {
                    VcsManagedClient.rememberExternalVpnMeshTunnels(activity, setOf(tunnel.name))
                }
                parentFragmentManager.setFragmentResult(RESULT_IMPORTED, Bundle())
                dismiss()
            } catch (e: Throwable) {
                binding.tunnelNameTextLayout.error = e.message
            } finally {
                creating = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configText = requireArguments().getString(KEY_CONFIG_TEXT)
        val configBytes = configText!!.toByteArray(StandardCharsets.UTF_8)
        config = try {
            Config.parse(ByteArrayInputStream(configBytes))
        } catch (e: Throwable) {
            when (e) {
                is BadConfigException, is IOException -> throw IllegalArgumentException("Invalid config passed to ${javaClass.simpleName}", e)
                else -> throw e
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val alertDialogBuilder = MaterialAlertDialogBuilder(activity)
        alertDialogBuilder.setTitle(R.string.import_from_qr_code)
        binding = ConfigNamingDialogFragmentBinding.inflate(activity.layoutInflater, null, false)
        binding?.apply {
            executePendingBindings()
            alertDialogBuilder.setView(root)
        }
        alertDialogBuilder.setPositiveButton(R.string.create_tunnel, null)
        alertDialogBuilder.setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
        val dialog = alertDialogBuilder.create()
        dialog.setOnShowListener {
            VcsDialogs.applyDefaultStyle(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { createTunnelAndDismiss() }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        return dialog
    }

    companion object {
        const val RESULT_IMPORTED = "local_tunnel_imported"
        private const val KEY_CONFIG_TEXT = "config_text"
        private const val KEY_MARK_EXTERNAL_VPN_MESH = "mark_external_vpn_mesh"

        fun newInstance(configText: String?, markExternalVpnMesh: Boolean = false): ConfigNamingDialogFragment {
            val extras = Bundle()
            extras.putString(KEY_CONFIG_TEXT, configText)
            extras.putBoolean(KEY_MARK_EXTERNAL_VPN_MESH, markExternalVpnMesh)
            val fragment = ConfigNamingDialogFragment()
            fragment.arguments = extras
            return fragment
        }
    }
}
