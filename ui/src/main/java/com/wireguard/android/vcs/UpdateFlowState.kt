package com.wireguard.android.vcs

enum class UpdatePhase(val step: Int) {
    CHECKING(0), DOWNLOADING(1), PAUSED(1), VERIFYING(2), READY(3), STAGING(3),
    CONFIRMING(3), INSTALLING(3), COMPLETED(4), UP_TO_DATE(4), FAILED(-1)
}

data class UpdateFlowState(
    val phase: UpdatePhase = UpdatePhase.CHECKING,
    val versionName: String? = null,
    val percent: Int? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val message: String? = null
) {
    companion object {
        fun percent(done: Long, total: Long): Int? = if (total > 0) ((done.coerceAtLeast(0).coerceAtMost(total) * 100) / total).toInt() else null
        fun installed(installedCode: Long, targetCode: Long) = targetCode > 0 && installedCode >= targetCode
        fun acceptsResult(storedSession: Int, receivedSession: Int) = storedSession >= 0 && storedSession == receivedSession
    }
}
