package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.CubicBezierEasing
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import com.applemusicktv.ui.components.LocalUiMotion
import com.applemusicktv.ui.viewmodel.PlayerState
import com.applemusicktv.ui.viewmodel.LyricsNoticePhase
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

internal const val PlayerMotionDurationMs = 360
internal fun playerMotionDuration(enabled: Boolean): Int = if (enabled) PlayerMotionDurationMs else 0

internal const val PlayerColumnMotionDurationMs = 500
internal val PlayerColumnEasing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/** A manually opened lyrics view returns within the player; portrait remains forced full-screen. */
@Composable
internal fun FullScreenLyricsBackHandler(preferred: Boolean, forced: Boolean, onReturnToSplit: () -> Unit) {
    BackHandler(enabled = preferred && !forced, onBack = onReturnToSplit)
}

internal data class PlayerColumnSpacing(val side: Dp, val gap: Dp)

internal fun playerColumnSpacing(width: Dp, side: Dp, gap: Dp, cover: Dp): PlayerColumnSpacing {
    val originalLyricsWidth = (width - side * 2 - gap - cover).coerceAtLeast(0.dp)
    val extra = originalLyricsWidth * 0.15f / 3
    return PlayerColumnSpacing(side + extra, gap + extra)
}

/** Panel overrides do not change the session's stable lyrics-layout decision. */
internal fun centerPlayerControls(state: PlayerState, hidden: Boolean, queueVisible: Boolean,
    fontsReady: Boolean, settledCentered: Boolean = false): Boolean {
    if(state.currentSong==null || state.stationLaunch!=null || queueVisible) return false
    return hidden || (fontsReady && settledCentered && state.lyrics.isEmpty())
}

/** Outgoing transition snapshots must never revive another selection's empty notice. */
internal fun emptyLyricsNoticeVisible(snapshot: PlayerState, current: PlayerState): Boolean {
    val id = current.currentSong?.id ?: return false
    return snapshot.currentSong?.id == id && snapshot.lyricsNoticeSongId == id &&
        snapshot.lyricsNoticeGeneration == current.lyricsNoticeGeneration &&
        current.lyricsNoticeSongId == id && current.lyricsNotice == LyricsNoticePhase.Visible &&
        current.exhaustedEmptyLyricsFor == id && current.lyrics.isEmpty() &&
        current.stationLaunch == null && !current.isLiveRadio && current.currentSong.isMusicVideo == false
}

/** Keep both subtrees mounted while moving the existing control column horizontally. */
@Composable
internal fun PlayerSplitLayout(viewportWidth: Dp, coverWidth: Dp, spacing: PlayerColumnSpacing,
    centered: Boolean, modifier: Modifier=Modifier, controls: @Composable ()->Unit,
    panel: @Composable BoxScope.()->Unit) {
    val motion=LocalUiMotion.current
    val start by animateDpAsState(if(centered) (viewportWidth-coverWidth)/2 else spacing.side,
        tween(if (motion.enabled) PlayerColumnMotionDurationMs else 0, easing=PlayerColumnEasing),label="playerColumnStart")
    val gap by animateDpAsState(if(centered) 0.dp else spacing.gap,
        tween(if (motion.enabled) PlayerColumnMotionDurationMs else 0, easing=PlayerColumnEasing),label="playerColumnGap")
    Row(modifier.fillMaxSize().padding(start=start,end=spacing.side,top=64.dp,bottom=32.dp),
        horizontalArrangement=Arrangement.spacedBy(gap),verticalAlignment=Alignment.CenterVertically) {
        controls()
        Box(Modifier.weight(1f).fillMaxHeight(),content=panel)
    }
}

/** Loading postpones hiding visible controls; automatic track/rebuffer changes never wake hidden controls. */
@Composable
internal fun PlayerChromeAutoHide(
    songId: String?, loading: Boolean, playing: Boolean, interaction: Long,
    popupOpen: Boolean, onVisibility: (Boolean) -> Unit,
) {
    var started by remember(songId) { mutableStateOf(false) }
    val publish by rememberUpdatedState(onVisibility)
    var visible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(interaction) }
    LaunchedEffect(songId, loading, playing) {
        if (loading) started = false
        else if (playing) started = true
    }
    LaunchedEffect(songId, loading, started, interaction, popupOpen) {
        val userInteracted = interaction != lastInteraction
        lastInteraction = interaction
        if (userInteracted || popupOpen) visible = true
        publish(visible)
        if (!visible || songId == null || loading || !started || popupOpen) return@LaunchedEffect
        delay(8_000L)
        visible = false
        publish(false)
    }
}

/** Visibility is owned by the player; remounting this text never restarts its timer. */
@Composable
internal fun EmptyLyrics(songId: String, fontScale: Float, visible: Boolean = true, centered: Boolean = false) {
    if (visible && !centered) PlayerStatusText(tr(R.string.msg_093), fontScale)
}

@Composable
internal fun StationLaunchMessage(failed: Boolean, fontScale: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PlayerStatusText(if (failed) tr(R.string.msg_094) else tr(R.string.msg_095), fontScale)
    }
}

@Composable
internal fun PlayerStatusText(message: String, fontScale: Float, modifier: Modifier = Modifier.padding(start = 8.dp, top = 32.dp)) {
    Text(message, fontSize = (32f * fontScale).sp,
        lineHeight = (44f * fontScale).sp, fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.40f), modifier = modifier)
}

/** Keep the hidden player parked on Play, without re-focusing on track changes or wake-up. */
@Composable
internal fun PlayerPlayFocusEffect(focus: androidx.compose.ui.focus.FocusRequester, visible: Boolean, suppressInitialFocus: Boolean = false) {
    var initial by remember {mutableStateOf(true)}
    LaunchedEffect(visible) {
        if(!initial && visible) return@LaunchedEffect
        initial=false
        if (suppressInitialFocus) return@LaunchedEffect
        repeat(8) {
            withFrameNanos {}
            if(runCatching {focus.requestFocus()}.getOrDefault(false)) return@LaunchedEffect
        }
    }
}
