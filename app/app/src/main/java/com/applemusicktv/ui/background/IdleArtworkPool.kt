package com.applemusicktv.ui.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Only recent successfully loaded public cover URLs; no bitmap copies or account data. */
internal object IdleArtworkPool {
    private val _urls = MutableStateFlow<List<String>>(emptyList())
    val urls: StateFlow<List<String>> = _urls
    @Synchronized fun record(data: Any) {
        val url = data as? String ?: return
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        if (!host.endsWith(".mzstatic.com") || !url.startsWith("https://")) return
        _urls.value = (_urls.value.filterNot { it == url } + url).takeLast(64)
    }
}
