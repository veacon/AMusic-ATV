package com.applemusicktv.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertTrue

/** Assert that a faded control contributes no visible ink over the fixture's black backdrop. */
fun assertBlackRegion(view: View, bounds: Rect) {
    val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(image))
    val left = bounds.left.toInt().coerceIn(0, image.width - 1)
    val top = bounds.top.toInt().coerceIn(0, image.height - 1)
    val right = bounds.right.toInt().coerceIn(left + 1, image.width)
    val bottom = bounds.bottom.toInt().coerceIn(top + 1, image.height)
    var brightest = 0
    for (y in top until bottom) for (x in left until right) {
        val p = image.getPixel(x, y)
        brightest = maxOf(brightest, Color.red(p), Color.green(p), Color.blue(p))
    }
    image.recycle()
    assertTrue("Hidden control still renders ink (brightest=$brightest, bounds=$bounds)", brightest <= 20)
}
