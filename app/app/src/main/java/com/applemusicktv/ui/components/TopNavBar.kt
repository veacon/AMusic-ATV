package com.applemusicktv.ui.components

import com.applemusicktv.i18n.tr
import com.applemusicktv.R


import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlin.math.pow
import com.applemusicktv.ui.navigation.TopNavTab
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.components.Icon

private const val TabSelectionDurationMillis = com.applemusicktv.ui.navigation.TabMotion.durationMillis

/** Beat-reactive 3-bar equalizer beside the Now Playing tab. A gentle per-bar wobble keeps it alive
 *  between beats; the live [BeatAnalyzer] energy punches all three up on each beat (Apple-style). */
@Composable
private fun EqBars(beatAnalyzer: com.applemusicktv.media.BeatAnalyzer?) {
    val energy by (beatAnalyzer?.energy?.collectAsState() ?: remember { mutableStateOf(0f) })
    // Sensitivity: bass-onset energy peaks low, so lift small values (gamma < 1) and add gain
    // before clamping — a quiet beat should still visibly punch the bars.
    val sens = (energy.coerceIn(0f, 1f).pow(0.55f) * 1.6f).coerceIn(0f, 1f)
    // Snappy attack, graceful fall.
    val punch by animateFloatAsState(sens, tween(55, easing = LinearEasing), label = "eqPunch")
    val inf = rememberInfiniteTransition(label = "eq")
    val w1 by inf.animateFloat(0.30f, 0.70f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "w1")
    val w2 by inf.animateFloat(0.65f, 0.28f, infiniteRepeatable(tween(310, easing = LinearEasing), RepeatMode.Reverse), label = "w2")
    val w3 by inf.animateFloat(0.38f, 0.72f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "w3")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(12.dp).testTag("dynamicPlayingIndicator")) {
        listOf(w1, w2, w3).forEach { wob ->
            val frac = (wob * 0.28f + punch * 0.9f).coerceIn(0.18f, 1f)
            Box(Modifier.width(2.dp).fillMaxHeight(frac).clip(RoundedCornerShape(1.dp)).background(Color(0xFFFA233B)))
        }
    }
}

