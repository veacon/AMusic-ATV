package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频音质档位。
 *
 * [ladder] 是「请求 → 兜底」的降级顺序，交给落雪脚本按平台可用列表过滤；
 * [minKbps] 是直链校验门槛：低于它的流会被判定为试听片段/假档位并换源。
 * 之所以高品质也要 96 而不是 200，是因为 320k 拿不到时会退 128k，
 * 200 的门槛会把这个兜底流当成试听片段误杀。
 */
enum class AudioQuality(
    val key: String,
    val ladder: List<String>,
    val minKbps: Int,
) {
    /** 128 kbps：省流量，弱网更稳。 */
    STANDARD("128k", listOf("128k"), 96),

    /** 320 kbps：默认档，体积与兼容性平衡最好。 */
    HIGH("320k", listOf("320k", "128k"), 96),

    /** 无损：优先 FLAC，拿不到再退 320k。 */
    LOSSLESS("flac", listOf("flac24bit", "flac", "320k"), 200);

    /**
     * 遥控器左右键走这里。刻意用「夹住」而不是循环：
     * 循环会让 128k 按一下左键直接跳到无损，容易让人不知道自己在哪一档；
     * 夹住时按钮保持可用，焦点不会因为禁用态被跳过。
     */
    fun next(direction: Int): AudioQuality {
        val i = (ordinal + direction.coerceIn(-1, 1)).coerceIn(0, entries.lastIndex)
        return entries[i]
    }

    companion object {
        val DEFAULT = HIGH

        fun fromKey(key: String?): AudioQuality =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 音质选择。落雪解析层与设置页共用，改档即时生效（清解析缓存）。 */
@Singleton
class AudioQualityPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("audio_quality_prefs", Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(AudioQuality.fromKey(prefs.getString(KEY, null)))
    val quality: StateFlow<AudioQuality> = _quality

    fun get(): AudioQuality = _quality.value

    fun set(quality: AudioQuality) {
        if (quality == _quality.value) return
        prefs.edit { putString(KEY, quality.key) }
        _quality.value = quality
    }

    private companion object {
        const val KEY = "quality"
    }
}
