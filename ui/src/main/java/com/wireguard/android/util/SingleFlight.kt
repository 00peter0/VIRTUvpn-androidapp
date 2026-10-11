package com.wireguard.android.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Navigation or a cancelled observer cannot cancel work shared with another observer. */
class SingleFlight<T>(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var running: Deferred<T>? = null
    suspend fun run(block: suspend () -> T): T {
        val operation = mutex.withLock {
            running?.takeIf { it.isActive } ?: scope.async { block() }.also { running = it }
        }
        return operation.await()
    }
}
