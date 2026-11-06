package com.applemusicktv.luoxue

import android.os.Build

/**
 * 曲目匹配：把 Apple 元数据与平台候选对齐。
 *
 * P0 实测暴露的三个坑，这里逐一处理：
 *  1) 繁简差异（後來的我們 / 后来的我们）→ ICU 繁转简 + 常见字兜底
 *  2) 艺人英文名（Mayday / 五月天）→ 别名表
 *  3) 跨语言曲名（Wayfarer / 山海）→ 无法靠标题，退化到"艺人+时长"第二遍搜索
 */
object TrackMatcher {

    private const val MIN_ACCEPT = 45

    private val VERSION_WORDS = listOf(
        "live", "remix", "piano", "cover", "karaoke", "instrumental", "acoustic", "dj", "demo",
        "remaster", "remastered", "deluxe", "edition", "version", "explicit", "mono", "stereo",
    )

    /** 常见繁体 → 简体（API 23 无 ICU 时使用；24+ 走 Transliterator）。 */
    private val T2S = mapOf(
        '們' to '们', '後' to '后', '來' to '来', '傳' to '传', '這' to '这', '個' to '个', '時' to '时',
        '說' to '说', '愛' to '爱', '願' to '愿', '夢' to '梦', '聲' to '声', '與' to '与', '為' to '为',
        '無' to '无', '誰' to '谁', '過' to '过', '還' to '还', '開' to '开', '關' to '关', '讓' to '让',
        '對' to '对', '從' to '从', '會' to '会', '學' to '学', '國' to '国', '樂' to '乐', '團' to '团',
        '隊' to '队', '聽' to '听', '寫' to '写', '絕' to '绝', '續' to '续', '變' to '变', '張' to '张',
        '陳' to '陈', '鄧' to '邓', '黃' to '黄', '劉' to '刘', '蕭' to '萧', '楊' to '杨', '葉' to '叶',
        '蘇' to '苏', '龍' to '龙', '鳳' to '凤', '齊' to '齐', '瑪' to '玛', '貝' to '贝', '爾' to '尔',
        '維' to '维', '亞' to '亚', '歐' to '欧', '陽' to '阳', '華' to '华', '萬' to '万', '億' to '亿',
        '點' to '点', '線' to '线', '麵' to '面', '髮' to '发', '鬥' to '斗', '氣' to '气', '風' to '风',
        '雲' to '云', '電' to '电', '車' to '车', '馬' to '马', '鳥' to '鸟', '魚' to '鱼', '歲' to '岁',
    )

    /** Apple 常用英文艺人名 → 平台中文名（按需扩充，先覆盖高频）。 */
    private val ARTIST_ALIAS = mapOf(
        "mayday" to "五月天",
        "jay chou" to "周杰伦",
        "g.e.m." to "邓紫棋",
        "gem" to "邓紫棋",
        "eason chan" to "陈奕迅",
        "jolin tsai" to "蔡依林",
        "jj lin" to "林俊杰",
        "a-mei" to "张惠妹",
        "amei" to "张惠妹",
        "fish leong" to "梁静茹",
        "stefanie sun" to "孙燕姿",
        "wang leehom" to "王力宏",
        "lee hom wang" to "王力宏",
        "no party for cao dong" to "草东没有派对",
        "sunset rollercoaster" to "落日飞车",
        "accusefive" to "告五人",
        "hins cheung" to "张敬轩",
        "khalil fong" to "方大同",
        "yoga lin" to "林宥嘉",
        "hebe tien" to "田馥甄",
        "sodagreen" to "苏打绿",
        "escape plan" to "逃跑计划",
        "new pants" to "新裤子",
    )

