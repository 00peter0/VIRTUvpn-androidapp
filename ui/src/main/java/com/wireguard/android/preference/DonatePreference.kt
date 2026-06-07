/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.preference

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import com.wireguard.android.R
import com.wireguard.android.updater.Updater
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.VcsDialogs
import androidx.core.net.toUri

class DonatePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    override fun getSummary() = context.getString(R.string.donate_summary)

    override fun getTitle() = context.getString(R.string.donate_title)

    override fun onClick() {
        /* Google Play Store forbids links to our donation page. */
        if (Updater.installerIsGooglePlay(context)) {
            VcsDialogs.show(
                context = context,
                title = context.getString(R.string.donate_title),
                message = context.getString(R.string.donate_google_play_disappointment),
                positive = VcsDialogs.action(context, android.R.string.ok, primary = true)
            )
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = "https://vcs.virtucomputing.com/".toUri()
        try {
            context.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_SHORT).show()
        }
    }
}
