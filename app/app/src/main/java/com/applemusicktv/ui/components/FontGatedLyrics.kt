package com.applemusicktv.ui.components

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.applemusicktv.data.fonts.FontDownloadState
import com.applemusicktv.data.fonts.FontDownloadPhase
import com.applemusicktv.ui.theme.AppPalette

/** Both normal and full-screen lyrics share the same all-fonts-ready gate. */
@Composable
internal fun FontGatedLyrics(state: FontDownloadState, modifier: Modifier = Modifier, fontScale: Float = 1f, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.ready) content()
        else Column(Modifier.width(IntrinsicSize.Max)) {
            Text(if (state.phase == FontDownloadPhase.Loading) tr(R.string.msg_301, state.percent) else state.lyricsMessage,
                color = Color.White.copy(alpha = 0.65f), fontSize = (32f * fontScale).sp,
                lineHeight = (44f * fontScale).sp, fontWeight = FontWeight.Bold)
            if (state.phase == FontDownloadPhase.Loading) {
                Spacer(Modifier.height(14.dp))
                val progress = state.percent / 100f
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(AppPalette.accent.copy(alpha = 0.2f)).semantics {
                        contentDescription = tr(R.string.msg_302)
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    }) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxSize().background(AppPalette.accent))
                }
            }
        }
    }
}
