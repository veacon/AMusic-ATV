package com.applemusicktv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.applemusicktv.ui.theme.AppPalette

/** Full semicircular ends at every button height, including focused outlines. */
internal val ContentActionShape = RoundedCornerShape(percent = 50)

/** Action focus keeps the original fill/ink; only scale and this outer ring change. */
internal val ContentActionFocusColor get() = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFC7C7CC), Color(0xFF707078))

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun contentActionColors(base: Color, ink: Color = AppPalette.contentOn(base)) =
    ClickableSurfaceDefaults.colors(
        containerColor = base, focusedContainerColor = base,
        contentColor = ink, focusedContentColor = ink,
    )

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun contentActionBorder(shape: Shape = ContentActionShape) =
    ClickableSurfaceDefaults.border(
        border = Border.None,
        focusedBorder = Border(BorderStroke(1.5.dp, ContentActionFocusColor), inset = 3.dp, shape = shape),
    )
