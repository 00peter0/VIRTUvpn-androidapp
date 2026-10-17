package com.wireguard.android.vcs

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import com.wireguard.android.activity.QrScannerActivity
import com.wireguard.android.util.AppPermissionAccess
import com.wireguard.android.util.NativeQrScanContract

/** Actual Android contract and early-cancellation checks; no QR payload is imported. */
internal object QrScannerChecks {
    fun verify(instrumentation: Instrumentation): List<String> {
        val context = instrumentation.targetContext
        val before = AppPermissionAccess.cameraAllowed(context)
        val contract = NativeQrScanContract()
        val request = contract.createIntent(context, "Controlled camera cancellation")
        check(request.component == ComponentName(context, QrScannerActivity::class.java))
        check(!context.packageManager.getActivityInfo(request.component!!, 0).exported)
        val payload = "Local contract fixture · žiadny import"
        val result = Intent().putExtra(NativeQrScanContract.EXTRA_RESULT, payload)
        check(contract.parseResult(Activity.RESULT_CANCELED, result) == null)
        check(contract.parseResult(Activity.RESULT_OK, null) == null)
        check(contract.parseResult(Activity.RESULT_OK, Intent()) == null)
        check(contract.parseResult(Activity.RESULT_OK, result) == payload)
        repeat(2) {
            val activity = instrumentation.startActivitySync(request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            check(activity is QrScannerActivity)
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!activity.isDestroyed && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            check(activity.isDestroyed)
        }
        check(AppPermissionAccess.cameraAllowed(context) == before)
        return listOf("Both native QR flows use a non-exported scanner and exact UTF-8 result contract",
            "Cancelled/missing results are never accepted as successful scans",
            "Opening then cancelling during camera setup destroys the scanner twice without requesting access or importing a profile")
    }
}
