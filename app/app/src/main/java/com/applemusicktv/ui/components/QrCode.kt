package com.applemusicktv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * 把一段文字渲染成二维码。
 *
 * 用 ZXing core 直接生成 BitMatrix 再转 Bitmap，避免引入 zxing-android 的相机等重依赖。
 * 二维码是「白底黑块」——周围留 [padding] 白边，深色主题下也清晰可扫。
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    cornerRadius: Dp = 14.dp,
    padding: Dp = 10.dp,
) {
    val bitmap = remember(content) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix: BitMatrix = MultiFormatWriter().encode(
                content, BarcodeFormat.QR_CODE, 320, 320, hints,
            )
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "二维码",
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color.White)
                // 一圈极淡的描边，让白卡在深色弹窗里边缘更利落。
                .border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(cornerRadius))
                .padding(padding),
        )
    }
}
