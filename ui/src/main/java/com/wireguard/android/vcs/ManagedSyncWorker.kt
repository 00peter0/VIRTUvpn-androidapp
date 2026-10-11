package com.wireguard.android.vcs

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** A battery-aware safety net. Android controls timing; explicit/foreground sync shares one operation. */
class ManagedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!VcsManagedClient.hasSession(applicationContext) && !VcsManagedClient.hasAccountSession(applicationContext)) return Result.success()
        return try {
            val result = VcsManagedClient.syncManagedTunnels(applicationContext)
            VcsManagedClient.reportCurrentStates(applicationContext)
            if (result.failures > 0 && runAttemptCount < 3) Result.retry() else Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ManagedSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("vcs-managed-device-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
