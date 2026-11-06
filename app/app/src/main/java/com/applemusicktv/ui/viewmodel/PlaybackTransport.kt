package com.applemusicktv.ui.viewmodel

import androidx.media3.common.Player

/** play() changes intent only; an idle player still needs its retained source prepared. */
internal fun resumePreparedPlayer(player: Player) {
    when (player.playbackState) {
        Player.STATE_IDLE -> player.prepare()
        Player.STATE_ENDED -> player.seekToDefaultPosition()
    }
    player.play()
}
