package com.wireguard.android.vcs

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
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
    @Test fun acceptingLocalSocketIsObservedWithoutSendingData() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket().use { listener ->
            listener.bind(InetSocketAddress(loopback, 0))
            assertEquals(PhonePortResult.ACCEPTING, PhonePortScan.probe(loopback, listener.localPort))
            listener.accept().use { peer -> assertEquals(-1, peer.getInputStream().read()) }
        }
    }
    @Test fun closedLocalPortIsRefusedAndNonLoopbackIsRejectedBeforeConnect() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }
        assertEquals(PhonePortResult.REFUSED, PhonePortScan.probe(loopback, closedPort))
        assertThrows(IllegalArgumentException::class.java) {
            PhonePortScan.probe(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 22)
        }
    }
    @Test fun inconclusiveEvidenceCannotProduceCompleteScan() {
        assertFalse(PhonePortReport(emptyList(), 64, 64, 1, 0).complete)
        assertFalse(PhonePortReport(emptyList(), 63, 64, 0, 0).complete)
        assertTrue(PhonePortReport(emptyList(), 64, 64, 0, 0).complete)
        assertEquals(32, PhonePortScan.PORTS.distinct().size)
    }
}
