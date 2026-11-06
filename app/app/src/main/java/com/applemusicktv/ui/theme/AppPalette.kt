package com.applemusicktv.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Browsing surfaces follow the selected appearance; artwork and player retain their own colors. */
object AppPalette {
    var isDark by mutableStateOf(false)
        private set
    fun load(context: Context) { isDark = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("dark", false) }
    fun setDark(context: Context, enabled: Boolean) {
        isDark = enabled
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("dark", enabled).apply()
    }
    fun adaptive(light: Color, dark: Color): Color = if (isDark) dark else light
    val background get() = adaptive(Color.White, Color(0xFF101012))
    val primary get() = adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7))
    val secondary get() = adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8))
    val surface get() = adaptive(Color(0xFFF5F5F7), Color(0xFF222225))
    val selected get() = adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))
    val accent = Color(0xFFFA233B)
    fun contentOn(background: Color): Color = if (background.luminance() < 0.4f) Color.White else Color(0xFF1D1D1F)
}
