package com.wireguard.android.vcs

/** Session facts are supplied by Android, never inferred from a percentage or timeout. */
object UpdateInstallSafety {
    const val DELAY_MILLIS = 180_000L

    fun canReplace(active: Boolean, committed: Boolean?, sealed: Boolean?, commitAttempted: Boolean?): Boolean =
        !active && when {
            committed != null -> !committed
            sealed != null -> !sealed
            else -> commitAttempted == false
        }

    fun interrupted(phase: UpdatePhase, active: Boolean, committed: Boolean?): Boolean =
        phase in setOf(UpdatePhase.INSTALLING, UpdatePhase.INSTALLATION_DELAYED) && !active && committed == false
}

/** Uses monotonic time; process recreation does not restart the waiting period. */
data class UpdateInstallWatch(val sessionId: Int, val boot: Int, val since: Long, val progress: Int) {
    fun observe(id: Int, bootCount: Int, now: Long, percent: Int): UpdateInstallWatch =
        if (id != sessionId || boot != bootCount || now < since || percent > progress)
            UpdateInstallWatch(id, bootCount, now, percent)
        else this

    fun delayed(now: Long): Boolean = now >= since && now - since >= UpdateInstallSafety.DELAY_MILLIS
}
