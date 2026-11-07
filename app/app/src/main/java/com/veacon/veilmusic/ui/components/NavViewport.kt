package com.veacon.veilmusic.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 顶部导航条实际占用的高度：Bar 自身 70dp（18 上边距 + 40 胶囊 + 2 下边距 + 内边距），
 * 再加 26dp 呼吸间距。所有滚动页统一用这个值，别在页面里各写一份 100.dp。
 */
val TopNavInset: Dp = 96.dp

/**
 * 页面底部留白。
 *
 * 这里曾经是 102dp，为一条早就没有挂载的底部播放条预留 —— 结果每一页都白白
 * 少掉 10% 的高度，最后一行永远被切在屏幕下沿（用户报的「下侧遮挡 / 没法全屏」）。
 * 现在没有任何常驻底栏，只需要给聚焦放大和阴影留一点呼吸位。
 */
val BottomNavInset: Dp = 32.dp

/**
 * 把导航条占用的高度放在「视口」而不是「滚动内容」里。
 *
 * 写成 `contentPadding = PaddingValues(top = 100.dp)` 时，100.dp 仍属于
 * LazyColumn 的视口；Compose 的 bring-into-view 在遥控器聚焦时会把当前行
 * 滚到视口顶端 y=0，于是卡片上半截被浮在屏幕上方的导航条压住。
 * 设置页用「padding 在 verticalScroll 之外」规避过同一问题，这里把做法
 * 统一成所有滚动页共用的扩展：`fillMaxSize().navAwareViewport()`。
 *
 * 注意要在 `fillMaxSize()` **之后**调用，padding 才会缩小可滚动视口本身。
 */
fun Modifier.navAwareViewport(bottom: Dp = BottomNavInset): Modifier =
    this.padding(top = TopNavInset, bottom = bottom)
