package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.annotation.OptIn
import com.applemusicktv.ui.components.FadeVisibility
import com.applemusicktv.ui.components.FadeThrough
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.layout.*
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.applemusicktv.ui.viewmodel.MV_QUALITY_TIERS
import com.applemusicktv.ui.viewmodel.MusicVideoViewModel
import com.applemusicktv.ui.viewmodel.qualityLabel
import com.applemusicktv.ui.components.TouchSeekBar
import com.applemusicktv.ui.components.touchClick
import com.applemusicktv.util.TvDevice
import kotlinx.coroutines.delay

/** One focusable control in the video player. */
private enum class MvTarget { ARTIST, AUDIO, QUALITY, QUEUE, SCRUB, PREV, PLAYPAUSE, NEXT }
private enum class MvPicker { NONE, AUDIO, QUALITY }

@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
fun MusicVideoScreen(
    vm: MusicVideoViewModel,
    onExit: () -> Unit,
    onFocusUp: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    showOnScreenControls: Boolean = false,   // Google TV remotes: draw prev/play/next on screen
    queue: List<com.applemusicktv.data.model.Song> = emptyList(),
    queueIndex: Int = 0,
    onPickQueueItem: (Int) -> Unit = {},
    focusRequester: FocusRequester,
    navVm: com.applemusicktv.ui.viewmodel.NavigationViewModel? = null,
) {
    val state by vm.state.collectAsState()
    val cues by vm.cues.collectAsState()
    val showQueue by vm.showQueue.collectAsState()
    val touchControls = TvDevice.usesTouchControls(LocalContext.current)
    var queueCursor by remember(showQueue) { mutableIntStateOf(queueIndex.coerceAtLeast(0)) }

    var controls by remember { mutableStateOf(true) }
    val interaction = navVm?.playerInteraction?.collectAsState()?.value ?: 0L
    val wakeKeys = remember {com.applemusicktv.ui.navigation.PlayerWakeKeyGate()}
    DisposableEffect(navVm) {onDispose {navVm?.setPlayerChromeVisible(true)}}
    val returnPage=com.applemusicktv.ui.navigation.LocalReturnPage.current
    val (returnFocusModifier,rememberOrigin)=com.applemusicktv.ui.navigation.rememberReturnTarget()
    var focus by remember(returnPage?.entry?.id) { mutableStateOf(
        returnPage?.handle?.get<String>("return.videoFocus")?.let {runCatching {MvTarget.valueOf(it)}.getOrNull()} ?: MvTarget.SCRUB) }
    SideEffect {returnPage?.handle?.set("return.videoFocus",focus.name)}
    var picker by remember { mutableStateOf(MvPicker.NONE) }
    var pickCursor by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var poke by remember { mutableIntStateOf(0) }

    val auds = state.audioTracks
    // Only the resolutions THIS video actually offers (from its master); fall back to the
    // standard tiers until they load.
    val qualities = state.availableQualities.ifEmpty { MV_QUALITY_TIERS }

    // Rows, top→bottom. Empty rows are skipped by up/down.
    val rows: List<List<MvTarget>> = remember(state.artist, auds, showOnScreenControls) {
        buildList {
            add(buildList {
                if (state.artist.isNotBlank()) add(MvTarget.ARTIST)
                if (auds.isNotEmpty()) add(MvTarget.AUDIO)
                add(MvTarget.QUALITY)
                add(MvTarget.QUEUE)
            })
            add(listOf(MvTarget.SCRUB))
            if (showOnScreenControls) add(listOf(MvTarget.PREV, MvTarget.PLAYPAUSE, MvTarget.NEXT))
        }
    }
    LaunchedEffect(rows) {if(rows.none {it.contains(focus)}) focus=MvTarget.SCRUB}
    fun rowOf(t: MvTarget) = rows.indexOfFirst { it.contains(t) }
    fun moveRow(delta: Int): Boolean {
        val r = rowOf(focus); val nr = r + delta
        if (nr < 0) { onFocusUp(); return true }
        if (nr >= rows.size) return true
        val col = rows[r].indexOf(focus).coerceIn(0, rows[nr].lastIndex)
        focus = rows[nr][col]; return true
    }
    fun moveCol(delta: Int) {
        val r = rowOf(focus); val c = rows[r].indexOf(focus) + delta
        if (c in rows[r].indices) focus = rows[r][c]
    }

    var lastPoke by remember { mutableStateOf(poke) }
    LaunchedEffect(poke, picker, showQueue, state.playing, state.buffering, state.loading) {
        if (poke != lastPoke || picker != MvPicker.NONE || showQueue) controls=true
        lastPoke=poke
        if (!controls) return@LaunchedEffect
        if (picker != MvPicker.NONE || showQueue || !state.playing || state.buffering || state.loading) { controls = true; return@LaunchedEffect }
        controls = true
        delay(5000)
        controls = false
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    fun poke() { poke++; controls = true }
    LaunchedEffect(interaction) {if(navVm!=null) poke()}
    SideEffect {navVm?.setPlayerChromeVisible(controls)}
    LaunchedEffect(controls) {
        if(!controls) {
            focus=if(showOnScreenControls) MvTarget.PLAYPAUSE else MvTarget.SCRUB
            runCatching {focusRequester.requestFocus()}
        }
    }

    androidx.activity.compose.BackHandler {
        when {
            !controls -> poke()
            showQueue -> vm.hideQueue()
            picker != MvPicker.NONE -> picker = MvPicker.NONE
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    poke()
                    if (showQueue) vm.hideQueue()
                    picker = MvPicker.NONE
                }
            }
            .then(returnFocusModifier)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                wakeKeys.consume(ev.nativeKeyEvent,active=true,hidden=!controls) {poke();focus=if(showOnScreenControls) MvTarget.PLAYPAUSE else MvTarget.SCRUB}
            }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                rememberOrigin()
                when (ev.key) {
                    Key.MediaNext, Key.MediaFastForward -> { vm.next(); poke(); return@onKeyEvent true }
                    Key.MediaPrevious, Key.MediaRewind -> { vm.prev(); poke(); return@onKeyEvent true }
                    Key.MediaPlayPause -> { vm.togglePlayPause(); poke(); return@onKeyEvent true }
                    else -> {}
                }
                if (showQueue) {
                    when (ev.key) {
                        Key.Back, Key.Menu -> { vm.hideQueue(); poke(); true }
                        Key.DirectionUp -> { if (queueCursor > 0) queueCursor--; true }
                        Key.DirectionDown -> { if (queueCursor < queue.size - 1) queueCursor++; true }
                        Key.DirectionCenter, Key.Enter -> { onPickQueueItem(queueCursor); vm.hideQueue(); poke(); true }
                        else -> true
                    }
                } else if (ev.key == Key.Menu) { queueCursor = queueIndex.coerceAtLeast(0); vm.toggleQueue(); true }
                else if (picker != MvPicker.NONE) {
                    val count = if (picker == MvPicker.AUDIO) auds.size else qualities.size
                    when (ev.key) {
                        Key.Back -> { picker = MvPicker.NONE; poke(); true }
                        Key.DirectionUp -> { if (pickCursor > 0) pickCursor--; true }
                        Key.DirectionDown -> { if (pickCursor < count - 1) pickCursor++; true }
                        Key.DirectionCenter, Key.Enter -> {
                            if (picker == MvPicker.AUDIO) auds.getOrNull(pickCursor)?.let { vm.setAudio(it.index) }
                            else qualities.getOrNull(pickCursor)?.let { vm.setQuality(it) }
                            picker = MvPicker.NONE; poke(); true
                        }
                        else -> true
                    }
                } else when (ev.key) {
                    Key.Back -> { onExit(); true }
                    Key.DirectionUp -> { poke(); scrub = null; moveRow(-1) }
                    Key.DirectionDown -> { poke(); scrub = null; moveRow(1) }
                    Key.DirectionLeft -> {
                        poke()
                        if (focus == MvTarget.SCRUB) scrub = ((scrub ?: state.positionMs) - 10_000).coerceIn(0, state.durationMs)
                        else moveCol(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        poke()
                        if (focus == MvTarget.SCRUB) scrub = ((scrub ?: state.positionMs) + 10_000).coerceIn(0, state.durationMs)
                        else moveCol(1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        poke()
                        when (focus) {
                            MvTarget.ARTIST -> vm.openArtist(onArtistClick)
                            MvTarget.AUDIO -> if (auds.isNotEmpty()) { picker = MvPicker.AUDIO; pickCursor = auds.indexOfFirst { it.index == state.audioIndex }.coerceAtLeast(0) }
                            MvTarget.QUALITY -> { picker = MvPicker.QUALITY; pickCursor = qualities.indexOf(state.qualityHeight).coerceAtLeast(0) }
                            MvTarget.QUEUE -> { queueCursor = queueIndex.coerceAtLeast(0); vm.toggleQueue() }
                            MvTarget.PREV -> vm.prev()
                            MvTarget.PLAYPAUSE -> vm.togglePlayPause()
                            MvTarget.NEXT -> vm.next()
                            MvTarget.SCRUB -> { val s = scrub; if (s != null) { vm.seekTo(s); scrub = null } else vm.togglePlayPause() }
                        }; true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Cold-load spinner only (nothing on screen yet). Mid-playback buffering + pause are shown
        // inline next to the play-head time in the transport bar (see below).
        if (state.loading) CircularProgressIndicator(
            color = Color.White, strokeWidth = 3.dp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 52.dp, bottom = 54.dp).size(26.dp),
        )

        if (state.error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr(R.string.msg_104), color = Color(0xFFFF453A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(com.applemusicktv.i18n.AppLanguage.message(state.error) ?: "", color = Color(0xFF888888), fontSize = 12.sp)
            }
        }

        // Captions (WebVTT; muxed CEA-608 in secure video can't be extracted).
        val captionText = cues.mapNotNull { it.text?.toString()?.trim()?.ifBlank { null } }.joinToString("\n")
        if (captionText.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 200.dp else 60.dp, start = 48.dp, end = 48.dp)) {
                Text(captionText, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }

        // ── Transport overlay ────────────────────────────────────────────────
        val chromeVisible = controls && !state.loading && state.error == null
        FadeVisibility(chromeVisible, modifier = Modifier.align(Alignment.BottomStart).fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000)))) {
                val dur = state.durationMs.coerceAtLeast(1)
                val shownPos = scrub ?: state.positionMs
                val frac = (shownPos.toFloat() / dur).coerceIn(0f, 1f)
                val scrubbing = scrub != null
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 52.dp, end = 52.dp, top = 34.dp, bottom = 20.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            val artistFocused = focus == MvTarget.ARTIST
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .touchClick { vm.openArtist(onArtistClick) }
                                    .background(if (artistFocused) Color(0x33FFFFFF) else Color.Transparent)
                                    .padding(horizontal = if (artistFocused) 9.dp else 0.dp, vertical = if (artistFocused) 3.dp else 0.dp),
                            ) {
                                Text(state.artist, color = if (artistFocused) Color.White else Color(0xB3FFFFFF),
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(state.title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                        }
                        if (auds.isNotEmpty()) {
                            PillButton(tr(R.string.msg_105), focus == MvTarget.AUDIO) {
                                picker = MvPicker.AUDIO
                                pickCursor = auds.indexOfFirst { it.index == state.audioIndex }.coerceAtLeast(0)
                                poke()
                            }
                            Spacer(Modifier.width(10.dp))
                        }
                        QualityPill(focused = focus == MvTarget.QUALITY) {
                            picker = MvPicker.QUALITY
                            pickCursor = qualities.indexOf(state.qualityHeight).coerceAtLeast(0)
                            poke()
                        }
                        Spacer(Modifier.width(10.dp))
                        RoundGlyph(GlyphKind.QUEUE, focus == MvTarget.QUEUE, 44.dp) {
                            queueCursor = queueIndex.coerceAtLeast(0)
                            vm.toggleQueue()
                            poke()
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    // Progress bar
                    val barH = if (focus == MvTarget.SCRUB) 7.dp else 4.dp
                    if (touchControls) {
                        TouchSeekBar(state.positionMs, state.durationMs, vm::seekTo, Modifier.fillMaxWidth(), onInteraction = { poke() })
                    } else {
                    Box(Modifier.fillMaxWidth().height(barH).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50))
                            .background(if (focus == MvTarget.SCRUB) Color.White else Color(0xE6FFFFFF)))
                    }
                    }
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.fillMaxWidth()) {
                        // Elapsed readout tracks the play-head. A pause glyph (when paused) and a
                        // small spinner (when buffering mid-playback) ride right beside it.
                        Row(
                            modifier = Modifier.align(BiasAlignment(horizontalBias = frac * 2f - 1f, verticalBias = 0f)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Pause glyph only on real user-pause; spinner only on buffer. Both can
                            // show together (paused mid-buffer), never a pause glyph for a plain load.
                            if (state.paused && !scrubbing) com.applemusicktv.ui.components.Icon(
                                com.applemusicktv.ui.components.Glyph.PAUSE, size = 12.dp, color = Color.White)
                            if (state.buffering) CircularProgressIndicator(
                                color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                            Text(fmt(shownPos), color = if (scrubbing) Color.White else Color(0xE6FFFFFF), fontSize = 13.sp,
                                fontWeight = if (scrubbing) FontWeight.Bold else FontWeight.Medium)
                        }
                        // The elapsed readout tracks the bar position; near the end it slides into the
                        // fixed remaining label. Fade remaining out (0.85→0.95) so they never overlap.
                        val endAlpha = ((0.85f - frac) / 0.10f).coerceIn(0f, 1f)
                        Text("-" + fmt(dur - shownPos), color = Color(0x99FFFFFF).copy(alpha = 0.6f * endAlpha),
                            fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                    // Media controls sit BELOW the playback bar — small, drawn, centred. Google TV only.
                    if (showOnScreenControls) {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            RoundGlyph(GlyphKind.PREV, focus == MvTarget.PREV, 40.dp) { vm.prev(); poke() }
                            Spacer(Modifier.width(18.dp))
                            RoundGlyph(if (state.playing) GlyphKind.PAUSE else GlyphKind.PLAY, focus == MvTarget.PLAYPAUSE, 48.dp) { vm.togglePlayPause(); poke() }
                            Spacer(Modifier.width(18.dp))
                            RoundGlyph(GlyphKind.NEXT, focus == MvTarget.NEXT, 40.dp) { vm.next(); poke() }
                        }
                    }
                }
            }
        }

        // ── Up-Next queue panel (right side) — finite fade ──
        FadeVisibility(showQueue, modifier = Modifier.align(Alignment.CenterEnd)) {
            Box(Modifier.fillMaxHeight().padding(14.dp).width(360.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF21C1C1E)).padding(vertical = 22.dp)) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item {
                        Text(tr(R.string.msg_106), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp, modifier = Modifier.padding(bottom = 14.dp))
                    }
                    itemsIndexed(queue) { i, song ->
                        val sel = i == queueCursor
                        val nowPlaying = i == queueIndex
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Color(0x26FFFFFF) else Color.Transparent)
                                .touchClick { onPickQueueItem(i); vm.hideQueue(); poke() }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (song.isMusicVideo)
                                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFA233B)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                    Text("MV", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = if (nowPlaying) Color(0xFFFA233B) else Color.White, fontSize = 14.sp,
                                    fontWeight = if (nowPlaying) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                                Text(song.artistName, color = Color(0x99FFFFFF), fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // ── Audio / Quality picker — pops from its button (bottom-right) ──
        FadeThrough(picker, contentKey = { if (it == MvPicker.NONE) null else it },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 52.dp, bottom = 190.dp)) { displayedPicker ->
            if (displayedPicker != MvPicker.NONE) {

            val title = if (displayedPicker == MvPicker.AUDIO) tr(R.string.msg_105) else tr(R.string.msg_107)
            Box(Modifier.width(230.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF21C1C1E)).padding(vertical = 10.dp)) {
                Column {
                    Text(title, color = Color(0x99FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    if (displayedPicker == MvPicker.AUDIO) {
                        auds.forEachIndexed { i, opt ->
                            PickerRow(opt.label, i == pickCursor, opt.index == state.audioIndex) {
                                vm.setAudio(opt.index); picker = MvPicker.NONE; poke()
                            }
                        }
                    } else {
                        qualities.forEachIndexed { i, h ->
                            PickerRow(qualityLabel(h), i == pickCursor, h == state.qualityHeight) {
                                vm.setQuality(h); picker = MvPicker.NONE; poke()
                            }
                        }
                    }
                }
            }
                    }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PickerRow(label: String, cursor: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp))
            .touchClick(onClick)
            .background(if (cursor) Color(0x26FFFFFF) else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) CheckGlyph()
    }
}

