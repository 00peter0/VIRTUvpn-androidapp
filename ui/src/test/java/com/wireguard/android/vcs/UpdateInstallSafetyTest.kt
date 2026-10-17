package com.wireguard.android.vcs

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallSafetyTest {
    @Test fun newerWorkflowMustNotRetargetAnAlreadyInstalledSession() {
        assertTrue(UpdateInstallSafety.installedSession(910, 910, 911))
    }

    @Test fun installedWorkflowMustNotAuthorizeRetiringAFutureSession() {
        assertFalse(UpdateInstallSafety.installedSession(910, 911, 910))
    }

    @Test fun invalidExplicitTargetCannotFallBackToCompletedWorkflow() {
        assertFalse(UpdateInstallSafety.installedSession(910, 0, 910))
        assertFalse(UpdateInstallSafety.installedSession(910, -1, 910))
    }

    @Test fun legacySessionCanRetireOnlyAfterItsRecordedWorkflowTargetIsInstalled() {
        assertTrue(UpdateInstallSafety.installedSession(911, null, 911))
        assertTrue(UpdateInstallSafety.installedSession(912, null, 911))
        assertFalse(UpdateInstallSafety.installedSession(910, null, 911))
    }

    @Test fun missingLegacyTargetCannotAuthorizeCleanup() {
        assertFalse(UpdateInstallSafety.installedSession(912, null, 0))
        assertFalse(UpdateInstallSafety.installedSession(912, null, -1))
    }

    @Test fun committedInstallationCannotBeReplacedEvenWhenInactiveOrMarkedNotAttempted() {
        for (active in listOf(false, true)) for (sealed in listOf(null, false, true))
            assertFalse(UpdateInstallSafety.canReplace(active, true, sealed, false))
    }

    @Test fun activeInstallationIsNeverReplaced() {
        for (committed in listOf(null, false, true)) for (sealed in listOf(null, false, true))
            for (attempted in listOf(null, false, true))
                assertFalse(UpdateInstallSafety.canReplace(true, committed, sealed, attempted))
    }

    @Test fun inactiveUncommittedSessionAfterRebootCanBeReplaced() {
        assertTrue(UpdateInstallSafety.canReplace(false, false, true, true))
        assertTrue(UpdateInstallSafety.interrupted(UpdatePhase.INSTALLING, false, false))
        assertTrue(UpdateInstallSafety.interrupted(UpdatePhase.INSTALLATION_DELAYED, false, false))
    }

    @Test fun pendingConfirmationAndUnknownCommitAreNotDeclaredInterrupted() {
        assertFalse(UpdateInstallSafety.interrupted(UpdatePhase.CONFIRMING, false, false))
        assertFalse(UpdateInstallSafety.interrupted(UpdatePhase.INSTALLING, true, false))
        assertFalse(UpdateInstallSafety.interrupted(UpdatePhase.INSTALLING, false, null))
        assertFalse(UpdateInstallSafety.interrupted(UpdatePhase.INSTALLING, false, true))
    }

    @Test fun olderAndroidUsesOnlyPositiveEvidenceThatCommitDidNotStart() {
        assertFalse(UpdateInstallSafety.canReplace(false, null, true, false))
        assertFalse(UpdateInstallSafety.canReplace(false, null, null, null))
        assertFalse(UpdateInstallSafety.canReplace(false, null, null, true))
        assertTrue(UpdateInstallSafety.canReplace(false, null, false, true))
        assertTrue(UpdateInstallSafety.canReplace(false, null, null, false))
    }

    @Test fun unchangedProgressBecomesDelayedWithoutAuthorizingCancellation() {
        val first = UpdateInstallWatch(10, 4, 100, 90)
        assertFalse(first.delayed(100 + UpdateInstallSafety.DELAY_MILLIS - 1))
        val waiting = first.observe(10, 4, 100 + UpdateInstallSafety.DELAY_MILLIS, 90)
        assertEquals(first, waiting)
        assertTrue(waiting.delayed(100 + UpdateInstallSafety.DELAY_MILLIS))
        assertFalse(UpdateInstallSafety.canReplace(false, true, true, true))
    }

    @Test fun progressResumesAndGetsANewWaitingPeriod() {
        val first = UpdateInstallWatch(10, 4, 100, 90)
        val resumed = first.observe(10, 4, 200_000, 91)
        assertEquals(200_000, resumed.since)
        assertFalse(resumed.delayed(200_001))
    }

    @Test fun fluctuatingOrLowerProgressDoesNotPostponeTheWarning() {
        val first = UpdateInstallWatch(10, 4, 100, 90)
        assertEquals(first, first.observe(10, 4, 100_000, 89))
        assertEquals(first, first.observe(10, 4, 150_000, 90))
    }

    @Test fun processRecreationRetainsTheOriginalWait() {
        val persisted = UpdateInstallWatch(10, 4, 100, 90)
        val restored = persisted.copy().observe(10, 4, 200_000, 90)
        assertEquals(persisted, restored)
        assertTrue(restored.delayed(200_000))
    }

    @Test fun rebootNewSessionOrInvalidMonotonicTimeStartsAFreshObservation() {
        val old = UpdateInstallWatch(10, 4, 500_000, 90)
        for (fresh in listOf(old.observe(10, 5, 600_000, 90), old.observe(11, 4, 600_000, 90),
            old.observe(10, 4, 100, 90))) {
            assertFalse(fresh.delayed(fresh.since))
            assertNotEquals(old, fresh)
        }
        assertFalse(old.delayed(0))
    }

    @Test fun progressAndCallbackDoNotProveInstallation() {
        assertFalse(UpdateFlowState.installed(907, 908))
        assertFalse(UpdateFlowState.installed(908, 0))
        assertTrue(UpdateFlowState.installed(908, 908))
        assertTrue(UpdateFlowState.installed(909, 908))
        assertEquals(100, UpdateFlowState.percent(100, 100))
        assertFalse(UpdateFlowState.installed(907, 908))
    }

    @Test fun lateOrForeignSessionResultIsRejected() {
        assertFalse(UpdateFlowState.acceptsResult(-1, -1))
        assertFalse(UpdateFlowState.acceptsResult(-1, 10))
        assertFalse(UpdateFlowState.acceptsResult(11, 10))
        assertTrue(UpdateFlowState.acceptsResult(11, 11))
    }
}
