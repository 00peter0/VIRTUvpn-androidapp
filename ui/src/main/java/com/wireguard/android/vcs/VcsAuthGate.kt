package com.wireguard.android.vcs

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.wireguard.android.R
import com.wireguard.android.activity.HomeActivity

object VcsAuthGate {
    fun requireSignedIn(activity: Activity): Boolean {
        if (VcsManagedClient.hasSession(activity)) return true
        Toast.makeText(activity, R.string.vcs_sign_in_required, Toast.LENGTH_LONG).show()
        activity.startActivity(signInIntent(activity))
        activity.finish()
        return false
    }

    fun startSignIn(context: Context) {
        Toast.makeText(context, R.string.vcs_sign_in_required, Toast.LENGTH_LONG).show()
        context.startActivity(signInIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun signInIntent(context: Context): Intent {
        return Intent(context, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
