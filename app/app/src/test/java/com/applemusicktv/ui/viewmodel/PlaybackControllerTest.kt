package com.applemusicktv.ui.viewmodel

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production controller entry points without networking or real account data. */
class PlaybackControllerTest {
    private class Engine(var state:Int=Player.STATE_READY,var wantsPlay:Boolean=true) {
        var prepared=0
        val exo=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) {_,method,args->
            when(method.name) {
                "getPlaybackState"->state
                "getPlayWhenReady"->wantsPlay
                "getMediaItemCount"->1
                "getCurrentPosition"->16856L
                "prepare"->{prepared++;state=Player.STATE_BUFFERING;null}
                "play"->{wantsPlay=true;null}
                "pause"->{wantsPlay=false;null}
                else->throw AssertionError("Unexpected transport call ${method.name}")
            }
        } as ExoPlayer
    }
    private fun vm(main:Engine,partner:Engine?=null):PlayerViewModel {
        val c=Class.forName("sun.misc.Unsafe");val u=c.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null)
        val vm=c.getMethod("allocateInstance",Class::class.java).invoke(u,PlayerViewModel::class.java) as PlayerViewModel
        fun field(name:String,value:Any)=PlayerViewModel::class.java.getDeclaredField(name).apply {isAccessible=true}.set(vm,value)
        field("player",main.exo);field("_state",MutableStateFlow(PlayerState()));field("sourcePreparation",SourcePreparation())
        if(partner!=null) field("crossfadeExo",partner.exo)
        return vm
    }
    @Test fun actualPlayButtonRepreparesIdleSourceEvenIfOldPlayIntentWasTrue() {
        val main=Engine(Player.STATE_IDLE,true);val vm=vm(main)
        vm.togglePlayPause();assertEquals(1,main.prepared);assertTrue(main.wantsPlay)
        vm.pause();vm.resume();assertEquals(1,main.prepared);assertTrue(main.wantsPlay)
    }
    @Test fun repeatedExplicitPauseNeverResumesAndRepeatedPlayNeverPauses() {
        val main=Engine();val vm=vm(main)
        vm.pause();vm.pause();assertFalse(main.wantsPlay)
        vm.resume();vm.resume();assertTrue(main.wantsPlay);assertEquals(0,main.prepared)
    }
    @Test fun pauseAndResumeControlBothCrossfadePlayersTogether() {
        val main=Engine();val incoming=Engine();val vm=vm(main,incoming)
        vm.pause();assertFalse(main.wantsPlay);assertFalse(incoming.wantsPlay)
        vm.togglePlayPause();assertTrue(main.wantsPlay);assertTrue(incoming.wantsPlay)
    }
    @Test fun pendingPreparationConsumesLatestIntentWithoutResumingAnOldPlayer() {
        val main=Engine();val vm=vm(main)
        val prep=PlayerViewModel::class.java.getDeclaredField("sourcePreparation").apply {isAccessible=true}.get(vm) as SourcePreparation
        prep.begin(7);vm.pause();assertFalse(main.wantsPlay)
        vm.resume();vm.resume();assertFalse(main.wantsPlay);assertEquals(true,prep.finish(7))
    }
}
