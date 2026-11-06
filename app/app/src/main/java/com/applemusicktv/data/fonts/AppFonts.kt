package com.applemusicktv.data.fonts

import android.content.Context
import android.graphics.Typeface
import com.applemusicktv.data.NetworkLog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppFonts private constructor(context: Context) {
    // Both .part and completed fonts live outside cacheDir; ordinary cache cleanup cannot evict them.
    internal val directory = File(context.filesDir, "fonts/noto-cjk-${FontAssets.VERSION}")
    private val store = FontFileStore(directory, FontAssets.all,
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES).let { builder ->
                if (android.os.Build.VERSION.SDK_INT <= 25)
                    FontTls.configure(builder, context.assets.open("certificates/isrg-root-x1.pem"))
                else builder
            }.build(),
        validateFont = { Typeface.createFromFile(it) },
    )
    private val session = FontDownloadSession(store, log = { NetworkLog.line("Fonts", it) })
    val state = session.state
    internal fun files() = FontAssets.all.map(store::file)
    private var job: Job? = null
    @Synchronized fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch { session.run() }
    }
    companion object {
        @Volatile private var instance: AppFonts? = null
        fun get(context: Context): AppFonts = instance ?: synchronized(this) {
            instance ?: AppFonts(context.applicationContext).also { instance = it }
        }
    }
}
