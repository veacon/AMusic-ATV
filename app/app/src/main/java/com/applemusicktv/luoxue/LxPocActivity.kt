package com.applemusicktv.luoxue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 垂直切片 PoC：验证「Apple 元数据 → 平台搜索 → 落雪脚本(QuickJS) → 直链播放」在真机可行。
 *
 * 独立于主 App 的播放链路（PlayerViewModel 未改动），只验证新链路本身：
 *  - QuickJS 能否在电视上跑通脚本（含 async/await、lx.request）
 *  - 搜索 + 匹配 + 脚本级联 + 直链校验的实际命中率与耗时
 *  - 解析出的 FLAC 能否被 ExoPlayer 直接播放
 */
class LxPocActivity : ComponentActivity() {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val engines = mutableListOf<LxScriptEngine>()
    private var resolver: AudioResolver? = null
    private var player: ExoPlayer? = null

    private val logs = mutableStateListOf<String>()
    private var status by mutableStateOf("初始化中…")
    private var busy by mutableStateOf(false)
    private var current by mutableStateOf<AudioStream?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()

        setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101014))
                        .padding(40.dp)
                ) {
                    Text("LX PoC · 落雪音源直连", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = Color(0xFF9FE1CB), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SAMPLE.forEach { s ->
                            Button(enabled = !busy, onClick = { play(s) }) {
                                Text(s.title, maxLines = 1)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    current?.let { st ->
                        Text(
                            "${st.kind.uppercase()} · ${st.kbps} kbps · ${st.source}/${st.quality} · " +
                                "解析 ${st.resolveMs}ms · 校验 ${st.verifyMs}ms",
                            color = Color(0xFFFAC775),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(st.url.take(110), color = Color(0xFF888780), style = MaterialTheme.typography.labelSmall)
                    }

                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(logs) { line ->
                            Text(
                                line,
                                color = Color(0xFFD3D1C7),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        val auto = intent.getStringExtra("auto")
        lifecycleScope.launch {
            initEngines()
            auto?.split("|")?.takeIf { it.size >= 2 }?.let { p ->
                play(
                    Sample(
                        title = p[0],
                        artist = p[1],
                        album = p.getOrElse(2) { "" },
                        durationSec = p.getOrElse(3) { "0" }.toIntOrNull() ?: 0,
                    )
                )
            }
        }
    }

    private fun trace(line: String) {
        android.util.Log.i("LxPoc", line)
    }

    private suspend fun initEngines() {
        withContext(Dispatchers.IO) {
            val dir = File(filesDir, "luoxue").apply { mkdirs() }
            if (dir.listFiles { f -> f.name.endsWith(".js") }?.isEmpty() != false) {
                runCatching {
                    assets.list("luoxue")?.forEach { name ->
                        assets.open("luoxue/$name").use { input ->
                            File(dir, name).outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
            dir.listFiles { f -> f.name.endsWith(".js") }?.sortedBy { it.name }?.forEach { f ->
                val engine = LxScriptEngine(f.name, f.readText(), http) { line ->
                    lifecycleScope.launch { logs.add(line) }
                }
                val ok = runCatching { engine.start() }.getOrDefault(false)
                if (ok) {
                    engines.add(engine)
                    lifecycleScope.launch {
                        logs.add("加载 ${engine.displayName} (${engine.meta.version}) 源=${engine.sources.keys}")
                    trace("ENGINE ${engine.fileName} ${engine.displayName} sources=${engine.sources.keys}")
                    }
                } else {
                    engine.close()
                    lifecycleScope.launch { logs.add("跳过 ${f.name}：未初始化") }
                }
            }
            resolver = AudioResolver(PlatformSearch(http), StreamVerifier(http), engines) { line ->
                lifecycleScope.launch { logs.add("  · $line") }
            }
            lifecycleScope.launch {
                status = "就绪：${engines.size} 个脚本可用，点一首试试"
                trace("READY engines=${engines.size}")
            }
        }
    }

    private fun play(song: Sample) {
        val r = resolver ?: run { trace("resolver not ready"); return }
        trace("PLAY ${song.title} / ${song.artist}")
        busy = true
        current = null
        logs.add("▶ ${song.title} — ${song.artist}")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                r.resolve(song.title, song.artist, song.album, song.durationSec) { step ->
                    lifecycleScope.launch { logs.add("  · $step") }
                }
            }
            logs.add("尝试 ${result.attempts.size} 次，总耗时 ${result.totalMs}ms")
            trace("DONE attempts=${result.attempts.size} total=${result.totalMs}ms stream=${result.stream}")
            result.attempts.takeLast(6).forEach {
                logs.add("   ${if (it.ok) "✓" else "✗"} ${it.stage}/${it.source}/${it.quality} ${it.detail}")
            }
            result.stream?.let { st ->
                current = st
                status = "播放中：${st.kind} ${st.kbps}kbps"
                val item = MediaItem.Builder()
                    .setUri(st.url)
                    .setMimeType(st.mimeType)
                    .build()
                player?.apply {
                    setMediaItem(item)
                    prepare()
                    play()
                }
            } ?: run { status = "未命中，已跳过"; trace("MISS") }
            busy = false
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        engines.forEach { runCatching { it.close() } }
        super.onDestroy()
    }

    data class Sample(val title: String, val artist: String, val album: String, val durationSec: Int)

    companion object {
        private val SAMPLE = listOf(
            Sample("晴天", "周杰伦", "叶惠美", 269),
            Sample("Anti-Hero", "Taylor Swift", "Midnights", 201),
            Sample("富士山下", "陈奕迅", "What's Going On...?", 259),
            Sample("Bohemian Rhapsody", "Queen", "A Night At The Opera", 354),
        )
    }
}
