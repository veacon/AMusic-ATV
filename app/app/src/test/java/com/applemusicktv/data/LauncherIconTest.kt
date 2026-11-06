package com.applemusicktv.data

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class LauncherIconTest {
    @Test fun android6AndLaterSwitchAliasesAndKeepRealActivityEnabledWithOneLauncher() {
        val context = RuntimeEnvironment.getApplication()
        val manager = LauncherIconManager(context)
        fun launchers() = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        assertEquals(LauncherIcon.Red, manager.current())
        assertEquals(listOf(LauncherIcon.Red.alias), launchers().map { it.activityInfo.name })
        for (icon in listOf(LauncherIcon.White, LauncherIcon.Red)) {
            manager.select(icon)
            assertEquals(icon, manager.current())
            assertEquals(listOf(icon.alias), launchers().map { it.activityInfo.name })
            val activity = ComponentName(context.packageName, "com.applemusicktv.MainActivity")
            assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(activity))
            assertEquals("com.applemusicktv.MainActivity", launchers().single().activityInfo.targetActivity)
        }
    }
}