/** Focus selects a listening destination immediately, including the avatar/settings destination. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopNavBar(
    selected: TopNavTab,
    onSelect: (TopNavTab) -> Unit,
    isPlaying: Boolean = false,
    beatAnalyzer: com.applemusicktv.media.BeatAnalyzer? = null,
    modifier: Modifier = Modifier,
    contentFocusRequester: FocusRequester? = null,
    tabFocusRequesters: Map<TopNavTab, FocusRequester> = emptyMap(),
    onTabFocusChanged: (TopNavTab, Boolean) -> Unit = { _, _ -> },
    avatarUrl: String? = null,
    onAvatarLoadError: () -> Unit = {},
    focusEntryId: Int = 0,
    navigationHasFocus: Boolean = true,
    focusEnabled: Boolean = true,
) {
    val selectionTransition = updateTransition(selected, label = "topTabSelection")
    var pillTab by remember { mutableStateOf(selected) }
    val inputModeManager=androidx.compose.ui.platform.LocalInputModeManager.current
    val localTabFocus = remember { TopNavTab.entries.associateWith { FocusRequester() } }
    val tabFocus = remember(tabFocusRequesters) { TopNavTab.entries.associateWith {
        tabFocusRequesters[it] ?: localTabFocus.getValue(it)
    } }
    val avatarFocus = tabFocus.getValue(TopNavTab.Dev)
    val visibleTabs = remember { listOf(TopNavTab.NowPlaying, TopNavTab.ListenNow, TopNavTab.Browse,
        TopNavTab.Library, TopNavTab.Search) }
    val shape = RoundedCornerShape(50)
    val tabBounds = remember { mutableStateMapOf<TopNavTab, Rect>() }
    var avatarFocused by remember { mutableStateOf(false) }
    var capsuleOrigin by remember { mutableStateOf(Offset.Zero) }
    val selectedTarget = tabBounds[selected]?.let { rect ->
        Rect(rect.left - capsuleOrigin.x, rect.top - capsuleOrigin.y,
            rect.right - capsuleOrigin.x, rect.bottom - capsuleOrigin.y)
    }
    val pillX = remember { Animatable(0f) }; val pillY = remember { Animatable(0f) }
    val pillWidth = remember { Animatable(0f) }; val pillHeight = remember { Animatable(0f) }
    var pillReady by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTarget, selected) {
        val previousTab = pillTab
        pillTab = selected
        val rect = selectedTarget ?: return@LaunchedEffect
        if (!pillReady) {
            pillX.snapTo(rect.left); pillY.snapTo(rect.top)
            pillWidth.snapTo(rect.width); pillHeight.snapTo(rect.height); pillReady = true
        } else coroutineScope {
            val duration = com.applemusicktv.ui.navigation.TabMotion.selectionDuration(previousTab, selected)
            val motion = tween<Float>(duration, easing = com.applemusicktv.ui.navigation.TabMotion.easing)
            launch { pillX.animateTo(rect.left, motion) }; launch { pillY.animateTo(rect.top, motion) }
            launch { pillWidth.animateTo(rect.width, motion) }; launch { pillHeight.animateTo(rect.height, motion) }
        }
    }
    // Fade in for 200ms, hold for 300ms, then fade out for 200ms.
    val focusBorderAlpha = remember { Animatable(0f) }
    LaunchedEffect(focusEntryId, navigationHasFocus) {
        val visible = focusEntryId > 0 && navigationHasFocus
        if (!visible) focusBorderAlpha.snapTo(0f)
        if (visible) {
            focusBorderAlpha.snapTo(0f)
            focusBorderAlpha.animateTo(1f, tween(200, easing = LinearEasing))
            kotlinx.coroutines.delay(300)
            focusBorderAlpha.animateTo(0f, tween(200, easing = LinearEasing))
        }
    }
    val avatarActive = avatarFocused || selected == TopNavTab.Dev
    val avatarScale by animateFloatAsState(if (avatarActive) 1.10f else 1f, tween(TabSelectionDurationMillis), label = "avatarFocusScale")

    // Rebalance the existing 20dp vertical padding: content moves down by 8dp,
    // while the complete header still occupies 70dp and never pushes the page down.
    BoxWithConstraints(modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center) {
        val avatarGap = 14.4.dp
        val capsuleMaxWidth = (maxWidth - 50.dp - avatarGap).coerceAtLeast(0.dp)
        Row(Modifier.testTag("topNavGroup"), horizontalArrangement = Arrangement.spacedBy(avatarGap),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(max = capsuleMaxWidth)
                .testTag("topNavCapsule")
                .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF0F0F3), Color(0xFF29292D)), shape)
                .border(0.75.dp, com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFDADADD), Color(0xFF48484D)), shape)
                .onGloballyPositioned { capsuleOrigin = it.positionInWindow() }) {
                // A single white pill represents both keyboard focus and selection.
                Canvas(Modifier.matchParentSize()) {
                    if (selectedTarget != null && pillReady) {
                        val origin = Offset(pillX.value, pillY.value)
                        val pillSize = Size(pillWidth.value, pillHeight.value)
                        drawRoundRect(com.applemusicktv.ui.theme.AppPalette.adaptive(Color.White, Color(0xFF48484D)), topLeft = origin, size = pillSize,
                            cornerRadius = CornerRadius(pillHeight.value / 2f))
                        if (focusBorderAlpha.value > 0f) {
                            val stroke = 1.5.dp.toPx()
                            drawRoundRect(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFC7C7CC), Color(0xFF707078)).copy(alpha = focusBorderAlpha.value),
                                topLeft = origin + Offset(stroke / 2f, stroke / 2f),
                                size = Size((pillSize.width - stroke).coerceAtLeast(0f), (pillSize.height - stroke).coerceAtLeast(0f)),
                                cornerRadius = CornerRadius((pillHeight.value - stroke) / 2f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        }
                    }
                }
                Row(Modifier.selectableGroup().padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    visibleTabs.forEachIndexed { index, tab -> key(tab) {
                        val active = selected == tab
                        val ink by selectionTransition.animateColor(
                            transitionSpec = { tween(
                                com.applemusicktv.ui.navigation.TabMotion.selectionDuration(initialState, targetState),
                                easing = com.applemusicktv.ui.navigation.TabMotion.easing) },
                            label = "tabInk:${tab.name}",
                        ) { target -> if (target == tab) com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFA233B), Color(0xFFFF6B78)) else com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE3E3E8)) }
                        Row(Modifier.focusRequester(tabFocus.getValue(tab))
                            .onGloballyPositioned {
                                val at = it.positionInWindow()
                                val bounds = Rect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                                if (tabBounds[tab] != bounds) tabBounds[tab] = bounds
                            }
                            .onFocusChanged {
                                onTabFocusChanged(tab, it.isFocused)
                                if (it.isFocused && inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard) onSelect(tab)
                            }
                            .focusProperties {
                                canFocus = focusEnabled
                                left = if (index == 0) FocusRequester.Cancel else tabFocus.getValue(visibleTabs[index - 1])
                                right = if (index == visibleTabs.lastIndex) avatarFocus else tabFocus.getValue(visibleTabs[index + 1])
                                contentFocusRequester?.let { down = it }
                            }
                            .clip(shape)
                            .selectable(active, role = Role.Tab, interactionSource = remember { MutableInteractionSource() },
                                indication = null, onClick = { onSelect(tab) })
                            .semantics { contentDescription = tab.label }
                            .heightIn(min = 40.dp).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (tab == TopNavTab.Search) Icon(Glyph.SEARCH, size = 19.dp, color = ink)
                            else Text(tab.label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            if (tab == TopNavTab.NowPlaying && isPlaying) EqBars(beatAnalyzer)
                        }
                    } }
                }
            }
            Box(Modifier.focusRequester(avatarFocus)
                .onFocusChanged {
                    avatarFocused = it.isFocused
                    onTabFocusChanged(TopNavTab.Dev, it.isFocused)
                    if (it.isFocused && inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard) onSelect(TopNavTab.Dev)
                }
                .focusProperties {
                    canFocus = focusEnabled
                    left = tabFocus.getValue(TopNavTab.Search); right = FocusRequester.Cancel
                    contentFocusRequester?.let { down = it }
                }
                .graphicsLayer { scaleX = avatarScale; scaleY = avatarScale }
                .border(if (avatarActive) 1.5.dp else 0.dp,
                    if (avatarActive) com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFB8B8BD), Color(0xFF77777F)) else Color.Transparent,
                    androidx.compose.foundation.shape.CircleShape)
                .selectable(selected == TopNavTab.Dev, role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { onSelect(TopNavTab.Dev) })
                .semantics { contentDescription = tr(R.string.msg_292) }
                .padding(3.dp)) {
                ProfileAvatar(avatarUrl, onLoadError = onAvatarLoadError)
            }
        }
    }
}