    fun normalize(raw: String): String {
        var s = raw.lowercase()
        s = if (Build.VERSION.SDK_INT >= 24) {
            runCatching {
                android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate(s)
            }.getOrDefault(s)
        } else {
            s.map { T2S[it] ?: it }.joinToString("")
        }
        s = s.replace(Regex("\\(.*?\\)|\\[.*?\\]|（.*?）|【.*?】"), " ")
        s = s.replace(Regex("[\\s\\-_/·,，.。'’\"“”!！?？:：&+]"), "")
        VERSION_WORDS.forEach { s = s.replace(it, "") }
        return s
    }

    private fun normArtist(raw: String): String {
        val first = raw.split("&", ",", "、", "/").first().trim()
        val lower = first.lowercase()
        return normalize(ARTIST_ALIAS[lower] ?: lower)
    }

    private fun bigramScore(target: String, cand: String): Int {
        if (target.isEmpty() || cand.isEmpty()) return 0
        val a = (0 until target.length - 1).map { target.substring(it, it + 2) }.toSet()
        val b = (0 until cand.length - 1).map { cand.substring(it, it + 2) }.toSet()
        if (a.isEmpty()) return 0
        return (30.0 * a.count { it in b } / a.size).toInt()
    }

    fun score(cand: Candidate, title: String, artist: String, album: String, durationSec: Int): Int {
        val nt = normalize(title)
        val nc = normalize(cand.title)
        var s = when {
            nt == nc -> 55
            nc.contains(nt) || nt.contains(nc) -> 38
            else -> bigramScore(nt, nc)
        }

        val at = normArtist(artist)
        val ac = normArtist(cand.artist)
        var artistOk = false
        if (at.isNotEmpty() && ac.isNotEmpty()) {
            s += when {
                at == ac -> { artistOk = true; 25 }
                ac.contains(at) || at.contains(ac) -> { artistOk = true; 18 }
                normalize(artist).contains(normalize(cand.artist)) -> { artistOk = true; 12 }
                else -> 0
            }
        }

        if (album.isNotBlank() && cand.album.isNotBlank() && normalize(album) == normalize(cand.album)) s += 8

        var durDiff = -1
        if (durationSec > 0 && cand.durationSec > 0) {
            durDiff = kotlin.math.abs(durationSec - cand.durationSec)
            s += when {
                durDiff <= 2 -> 12
                durDiff <= 5 -> 8
                durDiff <= 10 -> 3
                durDiff > 30 -> -10
                else -> 0
            }
        }

        // 同名不同曲：标题撞上但艺人完全不符、时长又对不上时，不能算命中
        // （P0 例：Apple 的 Wayfarer 被匹配到另一位歌手的同名曲）
        if (!artistOk && durDiff > 5) s -= 20

        VERSION_WORDS.forEach { w ->
            if (nc.contains(w) && !nt.contains(w)) s -= 14
        }
        return s
    }

    /** 标题+艺人一遍匹配。 */
    fun rank(
        cands: List<Candidate>,
        title: String,
        artist: String,
        album: String = "",
        durationSec: Int = 0,
    ): List<Candidate> {
        cands.forEach { it.score = score(it, title, artist, album, durationSec) }
        return cands.sortedByDescending { it.score }
    }

    /** 跨语言曲名兜底：只靠时长（±3s）+ 艺人弱匹配。 */
    fun rankByDurationOnly(
        cands: List<Candidate>,
        artist: String,
        durationSec: Int,
    ): List<Candidate> {
        return cands.map { c ->
            var s = 0
            if (durationSec > 0 && c.durationSec > 0) {
                val d = kotlin.math.abs(durationSec - c.durationSec)
                s += if (d <= 2) 50 else if (d <= 3) 40 else 0
            }
            val at = normArtist(artist)
            val ac = normArtist(c.artist)
            if (at.isNotEmpty() && ac.isNotEmpty() && (at == ac || ac.contains(at) || at.contains(ac))) s += 20
            c.score = s
            c
        }.filter { it.score >= 40 }.sortedByDescending { it.score }
    }

    fun isAcceptable(c: Candidate) = c.score >= MIN_ACCEPT
}
