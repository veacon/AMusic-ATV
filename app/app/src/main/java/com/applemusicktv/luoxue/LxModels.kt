package com.applemusicktv.luoxue

/** 平台侧命中的候选曲目（由自建搜索层产出，用于补齐落雪脚本所需的平台 ID）。 */
data class Candidate(
    val source: String,          // wy / tx / kg
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val extra: Map<String, String> = emptyMap(),
    var score: Int = 0,
)

/** 解析出的可播放音频流。 */
data class AudioStream(
    val url: String,
    val kind: String,            // flac / mp3 / mp4/aac
    val kbps: Int,
    val mimeType: String,
    val source: String,          // 平台
    val script: String,          // 命中的脚本文件
    val quality: String,         // 请求的音质档
    val resolveMs: Long,
    val verifyMs: Long,
)

/** 单次解析尝试记录，用于诊断页与命中率统计。 */
data class ResolveAttempt(
    val stage: String,           // search / resolve / verify
    val source: String = "",
    val script: String = "",
    val quality: String = "",
    val ok: Boolean = false,
    val detail: String = "",
    val ms: Long = 0,
)

class ResolveResult(
    val stream: AudioStream?,
    val attempts: List<ResolveAttempt>,
    val candidate: Candidate?,
    val totalMs: Long,
)
