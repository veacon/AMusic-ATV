package com.applemusicktv.luoxue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配层回归：这三条对应 P0 实测中真实踩到的坑。
 * 纯 JVM 单测（无 Robolectric）：Build.VERSION.SDK_INT = 0，走内置繁简兜底表，
 * 正好验证 API 23 那条路径。
 */
class TrackMatcherTest {

    private fun c(
        source: String = "tx",
        id: String = "1",
        title: String,
        artist: String,
        album: String = "",
        dur: Int,
    ) = Candidate(source, id, title, artist, album, dur)

    @Test
    fun `繁体标题与英文艺人名可以匹配`() {
        // Apple: 後來的我們 / Mayday ；平台: 后来的我们 / 五月天
        val cand = c(title = "后来的我们", artist = "五月天", album = "自传", dur = 346)
        val ranked = TrackMatcher.rank(listOf(cand), "後來的我們", "Mayday", "自傳", 346)
        assertEquals(1, ranked.size)
        assertTrue("score=${ranked[0].score}", ranked[0].score >= 45)
        assertTrue(TrackMatcher.isAcceptable(ranked[0]))
    }

    @Test
    fun `跨语言曲名靠时长兜底`() {
        // Apple: Wayfarer / No Party for Cao Dong ；平台: 山海 / 草东没有派对
        val wrong = c(id = "a", title = "Wayfarer", artist = "Kavinsky", dur = 269)
        val right = c(id = "b", title = "山海", artist = "草东没有派对", dur = 251)
        val ranked = TrackMatcher.rank(listOf(wrong, right), "Wayfarer", "No Party for Cao Dong", "", 251)
        assertTrue("标题匹配无法命中，得分应低于阈值", ranked.none { TrackMatcher.isAcceptable(it) })

        val fallback = TrackMatcher.rankByDurationOnly(listOf(wrong, right), "No Party for Cao Dong", 251)
        assertEquals("时长兜底应选中 251s 的《山海》", "b", fallback.first().id)
    }

    @Test
    fun `版本不符要降权`() {
        val studio = c(id = "s", title = "Into The Night", artist = "YOASOBI", dur = 262)
        val live = c(id = "l", title = "Into The Night (Live)", artist = "YOASOBI", dur = 293)
        val ranked = TrackMatcher.rank(listOf(live, studio), "Into The Night", "YOASOBI", "", 293)
        // 目标本身是 live，此时 live 不应被惩罚；改成目标是录音室版再验一次
        val ranked2 = TrackMatcher.rank(listOf(live, studio), "Into The Night", "YOASOBI", "", 262)
        assertEquals("录音室版应排在前面", "s", ranked2.first().id)
        assertTrue(ranked.isNotEmpty())
    }

    @Test
    fun `同曲不同版本按时长区分`() {
        val a = c(id = "a", title = "晴天", artist = "周杰伦", dur = 269)
        val b = c(id = "b", title = "晴天 (Live)", artist = "周杰伦", dur = 249)
        val ranked = TrackMatcher.rank(listOf(b, a), "晴天", "周杰伦", "叶惠美", 269)
        assertEquals("a", ranked.first().id)
    }
}
