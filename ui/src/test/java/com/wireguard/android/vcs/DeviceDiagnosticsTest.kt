package com.wireguard.android.vcs

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DeviceDiagnosticsTest {
    @Test fun missingOrInvalidCapacityDoesNotBecomeZeroUsage() {
        assertNull(DeviceDiagnosticMath.usedPercent(null, 10))
        assertNull(DeviceDiagnosticMath.usedPercent(0, 0))
        assertNull(DeviceDiagnosticMath.usedPercent(100, 101))
        assertNull(DeviceDiagnosticMath.usedPercent(100, -1))
        assertEquals(91, DeviceDiagnosticMath.usedPercent(100, 9))
    }
    @Test fun cpuSampleUsesOneCoreAndDoesNotClampMulticoreWork() {
        assertEquals(200.0, DeviceDiagnosticMath.cpuOneCorePercent(1500, 750)!!, 0.001)
        assertNull(DeviceDiagnosticMath.cpuOneCorePercent(-1, 750))
        assertNull(DeviceDiagnosticMath.cpuOneCorePercent(10, 0))
    }
    @Test fun cacheIsNotDoubleCounted() {
        assertEquals(300L, AppStorage("Fixture", "test.fixture", 100, 200, 60).totalBytes)
    }
    @Test fun missingFutureAndMalformedPatchDatesRemainUnknown() {
        val today = LocalDate.of(2026, 10, 11)
        assertNull(DeviceDiagnosticMath.patchAgeDays("", today))
        assertNull(DeviceDiagnosticMath.patchAgeDays("2026-02-31", today))
        assertNull(DeviceDiagnosticMath.patchAgeDays("2026-10-12", today))
        assertEquals(10L, DeviceDiagnosticMath.patchAgeDays("2026-10-01", today))
    }
    @Test fun sharesUseTheWholeMeasuredSampleRatherThanOnlyTheTopRows() {
        assertEquals(10.0, DeviceDiagnosticMath.sharePercent(100, 1000)!!, 0.001)
        assertEquals(100.0, DeviceDiagnosticMath.sharePercent(1000, 1000)!!, 0.001)
        assertEquals(0.0, DeviceDiagnosticMath.sharePercent(0, 1000)!!, 0.001)
    }
    @Test fun absentOrInvalidDenominatorsNeverBecomeZeroPercent() {
        assertNull(DeviceDiagnosticMath.sharePercent(0, 0))
        assertNull(DeviceDiagnosticMath.sharePercent(10, null))
        assertNull(DeviceDiagnosticMath.sharePercent(null, 100))
        assertNull(DeviceDiagnosticMath.sharePercent(-1, 100))
        assertNull(DeviceDiagnosticMath.sharePercent(101, 100))
    }
}
