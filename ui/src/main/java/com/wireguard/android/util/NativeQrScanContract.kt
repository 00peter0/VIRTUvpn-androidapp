package com.wireguard.android.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.wireguard.android.activity.QrScannerActivity

/** Both native QR entry points share one camera and the same cancellation contract. */
class NativeQrScanContract : ActivityResultContract<String, String?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(context, QrScannerActivity::class.java).putExtra(EXTRA_PROMPT, input)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_RESULT) else null

    companion object {
        const val EXTRA_PROMPT = "com.virtuvpn.android.qr.PROMPT"
        const val EXTRA_RESULT = "com.virtuvpn.android.qr.RESULT"
    }
}
