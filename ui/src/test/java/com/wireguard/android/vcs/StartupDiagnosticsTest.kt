package com.wireguard.android.vcs

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupDiagnosticsTest {
    @Test fun permissionAloneDoesNotProveAnEnabledBootReceiver() {
        assertEquals(StartupAppStatus.PERMISSION_ONLY, StartupDiagnosticPolicy.status(true, false, true, emptyList()))
    }
    @Test fun disabledAndStoppedAppsDoNotBecomeEligibleFromReceiverMetadata() {
        assertEquals(StartupAppStatus.DISABLED, StartupDiagnosticPolicy.status(false, false, true, listOf(true)))
        assertEquals(StartupAppStatus.STOPPED, StartupDiagnosticPolicy.status(true, true, true, listOf(true)))
        assertEquals(StartupAppStatus.DISABLED, StartupDiagnosticPolicy.status(true, false, true, listOf(false, false)))
    }
    @Test fun anEnabledReceiverWithoutBootPermissionIsNotReportedAsEnabledStartup() {
        assertEquals(StartupAppStatus.PERMISSION_NOT_GRANTED, StartupDiagnosticPolicy.status(true, false, false, listOf(true)))
    }
    @Test fun missingObservationsRemainUnverified() {
        assertEquals(StartupAppStatus.UNVERIFIED, StartupDiagnosticPolicy.status(null, false, true, listOf(true)))
        assertEquals(StartupAppStatus.UNVERIFIED, StartupDiagnosticPolicy.status(true, false, null, listOf(true)))
        assertEquals(StartupAppStatus.UNVERIFIED, StartupDiagnosticPolicy.status(true, false, true, listOf(false, null)))
    }
    @Test fun oneConfirmedEnabledReceiverIsEnoughToReportDeclaredBootCapability() {
        assertEquals(StartupAppStatus.ENABLED_RECEIVER, StartupDiagnosticPolicy.status(true, false, true, listOf(false, true)))
    }
}
