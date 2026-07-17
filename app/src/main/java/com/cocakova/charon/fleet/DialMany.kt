package com.cocakova.charon.fleet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Dial every [item] in parallel on IO, at most [parallel] permits at once — the
 * shared shape of the fleet soundings and the near-waters sweep. Results come back
 * in item order; each [dial] is responsible for its own failure handling.
 */
suspend fun <T, R> dialMany(items: List<T>, parallel: Int, dial: suspend (T) -> R): List<R> =
    withContext(Dispatchers.IO) {
        val gate = Semaphore(parallel)
        coroutineScope {
            items.map { item -> async { gate.withPermit { dial(item) } } }.awaitAll()
        }
    }
