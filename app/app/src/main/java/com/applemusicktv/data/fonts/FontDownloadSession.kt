package com.applemusicktv.data.fonts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One process-wide session. Navigation and authentication never own/cancel this job. */
internal class FontDownloadSession(
    private val store: FontFileStore,
    private val waitToRetry: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(FontDownloadState(totalBytes = store.totalBytes))
    val state = mutableState.asStateFlow()
    suspend fun run() {
        while (true) {
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(phase = FontDownloadPhase.Loading)
            try {
                withContext(Dispatchers.IO) {
                    store.prepare { bytes -> mutableState.value = FontDownloadState(bytes, store.totalBytes) }
                }
                mutableState.value = FontDownloadState(store.totalBytes, store.totalBytes, FontDownloadPhase.Ready)
                log("ready bytes=${store.totalBytes}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = FontDownloadState(store.downloadedBytes(), store.totalBytes, FontDownloadPhase.Failed)
                log("retry in ${FontAssets.RETRY_MS}ms: ${e.javaClass.simpleName}")
                waitToRetry(FontAssets.RETRY_MS)
            }
        }
    }
}
