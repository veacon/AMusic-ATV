package com.applemusicktv.ui.viewmodel

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlaybackTransportTest {
    private val calls=mutableListOf<String>()
    private fun player(state:Int)=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) {_,method,_->
        when(method.name) {
            "getPlaybackState"->state
            "prepare","play","seekToDefaultPosition"->{calls+=method.name;null}
            else->throw AssertionError("Unexpected call ${method.name}")
        }
    } as Player
    @Test fun stoppedSourceIsPreparedBeforePlay() {resumePreparedPlayer(player(Player.STATE_IDLE));assertEquals(listOf("prepare","play"),calls)}
    @Test fun endedSongRewindsBeforePlay() {resumePreparedPlayer(player(Player.STATE_ENDED));assertEquals(listOf("seekToDefaultPosition","play"),calls)}
    @Test fun pausedReadySongKeepsItsPosition() {resumePreparedPlayer(player(Player.STATE_READY));assertEquals(listOf("play"),calls)}
    @Test fun bufferingSourceIsNotPreparedOrRewoundAgain() {resumePreparedPlayer(player(Player.STATE_BUFFERING));assertEquals(listOf("play"),calls)}
    @Test fun repeatedExplicitPlayWhilePreparingCannotToggleToPause() {
        val preparation=SourcePreparation();preparation.begin(1,false)
        preparation.resume();preparation.resume();assertEquals(true,preparation.finish(1))
    }
    @Test fun latestPauseWinsOverEarlierErrorRecoveryIntent() {
        val preparation=SourcePreparation();preparation.begin(1,true);preparation.pause();preparation.pause()
        assertEquals(false,preparation.finish(1))
    }
    @Test fun interruptedRecoveryCannotAttachAfterNewSelection() {
        val preparation=SourcePreparation();preparation.begin(1);preparation.cancel();preparation.begin(3,false)
        assertNull(preparation.finish(1));assertEquals(false,preparation.finish(3))
    }
}
