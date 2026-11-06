package com.applemusicktv.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A shimmering placeholder shelf list shown while Home/Browse load — no spinner. */
@Composable
fun ShelfSkeleton(rows: Int = 4, cardSize: Int = 130, topPadding: Dp = 28.dp) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(
        initialValue = 0.18f, targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "a",
    )
    // Use a gray shimmer: white-at-low-alpha disappeared against the white Home/Browse canvas.
    val bar = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)).copy(alpha = a)
    // Mirror the real Home order: a big "为你精选" lockup row (210dp squares) leads, then
    // the standard shelves. A uniform grid didn't match and the swap to real content jumped.
    Column(
        Modifier.fillMaxSize().padding(top = topPadding),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        SkeletonRow(bar, cardSize = 210, cards = 5)   // hero: Top Picks
        repeat((rows - 1).coerceAtLeast(1)) {
            SkeletonRow(bar, cardSize = cardSize, cards = 6)
        }
    }
}

@Composable
private fun SkeletonRow(bar: Color, cardSize: Int, cards: Int) {
    Column {
        Box(Modifier.padding(start = pageHorizontalInset, bottom = 14.dp)
            .width(180.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).background(bar))
        Row(Modifier.padding(start = pageHorizontalInset), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(cards) {
                Column {
                    Box(Modifier.size(cardSize.dp).clip(RoundedCornerShape(12.dp)).background(bar))
                    Box(Modifier.padding(top = 8.dp).width((cardSize * 0.7).dp).height(11.dp)
                        .clip(RoundedCornerShape(4.dp)).background(bar))
                }
            }
        }
    }
}
