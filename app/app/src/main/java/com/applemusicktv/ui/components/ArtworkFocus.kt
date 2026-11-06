package com.applemusicktv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlin.math.round

/** A card scales as before; only explicitly marked artwork draws the focus outline. */
internal val LocalArtworkFocused = compositionLocalOf { false }
internal val LocalArtworkBorderHost = compositionLocalOf<ArtworkBorderHost?> { null }

/** Publish the image's bounds to its clickable surface, whose border is outside its clip.
 * This keeps the original image size and leaves a 1dp gap before the focus stroke.
 */
internal class ArtworkBorderHost {
    private var surface: LayoutCoordinates? = null
    private var artwork: LayoutCoordinates? = null
    private var artworkShape: Shape? = null
    var outline by mutableStateOf<Shape?>(null)
        private set

    fun surfacePlaced(coordinates: LayoutCoordinates) { surface = coordinates; update() }
    fun artworkPlaced(shape: Shape, coordinates: LayoutCoordinates) {
        artwork = coordinates; artworkShape = shape; update()
    }
    private fun update() {
        val parent = surface?.takeIf { it.isAttached } ?: return
        val child = artwork?.takeIf { it.isAttached } ?: return
        val shape = artworkShape ?: return
        val transformed = parent.localBoundingBoxOf(child, clipBounds = false)
        // The surface modifier is outside TV's scale layer. Undo that layer here;
        // the border itself will be scaled with the image by TV Material.
        val scaleX = transformed.width / child.size.width.coerceAtLeast(1)
        val scaleY = transformed.height / child.size.height.coerceAtLeast(1)
        if (scaleX <= 0f || scaleY <= 0f) return
        val left = (transformed.left - parent.size.width / 2f) / scaleX + parent.size.width / 2f
        val top = (transformed.top - parent.size.height / 2f) / scaleY + parent.size.height / 2f
        val rect = Rect(left, top, left + child.size.width, top + child.size.height)
        // Avoid floating-point position noise during the original focus scale animation.
        fun stable(value: Float) = round(value * 100) / 100
        outline = ArtworkOutline(shape, Rect(stable(rect.left), stable(rect.top), stable(rect.right), stable(rect.bottom)))
    }
}

private data class ArtworkOutline(val shape: Shape, val rect: Rect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // A 1dp clear gap plus half the stroke width puts the inner stroke edge 1dp away.
        val outset = with(density) { 1.75.dp.toPx() }
        val image = shape.createOutline(Size(rect.width, rect.height), layoutDirection, density)
        val path = Path().apply {
            when (image) {
                is Outline.Rectangle -> addRoundRect(RoundRect(
                    Rect(0f, 0f, rect.width + outset * 2, rect.height + outset * 2), CornerRadius(outset)))
                is Outline.Rounded -> {
                    val rounded = image.roundRect
                    fun CornerRadius.expanded() = CornerRadius(x + outset, y + outset)
                    addRoundRect(RoundRect(0f, 0f, rect.width + outset * 2, rect.height + outset * 2,
                        rounded.topLeftCornerRadius.expanded(), rounded.topRightCornerRadius.expanded(),
                        rounded.bottomRightCornerRadius.expanded(), rounded.bottomLeftCornerRadius.expanded()))
                }
                is Outline.Generic -> addPath(image.path, Offset(outset, outset))
            }
            translate(Offset(rect.left, rect.top))
        }
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
internal fun ArtworkBorderHost.border(focused: Boolean): Border? =
    outline?.takeIf { focused }?.let { Border(BorderStroke(1.5.dp, com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFC7C7CC), Color(0xFF707078))), inset = 1.75.dp, shape = it) }

@Composable
internal fun Modifier.focusedArtworkBorder(shape: Shape): Modifier {
    val host = LocalArtworkBorderHost.current ?: return this
    return onGloballyPositioned { host.artworkPlaced(shape, it) }
}
