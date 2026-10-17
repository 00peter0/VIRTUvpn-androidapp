package com.wireguard.android.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PublicIdentityProbeTest {
    @Test fun validatesEchoRepliesWithoutResolvingHostnames() {
        assertEquals("51.222.7.179", PublicIdentityProbe.parseIp("51.222.7.179\n", false))
        assertEquals("2001:db8::7", PublicIdentityProbe.parseIp("2001:db8::7", true))
        for (bad in listOf("example.com", "127.1", "010.0.0.1", "256.1.2.3", "1.2.3.4/path", "<html>51.222.7.179</html>", "1.2.3.4\n5.6.7.8")) {
            assertNull(bad, PublicIdentityProbe.parseIp(bad, false))
        }
        for (bad in listOf("ipv6.example.com", "fe80::1%wlan0", "2001:::7", "51.222.7.179", "2001:db8::7?x")) {
            assertNull(bad, PublicIdentityProbe.parseIp(bad, true))
        }
    }

    @Test fun noCurrentVpnMakesNoRequests() = runBlocking {
        assertNull(PublicIdentityProbe.read(true, true, { false }) { error("Must never use a fallback") })
    }

    @Test fun noIpv6RouteNeverProbesCellularIpv6() = runBlocking {
        val requests = mutableListOf<String>()
        val result = PublicIdentityProbe.read(true, false, { true }) {
            requests += it
            if (it.contains("icanhazip")) "51.222.7.179" else "{}"
        }
        assertEquals("51.222.7.179", result?.ipv4)
        assertNull(result?.ipv6)
        assertEquals(listOf("https://ipv4.icanhazip.com", "https://ipwho.is/51.222.7.179"), requests)
    }

    @Test fun networkChangeDuringIpv4DiscardsResultAndStopsFurtherRequests() = runBlocking {
        var current = true
        var calls = 0
        assertNull(PublicIdentityProbe.read(true, true, { current }) {
            calls++
            current = false
            "51.222.7.179"
        })
        assertEquals(1, calls)
    }

    @Test fun sameNamedTunnelReplacementDuringIpv6DiscardsBothAddresses() = runBlocking {
        var generation = 7
        var calls = 0
        val captured = generation
        assertNull(PublicIdentityProbe.read(true, true, { generation == captured }) {
            calls++
            if (calls == 2) generation++
            if (calls == 1) "51.222.7.179" else "2001:db8::7"
        })
        assertEquals(2, calls)
    }

    @Test fun staleGeolocationCannotRestorePreviousIdentity() = runBlocking {
        var current = true
        var calls = 0
        assertNull(PublicIdentityProbe.read(true, false, { current }) {
            calls++
            if (calls == 1) "51.222.7.179" else { current = false; "{}" }
        })
        assertEquals(2, calls)
    }

    @Test fun malformedOrUnavailableEchoDoesNotTriggerGeoOrClaimSuccess() = runBlocking {
        var calls = 0
        val result = PublicIdentityProbe.read(true, true, { true }) {
            calls++
            if (calls == 1) "<html>error</html>" else null
        }
        assertEquals(PublicIdentityResult(null, null, null), result)
        assertEquals(2, calls)
    }

    @Test fun geoFailureRetainsObservedIpAndIpv6OnlyIsSupported() = runBlocking {
        val result = PublicIdentityProbe.read(false, true, { true }) {
            if (it.contains("icanhazip")) "2001:db8::7" else null
        }
        assertEquals(PublicIdentityResult(null, "2001:db8::7", null), result)
    }

    @Test fun cancelledMeasurementDoesNotContinueOrBecomeSuccess() = runBlocking {
        var calls = 0
        try {
            coroutineScope {
                PublicIdentityProbe.read(true, true, { true }) {
                    calls++
                    cancel()
                    "51.222.7.179"
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertEquals(1, calls)
    }

    @Test fun freshRetryDoesNotReusePreviousSuccessOrFailure() = runBlocking {
        var reply: String? = "51.222.7.179"
        suspend fun read() = PublicIdentityProbe.read(true, false, { true }) {
            if (it.contains("icanhazip")) reply else null
        }
        assertEquals("51.222.7.179", read()?.ipv4)
        reply = null
        assertNull(read()?.ipv4)
        reply = "203.0.113.7"
        assertEquals("203.0.113.7", read()?.ipv4)
    }
}
