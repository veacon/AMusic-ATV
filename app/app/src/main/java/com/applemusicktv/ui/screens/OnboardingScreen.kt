package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.components.TouchSurface as Surface
import com.applemusicktv.ui.components.ContentActionShape
import androidx.compose.foundation.Canvas
import com.applemusicktv.ui.components.pageHorizontalInset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.applemusicktv.ui.viewmodel.OnboardingState
import com.applemusicktv.ui.viewmodel.OnboardingViewModel
import com.applemusicktv.util.QrCode

/** First-run login, completed explicitly after the phone saves the token. */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(vm) {
        vm.startMutPolling()
        onDispose { vm.stopMutPolling() }
    }
    OnboardingContent(state, onStart = {
        keyboard?.hide()
        vm.finish()
        onDone()
    }, modifier = modifier)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun OnboardingContent(state: OnboardingState, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { primaryFocus.requestFocus() }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(com.applemusicktv.ui.theme.AppPalette.background)) {
        val compact = maxHeight < 480.dp
        val inset = pageHorizontalInset
        val codeSize = if (compact) 200.dp else 236.dp
        Row(
            Modifier.fillMaxSize().padding(horizontal = inset, vertical = if (compact) 24.dp else 40.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Apple Music", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFA233B))
                Text(tr(R.string.msg_168), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                Text(tr(R.string.msg_169),
                    fontSize = 16.sp, lineHeight = 24.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                Spacer(Modifier.height(if (compact) 0.dp else 8.dp))
                Text(tr(R.string.msg_170),
                    fontSize = 14.sp, lineHeight = 22.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                if (state.phoneUrl.isNotEmpty()) {
                    ClickableText(
                        text = AnnotatedString(state.phoneUrl),
                        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium,
                            color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE3E3E8)), textDecoration = TextDecoration.Underline),
                        modifier = Modifier.focusProperties { canFocus = false },
                        onClick = {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(state.phoneUrl))) }
                                .onFailure { android.widget.Toast.makeText(context, tr(R.string.msg_171), android.widget.Toast.LENGTH_SHORT).show() }
                        },
                    )
                } else Text(tr(state.phoneSetupMessage ?: if (state.phoneServerUnavailable) R.string.phone_setup_unavailable else R.string.msg_172),
                    fontSize = 20.sp, fontWeight = FontWeight.Medium, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).background(if (state.hasMut) Color(0xFF248A3D) else com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFB6B6BC), Color(0xFFAAAAAF)), RoundedCornerShape(50)))
                    Text(when { state.checking -> tr(R.string.msg_173); state.tokenError != null -> com.applemusicktv.i18n.AppLanguage.message(state.tokenError).orEmpty(); state.hasMut -> tr(R.string.msg_174); else -> tr(R.string.msg_175) },
                        fontSize = 13.sp, lineHeight = 20.sp,
                        color = if (state.hasMut) Color(0xFF248A3D) else com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                }
                Spacer(Modifier.height(4.dp))
                Surface(
                    onClick = onStart,
                    modifier = Modifier.width(208.dp).height(52.dp).focusRequester(primaryFocus),
                    shape = ClickableSurfaceDefaults.shape(ContentActionShape),
                    colors = com.applemusicktv.ui.components.contentActionColors(Color(0xFFFA233B)),
                    border = com.applemusicktv.ui.components.contentActionBorder(),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                    glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (state.hasMut) tr(R.string.msg_176) else if (state.tokenStored) tr(R.string.msg_177) else tr(R.string.msg_178), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
                Text(tr(R.string.msg_179), fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val matrix = remember(state.phoneUrl) { state.phoneUrl.takeIf { it.isNotEmpty() }?.let { QrCode.encode(it) } }
                Box(Modifier.size(codeSize).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), RoundedCornerShape(24.dp)).padding(12.dp)) {
                    Box(Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(14.dp)).padding(24.dp), contentAlignment = Alignment.Center) {
                        if (matrix == null) {
                            Text(tr(state.phoneSetupMessage ?: if (state.phoneServerUnavailable) R.string.phone_setup_unavailable else R.string.msg_180), fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                        } else Canvas(Modifier.fillMaxSize()) {
                            val cell = size.minDimension / matrix.size
                            for (r in matrix.indices) for (c in matrix.indices) {
                                if (matrix[r][c]) drawRect(Color.Black,
                                    topLeft = androidx.compose.ui.geometry.Offset(c * cell, r * cell),
                                    size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
                            }
                        }
                    }
                }
                if (matrix != null) Text(tr(R.string.msg_181), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
        }
    }
}
