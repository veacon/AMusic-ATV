package com.applemusicktv.media

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.applemusicktv.MainActivity
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class)
class PlatformCompatibilityTest {
    private fun <T> allocate(type:Class<T>):T {
        val c=Class.forName("sun.misc.Unsafe");val u=c.getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null)
        return type.cast(c.getMethod("allocateInstance",Class::class.java).invoke(u,type))
    }
    private fun notify():NotificationManager {
        val context=RuntimeEnvironment.getApplication();val vm=allocate(PlayerViewModel::class.java)
        PlayerViewModel::class.java.getDeclaredField("context").apply { isAccessible=true }.set(vm,context)
        vm.showNotification("fixture service","fixture account",42)
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    @Test @Config(sdk=[23]) fun androidSixCanPostLegacyNotification() {
        assertEquals(1,shadowOf(notify()).allNotifications.size)
    }
    @Test @Config(sdk=[28]) fun newerAndroidCreatesTheAlertChannel() {
        val manager=notify();assertNotNull(manager.getNotificationChannel("am_alerts"))
        assertEquals(1,shadowOf(manager).allNotifications.size)
    }
    @Test @Config(sdk=[34]) fun deniedNotificationPermissionDoesNotCrashOrPost() {
        assertEquals(0,shadowOf(notify()).allNotifications.size)
    }
}
