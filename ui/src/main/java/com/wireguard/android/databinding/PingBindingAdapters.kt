/*
 * Copyright © 2025-2026 Virtu VPN (vcs.virtucomputing.com). All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * This file is part of Virtu VPN, based on wireguard-android
 * by WireGuard LLC, licensed under Apache-2.0.
 */
package com.wireguard.android.databinding

import android.view.View
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.databinding.BindingAdapter
import com.wireguard.android.backend.Tunnel

object PingBindingAdapters {

    @BindingAdapter("latencyText")
    @JvmStatic
    fun setLatencyText(view: TextView, latencyMs: Long) {
        view.text = when {
            latencyMs < 0 -> "•••"
            latencyMs < 1000 -> "${latencyMs} ms"
            else -> "${"%.1f".format(latencyMs / 1000.0)} s"
        }
    }

    @BindingAdapter("latencyTextColor")
    @JvmStatic
    fun setLatencyTextColor(view: TextView, latencyMs: Long) {
        view.setTextColor(latencyColor(latencyMs))
    }

    @BindingAdapter("tunnelState", "latencyMs")
    @JvmStatic
    fun setDotTint(view: View, state: Tunnel.State?, latencyMs: Long) {
        val color = if (state == Tunnel.State.UP) {
            "#00BFA5".toColorInt() // connected = green
        } else {
            latencyColor(latencyMs)
        }
        view.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun latencyColor(latencyMs: Long): Int = when {
        latencyMs < 0 -> "#667788".toColorInt()   // unknown/measuring
        latencyMs < 50 -> "#00BFA5".toColorInt()   // excellent
        latencyMs < 100 -> "#FFB300".toColorInt()  // medium
        else -> "#FF5252".toColorInt()              // poor
    }
}
