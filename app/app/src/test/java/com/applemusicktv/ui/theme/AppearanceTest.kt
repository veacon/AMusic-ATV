package com.applemusicktv.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppearanceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun cleanUp() {
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        AppPalette.load(context)
    }

    @Test fun defaultsToLightAndPersistsBothChoices() {
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        AppPalette.load(context)
        assertEquals(Color.White, AppPalette.background)
        AppPalette.setDark(context, true)
        AppPalette.load(context)
        assertEquals(Color(0xFF101012), AppPalette.background)
        assertEquals(Color(0xFFF5F5F7), AppPalette.primary)
        AppPalette.setDark(context, false)
        AppPalette.load(context)
        assertEquals(Color.White, AppPalette.background)
    }

    @Test fun buttonTextUsesItsActualBackground() {
        AppPalette.setDark(context, true)
        assertEquals(Color(0xFF1D1D1F), AppPalette.contentOn(Color.White))
        assertEquals(Color.White, AppPalette.contentOn(Color.Black))
    }
}
