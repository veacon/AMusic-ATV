package com.applemusicktv.ui.components

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.applemusicktv.ui.theme.AppPalette

/** The existing one-click cache-clear dialog is the common confirmation template. */
@Composable
internal fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmColor: Color = AppPalette.accent,
    focusConfirm: Boolean = false,
) {
    val initialFocus = remember { FocusRequester() }
    MotionDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.menuInitialFocus(initialFocus).width(400.dp).testTag("confirmationDialog")
                .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), RoundedCornerShape(20.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
            Text(message, fontSize = 14.sp, lineHeight = 22.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                modifier = Modifier.heightIn(min = 42.dp))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmationButton(tr(R.string.msg_298), com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), Modifier.weight(1f).then(if (!focusConfirm) Modifier.focusRequester(initialFocus) else Modifier), onDismiss)
                ConfirmationButton(confirmLabel, confirmColor, Modifier.weight(1f).then(if (focusConfirm) Modifier.focusRequester(initialFocus) else Modifier), onConfirm)
            }
        }
    }
}

@Composable
internal fun ExitAppDialog(onDismiss: () -> Unit, onExit: () -> Unit) {
    ConfirmationDialog(tr(R.string.msg_299), tr(R.string.msg_231), tr(R.string.msg_300), onDismiss, onExit, Color(0xFFB22222), focusConfirm = true)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmationButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    TouchSurface(onClick = onClick, modifier = modifier.height(52.dp),
        shape = ClickableSurfaceDefaults.shape(ContentActionShape),
        colors = contentActionColors(color),
        border = contentActionBorder(ContentActionShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppPalette.contentOn(color))
        }
    }
}
