package com.applemusicktv.data.network

import com.squareup.moshi.JsonClass

/** Only artwork is decoded; names, identifiers and other account fields are not retained. */
@JsonClass(generateAdapter = true)
data class AppleProfileAttributes(val avatarArtwork: AppleArtwork? = null, val artwork: AppleArtwork? = null)
