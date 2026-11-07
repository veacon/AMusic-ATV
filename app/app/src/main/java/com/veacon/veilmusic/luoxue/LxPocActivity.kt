package com.veacon.veilmusic.luoxue

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
import com.veacon.veilmusic.data.model.Song
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 落雪音源自助诊断入口（桌面图标 "LX PoC"）。
 *
 * P1 起它不再是"另一条链路"：和主播放器共用同一个 [LxPlayback] 单例，
 * 这里点一首歌就等于走一遍主播放器的解析路径，用来在真机/模拟器上快速判定：
 *  - 脚本能不能起来（QuickJS + lx.request）
 *  - 搜索/匹配/级联/校验的实际命中率与耗时
 *  - 解析出的 FLAC 能否被 ExoPlayer 直接播
 */
class LxPocActivity : ComponentActivity() {

    private lateinit var lx: LxPlayback
    private var player: ExoPlayer? = null

    private val logs = mutableStateListOf<String>()
    private var status by mutableStateOf("初始化中…")
    private var busy by mutableStateOf(false)
    private var current by mutableStateOf<AudioStream?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        lx = EntryPointAccessors.fromApplication(applicationContext, LxEntryPoint::class.java).lxPlayback()
        lx.logger = { line -> runOnUiThread { if (logs.size > 200) logs.removeAt(0); logs.add(line) } }

        setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101014))
                        .padding(40.dp)
                ) {
                    Text("LX 音源诊断", color = Color.White, style = MaterialTheme.typography.headlineSmall)
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

        // 跑测模式：`am start ... --ez bench true`，对 files/bench 下所有脚本做全量取链，
        // 结果写 files/bench.csv（并复制到外部目录，方便 adb pull）。
        if (intent.getBooleanExtra("bench", false)) {
            lifecycleScope.launch { runBench() }
            return
        }

        val auto = intent.getStringExtra("auto")
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { lx.ensureReady() }
            status = if (ready) "就绪：${lx.describe()}" else "脚本初始化失败：${lx.statsJson()}"
            android.util.Log.i("LxPoc", "READY=$ready ${lx.describe()}")
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

    private suspend fun runBench() {
        // 优先用外部目录（adb push 直接可写），省掉 run-as 拷贝；
        // 没有就退回内部目录。
        val ext = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        val int = File(filesDir, "bench").apply { mkdirs() }
        val hasScripts = { d: File -> d.listFiles { f -> f.isFile && f.name.endsWith(".js", true) }?.isNotEmpty() == true }
        val dir = if (hasScripts(ext)) ext else int
        status = "跑测中…（${dir.absolutePath}）"
        val csv = File(filesDir, "bench.csv")
        val quality = intent.getStringExtra("quality") ?: "320k"
        val conc = intent.getIntExtra("c", 5)
        val tracks = intent.getStringExtra("tracks")
            ?.split(";")?.mapNotNull { t ->
                val p = t.split("|")
                if (p.size >= 2) LxBench.Track(p[0], p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "0" }.toIntOrNull() ?: 0) else null
            }?.takeIf { it.isNotEmpty() } ?: LxBench.DEFAULT_TRACKS
        val bench = LxBench(dir, tracks, quality = quality, concurrency = conc) { line ->
            runOnUiThread { if (logs.size > 400) logs.removeAt(0); logs.add(line) }
            android.util.Log.i("LxBench", line)
        }
        val result = withContext(Dispatchers.IO) { bench.run(csv) }
        runCatching { File(getExternalFilesDir(null), "bench.csv").writeText(csv.readText()) }
        status = result
        android.util.Log.i("LxBench", "RESULT $result")
        android.util.Log.i("LxBench", "CSV ${csv.absolutePath}")
    }

    private fun trace(line: String) {
        android.util.Log.i("LxPoc", line)
    }

    private fun play(sample: Sample) {
        trace("PLAY ${sample.title} / ${sample.artist}")
        busy = true
        current = null
        logs.add("▶ ${sample.title} — ${sample.artist}")
        lifecycleScope.launch {
            val song = Song(
                id = "poc-${sample.title}",
                title = sample.title,
                artistName = sample.artist,
                albumName = sample.album,
                durationMs = sample.durationSec * 1000L,
                artworkUrl = null,
                artworkBgColor = null,
                previewUrl = null,
            )
            val t0 = System.currentTimeMillis()
            val stream = withContext(Dispatchers.IO) { lx.resolve(song, force = true) }
            val elapsed = System.currentTimeMillis() - t0
            logs.add("解析结果：${stream?.kind ?: "MISS"} 用时 ${elapsed}ms")
            trace("DONE stream=$stream total=${elapsed}ms")
            if (stream != null) {
                current = stream
                status = "播放中：${stream.kind} ${stream.kbps}kbps（${stream.source}）"
                player?.apply {
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(stream.url)
                            .setMimeType(stream.mimeType.takeIf { it.isNotBlank() })
                            .build()
                    )
                    prepare()
                    play()
                }
            } else {
                status = "未命中：${lx.describe()}"
                trace("MISS")
            }
            busy = false
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
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