/** Snap an odd HLS ladder height (432p, 486p, 624p…) to the nearest clean tier for display. */
private fun snapTier(h: Int): Int = MV_QUALITY_TIERS.minByOrNull { kotlin.math.abs(it - h) } ?: h

/** Quality control: just the sparkles.tv glyph (no resolution readout). */
@androidx.compose.runtime.Composable
private fun QualityPill(focused: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(50))
            .touchClick(onClick)
            .background(if (focused) Color.White else Color(0x2EFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        com.applemusicktv.ui.components.Icon(
            com.applemusicktv.ui.components.Glyph.SPARKLES_TV, size = 22.dp,
            color = if (focused) Color.Black else Color.White)
    }
}

/** A translucent rounded pill (audio / quality label). */
@androidx.compose.runtime.Composable
private fun PillButton(label: String, focused: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(50)).touchClick(onClick).background(if (focused) Color.White else Color(0x2EFFFFFF)).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private enum class GlyphKind { PREV, NEXT, PLAY, PAUSE, QUEUE }

/** A round translucent button with a DRAWN glyph — no emoji. */
@androidx.compose.runtime.Composable
private fun RoundGlyph(kind: GlyphKind, focused: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val fg = if (focused) Color.Black else Color.White
    Box(
        Modifier.size(size).clip(RoundedCornerShape(50)).touchClick(onClick).background(if (focused) Color.White else Color(0x2EFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        // SF Symbol glyphs (same set as the rest of the app).
        val glyph = when (kind) {
            GlyphKind.PLAY  -> com.applemusicktv.ui.components.Glyph.PLAY
            GlyphKind.PAUSE -> com.applemusicktv.ui.components.Glyph.PAUSE
            GlyphKind.NEXT  -> com.applemusicktv.ui.components.Glyph.NEXT
            GlyphKind.PREV  -> com.applemusicktv.ui.components.Glyph.PREV
            GlyphKind.QUEUE -> com.applemusicktv.ui.components.Glyph.QUEUE
        }
        com.applemusicktv.ui.components.Icon(glyph, size = size * 0.55f, color = fg)
    }
}

@androidx.compose.runtime.Composable
private fun CheckGlyph() {
    com.applemusicktv.ui.components.Icon(
        com.applemusicktv.ui.components.Glyph.CHECK, size = 14.dp, color = Color.White)
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
