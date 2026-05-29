/*
 * Copyright © 2025-2026 Virtu VPN (vcs.virtucomputing.com). All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * This file is part of Virtu VPN, based on wireguard-android
 * by WireGuard LLC, licensed under Apache-2.0.
 */
package com.wireguard.android.databinding

import android.graphics.Color
import android.view.View
import android.widget.TextView
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
            Color.parseColor("#00BFA5") // connected = green
        } else {
            latencyColor(latencyMs)
        }
        view.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun latencyColor(latencyMs: Long): Int = when {
        latencyMs < 0 -> Color.parseColor("#667788")   // unknown/measuring
        latencyMs < 50 -> Color.parseColor("#00BFA5")   // excellent
        latencyMs < 100 -> Color.parseColor("#FFB300")  // medium
        else -> Color.parseColor("#FF5252")              // poor
    }
}
