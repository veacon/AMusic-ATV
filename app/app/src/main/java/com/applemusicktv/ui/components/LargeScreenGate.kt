package com.applemusicktv.ui.components

import com.applemusicktv.i18n.tr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applemusicktv.R

internal fun supportsAppWindow(widthDp: Float, heightDp: Float): Boolean = widthDp >= 540f && heightDp >= 480f

@Composable
internal fun LargeScreenGate(onAccessChanged: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
        val allowed = supportsAppWindow(maxWidth.value, maxHeight.value)
        SideEffect { onAccessChanged(allowed) }
        if (allowed) content() else UnsupportedScreen()
    }
}

@Composable
internal fun UnsupportedScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.mipmap.ic_launcher), "Apple Music TV", Modifier.size(88.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Apple Music", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D1D1F), textAlign = TextAlign.Center)
                Text(tr(R.string.msg_291), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6E6E73), textAlign = TextAlign.Center)
            }
        }
    }
}
