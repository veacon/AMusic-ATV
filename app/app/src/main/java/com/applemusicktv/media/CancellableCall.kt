package com.applemusicktv.media

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal data class TextResponse(val code: Int, val body: String)

/** Keep cancellation attached until the body is read, including a slow body after successful headers. */
internal suspend fun Call.awaitTextResponse(): TextResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { TextResponse(it.code, it.body?.string().orEmpty()) }
                continuation.resumeWith(Result.success(result))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
