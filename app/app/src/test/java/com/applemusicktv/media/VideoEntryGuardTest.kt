package com.applemusicktv.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],manifest=Config.NONE)
class VideoEntryGuardTest {
    @Test fun rawMvPlaybackStopsBeforeAnyAccountOrNetworkRequest() = runBlocking {
        try { AppleDirectClient().getMusicVideoPlayback("invalid-id","","",1080);fail("Expected video gate") }
        catch (e: IllegalStateException) { assertEquals("Video playback is disabled in this user release",e.message) }
    }
}
