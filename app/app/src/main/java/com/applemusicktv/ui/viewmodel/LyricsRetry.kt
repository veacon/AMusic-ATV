package com.applemusicktv.ui.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/** First request, then at most two background retries within an independent three-second budget. */
internal suspend fun <T> fetchLyricsWithRetries(
    isCurrent: () -> Boolean,
    fetch: suspend () -> List<T>,
    publish: (List<T>) -> Unit,
    report: (attempt: Int, lines: Int, failure: Throwable?) -> Unit = { _, _, _ -> },
    exhausted: () -> Unit = {},
    wait: suspend (Long) -> Unit = { delay(it) },
    now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    var retryDeadline = Long.MAX_VALUE
    fun remainingWindowMs() = (retryDeadline - now()).coerceAtLeast(0L)
    suspend fun attempt(number:Int,budget:Long):Boolean {
        currentCoroutineContext().ensureActive()
        if(!isCurrent()) return true
        var failure:Throwable?=null
        val lines=try {
            withTimeoutOrNull(budget) {fetch()} ?: run {failure=TimeoutException("lyrics request timed out");emptyList()}
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {failure=e;emptyList()}
        currentCoroutineContext().ensureActive()
        if(!isCurrent()) return true
        if(number>1 && remainingWindowMs()<=0) {
            report(number,0,TimeoutException("lyrics window ended"));return false
        }
        report(number,lines.size,failure)
        if(lines.isNotEmpty()) {publish(lines);return true}
        return false
    }
    if(attempt(1,5_000L)) return
    retryDeadline = now() + 3_000L
    for(number in 2..3) {
        currentCoroutineContext().ensureActive()
        if(!isCurrent()) return
        var remaining=remainingWindowMs()
        if(remaining<=0) break
        wait(minOf(150L,remaining))
        remaining=remainingWindowMs()
        if(remaining<=0) break
        if(attempt(number,minOf(1_200L,remaining))) return
    }
    if(isCurrent()) exhausted()
}
