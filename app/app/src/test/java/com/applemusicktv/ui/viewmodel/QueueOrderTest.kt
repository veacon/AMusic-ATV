package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class QueueOrderTest {
    private fun song(id:String)=Song(id,id,"artist","album",60000,null,null,null)
    private fun <T> allocate(type:Class<T>):T {
        val c=Class.forName("sun.misc.Unsafe");val u=c.getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null)
        return type.cast(c.getMethod("allocateInstance",Class::class.java).invoke(u,type))
    }
    private fun vm(state:MutableStateFlow<PlayerState>):PlayerViewModel {
        val vm=allocate(PlayerViewModel::class.java)
        PlayerViewModel::class.java.getDeclaredField("_state").apply { isAccessible=true }.set(vm,state)
        return vm
    }
    @Test fun restoringOrderKeepsActualCurrentSongEvenWhenItsOriginalIndexIsSmaller() {
        val original=(0..4).map { song("$it") }
        val queue=listOf(original[0],original[2],original[3],original[4],original[1])
        val state=MutableStateFlow(PlayerState(queue=queue,queueIndex=4,currentSong=original[1],song=original[1],isShuffled=true,originalQueue=original))
        vm(state).toggleShuffle()
        assertEquals(original,state.value.queue);assertEquals(1,state.value.queueIndex)
        assertEquals(state.value.currentSong,state.value.queue[state.value.queueIndex])
    }
    @Test fun shuffleAndRestorePreserveAllEntriesAcrossFiveAndHundredSongQueues() {
        for(size in listOf(5,100)) repeat(20) {
            val original=(0 until size).map { song("$it") }
            val state=MutableStateFlow(PlayerState(queue=original,queueIndex=0,currentSong=original[0],song=original[0]))
            val vm=vm(state);vm.toggleShuffle()
            val shuffled=state.value.queue
            assertEquals(size,shuffled.size);assertEquals(original.toSet(),shuffled.toSet())
            val index=shuffled.indices.random();val current=shuffled[index]
            state.value=state.value.copy(queueIndex=index,currentSong=current,song=current)
            vm.toggleShuffle()
            assertEquals(original,state.value.queue);assertEquals(current,state.value.queue[state.value.queueIndex])
        }
    }
    @Test fun duplicateIdsRetainPlayingOccurrenceAndInsertedPlayNextEntry() {
        val a=song("duplicate");val b=song("b");val c=song("duplicate");val added=song("next")
        val (restored,index)=restoreQueueOrder(listOf(a,added,c,b),listOf(a,b,c),2)
        assertEquals(listOf(a,added,b,c),restored);assertSame(c,restored[index]);assertEquals(4,restored.size)
    }
    @Test fun staleAsyncPlaylistCannotCommitAfterAnotherSelection() {
        val state=MutableStateFlow(PlayerState());val vm=vm(state);val gate=PlaybackRequestGate()
        PlayerViewModel::class.java.getDeclaredField("playbackRequests").apply { isAccessible=true }.set(vm,gate)
        val old=gate.select();gate.select()
        vm.playAlbumIfCurrent(old,listOf(song("stale")))
        assertTrue(state.value.queue.isEmpty())
    }
    @Test fun repeatedReferenceKeepsItsOccurrenceInsteadOfJumpingToFirstCopy() {
        val a=song("a");val b=song("b")
        val (restored,index)=restoreQueueOrder(listOf(a,a,b),listOf(a,b,a),1)
        assertEquals(listOf(a,b,a),restored);assertEquals(2,index)
    }

}
