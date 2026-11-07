package com.applemusicktv.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 顶部导航条占用的高度。所有滚动页统一用这个值，别在页面里各写一份 100.dp。 */
val TopNavInset: Dp = 100.dp
val BottomNavInset: Dp = 102.dp

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
