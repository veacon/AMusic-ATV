package com.applemusicktv.media

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response

/** Registration and cancellation share a lock, including the gap before execute() enters Dispatcher. */
internal class DecryptCallRegistry {
    private val calls = mutableMapOf<Call, String>()
    fun cancelExcept(songId: String?) = synchronized(calls) {
        calls.forEach { (call, owner) -> if (owner != songId) call.cancel() }
    }
    fun <T> read(call: Call, owner: String, job: Job, block: (Response) -> T): T {
        synchronized(calls) { job.ensureActive(); calls[call] = owner }
        return try { call.execute().use(block) }
        finally { synchronized(calls) { calls.remove(call) } }
    }
}
