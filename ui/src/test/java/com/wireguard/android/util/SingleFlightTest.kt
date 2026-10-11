package com.wireguard.android.util

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    @Test fun concurrentObserversExecuteOnlyOnceAndCancellationDoesNotCancelSharedWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<Int>(scope)
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val first = async { flight.run { count.incrementAndGet(); started.complete(Unit); release.await(); 7 } }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { flight.run { error("Duplicate operation") } }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(7, second.await())
            assertEquals(1, count.get())
            assertEquals(8, flight.run { 8 })
        } finally { scope.cancel() }
    }
    @Test fun failureCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<Int>(scope)
            try { flight.run { error("Network failure") }; fail() } catch (_: IllegalStateException) {}
            assertEquals(9, flight.run { 9 })
        } finally { scope.cancel() }
    }
}
