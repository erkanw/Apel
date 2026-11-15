package io.github.erkanw.vault.provider

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * Runs [block] (which blocks a thread in [call]) and cancels the call the moment the coroutine is cancelled: Stop and
 * time limits then end a slow request at once instead of when it times out. A watcher waits on another thread of the
 * same dispatcher, so the caller must run on a pool (Dispatchers.IO).
 */
suspend fun <T> cancelling(call: Call, block: suspend () -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try { block() } finally { watcher.cancel() }
}
