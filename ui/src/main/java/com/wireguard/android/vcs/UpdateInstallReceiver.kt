package com.wireguard.android.vcs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit, non-exported PackageInstaller result target. Never launches UI from the background. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { ManagedUpdateFlow.result(context, intent) }
}
