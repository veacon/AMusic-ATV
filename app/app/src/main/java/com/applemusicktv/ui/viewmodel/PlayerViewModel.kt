package com.applemusicktv.ui.viewmodel

import com.applemusicktv.i18n.tr
import com.applemusicktv.R
import com.applemusicktv.data.VideoPlaybackPolicy

import android.app.NotificationChannel
import android.app.NotificationManager
import com.applemusicktv.data.cache.AppDiskCache
import com.applemusicktv.data.cache.CacheLimits
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.applemusicktv.data.LyricsOffsetPreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.media.InAppWebServer
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.ui.background.unifiedFluidQuality
import com.applemusicktv.ui.background.FluidQuality
import com.applemusicktv.media.AppleDirectClient
import com.applemusicktv.media.AppleMusicDrmCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.applemusicktv.media.BeatAnalyzer
import com.applemusicktv.media.BeatAwareRenderersFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepeatMode { Off, One, All }

/** Rebuffers on one song before standalone is abandoned for it. */
private const val STUTTER_LIMIT = 3
/** Sleep timer eases the volume down over this window before pausing. */
private const val SLEEP_FADE_MS = 5_000L

/** Dump schm/tenc/pssh of the standalone init segment. Costs a full segment download. */
private const val PROBE_INIT_SEGMENT = false

/** Path 1 milestone: run our own software Widevine CDM and log the raw content key
 *  (AMKEY). Compare against server/get_key.py for the same track to prove the on-device
 *  CDM derives the correct key before building the in-app decrypt. */
private const val PROBE_CDM_KEY = false

/** Path 1: decrypt segments in-app with our own CDM and play CLEAR AAC (no MediaDrm),
 *  which is what kills the 0x4004 chop. Falls back to the MediaDrm path on failure. */
private const val DECRYPT_IN_APP = true

/** DIAGNOSTIC: capture decoded PCM of the first track to inspect the chop offline. */
private const val CAPTURE_PCM = false

/**
 * What the Now Playing screen draws behind the card.
 *
 * - [DYNAMIC]: the drifting, album-coloured beat blobs (default).
 * - [PROJECTOR]: the same blobs pulled to the centre on true black, dissolved to black on every
 *   edge, so a projected image has no visible boundary. Halo brightness stays constant with the
 *   beat — only size moves — so the black never pumps.
 * - [BLACK]: plain black, no blobs, no beat.
 */
enum class NowPlayingBackground(private val labelId: Int) {
    DYNAMIC(R.string.msg_012),
    PROJECTOR(R.string.msg_013),
    BLACK(R.string.msg_014),
    ;
    val label: String get() = tr(labelId)


    companion object {
        fun fromName(name: String?): NowPlayingBackground =
            entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}

// Apple Music live radio stations (isLive=true). Fixed set — these stream a live Widevine HLS
// feed via /v1/play/assets, unlike personal/genre stations which return a next-tracks song queue.
private val LIVE_STATION_IDS = setOf(
    "ra.978194965",   // Apple Music 1
    "ra.1498155548",  // Apple Music Hits
    "ra.1498157166",  // Apple Music Country
    "ra.1740613864",  // Apple Música Uno
    "ra.1740614260",  // Apple Music Chill
    "ra.1740613859",  // Apple Music Club
    "ra.1534941676",  // Apple Music TV (video)
)

data class PlayerState(
    val stationLaunch: StationLaunch? = null,
    val currentSong:      Song?           = null,
    val song:             Song?           = null,
    val isPlaying:        Boolean         = false,
    val progressMs:       Long            = 0L,
    val queue:            List<Song>      = emptyList(),
    val queueIndex:       Int             = 0,
    val lyrics:           List<LyricLine> = emptyList(),
    val lyricsNotice: LyricsNoticePhase = LyricsNoticePhase.Idle,
    val lyricsNoticeSongId: String? = null,
    val lyricsNoticeGeneration: Long = 0L,
    val emptyLyricsNoticeCompletedFor: String? = null,
    val exhaustedEmptyLyricsFor: String? = null,
    val isFullStream:     Boolean         = false,
    val motionUrl:        String?         = null,
    /** A/V-sync EXTRA the user dials on top of everything. 0 normally; the only value shown in the UI. */
    val lyricsOffsetMs:   Long            = 0L,
    /** Automatic A/V sync: add the Bluetooth delta when a BT output is live. */
    val avSyncAuto:       Boolean         = true,
    /** A BT output is currently active. */
    val avOnBluetooth:    Boolean         = false,
    /** Total delay applied to the LYRIC clock: hidden 200 ms baseline (+ BT delta) + extra. */
    val avLyricsMs:       Long            = LYRICS_BASELINE_MS,
    /** Human label for the current audio output. */
    val avOutputLabel:    String          = tr(R.string.msg_015),
    val isShuffled:       Boolean         = false,
    val originalQueue:    List<Song>      = emptyList(),
    val repeatMode:       RepeatMode      = RepeatMode.Off,
    val sleepTimerEndsAt: Long?           = null,
    val sleepAfterSong:   Boolean         = false,
    val mutExpired:       Boolean         = false,
    val beatIntensity:    Float           = 1.0f,
    val userQueue:        List<Song>      = emptyList(),
    val crossfadeEnabled: Boolean         = true,
    /** Idle minutes before the ambient screensaver; 0 = off. */
    val screensaverTimeoutMin: Int        = 10,
    /** What's drawn behind the Now Playing card. */
    val nowPlayingBackground: NowPlayingBackground = NowPlayingBackground.DYNAMIC,
    val fluidQuality: FluidQuality = FluidQuality.STANDARD,
    /**
     * When true, the beat blobs keep running once the screensaver kicks in. Off by default: the
     * screensaver drops to plain black so the extra motion doesn't linger overnight.
     */
    val screensaverKeepBackground: Boolean = false,
    /** Show the clock and the queue/lyrics panel hint on Now Playing. Off = a cleaner, art-only view. */
    val showNowPlayingInfo: Boolean = true,
    /** Animated album art is enabled by default; one bounded, muted decoder at a time. */
    val motionArtworkEnabled: Boolean = false,
    /** Projector orb drift speed: 0.6 slow · 1.0 normal · 1.6 fast. */
    val orbSpeed: Float = 1.0f,
    /** Lyrics font scale: 0.8 extra small · 1.0 small · 1.25 standard · 1.6 large · 2.0 extra large. */
    val lyricsScale: Float = 1.0f,
    /** Rounded (true) vs square (false) album-art corners. */
    val artworkRounded: Boolean = true,
    /** Accessibility: freeze/minimise background motion. */
    val reduceMotion: Boolean = false,
    /** Low Power Mode: cheaper background rendering (also forces motion art off). */
    val lowPowerMode: Boolean = false,
    /** Volume leveling (loudness AGC) on the audio path. */
    val volumeLeveling: Boolean = false,
    /** Decrypt/buffer in flight — a cold track takes 15-20s, so the UI must say so. */
    val isLoading:        Boolean         = false,
    /** True while the current track is playing via on-device Widevine. */
    val standaloneActive: Boolean         = false,
    /** Live radio: continuous DRM stream. Drives the LIVE badge, hides Up Next, disables skip. */
    val isLiveRadio: Boolean              = false,
    /** Station cover, shown on the Now Playing art when a live station is paused. */
    val radioStationArt: String?          = null,
    /** Keep audio playing when the app goes to the background / home is pressed. */
    val backgroundPlayEnabled: Boolean    = false,
    /** True while the activity is in Picture-in-Picture (renders the minimal PiP view). */
    val isInPip:          Boolean         = false,
)

/** Hidden LYRICS baseline: the Fire TV shows the lyric line ~200 ms before you hear the word even over
 *  HDMI, so the lyric clock is always shifted by this. Internal — never surfaced in the UI, so the
 *  menu's "extra" reads 0 normally. The BEAT does NOT get this baseline (it's already aligned on HDMI). */
private const val LYRICS_BASELINE_MS = 200L
/** Added to BOTH the beat and the lyrics when a Bluetooth output is live (Automatic mode). Bluetooth
 *  delays the actual sound by roughly this much; an estimate, since Android exposes no exact figure. */
private const val BT_DELTA_MS = 350L

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MusicRepository,
    private val moshi: Moshi,
    private val mutPrefs: com.applemusicktv.data.MutPreferences,
    private val serverPrefs: ServerPreferences,
    private val appleClient: AppleDirectClient,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
    private val crossfadePrefs: com.applemusicktv.data.CrossfadePreferences,
    private val onboardingPrefs: com.applemusicktv.data.OnboardingPreferences,
    private val standalonePrefs: com.applemusicktv.data.StandalonePreferences,
    private val webServer: InAppWebServer,
    val beatAnalyzer: BeatAnalyzer,
) : ViewModel() {

    private var albumWallPrefetch: kotlinx.coroutines.Deferred<List<com.applemusicktv.ui.wall.CachedWallCard>>? = null

    /** One stable selection per player ViewModel lifetime, shared across player re-entry. */
    private var albumWallCapacity = 100
    private var currentWallBoard: com.applemusicktv.ui.wall.WallBoard? = null

    fun albumWallBoard(pool: List<com.applemusicktv.ui.wall.CachedWallCard>, capacity: Int): com.applemusicktv.ui.wall.WallBoard {
        val previous = currentWallBoard
        if (previous != null && previous.pool === pool && previous.capacity == capacity) return previous
        return com.applemusicktv.ui.wall.WallBoard(pool, capacity,
            previous?.takeIf { it.pool === pool }?.cards.orEmpty()).also { currentWallBoard = it }
    }
    fun prefetchAlbumWall(capacity: Int = 100): kotlinx.coroutines.Deferred<List<com.applemusicktv.ui.wall.CachedWallCard>> {
        albumWallCapacity = capacity.coerceIn(1, 100)
        return albumWallPrefetch ?: viewModelScope.async { albumWallArtwork() }.also { albumWallPrefetch = it }
    }

    private var nextAlbumWall: List<com.applemusicktv.ui.wall.CachedWallCard>? = null
    private var albumWallRefresh: kotlinx.coroutines.Job? = null

    /** Only prepare a new selection after the wall is no longer visible. */
    fun prepareNextAlbumWall() {
        if (nextAlbumWall != null || albumWallRefresh?.isActive == true) return
        albumWallRefresh = viewModelScope.launch {
            val prepared = albumWallArtwork()
            if (prepared.isNotEmpty()) nextAlbumWall = prepared
        }
    }

    /** Called only while the wall is hidden; never replace a visible wall. */
    fun takePreparedAlbumWall(): List<com.applemusicktv.ui.wall.CachedWallCard>? {
        val prepared = nextAlbumWall ?: return null
        nextAlbumWall = null
        albumWallPrefetch = kotlinx.coroutines.CompletableDeferred(prepared)
        return prepared
    }

    /** Metadata from all three page caches, joined to existing local image snapshots. */
    suspend fun albumWallArtwork(): List<com.applemusicktv.ui.wall.CachedWallCard> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val account = mutPrefs.getMUT()
            val cards = com.applemusicktv.ui.wall.CachedWallCards(context, moshi).load(account, (albumWallCapacity + 40).coerceAtMost(160))
            if (account == mutPrefs.getMUT()) cards else emptyList()
        }

    private fun hasMUT() = mutPrefs.hasMUT()
    private fun isStandalone() = !serverPrefs.hasPcServer()

    /** Resolve a card's animated-artwork loop lazily (on focus). Null = stay static.
     *  Honors the "动态封面" setting so cards don't spin up video decoders when it's off. */
    suspend fun cardMotion(album: Album): String? =
        if (!_state.value.motionArtworkEnabled) null else repo.cardMotion(album.id, album.type)


    private val prefs: SharedPreferences =
        context.getSharedPreferences("player_state", Context.MODE_PRIVATE)

    private val bookmarkWriterDelegate = lazy {
        PlaybackStateWriter(prefs, moshi, onError = { error ->
            webServer.addLog("SAVE", "bookmark failed (${error.javaClass.simpleName})")
        })
    }
    private val bookmarkWriter by bookmarkWriterDelegate

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state
    val artworkCentered: StateFlow<Boolean> = _state.stablePlayerLayout()
        .onEach { webServer.addLog("LAYOUT", "lyrics layout=${if (it) "centered" else "split"}") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val lyricsFailureNotice = LyricsFailureNotice(viewModelScope)
    private val diskCache = AppDiskCache.get(context)
    private val systemAudio by lazy {
        com.applemusicktv.media.SystemAudioLoader(viewModelScope,
            com.applemusicktv.data.cache.EncryptedAudioStore(diskCache.store), mutPrefs::getMUT,
            appleClient::getBearer, appleClient::getWebPlayback,
            { webServer.addLog("AUDIOCACHE", it) })
    }
    private val systemCacheSources = mutableSetOf<String>()
    private var systemCacheRetryFor: String? = null
    private var api23SoftwareFallbackSong: String? = null

    init {
        viewModelScope.launch { lyricsFailureNotice.status.collect { notice ->
            _state.update { current ->
                if (notice.songId != current.currentSong?.id || notice.generation < current.lyricsNoticeGeneration) current
                else current.copy(lyricsNotice=notice.phase,lyricsNoticeSongId=notice.songId,
                    lyricsNoticeGeneration=notice.generation,emptyLyricsNoticeCompletedFor=notice.completedEmptySong)
            }
        } }

        diskCache.protect("audio") {
            val s = _state.value
            AppDiskCache.audioNames(listOfNotNull(s.currentSong?.id,
                s.queue.getOrNull(s.queueIndex - 1)?.id,
                s.queue.getOrNull(s.queueIndex + 1)?.id,
                s.userQueue.firstOrNull()?.id))
        }
    }

    // Integrated queue: the queue may hold music videos. When the current item is a video,
    // the audio engine idles and this emits the video so the UI hands it to the video player.
    // Null means the current item is a normal song (any active video should be dismissed).
    // autoOpen=true only when the user EXPLICITLY picked the video (tapped it in a list) — that
    // jumps to Now Playing. Auto-advance / skip keep it false so the video plays without yanking
    // the user off whatever page they're browsing; they just see it when they visit Now Playing.
    data class VideoRequest(val song: Song, val autoOpen: Boolean, val startPaused: Boolean = false)
    private val _videoRequest = MutableStateFlow<VideoRequest?>(null)
    val videoRequest: StateFlow<VideoRequest?> = _videoRequest
    private val _userPlaybackRequests = MutableStateFlow(0L)
    val userPlaybackRequests: StateFlow<Long> = _userPlaybackRequests
    fun clearVideoRequest() { _videoRequest.value = null }

    /**
     * One-off user-facing messages. extraBufferCapacity so an emit from the polling
     * thread never suspends, and replay=0 so a message doesn't re-fire on rotation.
     */
    private val _toasts = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts
    fun toast(msg: String) { _toasts.tryEmit(msg) }

    private var lastErrorKey: String? = null
    private var hasPlayedSomething = false
    var nowPlayingVisible = false
    private var mediaSession: androidx.media3.session.MediaSession? = null
    fun showEmptyLyricsNotice(songId: String, generation: Long) = lyricsFailureNotice.showWhenReady(songId,generation)
    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var lyricsLoadGeneration = 0L
    // Small cache of already-fetched lyrics, keyed by song id. Lets a track change show its lyrics
    // instantly (from the N+1 prefetch) instead of blanking to empty and re-fetching — which, in
    // full-screen lyrics, flashed the view back to the player for a beat.
    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, List<LyricLine>>()
    private var motionJob: kotlinx.coroutines.Job? = null
    private var fadeJob: kotlinx.coroutines.Job? = null
    private var crossfadeInProgress = false
    /** Mirrors [crossfadePrefs] — read on the polling thread, so keep it a plain field. */
    @Volatile private var crossfadeDurationMs = com.applemusicktv.data.CrossfadePreferences.DEFAULT_MS
    private var crossfadeExo: ExoPlayer? = null
    private var cfExoErrListener: Player.Listener? = null  // stored so STATE_ENDED snap can remove it
    private var prefetchRequestedForSongId: String? = null
    private var crossfadeSkipSongId: String? = null
    /** Song whose crossfade-window decision has already been logged (see pollProgress). */
    private var cfWindowLoggedForSongId: String? = null
    /** Title of the song whose standalone attempt already failed — retry proxy once, then give up. */
    private var standaloneFailedSongId: String? = null
    /** True while the sleep timer's final-5s volume ramp is active (so a cancel can restore volume). */
    private var sleepFadeActive = false
    /** Library song id whose catalog (not-in-library) on-device retry already ran — try once. */
    private var catalogRetriedSongId: String? = null
    /** Songs Apple 404s on (pulled from the catalogue). Skipped without a retry. */
    private val unavailableSongIds = mutableSetOf<String>()
    /** Avoids re-logging the audio format on every READY (seeks re-enter it). */
    private var lastFormatLoggedFor: String? = null
    /** Rebuffers seen on the current song, and which song they belong to. */
    private var stutterSongId: String? = null
    private var stutterCount = 0
    /** Songs whose standalone encode stutters — permanently routed via the proxy. */
    private val proxyOnlySongIds = mutableSetOf<String>()
    private var standaloneFailures = 0
    /** Pending N+1 prefetch, cancelled whenever a new song starts loading. */
    private var prefetchJob: kotlinx.coroutines.Job? = null
    /** Bumped on every playQueueItem — a slow async standalone build checks it before
     *  starting playback so rapidly-skipped songs don't each play for a moment. */
    private var playGen = 0
    private val playbackRequests = PlaybackRequestGate()
    private var stationJob: kotlinx.coroutines.Job? = null
    private var relatedQueueJob: kotlinx.coroutines.Job? = null
    private var relatedRequestedGeneration = -1
    private val sourcePreparation = SourcePreparation()
    private var attachedSourceGeneration = -1

    private var radioRetryJob: kotlinx.coroutines.Job? = null

    /** Reserve ownership before fetching a playlist/station outside this ViewModel. */
    fun reservePlaybackRequest(): Long = beginPlaybackSelection()
    fun playAlbumIfCurrent(request: Long, songs: List<Song>) {
        if (playbackRequests.isCurrent(request)) {
            val station = _state.value.stationLaunch != null
            if (station) stationJob = null
            playAlbum(songs, openPlayer = !station)
        }
    }

    private fun beginPlaybackSelection(keepDecryptFor: String? = null): Long {
        if (_state.value.stationLaunch != null) _state.update { it.copy(stationLaunch = null,
            emptyLyricsNoticeCompletedFor = null, exhaustedEmptyLyricsFor = null) }
        if (sourcePreparation.isPending) {
            ++playGen
            sourcePreparation.cancel()
            awaitingSongStart = null
            _state.update { it.copy(isLoading = false) }
        }
        radioRetryJob?.cancel(); radioRetryJob = null
        prefetchJob?.cancel()
        cancelOtherDecrypts(keepDecryptFor)
        stationJob?.cancel()
        stationJob = null
        return playbackRequests.select()
    }

    private fun currentSourceReady(generation: Int): Boolean =
        generation == playGen && attachedSourceGeneration == generation &&
            player.playbackState == Player.STATE_READY

    // True while the on-device (Widevine) path is driving playback, so the
    // error handler doesn't bounce back to the proxy in a loop.
    private var usingStandalone: Boolean = false
    private var lastCheckedProxy: String? = null
    private var proxyHadSuccessfulCheck = false


    /** Held so onCleared can unregister it — AudioManager outlives this ViewModel. */
    private var audioDeviceCallback: AudioDeviceCallback? = null



    /**
     * The stock c2.android.aac.decoder glitches on some HE-AAC (SBR) encodes at
     * segment boundaries (Love Me Again, Bonetrousle). The device also ships the
     * older OMX.google.aac.decoder — a different implementation — so prefer it for
     * AAC and see if it handles the boundaries cleanly. Both are software, so both
     * work on the Widevine (L3) secure path.
     */
    @OptIn(UnstableApi::class)
    private val aacPreferOmxSelector =
        androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, secure, tunneling ->
            val infos = androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                .getDecoderInfos(mimeType, secure, tunneling)
            if (mimeType == androidx.media3.common.MimeTypes.AUDIO_AAC) {
                webServer.addLog("CODEC", "aac decoders (secure=$secure): ${infos.joinToString { it.name }}")
                infos.sortedByDescending { it.name.startsWith("OMX.google.aac") }
            } else infos
        }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(): ExoPlayer = run {
        // The proxy holds the HTTP connection open for a few seconds while it
        // decrypts (no bytes until mp4decrypt finishes), so give ExoPlayer
        // generous connect/read timeouts — otherwise the first play of a fresh
        // (uncached) song times out and silently fails, which looks like "some
        // songs don't play."
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory =
            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,  // min buffer — standalone streams live from Apple's CDN, and
                         // a WAN dip underruns long before a 15s floor notices
                180_000, // max buffer: hoard as much of the track as we can
                2_500,   // buffer to start after initial load
                5_000,   // buffer to restart after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // Byte cap, not just seconds. The default is sized for video and starves a
            // 320kbps audio stream well before the duration targets are reached, which
            // is what left otherside sitting at 35% buffered and skipping.
            .setTargetBufferBytes(32 * 1024 * 1024)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setRenderersFactory(
                BeatAwareRenderersFactory(context, beatAnalyzer.newProcessor().also { p ->
                    mainProc = p; beatAnalyzer.activate(p)
                }, com.applemusicktv.media.GapConcealProcessor())
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(aacPreferOmxSelector)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), false, // don't yield to Fire TV Alexa audio focus steals
            )
            .setHandleAudioBecomingNoisy(false)
            .build().also { it.repeatMode = Player.REPEAT_MODE_OFF }
    }

    /** Processor feeding the beat bus for [player]; [cfProc] for the crossfade player. */
    private var mainProc: com.applemusicktv.media.BeatProcessor? = null
    private var cfProc: com.applemusicktv.media.BeatProcessor? = null

    private data class PreparedNext(val songId:String,val generation:Int,val exo:ExoPlayer,
        val processor:com.applemusicktv.media.BeatProcessor?,val listener:Player.Listener)
    private var preparedNext:PreparedNext?=null
    private var preparedNextJob:kotlinx.coroutines.Job?=null

    private fun clearPreparedNext(keep:String?=null) {
        if(keep!=null && preparedNext?.songId==keep) return
        preparedNextJob?.cancel();preparedNextJob=null
        preparedNext?.let {it.exo.removeListener(it.listener);it.exo.stop();it.exo.release()}
        preparedNext=null
    }
    private fun takePreparedNext(id:String):PreparedNext? {
        val held=preparedNext
        val usable=held?.takeIf {it.songId==id && it.generation==playGen && it.exo.playbackState==Player.STATE_READY}
        if(usable!=null) {preparedNext=null;preparedNextJob?.cancel();preparedNextJob=null;usable.exo.removeListener(usable.listener)}
        else clearPreparedNext()
        return usable
    }
    /** Check live settings both before and after the asynchronous source preparation. */
    private fun canPrepareNextWithoutFade(song:Song,generation:Int):Boolean {
        val st=_state.value
        val current=st.currentSong ?: return false
        val next=st.userQueue.firstOrNull() ?: st.queue.getOrNull(st.queueIndex+1)
            ?: st.queue.firstOrNull().takeIf {st.repeatMode==RepeatMode.All && st.queue.size>1}
        val sameAlbum=if(current.albumId!=null && song.albumId!=null) current.albumId==song.albumId
            else current.albumName.isNotBlank() && current.albumName==song.albumName
        val consecutive=current.trackNumber!=null && song.trackNumber==current.trackNumber+1
        val noFade=com.applemusicktv.data.CrossfadePreferences.needsPreparedNext(
            st.crossfadeEnabled,crossfadeDurationMs,sameAlbum && consecutive && st.userQueue.isEmpty())
        return generation==playGen && next?.id==song.id && noFade && !song.isMusicVideo && st.repeatMode!=RepeatMode.One
    }

    /** Same-album/no-fade transitions still need a prepared decoder and DRM session before the boundary. */
    private fun prepareNextWithoutFade(song:Song,generation:Int) {
        if(!canPrepareNextWithoutFade(song,generation)) return
        clearPreparedNext()
        preparedNextJob=viewModelScope.launch {
            val source=buildStandaloneSource(song) ?: return@launch
            if(!canPrepareNextWithoutFade(song,generation) || _state.value.stationLaunch!=null) return@launch
            val previousProc=cfProc
            val exo=buildCrossfadeExo()
            val proc=cfProc;cfProc=previousProc
            val listener=object:Player.Listener {
                override fun onPlaybackStateChanged(state:Int) {
                    if(state==Player.STATE_READY) webServer.addLog("PRE","player ready song=${song.id}")
                }
                override fun onPlayerError(error:androidx.media3.common.PlaybackException) {
                    webServer.addLog("PRE","player failed song=${song.id} code=${error.errorCodeName}")
                    if(preparedNext?.exo===exo) clearPreparedNext()
                }
            }
            preparedNext=PreparedNext(song.id,generation,exo,proc,listener)
            exo.addListener(listener);exo.volume=1f;exo.playWhenReady=false
            exo.setMediaSource(source);exo.prepare()
        }
    }

    /** Hand the beat bus over to the crossfade player once it becomes the one we hear. */
    private fun promoteCrossfadeBeat() {
        cfProc?.let { beatAnalyzer.activate(it); mainProc = it }
        cfProc = null
    }

    /** Crossfade-only player. Own BeatProcessor so it can drive the visuals after the swap. */
    @OptIn(UnstableApi::class)
    private fun buildCrossfadeExo(): ExoPlayer {
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000).setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .setRenderersFactory(
                BeatAwareRenderersFactory(context, beatAnalyzer.newProcessor().also { cfProc = it }, com.applemusicktv.media.GapConcealProcessor())
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(aacPreferOmxSelector)
            )
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setHandleAudioBecomingNoisy(false)
            .build().also { it.repeatMode = Player.REPEAT_MODE_OFF }
    }

    /**
     * MediaCodec-only player for LIVE radio. Radio decodes Widevine-**secure** (L1) AAC inside
     * ExoPlayer; the bundled FFmpeg software renderer can't read secure buffers (it throws
     * `FfmpegAudioRenderer error … NO_UNSUPPORTED_DRM`). So this player uses
     * EXTENSION_RENDERER_MODE_OFF — MediaCodec handles the secure path. The main [player] keeps
     * PREFER because standalone songs need FFmpeg for HE-AAC; radio is always plain AAC-LC.
     */
    @OptIn(UnstableApi::class)
    private fun buildRadioExoPlayer(): ExoPlayer {
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000).setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)))
            .setLoadControl(loadControl)
            .setRenderersFactory(
                BeatAwareRenderersFactory(context, beatAnalyzer.newProcessor().also { p ->
                    mainProc = p; beatAnalyzer.activate(p)
                }, com.applemusicktv.media.GapConcealProcessor())
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    .setEnableDecoderFallback(true)
            )
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setHandleAudioBecomingNoisy(false)
            .build().also { it.repeatMode = Player.REPEAT_MODE_OFF }
    }

    /** True while [player] is the MediaCodec-only radio instance. */
    private var radioActive = false
    /** Bounded re-prepare attempts after a live-radio decoder/network drop; reset when it recovers. */
    private var radioRecoveries = 0
    private var lastMemWarn = 0L
    /** The station's own cover (Apple Music 1 logo, etc.) — shown when paused, since a paused
     *  stream has no "current track". Kept across the metadata track-art swaps. */
    private var radioStationArt: String? = null

    /** Warn (throttled) when the device is under memory pressure — the Fire TV reclaims the media
     *  codec when RAM runs low, which is what drops live radio. Thermal isn't readable without
     *  privilege, but memory pressure tracks it closely here. */
    private fun maybeWarnMemory() {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val now = System.currentTimeMillis()
        if ((mi.lowMemory || mi.availMem < mi.threshold * 2) && now - lastMemWarn > 60_000L) {
            lastMemWarn = now
            toast(tr(R.string.msg_016))
        }
    }

    /** Swap the shared [player] for a fresh instance built by [factory], carrying over the listener
     *  and MediaSession. Mirrors the crossfade snap swap. */
    @OptIn(UnstableApi::class)
    private fun swapPlayer(factory: () -> ExoPlayer) {
        val oldP = player
        try { oldP.removeListener(playerListener); oldP.stop(); oldP.release() } catch (_: Exception) {}
        player = factory().also { it.addListener(playerListener) }
        mediaSession?.release(); mediaSession = buildMediaSession(player)
    }

    /** Leaving radio → restore the FFmpeg-preferring player the rest of playback expects. */
    @OptIn(UnstableApi::class)
    private fun ensureMainPlayer() {
        if (!radioActive) return
        radioActive = false
        radioRecoveries = 0
        _state.update { it.copy(isLiveRadio = false) }
        swapPlayer { buildExoPlayer() }
    }

    @OptIn(UnstableApi::class)
    var player: ExoPlayer = buildExoPlayer()
        private set

    init {
        // Offset can also be changed from the phone web server — follow it live.
        viewModelScope.launch {
            lyricsOffsetPrefs.offsetMs.collect { ms ->
                _state.update { it.copy(lyricsOffsetMs = ms) }
                updateOutputLatency()
            }
        }
        // Same for crossfade length. Takes effect at the next song boundary — an
        // in-flight fade keeps the length it started with.
        viewModelScope.launch {
            crossfadePrefs.durationMs.collect { ms ->
                val wasEnabled=com.applemusicktv.data.CrossfadePreferences.isEnabled(_state.value.crossfadeEnabled,crossfadeDurationMs)
                crossfadeDurationMs=ms
                val enabled=com.applemusicktv.data.CrossfadePreferences.isEnabled(_state.value.crossfadeEnabled,ms)
                if(wasEnabled!=enabled && !crossfadeInProgress) {
                    clearPreparedNext()
                    // A previous prefetch may already have finished while fading was enabled.
                    // Reuse the warmed cache to prepare the no-fade handoff after switching off.
                    val s=_state.value
                    val next=s.userQueue.firstOrNull() ?: s.queue.getOrNull(s.queueIndex+1)
                        ?: s.queue.firstOrNull().takeIf {s.repeatMode==RepeatMode.All && s.queue.size>1}
                    if(!enabled && s.currentSong!=null && next!=null) prefetchSong(next)
                }
            }
        }
    }

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetPrefs.setOffset(ms)
        _state.update { it.copy(lyricsOffsetMs = ms) }
        updateOutputLatency()
    }


    fun setAvSyncAuto(auto: Boolean) {
        prefs.edit().putBoolean("av_auto", auto).apply()
        _state.update { it.copy(avSyncAuto = auto) }
        updateOutputLatency()
    }

    /**
     * Recompute the single output-latency figure that drives BOTH the beat visuals and the lyric clock:
     * the embedded [AV_BASELINE_MS] baseline, plus [AV_BT_ESTIMATE_MS] when a Bluetooth output is live
     * and Automatic mode is on, plus the user's [PlayerState.lyricsOffsetMs] extra. Called on init, on
     * every audio-device change, and when either the mode or the extra is edited.
     */
    private fun updateOutputLatency() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        val btDev = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in btTypes }
        }.getOrNull()
        val onBt = btDev != null
        val extra = _state.value.lyricsOffsetMs
        val auto = _state.value.avSyncAuto
        val btDelta = if (onBt && auto) BT_DELTA_MS else 0L
        // Beat has NO HDMI baseline (it's aligned already), it only gains the Bluetooth delta.
        val beatMs = btDelta + extra
        // Lyrics carry the hidden 200 ms display baseline on every output, plus the same BT delta.
        val lyricsMs = LYRICS_BASELINE_MS + btDelta + extra
        beatAnalyzer.latencyMs = beatMs
        val label = if (onBt) tr(R.string.msg_017) + (btDev?.productName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    else tr(R.string.msg_015)
        _state.update { it.copy(avOnBluetooth = onBt, avLyricsMs = lyricsMs, avOutputLabel = label) }
    }

    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: androidx.media3.common.Metadata) = onRadioMetadata(metadata)
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val pos = player.currentPosition
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("EXO", "isPlaying=$isPlaying pos=${pos}ms song=$song source=${player.currentMediaItem?.mediaId} gen=$attachedSourceGeneration/$playGen preparing=${sourcePreparation.isPending} cfade=$crossfadeInProgress")
            if (isPlaying && radioActive) radioRecoveries = 0   // live radio recovered
            _state.update { it.copy(isPlaying = isPlaying, stationLaunch = if (isPlaying && radioActive && (it.stationLaunch == null || player.currentMediaItem?.mediaId == it.stationLaunch.id)) null else it.stationLaunch) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($state)"
            }
            val pos = player.currentPosition
            val dur = player.duration
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("EXO", "state=$name pos=${pos}ms dur=${dur}ms song=$song source=${player.currentMediaItem?.mediaId} gen=$attachedSourceGeneration/$playGen preparing=${sourcePreparation.isPending} cfade=$crossfadeInProgress")
            // One-line audio diagnostic per track: what actually got decoded, and by
            // which path. Guessing at codec/rate from the server side isn't possible.
            // Some encodes stream badly on the standalone path — the proxy's remux
            // repairs segment gaps that ExoPlayer just plays through. Rather than
            // guessing which tracks those are, watch for repeated rebuffering and
            // move that song to the proxy for good.
            val curSong = _state.value.queue.getOrNull(_state.value.queueIndex)
            if (state == Player.STATE_BUFFERING && usingStandalone && player.playWhenReady) {
                if (stutterSongId != curSong?.id) { stutterSongId = curSong?.id; stutterCount = 0 }
                stutterCount++
                // Only worth falling back to the proxy if the proxy is actually
                // reachable. With in-app decrypt the file plays fine and a brief
                // seek-induced rebuffer must NOT bounce us to a dead server.
                if (stutterCount >= STUTTER_LIMIT && curSong != null && serverPrefs.serverReachable &&
                    curSong.id !in proxyOnlySongIds && player.currentPosition > 3_000
                ) {
                    proxyOnlySongIds.add(curSong.id)
                    usingStandalone = false
                    val at = player.currentPosition
                    webServer.addLog("PLR", "standalone stuttering on ${curSong.title} " +
                        "($stutterCount rebuffers) — switching to proxy at ${at}ms")
                    player.setMediaItem(buildMediaItem(curSong, repo.streamUrl(curSong.id)), at)
                    player.prepare(); player.play()
                }
            }
            if (state == Player.STATE_READY && lastFormatLoggedFor != song) {
                lastFormatLoggedFor = song
                val f = player.audioFormat
                webServer.addLog(
                    "FMT",
                    "codec=${f?.sampleMimeType ?: "?"} " +
                        "bitrate=${f?.bitrate ?: -1} rate=${f?.sampleRate ?: -1}Hz " +
                        "ch=${f?.channelCount ?: -1} enc=${f?.pcmEncoding ?: -1} " +
                        "path=${if (usingStandalone) "standalone" else "proxy"} " +
                        "buffered=${player.bufferedPercentage}%",
                )
            }
            if(state==Player.STATE_ENDED && !crossfadeInProgress &&
                preparedNext?.generation==playGen && preparedNext?.exo?.playbackState==Player.STATE_READY) {
                val generation=playGen
                viewModelScope.launch {
                    kotlinx.coroutines.yield()
                    if(generation==playGen && player.playbackState==Player.STATE_ENDED) advanceQueue()
                }
            }
            // Old player ended naturally during crossfade — snap cfExo in immediately
            if (state == Player.STATE_ENDED && crossfadeInProgress) {
                webServer.addLog("CFXO", "STATE_ENDED mid-fade cfExo=${crossfadeExo != null} cfExoState=${crossfadeExo?.playbackState} fadeJob=${fadeJob?.isActive}")
                try {
                    fadeJob?.cancel()
                    val cfExo = crossfadeExo
                    crossfadeExo = null
                    crossfadeInProgress = false
                    val oldP = player
                    oldP.removeListener(this)
                    oldP.volume = 0f; oldP.stop(); oldP.release()
                    if (cfExo != null && (cfExo.playbackState == Player.STATE_READY || cfExo.playbackState == Player.STATE_BUFFERING)) {
                        cfExo.volume = 1f
                        player = cfExo
                        promoteCrossfadeBeat()
                        cfExoErrListener?.let { cfExo.removeListener(it) }; cfExoErrListener = null
                        cfExo.addListener(this)
                        webServer.addLog("CFXO", "snap done cfExo.isPlaying=${cfExo.isPlaying} state=${cfExo.playbackState}")
                        val sNow = _state.value
                        val nextUp = sNow.userQueue.firstOrNull() ?: sNow.queue.getOrNull(sNow.queueIndex + 1)
                        if (nextUp != null && prefetchRequestedForSongId != nextUp.id) {
                            prefetchRequestedForSongId = nextUp.id; prefetchSong(nextUp)
                        }
                        _state.update { it.copy(isPlaying = cfExo.isPlaying) }
                        // Rebuilding the MediaSession reinits the audio output pipeline —
                        // defer it so it doesn't gap the audio right at the swap.
                        viewModelScope.launch {
                            delay(500)
                            mediaSession?.release()
                            mediaSession = buildMediaSession(player)
                        }
                    } else {
                        // cfExo bad or null — need a fresh player, then advance
                        webServer.addLog("CFXO", "snap: cfExo unusable (state=${cfExo?.playbackState}) — rebuilding player and advancing")
                        try { cfExo?.stop(); cfExo?.release() } catch (_: Exception) {}
                        player = buildExoPlayer().also { it.addListener(this) }
                        mediaSession?.release()
                        mediaSession = buildMediaSession(player)
                        advanceQueue()
                    }
                } catch (e: Exception) {
                    webServer.addLog("CFXO", "snap exception: ${e.message}")
                    crossfadeInProgress = false
                    advanceQueue()
                }
            }
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            // During crossfade, REPEAT_MODE_ONE is intentionally set on old player — don't reset it
            if (repeatMode != Player.REPEAT_MODE_OFF && !crossfadeInProgress) player.repeatMode = Player.REPEAT_MODE_OFF
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val pos = player.currentPosition
            // Live radio: a single continuous stream, so advanceQueue() has nothing to skip to.
            // Transient MediaCodec/network drops (esp. under thermal pressure) just kill it — so
            // re-prepare the live source at the live edge. Bounded so a persistent failure doesn't spin.
            if (radioActive) {
                val sid = _state.value.currentSong?.id
                maybeWarnMemory()   // a reclaimed codec under memory/thermal pressure is the usual cause
                webServer.addLog("ERR", "live radio ${error.errorCodeName} — re-prepare $sid (#${radioRecoveries + 1})")
                if (sid != null && sid.startsWith("ra.") && radioRecoveries < 6) {
                    radioRecoveries++
                    radioActive = false   // force a fresh MediaCodec player in playLiveStation
                    val request = playbackRequests.revision
                    val generation = playGen
                    radioRetryJob?.cancel()
                    radioRetryJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(800L * radioRecoveries)
                        if (playbackRequests.isCurrent(request) && generation == playGen && player.playWhenReady) {
                            radioRetryJob = null
                            playLiveStation(sid, openPlayer = false)
                        }
                    }
                } else {
                    toast(tr(R.string.msg_018))
                    _state.value.stationLaunch?.let { failStationIfCurrent(it.request) }
                    _state.update { it.copy(isPlaying = false) }
                }
                return
            }
            // Preserve the user's play/pause intent across error recovery — a restored-paused track
            // that fails standalone and retries via proxy must NOT auto-start playing.
            val wasPlaying = player.playWhenReady
            val song = _state.value.currentSong?.title ?: "?"
            webServer.addLog("ERR", "${error.errorCodeName} pos=${pos}ms song=$song cfade=$crossfadeInProgress cause=${error.cause?.message}")
            val current=_state.value.currentSong
            val isCurrentApi23Song = android.os.Build.VERSION.SDK_INT == 23 && usingStandalone &&
                current != null && !current.isMusicVideo && !crossfadeInProgress &&
                player.currentMediaItem?.mediaId == current.id && attachedSourceGeneration == playGen
            if (isCurrentApi23Song && api23SoftwareFallbackSong == current!!.id) {
                webServer.addLog("SOURCE", "api23 software fallback failed")
                stopFailedPreparation(current)
                return
            }
            val unsupportedDrm = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ||
                generateSequence(error as Throwable?) { it.cause }.take(12).any {
                    it is android.media.MediaDrmException && it.message?.contains("dummy ExoMediaDrm") == true
                }
            if (isCurrentApi23Song && unsupportedDrm && api23SoftwareFallbackSong == null) {
                val target = current!!
                api23SoftwareFallbackSong = target.id
                clearPreparedNext()
                val generation = ++playGen
                sourcePreparation.begin(generation, wasPlaying)
                attachedSourceGeneration = -1
                player.stop(); player.clearMediaItems()
                _state.update { it.copy(isLoading = wasPlaying, isPlaying = false) }
                webServer.addLog("SOURCE", "api23 system drm unavailable; trying software once")
                viewModelScope.launch {
                    val fallback = buildDecryptedStandaloneSource(target)
                    if (generation != playGen) return@launch
                    if (fallback == null) { stopFailedPreparation(target); return@launch }
                    val play = sourcePreparation.finish(generation) ?: return@launch
                    attachedSourceGeneration = generation
                    player.setMediaSource(fallback, pos.coerceAtLeast(0L)); player.prepare(); player.playWhenReady = play
                }
                return
            }
            if (current!=null && current.id in systemCacheSources && systemCacheRetryFor!=current.id &&
                player.currentMediaItem?.mediaId==current.id && !crossfadeInProgress) {
                systemCacheRetryFor=current.id
                systemCacheSources-=current.id
                systemAudio.invalidate(current.id)
                clearPreparedNext()
                systemAudio.cancelExcept(current.id)
                val generation=++playGen
                sourcePreparation.begin(generation,wasPlaying)
                attachedSourceGeneration=-1
                player.stop();player.clearMediaItems()
                _state.update {it.copy(isLoading=wasPlaying,isPlaying=false)}
                webServer.addLog("SOURCE","refresh cached source song=${current.id}")
                viewModelScope.launch {
                    val refreshed=buildSystemStandaloneSource(current)
                    if(generation!=playGen) return@launch
                    if(refreshed==null) {stopFailedPreparation(current);return@launch}
                    val play=sourcePreparation.finish(generation) ?: return@launch
                    attachedSourceGeneration=generation
                    player.setMediaSource(refreshed,pos.coerceAtLeast(0L));player.prepare();player.playWhenReady=play
                }
                return
            }
            // NOTE: each branch below owns its own toast — the generic "skipping" one lives on the
            // final give-up path so a single failure never fires two toasts (404 / proxy-retry did).
            val gone = error.errorCode ==
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                (error.cause?.message?.contains("404") == true)
            if (gone) {
                val cur = _state.value.queue.getOrNull(_state.value.queueIndex)
                if (cur != null) unavailableSongIds.add(cur.id)
                webServer.addLog("ERR", "unavailable (404) song=$song — skipping, won't retry")
                toast(tr(R.string.msg_019, song))
                advanceQueue()
                return
            }

            // Retry a stale release once, after validating a same-song catalog replacement.
            run {
                val cur = _state.value.queue.getOrNull(_state.value.queueIndex)
                if (usingStandalone && !crossfadeInProgress && cur != null &&
                    !_state.value.isLiveRadio && !cur.isMusicVideo && catalogRetriedSongId != cur.id) {
                    val myGen = ++playGen
                    sourcePreparation.begin(myGen, wasPlaying)
                    viewModelScope.launch {
                        val src = tryCatalogRecoverySource(cur, myGen)
                        if (myGen != playGen) return@launch
                        val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
                        if (src != null) {
                            usingStandalone = true
                            attachedSourceGeneration = myGen
                            player.setMediaSource(src, pos); player.prepare()
                            player.volume = 1f; player.playWhenReady = shouldPlay
                            _state.update { it.copy(standaloneActive = true) }
                        } else {
                            retryStandaloneOrProxy(cur, pos, shouldPlay, song)
                        }
                    }
                    return
                }
            }
            // A standalone failure must not advance — it'd fail identically on the next
            // song and rip through the whole queue. Retry this one through the proxy.
            if (usingStandalone && !crossfadeInProgress && standaloneFailedSongId != song) {
                val cur = _state.value.queue.getOrNull(_state.value.queueIndex)
                if (cur != null) {
                    retryStandaloneOrProxy(cur, pos, wasPlaying, song)
                    return
                }
            }
            if (crossfadeInProgress && crossfadeExo != null) {
                // Old player died mid-fade — snap cfExo to full volume immediately
                crossfadeExo!!.volume = 1f
                // cfExo is audible; keep UI showing playing so it doesn't flash paused
                _state.update { it.copy(isPlaying = true) }
            } else {
                // Final give-up: neither standalone retry nor a 404 owned this — say why we skipped.
                toast(if (serverPrefs.serverReachable) tr(R.string.msg_020, song)
                      else tr(R.string.msg_021))
                advanceQueue()
            }
        }
    }

    /** Last-resort fallback for a song that failed on-device (both its own and, for
     *  library ids, its catalog copy): route it through the PC proxy. No-op if there's
     *  no current queue item. */
    private fun retryStandaloneOrProxy(cur: Song, pos: Long, wasPlaying: Boolean, songTitle: String) {
        standaloneFailedSongId = songTitle
        usingStandalone = false
        standaloneFailures++
        _state.update { it.copy(standaloneActive = false) }
        if (!serverPrefs.serverReachable) {
            stopFailedPreparation(cur)
            return
        }
        webServer.addLog("PLR", "on-device exhausted for $songTitle — retrying via proxy (#$standaloneFailures)")
        if (standaloneFailures >= 3 && standalonePrefs.isEnabled()) {
            standalonePrefs.setEnabled(false)
            webServer.addLog("PLR", "standalone disabled after $standaloneFailures failures")
            toast(tr(R.string.msg_022))
        } else {
            toast(tr(R.string.msg_023))
        }
        attachedSourceGeneration = playGen
        player.setMediaItem(buildMediaItem(cur, repo.streamUrl(cur.id)))
        player.prepare()
        if (pos > 3_000) player.seekTo(pos)
        player.volume = 1f; player.playWhenReady = wasPlaying
    }

    private fun buildMediaSession(exo: ExoPlayer): androidx.media3.session.MediaSession =
        androidx.media3.session.MediaSession.Builder(context, exo)
            .setCallback(object : androidx.media3.session.MediaSession.Callback {
                override fun onConnect(
                    session: androidx.media3.session.MediaSession,
                    controller: androidx.media3.session.MediaSession.ControllerInfo,
                ): androidx.media3.session.MediaSession.ConnectionResult {
                    val pkg = controller.packageName
                    val allowed = pkg == context.packageName
                    return if (allowed) super.onConnect(session, controller)
                    else androidx.media3.session.MediaSession.ConnectionResult.reject()
                }
            }).build()

    init {
        // Apply the requested defaults once to existing installations as well as new ones.
        // Later user changes are preserved. Account, library and playback history are untouched.
        if (!prefs.getBoolean("lab3_defaults_applied", false)) {
            prefs.edit {
                putBoolean("background_play", false)
                putFloat("lyrics_scale", 1.0f)
                if (!prefs.contains("motion_art")) putBoolean("motion_art", false)
                putBoolean("lab3_defaults_applied", true)
            }
        }
        // Load the lightweight display settings up front so they apply even on a cold launch with
        // no track to restore (restoreState, which reads the rest, only runs when a song was saved).
        val initBg = NowPlayingBackground.fromName(prefs.getString("np_background", null))
        com.applemusicktv.media.GainProcessor.enabled = prefs.getBoolean("volume_leveling", false)
        // Volume-leveling per-track gain memory. The cache is a tiny prefs store (one float per track id),
        // capped so it can't grow without bound. (The VOL live logger is wired in AppleMusicApp so it goes
        // to the APP log, not the network log.)
        run {
            val gainCache = context.getSharedPreferences("gain_cache", Context.MODE_PRIVATE)
            com.applemusicktv.media.GainProcessor.cacheGet = { key -> if (gainCache.contains(key)) gainCache.getFloat(key, 1f) else null }
            com.applemusicktv.media.GainProcessor.cachePut = { key, g ->
                if (gainCache.all.size > 500) gainCache.edit().clear().apply()
                gainCache.edit().putFloat(key, g).apply()
            }
        }
        // The 200 ms lyrics latency is now embedded (LYRICS_BASELINE_MS) and hidden, so the user-facing
        // "extra" starts at 0. Force it to 0 once so nobody carries a pre-existing manual offset on top
        // of the new baseline (which would double-count).
        if (!prefs.getBoolean("av_migrated_v2", false)) {
            lyricsOffsetPrefs.setOffset(0L)
            prefs.edit().putBoolean("av_migrated_v2", true).apply()
        }
        if(!prefs.getBoolean("unified_fluid_controls_v9",false)) {
            val quality=FluidQuality.fromName(prefs.getString("fluid_quality",null))
            val migrated=unifiedFluidQuality(quality,prefs.getBoolean("reduce_motion",false),prefs.getBoolean("low_power",false))
            prefs.edit {
                putString("fluid_quality",migrated.name)
                remove("reduce_motion");remove("low_power")
                putBoolean("unified_fluid_controls_v9",true)
            }
        }
        _state.update { it.copy(
            lyricsOffsetMs = lyricsOffsetPrefs.getOffset(),
            avSyncAuto = prefs.getBoolean("av_auto", true),
            backgroundPlayEnabled = prefs.getBoolean("background_play", false),
            nowPlayingBackground = initBg,
            fluidQuality = FluidQuality.fromName(prefs.getString("fluid_quality", null)),
            screensaverKeepBackground = prefs.getBoolean("screensaver_keep_bg", false),
            showNowPlayingInfo = prefs.getBoolean("np_info", true),
            motionArtworkEnabled = prefs.getBoolean("motion_art", false),
            beatIntensity = prefs.getFloat(intensityKey(initBg), 1.0f),
            orbSpeed = prefs.getFloat("orb_speed", 1.0f),
            lyricsScale = prefs.getFloat("lyrics_scale", 1.0f),
            artworkRounded = prefs.getBoolean("artwork_rounded", true),
            reduceMotion = false,
            lowPowerMode = false,
            volumeLeveling = prefs.getBoolean("volume_leveling", false),
        ) }
        player.addListener(playerListener)
        mediaSession = buildMediaSession(player)
        // Confirm the bundled FFmpeg audio decoder loaded — when true, ExoPlayer
        // prefers it over MediaCodec (EXTENSION_RENDERER_MODE_PREFER) and HE-AAC
        // decodes like the proxy, killing the standalone chop.
        webServer.addLog("FFMPEG", "FfmpegLibrary.isAvailable=${androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()}")
        // DIAGNOSTIC: capture ~25s of decoded PCM (44100Hz stereo s16le) from the
        // active player to inspect the standalone chop offline. Remove after repair.
        if (CAPTURE_PCM) try {
            val f = java.io.File(context.cacheDir, "pcmcap.raw")
            beatAnalyzer.startCapture(f.outputStream().buffered(), 44_100 * 2 * 2 * 25)
            webServer.addLog("CAP", "capturing PCM to ${f.absolutePath}")
        } catch (e: Exception) { webServer.addLog("CAP", "capture start failed: ${e.message}") }
        // EventLogger formats a string on every playback event for the life of the
        // player — pure debug overhead on an underpowered Fire TV. Gate it behind the
        // same probe flag the init-segment dump uses.
        if (PROBE_INIT_SEGMENT) player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())

        updateOutputLatency()
        // AudioManager is a system service and outlives this ViewModel, so hold the
        // callback and unregister it in onCleared — otherwise every future BT connect
        // fires into a dead instance.
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { updateOutputLatency() }
            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { updateOutputLatency() }
        }
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(audioDeviceCallback, null)

        viewModelScope.launch {
            repo.authIssue.collect { issue ->
                _state.update { it.copy(mutExpired = issue == com.applemusicktv.data.auth.AuthorizationIssue.UserRejected) }
                when (issue) {
                    com.applemusicktv.data.auth.AuthorizationIssue.UserRejected -> showAuthNotification()
                    com.applemusicktv.data.auth.AuthorizationIssue.DeveloperUnavailable -> toast(tr(R.string.msg_024))
                    null -> Unit
                }
            }
        }
        pollProgress()
        // Don't restore-and-play behind the setup screen — a first-run user would
        // get audio over onboarding. onOnboardingFinished() picks it up instead.
        if (onboardingPrefs.completed) restoreState()
        checkServerReachable()
        viewModelScope.launch { checkAppleServiceStatus() }
    }

    fun onboardingCompleted(): Boolean = onboardingPrefs.completed

    /** Remote type chosen in setup — overrides hardware detection for the toggle button. */
    fun remoteOverride(): String = onboardingPrefs.remoteOverride
    fun setRemoteOverride(choice: String) { onboardingPrefs.remoteOverride = choice }
    fun webServerEnabled(): Boolean = webServer.isEnabled
    fun setWebServerEnabled(on: Boolean) { webServer.setEnabled(on) }

    /** Flips true when the user asks to replay setup, so the shell can show it without a relaunch. */
    private val _replayOnboarding = MutableStateFlow(false)
    val replayOnboarding: StateFlow<Boolean> = _replayOnboarding
    val authorizationRevision: StateFlow<Long> = mutPrefs.authorizationRevision

    /** Dev menu: replay setup now (and on next launch until finished). */
    fun resetOnboarding() {
        onboardingPrefs.reset()
        _replayOnboarding.value = true
    }

    /** Shell consumed the replay signal (setup is now on screen). */
    fun consumeReplayOnboarding() { _replayOnboarding.value = false }

    /** Setup just finished — safe to bring back whatever was playing before. */
    fun onOnboardingFinished() {
        // Setup may have just added the token; the proxy needs its own copy before
        // any library call, and _state still says isFullStream=false.
        viewModelScope.launch {
            if (hasMUT()) runCatching { repo.syncMUTToServer(mutPrefs.getMUT()) }
            recheckServer().join()
            _state.update { it.copy(isFullStream = hasMUT()) }
            restoreState()
        }
    }

    /** Only explicitly configured proxies are checked. No address means local mode. */
    fun recheckServer() = viewModelScope.launch {
        val address = serverPrefs.getPcServerIp()
        if (address.isEmpty()) {
            serverPrefs.serverReachable = false
            lastCheckedProxy = null
            proxyHadSuccessfulCheck = false
            return@launch
        }
        if (lastCheckedProxy != address) {
            lastCheckedProxy = address
            proxyHadSuccessfulCheck = false
        }
        val up = repo.pingServer()
        currentCoroutineContext().ensureActive()
        // A check for an old address must not change the newly selected mode.
        if (address != serverPrefs.getPcServerIp()) return@launch
        val wasUp = serverPrefs.serverReachable
        serverPrefs.serverReachable = up
        Log.i("PlayerVM", if (up) "Server reachable — proxy mode" else "Server DOWN — standalone mode")
        val announce = !standalonePrefs.isEnabled()
        if (!up) {
            if (wasUp && announce) toast(tr(R.string.msg_025))
            if (wasUp) repo.prepareStandalone()
        } else if (!wasUp) {
            usingStandalone = false
            if (!wasUp && proxyHadSuccessfulCheck && announce) toast(tr(R.string.msg_026))
            proxyHadSuccessfulCheck = true
        }
    }

    private fun checkServerReachable() = viewModelScope.launch {
        // Changing/clearing the address cancels its timer; an empty address has no loop.
        serverPrefs.proxyAddress.collectLatest { address ->
            if (address.isEmpty()) {
                serverPrefs.serverReachable = false
                lastCheckedProxy = null
                proxyHadSuccessfulCheck = false
                com.applemusicktv.data.NetworkLog.line("Proxy", "not configured; local mode; health polling stopped")
                repo.prepareStandalone()
                return@collectLatest
            }
            recheckServer().join()
            if (!serverPrefs.serverReachable) repo.prepareStandalone()
            while (true) {
                delay(30_000)
                recheckServer().join()
            }
        }
    }

    /** Never route playback to the placeholder URL when a proxy hasn't been configured. */
    private fun useStandalone() = !serverPrefs.hasPcServer() || standalonePrefs.isEnabled() || !serverPrefs.serverReachable

    private fun saveState() {
        val s = _state.value
        if (s.stationLaunch != null) return
        val song = s.currentSong ?: return
        // Preserve V12's crossfade and interrupted-source bookmark rules.
        if (crossfadeInProgress) return
        val savedPosition = pendingRestore?.posMs ?: player.currentPosition
        // Preferences remain synchronous and cannot be overwritten by an older IO bookmark.
        if (prefs.getFloat("beat_intensity", 1f) != s.beatIntensity ||
            prefs.getBoolean("crossfade_enabled", true) != s.crossfadeEnabled ||
            prefs.getBoolean("background_play", false) != s.backgroundPlayEnabled) {
            prefs.edit {
                putFloat("beat_intensity", s.beatIntensity)
                putBoolean("crossfade_enabled", s.crossfadeEnabled)
                putBoolean("background_play", s.backgroundPlayEnabled)
            }
        }
        bookmarkWriter.submit(PlaybackBookmark(song, s.queue, s.queueIndex, savedPosition, s.isFullStream))
    }

    /** True when the app should keep playing after it leaves the foreground. */
    val backgroundPlayEnabled: Boolean get() = _state.value.backgroundPlayEnabled

    fun toggleBackgroundPlay() {
        val next = !_state.value.backgroundPlayEnabled
        _state.update { it.copy(backgroundPlayEnabled = next) }
        prefs.edit { putBoolean("background_play", next) }
    }

    /** Called by the activity when it enters/leaves Picture-in-Picture. */
    fun setPipMode(on: Boolean) { _state.update { it.copy(isInPip = on) } }

    private fun restoreState() = viewModelScope.launch {
        val songJson = prefs.getString("song", null) ?: return@launch
        if (hasPlayedSomething) return@launch
        try {
            val adapter = moshi.adapter(Song::class.java)
            val listType = Types.newParameterizedType(List::class.java, Song::class.java)
            val listAdapter = moshi.adapter<List<Song>>(listType)
            val savedSong = adapter.fromJson(songJson) ?: return@launch
            val savedQueue = listAdapter.fromJson(prefs.getString("queue", "[]") ?: "[]") ?: listOf(savedSong)
            val restored = VideoPlaybackPolicy.restored(savedSong, savedQueue, prefs.getInt("queue_index", 0))
            val song = restored.song
            val queue = restored.songs
            val idx = restored.index
            val posMs = if (song == savedSong) prefs.getLong("position_ms", 0L) else 0L
            val full  = hasMUT()
            val crossfade = prefs.getBoolean("crossfade_enabled", true)
            val screensaverMin = prefs.getInt("screensaver_min", 10)
            val bgPlay = prefs.getBoolean("background_play", false)
            val npBg = NowPlayingBackground.fromName(prefs.getString("np_background", null))
            val beat      = prefs.getFloat(intensityKey(npBg), 1.0f)
            val keepBg = prefs.getBoolean("screensaver_keep_bg", false)
            val npInfo = prefs.getBoolean("np_info", true)
            val motionArt = prefs.getBoolean("motion_art", false)
            com.applemusicktv.media.GainProcessor.enabled = prefs.getBoolean("volume_leveling", false)
            _state.update { it.copy(currentSong = song, song = song, queue = queue, queueIndex = idx, isFullStream = full, beatIntensity = beat, crossfadeEnabled = crossfade, screensaverTimeoutMin = screensaverMin, backgroundPlayEnabled = bgPlay, nowPlayingBackground = npBg, screensaverKeepBackground = keepBg, showNowPlayingInfo = npInfo, motionArtworkEnabled = motionArt,
                orbSpeed = prefs.getFloat("orb_speed", 1.0f), lyricsScale = prefs.getFloat("lyrics_scale", 1.0f), artworkRounded = prefs.getBoolean("artwork_rounded", true), reduceMotion = false, lowPowerMode = false, volumeLeveling = prefs.getBoolean("volume_leveling", false), progressMs = posMs) }
            if (song == null) return@launch
            // A restored music video must go to the video player, NOT the audio stream — otherwise
            // it hits /api/stream, 404s ("No playable asset"), and gets skipped as if unavailable.
            if (song.isMusicVideo) {
                webServer.addLog("PLR", "restoreState idx=$idx VIDEO ${song.title}")
                player.pause()
                _videoRequest.value = VideoRequest(song, autoOpen = false, startPaused = true)
                return@launch
            }
            // DON'T load the track into the player on restore. Loading a media item + having a live
            // MediaSession makes Android's media-resumption auto-RESUME playback on foreground — that
            // was the "song autoplays on open". Stash it and load only on the user's first play press.
            webServer.addLog("PLR", "restoreState idx=$idx posMs=$posMs song=${song.title} — deferred (paused)")
            pendingRestore = RestoreInfo(song, posMs, full)
            // Lyrics/motion are just display — safe (and nice) to warm now.
            if (full) loadLyrics(song.id)
            loadMotion(song.id)
        } catch (_: Exception) {}
    }

    /** A restored track not yet loaded into the player — loaded on the first play press so nothing
     *  (incl. Android media resumption) can auto-start it. */
    private data class RestoreInfo(val song: Song, val posMs: Long, val full: Boolean)
    private var pendingRestore: RestoreInfo? = null

    private fun startPendingRestore() {
        val r = pendingRestore ?: return
        pendingRestore = null
        hasPlayedSomething = true
        val standalone = r.full && useStandalone()
        usingStandalone = standalone
        failedPreparationSongId = null
        beginPlaybackSelection()
        val myGen = ++playGen
        sourcePreparation.begin(myGen)
        attachedSourceGeneration = -1
        player.playWhenReady = false
        player.stop(); player.clearMediaItems()
        _state.update { it.copy(isLoading = true, isPlaying = false) }
        viewModelScope.launch {
            val src = if (standalone) buildStandaloneSource(r.song) ?:
                tryCatalogRecoverySource(r.song, myGen) else null
            if (myGen != playGen) return@launch
            if (src == null && !serverPrefs.serverReachable && (r.full || r.song.previewUrl == null)) {
                stopFailedPreparation(r.song)
                return@launch
            }
            _state.update { it.copy(standaloneActive = src != null) }
            val uri = if (r.full) repo.streamUrl(r.song.id) else (r.song.previewUrl ?: repo.streamUrl(r.song.id))
            val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
            attachedSourceGeneration = myGen
            if (src != null) player.setMediaSource(src, r.posMs) else player.setMediaItem(buildMediaItem(r.song, uri), r.posMs)
            player.prepare(); player.playWhenReady = shouldPlay
            val nextSong = _state.value.queue.getOrNull(_state.value.queueIndex + 1)
            if (r.full && !standalone && nextSong != null && prefetchRequestedForSongId != nextSong.id) {
                prefetchRequestedForSongId = nextSong.id
                prefetchSong(nextSong)
            }
        }
    }

    fun playFromQueue(idx: Int) = playQueueItem(idx)

    private fun advanceQueue() {
        val s = _state.value
        val pos = player.currentPosition
        webServer.addLog("ADV", "advanceQueue pos=${pos}ms from=${s.currentSong?.title} idx=${s.queueIndex}/${s.queue.size}")
        if (s.sleepAfterSong) { _state.update { it.copy(sleepAfterSong = false) }; player.stop(); webServer.addLog("SLEEP", "sleepAfterSong — stopped"); return }
        // Repeat one: replay current
        if (s.repeatMode == RepeatMode.One) { playQueueItem(s.queueIndex, skipFadeIn = true); return }

        // Drain user priority queue first (Play Next / Add to Queue songs)
        if (s.userQueue.isNotEmpty()) {
            val next = s.userQueue.first()
            val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
            val newQueue = s.queue.toMutableList().also { it.add(insertIdx, next) }
            _state.update { it.copy(queue = newQueue, userQueue = it.userQueue.drop(1)) }
            playQueueItem(insertIdx)
            return
        }

        // Shuffle already permuted the queue. Consume every entry exactly once.
        val nextIdx = s.queueIndex + 1

        webServer.addLog("PLR", "advance → idx=$nextIdx / ${s.queue.size}")
        if (nextIdx >= s.queue.size) {
            if (s.repeatMode == RepeatMode.All) { playQueueItem(0); return }
            // Queue exhausted — fetch related songs and continue
            val lastSong = s.queue.lastOrNull()
            if (lastSong != null && relatedRequestedGeneration != playGen) {
                relatedRequestedGeneration = playGen
                val request = playbackRequests.revision
                val generation = playGen
                relatedQueueJob = viewModelScope.launch {
                    val related = repo.getRelatedSongs(lastSong.id).getOrDefault(emptyList())
                    currentCoroutineContext().ensureActive()
                    if (!playbackRequests.isCurrent(request) || generation != playGen) return@launch
                    if (related.isNotEmpty()) {
                        relatedQueueJob = null
                        _state.update { it.copy(queue = it.queue + related) }
                        playQueueItem(nextIdx)
                    }
                }
            }
            return
        }
        playQueueItem(nextIdx)
    }

    fun toggleShuffle() {
        _state.update { s ->
            if (s.isShuffled) {
                // Turning off: restore original order, keep current song position
                val (restored, newIdx) = restoreQueueOrder(s.queue, s.originalQueue, s.queueIndex)
                s.copy(isShuffled = false, queue = restored, queueIndex = newIdx, originalQueue = emptyList())
            } else {
                // Turning on: save original, shuffle remaining
                val split = (s.queueIndex + 1).coerceIn(0, s.queue.size)
                val before = s.queue.take(split)
                val after = s.queue.drop(split).shuffled()
                s.copy(isShuffled = true, originalQueue = s.queue, queue = before + after)
            }
        }
    }
    fun cyclePlaybackOrder() {
        _state.update { if (it.stationLaunch == null) it.nextPlaybackOrder() else it }
    }
    fun toggleRepeat() {
        _state.update { it.copy(repeatMode = when (it.repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        })}
    }
    fun setSleepTimer(minutes: Int) { _state.update { it.copy(sleepTimerEndsAt = System.currentTimeMillis() + minutes * 60_000L, sleepAfterSong = false) } }
    fun setSleepAfterSong() { _state.update { it.copy(sleepAfterSong = true, sleepTimerEndsAt = null) } }
    fun cancelSleepTimer() { _state.update { it.copy(sleepTimerEndsAt = null, sleepAfterSong = false) } }
    fun dismissMutExpired() { _state.update { it.copy(mutExpired = false) } }
    // Calm / Normal / Strong / Crazy — the multiplier applied to beat energy AND the orb bands, so it
    // drives how hard both Dynamic and Projector react.
    private val intensitySteps = floatArrayOf(0.55f, 1.0f, 2.0f, 3.5f)

    fun cycleBeatIntensity() = stepBeatIntensity(1)

    fun stepBeatIntensity(dir: Int) {
        val cur = intensitySteps.indexOfFirst { kotlin.math.abs(it - _state.value.beatIntensity) < 0.05f }.let { if (it < 0) 1 else it }
        val next = intensitySteps[(cur + dir).coerceIn(0, intensitySteps.lastIndex)]
        _state.update { it.copy(beatIntensity = next) }
        // Remembered per background mode (Dynamic vs Projector).
        prefs.edit { putFloat(intensityKey(_state.value.nowPlayingBackground), next) }
    }

    fun toggleCrossfade() {
        val next = !_state.value.crossfadeEnabled
        _state.update { it.copy(crossfadeEnabled = next) }
        prefs.edit { putBoolean("crossfade_enabled", next) }
    }

    /**
     * Hard stop for a real app exit (the "退出" confirmation). `moveTaskToBack`/`finish` alone leave
     * this ViewModel — and its ExoPlayer — alive in the background, so the audio kept playing; stop
     * the output explicitly so exiting actually stops the music.
     */
    fun stopPlayback() {
        // A window/posture change can interrupt source preparation or a two-player fade.
        // Keep the displayed track's bookmark before invalidating those asynchronous owners.
        val needsSource = sourcePreparation.isPending || crossfadeInProgress
        val position = if (pendingRestore != null) pendingRestore!!.posMs
            else if (crossfadeInProgress) crossfadeExo?.currentPosition ?: 0L
            else if (sourcePreparation.isPending) _state.value.progressMs else player.currentPosition
        if (needsSource && !_state.value.isLiveRadio) {
            _state.value.currentSong?.takeUnless { it.isMusicVideo }?.let {
                pendingRestore = RestoreInfo(it, position.coerceAtLeast(0L), _state.value.isFullStream)
            }
        }
        pause()
        beginPlaybackSelection()
        ++playGen
        sourcePreparation.cancel()
        awaitingSongStart = null
        fadeJob?.cancel(); fadeJob = null
        crossfadeInProgress = false
        crossfadeExo?.let { cf ->
            cfExoErrListener?.let { cf.removeListener(it) }
            cf.stop(); cf.release()
        }
        crossfadeExo = null; cfExoErrListener = null
        player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        player.stop()
        if (needsSource) player.clearMediaItems()
        _state.update { it.copy(isPlaying = false, isLoading = false, progressMs = position.coerceAtLeast(0L)) }
        saveState()
        beatAnalyzer.resetBeat()
    }

    /** Cycle the Now Playing backdrop: Dynamic → Projector → Black → Dynamic (dir = +1 / -1). */
    /** Intensity is remembered PER background mode (Dynamic vs Projector); Black has no orbs. */
    private fun intensityKey(mode: NowPlayingBackground): String =
        if (mode == NowPlayingBackground.PROJECTOR) "intensity_projector" else "intensity_dynamic"

    fun stepFluidQuality(dir: Int) {
        val qualities = FluidQuality.entries
        val next = qualities[(_state.value.fluidQuality.ordinal + dir).coerceIn(0, qualities.lastIndex)]
        _state.update { it.copy(fluidQuality = next) }
        prefs.edit { putString("fluid_quality", next.name) }
    }

    fun stepNowPlayingBackground(dir: Int) {
        val modes = NowPlayingBackground.entries
        val cur = _state.value.nowPlayingBackground.ordinal
        val next = modes[((cur + dir) % modes.size + modes.size) % modes.size]
        // Swap in the intensity remembered for the mode we're switching to.
        val nextIntensity = prefs.getFloat(intensityKey(next), 1.0f)
        _state.update { it.copy(nowPlayingBackground = next, beatIntensity = nextIntensity) }
        prefs.edit { putString("np_background", next.name) }
    }

    fun stepOrbSpeed(dir: Int) {
        val steps = floatArrayOf(0.6f, 1.0f, 1.6f)
        val cur = steps.indexOfFirst { kotlin.math.abs(it - _state.value.orbSpeed) < 0.05f }.let { if (it < 0) 1 else it }
        val next = steps[(cur + dir).coerceIn(0, steps.lastIndex)]
        _state.update { it.copy(orbSpeed = next) }
        prefs.edit { putFloat("orb_speed", next) }
    }

    fun stepLyricsScale(dir: Int) {
        val steps = floatArrayOf(0.8f, 1.0f, 1.25f, 1.6f, 2.0f)
        val cur = steps.indexOfFirst { kotlin.math.abs(it - _state.value.lyricsScale) < 0.05f }.let { if (it < 0) 2 else it }
        val next = steps[(cur + dir).coerceIn(0, steps.lastIndex)]
        _state.update { it.copy(lyricsScale = next) }
        prefs.edit { putFloat("lyrics_scale", next) }
    }

    fun toggleArtworkRounded() {
        val next = !_state.value.artworkRounded
        _state.update { it.copy(artworkRounded = next) }
        prefs.edit { putBoolean("artwork_rounded", next) }
    }

    fun toggleVolumeLeveling() {
        val next = !_state.value.volumeLeveling
        com.applemusicktv.media.GainProcessor.enabled = next
        _state.update { it.copy(volumeLeveling = next) }
        prefs.edit { putBoolean("volume_leveling", next) }
    }

    fun toggleScreensaverKeepBackground() {
        val next = !_state.value.screensaverKeepBackground
        _state.update { it.copy(screensaverKeepBackground = next) }
        prefs.edit { putBoolean("screensaver_keep_bg", next) }
    }

    fun toggleNowPlayingInfo() {
        val next = !_state.value.showNowPlayingInfo
        _state.update { it.copy(showNowPlayingInfo = next) }
        prefs.edit { putBoolean("np_info", next) }
    }

    fun toggleMotionArtwork() {
        val next = !_state.value.motionArtworkEnabled
        _state.update { it.copy(motionArtworkEnabled = next, motionUrl = if (next) it.motionUrl else null) }
        prefs.edit { putBoolean("motion_art", next) }
        // Fetch it now if we just turned it on mid-song; clearing above handles turning it off.
        if (next) _state.value.currentSong?.let { loadMotion(it.id) }
    }

    private val screensaverSteps = listOf(0, 1, 2, 5, 10, 20, 30, 60, 120)

    /** Cycle the screensaver idle timeout: Off → 1 → 2 → 5 → 10 → 20 → 30 → Off. */
    fun cycleScreensaverTimeout() = stepScreensaverTimeout(1)

    /** Step the screensaver idle timeout through [screensaverSteps] (dir = +1 / -1). */
    fun stepScreensaverTimeout(dir: Int) {
        val cur = screensaverSteps.indexOf(_state.value.screensaverTimeoutMin).coerceAtLeast(0)
        val next = screensaverSteps[(cur + dir).coerceIn(0, screensaverSteps.lastIndex)]
        _state.update { it.copy(screensaverTimeoutMin = next) }
        prefs.edit { putInt("screensaver_min", next) }
    }

    // Set on every track change; holds the UI clock at 0:00 until the NEW item is actually
    // playing, so the poll loop can't push the outgoing song's elapsed/duration through the
    // window while the standalone source is still building.
    @Volatile private var awaitingSongStart: String? = null
    private var failedPreparationSongId: String? = null

    private fun stopFailedPreparation(song: Song) {
        failedPreparationSongId = song.id
        sourcePreparation.cancel()
        attachedSourceGeneration = -1
        awaitingSongStart = null
        usingStandalone = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        _state.update { it.copy(isLoading = false, isPlaying = false, standaloneActive = false) }
        webServer.addLog("PLR", "source preparation failed for ${song.title}; no reachable configured proxy; stopped waiting")
        toast(tr(R.string.msg_027, song.title))
    }

    private fun playQueueItem(idx: Int, skipFadeIn: Boolean = false, userOpened: Boolean = false) {
        ensureMainPlayer()      // leaving live radio → restore the FFmpeg-preferring player
        if (_state.value.isLiveRadio) _state.update { it.copy(isLiveRadio = false) }
        pendingRestore = null   // any real queue action supersedes a deferred restore
        val q = _state.value.queue
        if (q.isEmpty() || idx !in q.indices) {
            webServer.addLog("PLR", "playQueueItem idx=$idx out of bounds (size=${q.size}) — stopping")
            return
        }
        val prepared=takePreparedNext(q[idx].id)
        beginPlaybackSelection(keepDecryptFor = q[idx].id)
        val myGen = ++playGen
        sourcePreparation.cancel()
        attachedSourceGeneration = -1
        prefetchJob?.cancel()
        relatedQueueJob?.cancel(); relatedQueueJob = null
        beatAnalyzer.resetBeat(); mainProc?.resetBeat()
        crossfadeSkipSongId = null
        systemCacheRetryFor = null
        api23SoftwareFallbackSong = null
        catalogRetriedSongId = null   // fresh selection → allow a new catalog on-device retry
        // Cancel any in-progress crossfade
        fadeJob?.cancel()
        crossfadeInProgress = false
        crossfadeExo?.let { cf ->
            cfExoErrListener?.let { cf.removeListener(it) }
            cf.stop(); cf.release()
        }
        crossfadeExo = null; cfExoErrListener = null

        val song = q[idx]
        if (!VideoPlaybackPolicy.allows(song)) return
        failedPreparationSongId = null
        if (song.id in unavailableSongIds) {
            prepared?.exo?.release()
            webServer.addLog("PLR", "skipping known-unavailable ${song.title}")
            if (idx + 1 < q.size) playQueueItem(idx + 1, skipFadeIn) 
            return
        }
        // Music video in the queue → hand it to the video player; the audio engine idles.
        if (song.isMusicVideo) {
            prepared?.exo?.release()
            webServer.addLog("PLR", "playQueueItem idx=$idx VIDEO ${song.title}")
            player.stop(); player.clearMediaItems()
            awaitingSongStart = null
            _state.update { it.copy(currentSong = song, song = song, queueIndex = idx, lyrics = emptyList(), motionUrl = null, progressMs = 0L) }
            saveState()
            _videoRequest.value = VideoRequest(song, autoOpen = userOpened)
            return
        }
        _videoRequest.value = null   // audio item → dismiss any active video

        val full = _state.value.isFullStream
        val standalone = full && useStandalone() && (song.id !in proxyOnlySongIds || !serverPrefs.serverReachable)
        val uri = if (full) repo.streamUrl(song.id) else (song.previewUrl ?: repo.streamUrl(song.id))
        webServer.addLog("PLR", "playQueueItem idx=$idx song=${song.title}${if (standalone) " [standalone]" else ""}")
        awaitingSongStart = song.id
        sourcePreparation.begin(myGen)
        com.applemusicktv.media.GainProcessor.currentTrackKey = song.id   // per-track volume-leveling memory
        _state.update { it.copy(currentSong = song, song = song, queueIndex = idx, lyrics = lyricsCache[song.id] ?: emptyList(), motionUrl = null, progressMs = 0L, isLoading = true, isPlaying = false) }
        saveState()
        if (!full && song.previewUrl == null && !serverPrefs.serverReachable) {
            prepared?.exo?.release()
            stopFailedPreparation(song)
            return
        }
        if (!standalone) prepared?.exo?.release()
        player.repeatMode = Player.REPEAT_MODE_OFF
        // Remove the outgoing item, not just its play intent: no controller can resume it
        // underneath the new title while the new source is still being prepared.
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        fun startPlayback(shouldPlay: Boolean) {
            player.volume = 1f
            player.playWhenReady = shouldPlay
        }
        if (prepared!=null && standalone) {
            val old=player
            old.removeListener(playerListener)
            mediaSession?.release();mediaSession=null
            old.release()
            player=prepared.exo
            prepared.processor?.let {mainProc=it;beatAnalyzer.activate(it)}
            player.addListener(playerListener)
            val shouldPlay=sourcePreparation.finish(myGen) ?: false
            attachedSourceGeneration=myGen;awaitingSongStart=null;usingStandalone=true
            mediaSession=buildMediaSession(player)
            player.playWhenReady=shouldPlay
            _state.update {it.copy(isLoading=false,isPlaying=player.isPlaying,standaloneActive=true)}
            webServer.addLog("SOURCE","prepared handoff song=${song.id} gen=$myGen")
        } else if (standalone) {
            prepared?.exo?.release()
            usingStandalone = true
            viewModelScope.launch {
                val original = buildStandaloneSource(song)
                if (myGen != playGen) return@launch
                val src = original ?: tryCatalogRecoverySource(song, myGen)
                // Superseded by a newer selection while we were decrypting — drop it so
                // the skipped-over song never briefly plays.
                if (myGen != playGen) return@launch
                if (src == null && !serverPrefs.serverReachable) {
                    stopFailedPreparation(song)
                    return@launch
                }
                val shouldPlay = sourcePreparation.finish(myGen) ?: return@launch
                attachedSourceGeneration = myGen
                webServer.addLog("PLR", "attach source song=${song.title} id=${song.id} gen=$myGen play=$shouldPlay")
                if (src != null) player.setMediaSource(src)
                else player.setMediaItem(buildMediaItem(song, uri))
                player.prepare()
                if (src != null) standaloneFailures = 0
                _state.update { it.copy(standaloneActive = src != null) }
                startPlayback(shouldPlay)
            }
        } else {
            usingStandalone = false
            _state.update { it.copy(standaloneActive = false) }
            val shouldPlay = sourcePreparation.finish(myGen) ?: return
            attachedSourceGeneration = myGen
            player.setMediaItem(buildMediaItem(song, uri))
            player.prepare()
            startPlayback(shouldPlay)
        }
        prefetchRequestedForSongId = null
        if (full) loadLyrics(song.id)
        loadMotion(song.id)
        if (song.artistId == null || song.albumId == null) enrichSongIds(song.id)
        // Prefetch N+1 immediately so it's cached well before crossfade. Under Repeat
        // All the last track's "next" is index 0, so warm that instead of nothing.
        val nextSong = (_state.value.userQueue.firstOrNull()
            ?: q.getOrNull(idx + 1)
            ?: q.firstOrNull().takeIf { _state.value.repeatMode == RepeatMode.All && q.size > 1 })
        // Wait for THIS song to be playable before warming the next one. Firing both at
        // once splits the WAN download and the song you're waiting on lands last — a
        // skip used to queue up four concurrent decrypts and take 30s+. Cancelled on
        // the next playQueueItem, so hammering skip only ever warms the song you land on.
        // Persist the song change right away rather than waiting up to 10s for the
        // ticker — that window is what let a restore come back on a previous track.
        lastAutoSaveMs = System.currentTimeMillis()
        saveState()
        prefetchJob?.cancel()
        // Prefetch N+1 in BOTH modes. Standalone was excluded before, which is why the
        // crossfade partner was always cold and crossfade/skip felt broken — now
        // prefetchSong warms the in-app decrypt cache for the next song.
        if (full && nextSong != null) {
            prefetchLyrics(nextSong)   // warm lyrics now — cheap, independent of the audio decrypt
            prefetchJob = viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 60_000
                while (myGen == playGen && !currentSourceReady(myGen) &&
                       System.currentTimeMillis() < deadline) {
                    delay(300)
                }
                if (!currentSourceReady(myGen)) {
                    webServer.addLog("PRE", "prefetch N+1 abandoned — ${song.title} not current/ready gen=$myGen/$playGen")
                    return@launch
                }
                prefetchRequestedForSongId = nextSong.id
                webServer.addLog("PRE", "prefetch N+1 song=${nextSong.title}")
                prefetchSong(nextSong)
            }
        } else {
            webServer.addLog("PRE", "prefetch N+1 skip — full=$full nextSong=${nextSong?.title}")
        }
    }

    fun playSong(album: Album) {
        if (!VideoPlaybackPolicy.allows(album.id, album.type)) return
        val song = Song(id = album.id, title = album.title, artistName = album.artistName,
            artworkUrl = album.artworkUrl, artworkBgColor = album.artworkBgColor,
            durationMs = 0, albumName = "", previewUrl = null)
        playSong(song)
    }

    fun playSong(song: Song, useFullStream: Boolean = hasMUT()) {
        if (!VideoPlaybackPolicy.allows(song)) return
        _userPlaybackRequests.value++
        usingStandalone = false
        lastErrorKey = null
        hasPlayedSomething = true
        _state.update { it.copy(currentSong = song, song = song, queue = listOf(song), queueIndex = 0, lyrics = emptyList(), isFullStream = useFullStream, motionUrl = null) }
        playQueueItem(0, userOpened = true)
    }

    /** Play a shelf of video DTOs (music-videos / interviews) in the video player. */
    fun playVideos(dtos: List<com.applemusicktv.data.network.SongDto>, startIndex: Int = 0) {
        if (!VideoPlaybackPolicy.ENABLED) return
        playAlbum(dtos.map(repo::songFromDto), startIndex)
    }

    fun playAlbum(inputSongs: List<Song>, startIndex: Int = 0, useFullStream: Boolean = hasMUT(), shuffle: Boolean = false, openPlayer: Boolean = true) {
        val selection = VideoPlaybackPolicy.selection(inputSongs, startIndex)
        val songs = selection.songs
        if (songs.isEmpty()) return
        if (openPlayer) _userPlaybackRequests.value++
        val stack = Thread.currentThread().stackTrace
        val callers = (3..7).mapNotNull { stack.getOrNull(it) }.joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        webServer.addLog("PLR", "playAlbum size=${songs.size} idx=$startIndex shuffle=$shuffle << $callers")
        usingStandalone = false
        lastErrorKey = null
        hasPlayedSomething = true
        val idx = selection.index
        val queue = if (shuffle) {
            val first = songs[idx]
            listOf(first) + songs.filterIndexed { i, _ -> i != idx }.shuffled()
        } else songs
        val queueIdx = if (shuffle) 0 else idx
        _state.update { it.copy(queue = queue, isFullStream = useFullStream, isShuffled = shuffle, originalQueue = if (shuffle) songs else emptyList()) }
        // User explicitly started this list → a video here should open fullscreen Now Playing.
        playQueueItem(queueIdx, userOpened = openPlayer)
    }

    fun shufflePlayPlaylist(playlistId: String): kotlinx.coroutines.Job {
        val request = beginPlaybackSelection()
        return viewModelScope.launch {
            val tracks = repo.getPlaylistTracks(playlistId).getOrDefault(emptyList())
            currentCoroutineContext().ensureActive()
            if (playbackRequests.isCurrent(request) && tracks.isNotEmpty())
                playAlbum(tracks, startIndex = tracks.indices.random(), shuffle = true)
        }
    }

    /** Temp: dump a personalized ra.* station's payload to logcat (tag StationProbe). */
    fun probeStation(id: String) = viewModelScope.launch { repo.probeStation(id) }

    // ── Library writes ──────────────────────────────────────────────────
    fun addToLibrary(song: com.applemusicktv.data.model.Song) = viewModelScope.launch {
        repo.addToLibrary(song)
            .onSuccess { toast(tr(R.string.msg_028, song.title)) }
            .onFailure { toast(tr(R.string.msg_029)) }
    }

    /** Editable library playlists for the "添加到播放列表" picker. */
    suspend fun editablePlaylists() = repo.getLibraryPlaylists(limit = 100).getOrDefault(emptyList())

    fun addToPlaylist(playlistId: String, playlistName: String, song: com.applemusicktv.data.model.Song) = viewModelScope.launch {
        repo.addToPlaylist(playlistId, song)
            .onSuccess { toast(tr(R.string.msg_030, playlistName)) }
            .onFailure { toast(tr(R.string.msg_031)) }
    }

    private fun showStationLaunch(request: Long, id: String, title: String, artwork: String?, openPlayer: Boolean = true) {
        pause() // Bookmark the real track before publishing transient station metadata.
        ++playGen
        sourcePreparation.cancel(); attachedSourceGeneration = -1; awaitingSongStart = null
        pendingRestore = null; failedPreparationSongId = null
        radioRetryJob?.cancel(); radioRetryJob = null
        prefetchJob?.cancel(); cancelOtherDecrypts(null)
        stationJob?.cancel(); stationJob = null
        fadeJob?.cancel(); fadeJob = null; crossfadeInProgress = false
        crossfadeExo?.let { cf -> cfExoErrListener?.let { cf.removeListener(it) }; cf.stop(); cf.release() }
        crossfadeExo = null; cfExoErrListener = null
        player.stop(); player.clearMediaItems(); player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        clearVideoRequest()
        _state.update { it.copy(stationLaunch = StationLaunch(request, id, title.ifBlank { "Apple Music Radio" }, artwork),
            isPlaying = false, isLoading = true) }
        if (openPlayer) _userPlaybackRequests.value++
    }

    fun reserveStationRequest(id: String, title: String, artwork: String?): Long {
        val request = beginPlaybackSelection()
        showStationLaunch(request, id, title, artwork)
        return request
    }

    fun failStationIfCurrent(request: Long) {
        if (!playbackRequests.isCurrent(request)) return
        _state.update { state -> state.stationLaunch?.takeIf { it.request == request }?.let {
            state.copy(stationLaunch = it.copy(failed = true), isLoading = false, isPlaying = false)
        } ?: state }
    }

    fun watchStationRequest(request: Long, job: kotlinx.coroutines.Job) {
        if (!playbackRequests.isCurrent(request)) { job.cancel(); return }
        stationJob = job
        job.invokeOnCompletion { if (playbackRequests.isCurrent(request)) failStationIfCurrent(request) }
    }

    fun playStation(stationId: String, stationArt: String? = null, stationTitle: String = "Apple Music Radio", openPlayer: Boolean = true) {
        // Apple's LIVE radio stations (fixed set) stream a live Widevine HLS feed via /play/assets,
        // not a next-tracks song queue — route them to the live path.
        if (stationId in LIVE_STATION_IDS) { playLiveStation(stationId, stationArt, stationTitle, openPlayer); return }
        val request = playbackRequests.station(stationId)
        if (request == null) {
            webServer.addLog("PLR", "station request already pending; ignored duplicate")
            return
        }
        showStationLaunch(request, stationId, stationTitle, stationArt, openPlayer)
        webServer.addLog("PLR", "station queue requested revision=$request")
        stationJob = viewModelScope.launch {
            try {
                val songs = repo.getStationTracks(stationId).getOrDefault(emptyList())
                currentCoroutineContext().ensureActive()
                if (!playbackRequests.isCurrent(request)) {
                    webServer.addLog("PLR", "discarded stale station queue revision=$request")
                    return@launch
                }
                // Clear before playAlbum invalidates other queue requests; don't cancel ourselves.
                stationJob = null
                playbackRequests.finishStation(request)
                if (songs.isNotEmpty()) playAlbum(songs, openPlayer = false)
                else stationJob = launchLiveStation(request, stationId, stationArt, stationTitle)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failStationIfCurrent(request)
                toast(tr(R.string.msg_032))
            } finally {
                playbackRequests.finishStation(request)
            }
        }
    }

    /** "创建电台" from a song — Apple exposes a per-song radio whose id is `ra.{catalogId}`
     *  (verified: song 6799279367 → station ra.6799279367). Library ids resolve to catalog first. */
    fun createSongStation(song: com.applemusicktv.data.model.Song): kotlinx.coroutines.Job {
        val request = reserveStationRequest("song-station:${song.id}", tr(R.string.msg_033, song.title), song.artworkUrl(600))
        return viewModelScope.launch {
            val catId = if (song.id.startsWith("i."))
                appleClient.resolveCatalogFor(song.id, mutPrefs.getMUT())
            else song.id.filter { it.isDigit() }.ifEmpty { song.id }
            currentCoroutineContext().ensureActive()
            if (!playbackRequests.isCurrent(request)) return@launch
            if (catId.isNullOrEmpty()) { failStationIfCurrent(request); toast(tr(R.string.msg_034, song.title)); return@launch }
            toast(tr(R.string.msg_035, song.title))
            playStation("ra.$catId", song.artworkUrl(600), tr(R.string.msg_033, song.title), openPlayer = false)
        }.also { watchStationRequest(request, it) }
    }

    /** Genre station — shuffled top songs for the genre, played standalone. */
    fun playGenreStation(genreId: String): kotlinx.coroutines.Job {
        val request = beginPlaybackSelection()
        return viewModelScope.launch {
            val songs = repo.getGenreStation(genreId).getOrDefault(emptyList())
            currentCoroutineContext().ensureActive()
            if (playbackRequests.isCurrent(request) && songs.isNotEmpty()) playAlbum(songs, shuffle = true)
        }
    }

    /** Plain internet-radio stream player. Radio UI is currently hidden — kept only so
     *  RadioScreen still compiles. No ICY song-identification. */
    @OptIn(UnstableApi::class)
    fun playInternetRadio(name: String, streamUrl: String, subtitle: String = tr(R.string.msg_036)) {
        beginPlaybackSelection()
        ++playGen; sourcePreparation.cancel(); attachedSourceGeneration = playGen
        awaitingSongStart = null
        usingStandalone = false
        lastErrorKey = null
        prefetchJob?.cancel()
        val fakeSong = com.applemusicktv.data.model.Song(
            id = "radio:$streamUrl", title = name, artistName = subtitle,
            albumName = "", durationMs = 0L, artworkUrl = null, artworkBgColor = null,
            previewUrl = null, hasLyrics = false,
        )
        _state.update { it.copy(queue = listOf(fakeSong), currentSong = fakeSong, song = fakeSong, queueIndex = 0, lyrics = emptyList(), isFullStream = true, motionUrl = null, isLiveRadio = true, userQueue = emptyList()) }
        player.setMediaItem(buildMediaItem(fakeSong, streamUrl))
        player.prepare()
        player.volume = 1f
        player.play()
    }

    /** In-band timed metadata from the live radio stream. Apple ships the current track as ID3
     *  frames inside each CMAF segment: TIT2 title, TPE1 artist, TALB album, and WXXX artwork URLs
     *  (390px + 1400px). Fires every segment, so only push a state update when the track changes. */
    @OptIn(UnstableApi::class)
    private fun onRadioMetadata(metadata: androidx.media3.common.Metadata) {
        if (!radioActive) return
        if (_state.value.stationLaunch?.let { player.currentMediaItem?.mediaId != it.id } == true) return
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var art: String? = null
        var adamId: String? = null
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                is androidx.media3.extractor.metadata.id3.TextInformationFrame -> {
                    val v = e.values.firstOrNull()
                    when (e.id) {
                        "TIT2", "TT2" -> title = v
                        "TPE1", "TP1" -> artist = v
                        "TALB", "TAL" -> album = v
                    }
                }
                is androidx.media3.extractor.metadata.id3.UrlLinkFrame ->
                    if (e.id == "WXXX") { if (art == null || e.url.contains("1400")) art = e.url }
                is androidx.media3.extractor.metadata.id3.PrivFrame ->
                    if (e.owner == "com.apple.radio.adamid")
                        adamId = String(e.privateData, Charsets.US_ASCII).filter { it.isDigit() }.ifEmpty { null }
            }
        }
        if (title == null && artist == null) return   // a jingle/ping has no track frames
        val cur = _state.value.currentSong
        if (cur != null && cur.title == title && cur.artistName == artist) return  // unchanged
        _state.update {
            val c = it.currentSong ?: return@update it
            val s = c.copy(
                id = adamId ?: c.id,
                title = title ?: c.title,
                artistName = artist ?: c.artistName,
                albumName = album ?: c.albumName,
                artworkUrl = art ?: c.artworkUrl,
            )
            it.copy(currentSong = s, song = s, queue = listOf(s), motionUrl = null, stationLaunch = null)
        }
    }

    fun playLiveStation(stationId: String, stationArt: String? = null, stationTitle: String = "Apple Music Radio", openPlayer: Boolean = true): kotlinx.coroutines.Job {
        val request = beginPlaybackSelection()
        showStationLaunch(request, stationId, stationTitle, stationArt, openPlayer)
        return launchLiveStation(request, stationId, stationArt, stationTitle).also { stationJob = it }
    }

    private fun launchLiveStation(request: Long, stationId: String, stationArt: String?, stationTitle: String): kotlinx.coroutines.Job {
        return viewModelScope.launch {
          try {
            val info = repo.getStationStream(stationId).getOrNull() ?: run { failStationIfCurrent(request); return@launch }
            currentCoroutineContext().ensureActive()
            if (!playbackRequests.isCurrent(request)) return@launch
            val url = info.liveStreamUrl ?: run { failStationIfCurrent(request); return@launch }
            ++playGen; sourcePreparation.cancel(); attachedSourceGeneration = playGen
            awaitingSongStart = null
            Log.d("PlayerVM", "playLiveStation id=$stationId url=${url.take(80)} keyUri=${info.drmKeyUri?.take(40)}")
            usingStandalone = false
            lastErrorKey = null
            val art = stationArt ?: radioStationArt
            radioStationArt = art
            val fakeSong = com.applemusicktv.data.model.Song(
                id = stationId, title = stationTitle, artistName = "Apple Music",
                albumName = "", durationMs = 0L, artworkUrl = art, artworkBgColor = null,
                previewUrl = null, hasLyrics = false,
            )
            _state.update { it.copy(queue = listOf(fakeSong), currentSong = fakeSong, song = fakeSong, queueIndex = 0, lyrics = emptyList(), isFullStream = true, motionUrl = null, isLiveRadio = true, userQueue = emptyList(), radioStationArt = art) }

            // Live radio uses a STANDARD Widevine proxy (raw challenge → raw license) at Apple's
            // linear.tv key server — NOT the song `acquireWebPlaybackLicense` JSON wrapper. So the
            // built-in HttpMediaDrmCallback is the right callback; we only add the auth headers.
            val keyUri = info.drmKeyUri
            if (keyUri != null) {
                try {
                    // Secure Widevine AAC needs the MediaCodec-only player (FFmpeg can't decode it).
                    if (!radioActive) { swapPlayer { buildRadioExoPlayer() }; radioActive = true }
                    val bearer = appleClient.getBearer()
                    if (!playbackRequests.isCurrent(request)) return@launch
                    val mut = mutPrefs.getMUT()
                    val drmCallback = com.applemusicktv.media.LiveRadioDrmCallback(
                        keyUri = keyUri, bearer = bearer, mut = mut,
                        adamId = info.adamId ?: stationId.removePrefix("ra."),
                        wvKeyUri = info.wvKeyUri ?: "",
                    )
                    val drmManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(false)
                        .build(drmCallback)
                    val mediaSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(
                        androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    )
                        .setDrmSessionManagerProvider { drmManager }
                        .createMediaSource(buildMediaItem(fakeSong, url))
                    player.setMediaSource(mediaSource)
                    player.prepare()
                    player.play()
                    return@launch
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("PlayerVM", "Live station DRM failed: ${e.message}")
                }
            }
            currentCoroutineContext().ensureActive()
            if (!playbackRequests.isCurrent(request)) return@launch
            // No DRM key or DRM failed — try plain HLS
            player.setMediaItem(buildMediaItem(fakeSong, url))
            player.prepare()
            player.play()
          } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failStationIfCurrent(request)
            toast(tr(R.string.msg_032))
          }
        }
    }

    /** One metadata recovery per selection; stale network results cannot attach after a song change. */
    private suspend fun tryCatalogRecoverySource(song: Song, generation: Int): androidx.media3.exoplayer.source.MediaSource? {
        if (generation != playGen || song.isMusicVideo || catalogRetriedSongId == song.id) return null
        catalogRetriedSongId = song.id
        webServer.addLog("PLR", "recover catalog release for ${song.title} sf=${repo.catalogStorefront}")
        val replacement = appleClient.findReplacementCatalogSong(song, mutPrefs.getMUT(), repo.catalogStorefront)
        if (generation != playGen) return null
        if (replacement == null) {
            webServer.addLog("PLR", "no verified catalog replacement for ${song.title}")
            return null
        }
        _state.update { it.withRecoveredArtwork(song.id, replacement) }
        saveState()
        val displaySong = _state.value.currentSong?.takeIf { it.id == song.id } ?: song
        webServer.addLog("PLR", "verified catalog replacement id=${replacement.id} for ${song.title}")
        return buildStandaloneSource(displaySong.copy(id = replacement.id))
    }

    /**
     * Widevine media source for on-device playback. Suspends on a network round-trip
     * (bearer + webPlayback), so callers must await it before touching play() —
     * fire-and-forget here meant the PREVIOUS track kept playing and faded back in
     * while the UI already showed the new one. Returns null if standalone can't run;
     * the caller falls back to the proxy URL.
     */
    @OptIn(UnstableApi::class)
    private suspend fun buildStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? {
        @Suppress("ConstantConditionIf")
        if (DECRYPT_IN_APP && android.os.Build.VERSION.SDK_INT > 23) {
            val clear = buildDecryptedStandaloneSource(song)
            if (clear != null) return clear
            Log.w("AMCENC", "in-app decrypt failed, falling back to MediaDrm path")
        }
        return buildSystemStandaloneSource(song)
    }

    /** Android 6 uses its supported MediaDrm path directly, backed by account-bound encrypted audio. */
    @OptIn(UnstableApi::class)
    private suspend fun buildSystemStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? = try {
        val asset=systemAudio.prepare(song.id,foreground=true)
        val mut=mutPrefs.getMUT()
        if (mut.isEmpty() || com.applemusicktv.data.cache.EncryptedAudioStore.scope(mut)!=asset.scope) null
        else {
            val bearer=appleClient.getBearer()
            val wb=asset.playback
            val rewritten=appleClient.rewritePlaylistForExo(wb.hlsText,wb.hlsUrl)
            val playlist=if(asset.entry!=null) com.applemusicktv.data.cache.EncryptedAudioStore.localPlaylist(
                rewritten,"amtv-audio://cache/${asset.entry.name}") else rewritten
            val factory=androidx.media3.datasource.DataSource.Factory {
                com.applemusicktv.data.cache.SystemAudioDataSource(diskCache.store,asset.scope,
                    playlist.toByteArray(Charsets.UTF_8),androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource())
            }
            val drm=DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID,FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false).build(AppleMusicDrmCallback(wb.adamId,wb.keyUri,bearer,mut,http=cdmHttp))
            if(asset.hit) systemCacheSources+=song.id else systemCacheSources-=song.id
            webServer.addLog("SOURCE","song=${song.id} system-drm local=${asset.entry!=null} cacheHit=${asset.hit}")
            DefaultMediaSourceFactory(factory).setDrmSessionManagerProvider {drm}
                .createMediaSource(buildMediaItem(song,"amtv-playlist://audio/track.m3u8")
                    .buildUpon().setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build())
        }
    } catch(e:Exception) {
        if(e is kotlinx.coroutines.CancellationException) throw e
        webServer.addLog("SOURCE","system-drm failed song=${song.id} error=${e.javaClass.simpleName}")
        null
    }

    /** Path 1 milestone probe: derive the raw content key on-device with our own
     *  software Widevine CDM and log it (AMKEY). Non-fatal; wrapped in runCatching. */
    private val cdmHttp by lazy { okhttp3.OkHttpClient.Builder()
        .addInterceptor(com.applemusicktv.data.auth.AppleAuthorizationInterceptor(
            bearer = { kotlinx.coroutines.runBlocking { appleClient.getBearer() } },
            refresh = { rejected -> kotlinx.coroutines.runBlocking { appleClient.refreshBearer(rejected) } },
            userToken = mutPrefs::getMUT,
            state = appleClient.authorization,
        )).addNetworkInterceptor { chain ->
        val request = chain.request()
        chain.proceed(if (com.applemusicktv.data.auth.isAppleAuthorizationHost(request.url.host)) request
            else request.newBuilder().removeHeader("Authorization").removeHeader("Cookie").build())
    }.build() }
    private val decryptCalls = com.applemusicktv.media.DecryptCallRegistry()

    /** Run our software Widevine CDM to get the raw content key (hex) for a track. */
    private fun deriveContentKey(adamId: String, keyUri: String, bearer: String, mut: String, owner: String? = null, ownerJob: kotlinx.coroutines.Job? = null): String? =
        com.applemusicktv.media.widevine.WidevineCdm().getContentKey(keyUri) { challengeB64 ->
            val body = org.json.JSONObject().apply {
                put("challenge", challengeB64)
                put("key-system", "com.widevine.alpha")
                put("uri", keyUri)
                put("adamId", adamId)
                put("isLibrary", false)
                put("user-initiated", true)
            }.toString()
            val call = cdmHttp.newCall(
                okhttp3.Request.Builder()
                    .url("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $bearer")
                    .addHeader("Cookie", "media-user-token=$mut")
                    .addHeader("Origin", "https://music.apple.com")
                    .build()
            )
            val licenseB64 = if (owner != null && ownerJob != null)
                decryptCalls.read(call, owner, ownerJob) { org.json.JSONObject(it.body!!.string()).getString("license") }
            else call.execute().use { org.json.JSONObject(it.body!!.string()).getString("license") }
            android.util.Base64.decode(licenseB64, android.util.Base64.DEFAULT)
        }

    private fun probeCdmKey(adamId: String, keyUri: String, bearer: String, mut: String) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val key = deriveContentKey(adamId, keyUri, bearer, mut)
            Log.i("AMKEY", "adamId=$adamId key=$key (${System.currentTimeMillis() - t0}ms)")
            webServer.addLog("AMKEY", "adamId=$adamId key=$key (${System.currentTimeMillis() - t0}ms)")
        }.onFailure { Log.e("AMKEY", "probe error: ${it.message}", it) }
    }

    /**
     * Path 1 real path: derive key → download init + segments → decrypt in-app to a
     * CLEAR fMP4 on disk → play it with NO DRM, so the AAC decoder gets intact frames.
     * Returns null on any failure so the caller can fall back to the MediaDrm path.
     */
    private suspend fun buildDecryptedStandaloneSource(song: Song): androidx.media3.exoplayer.source.MediaSource? {
        val out = decryptToFile(song) ?: return null
        return DefaultMediaSourceFactory(diskCache.mediaDataSourceFactory())
            .createMediaSource(buildMediaItem(song, android.net.Uri.fromFile(out).toString()))
    }

    private val decryptInFlight = com.applemusicktv.media.OwnedTaskRegistry<String, kotlinx.coroutines.Deferred<java.io.File?>>()
    /** Serializes standalone decrypts — one whole-file download at a time. Running several at once
     *  (fast-skip + prefetch) split the WAN N ways: a single decrypt is ~3-4s, four concurrent
     *  measured 14-29s each, and the foreground song you landed on was the slowest of them. */
    private val decryptMutex = kotlinx.coroutines.sync.Mutex()
    /** Cancel abandoned foreground work as well as prefetch; preserve a promoted N+1 download. */
    private fun cancelOtherDecrypts(keepSongId: String?) {
        clearPreparedNext(keepSongId)
        systemAudio.cancelExcept(keepSongId)
        decryptInFlight.forEach { (id, job) ->
            if (id != keepSongId) { job.cancel(); decryptInFlight.remove(id, job) }
        }
        decryptCalls.cancelExcept(keepSongId)
    }

    /** Derive key → fetch whole fMP4 → decrypt in-app → clear file on disk. Reuses an
     *  already-decrypted file (that's what lets prefetch warm crossfade/skip targets),
     *  and dedupes concurrent requests for the same song (fast-skip fires many).
     *  `foreground` = the song being played right now; it preempts any in-flight prefetch download
     *  and takes the decrypt slot next, so it never waits behind a background warm. */
    private suspend fun decryptToFile(song: Song, foreground: Boolean = true): java.io.File? {
        val out = diskCache.mediaFile("clear_${song.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.mp4")
        diskCache.cachedMedia(out.name)?.let { return it }
        val existing = decryptInFlight[song.id]
        if (existing != null) {
            if (foreground) cancelOtherDecrypts(keepSongId = song.id)   // promote; but never abort THIS song
            return existing.await()
        }
        // Foreground preempts whatever prefetch download is in flight so it gets the slot immediately.
        if (foreground) cancelOtherDecrypts(keepSongId = song.id)
        val job = viewModelScope.async(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // Serialize: only one whole-file download runs at a time (no bandwidth splitting).
                decryptMutex.lock()
                try {
                    val bearer = appleClient.getBearer()
                    val mut = mutPrefs.getMUT()
                    if (bearer.isEmpty() || mut.isEmpty()) return@async null
                    val wb = appleClient.getWebPlayback(song.id, bearer, mut)
                    val keyUri = wb.keyUri ?: return@async null
                    val t0 = System.currentTimeMillis()
                    val keyHex = deriveContentKey(wb.adamId, keyUri, bearer, mut, song.id, currentCoroutineContext()[kotlinx.coroutines.Job]) ?: run {
                        Log.e("AMCENC", "no key"); return@async null
                    }
                    currentCoroutineContext().ensureActive()
                    // Apple delivers ONE fMP4 file, segmented only by #EXT-X-BYTERANGE.
                    val base = wb.hlsUrl.substring(0, wb.hlsUrl.lastIndexOf('/') + 1)
                    fun abs(u: String) = if (u.startsWith("http")) u else base + u
                    val fileUri = (Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(wb.hlsText)?.groupValues?.get(1)
                        ?: wb.hlsText.lineSequence().map { it.trim() }
                            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: return@async null)
                        .substringBefore('?').let(::abs)
                    val call = cdmHttp.newCall(
                        okhttp3.Request.Builder().url(fileUri)
                            .addHeader("Authorization", "Bearer $bearer")
                            .addHeader("Cookie", "media-user-token=$mut").build()
                    )
                    val encrypted = decryptCalls.read(call, song.id, currentCoroutineContext()[kotlinx.coroutines.Job]!!) { response ->
                        val body = response.body
                        if (!response.isSuccessful || body == null || body.contentLength() > CacheLimits.MAX_MEDIA_ENTRY_BYTES) null
                        else body.bytes()
                    } ?: return@async null
                    currentCoroutineContext().ensureActive()
                    val currentJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                    val dec = com.applemusicktv.media.widevine.CencDecryptor(keyHex)
                    val clear = dec.decryptWhole(encrypted) { currentJob.ensureActive() }
                    currentCoroutineContext().ensureActive()
                    val saved = diskCache.putMedia(out.name, clear) ?: return@async null
                    webServer.addLog("AMCENC", "song=${song.id} in=${encrypted.size} out=${out.length()} ${System.currentTimeMillis() - t0}ms ${if (foreground) "fg" else "bg"}")
                    saved
                } finally { decryptMutex.unlock() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AMCENC", "decrypt failed for ${song.id}: ${e.message}", e); null
            } finally {
                decryptInFlight.remove(song.id, currentCoroutineContext()[kotlinx.coroutines.Job])
            }
        }
        decryptInFlight[song.id] = job
        return job.await()
    }

    fun pause() {
        radioRetryJob?.cancel(); radioRetryJob = null
        sourcePreparation.pause()
        player.pause()
        crossfadeExo?.pause()
        _state.update { it.copy(isPlaying = false, isLoading = false) }
        saveState()
    }

    /** Explicit play is idempotent, including stopped/ended players and interrupted loads. */
    fun resume() {
        radioRetryJob?.cancel(); radioRetryJob = null
        if (pendingRestore != null) { startPendingRestore(); return }
        if (sourcePreparation.isPending) {
            sourcePreparation.resume()
            _state.update { it.copy(isLoading = true) }
            return
        }
        if (failedPreparationSongId != null && failedPreparationSongId == _state.value.currentSong?.id) {
            playQueueItem(_state.value.queueIndex)
            return
        }
        if (player.mediaItemCount == 0) return
        attachedSourceGeneration = playGen
        resumePreparedPlayer(player)
        crossfadeExo?.takeIf { it.mediaItemCount > 0 }?.let(::resumePreparedPlayer)
    }

    fun togglePlayPause() {
        if (_state.value.stationLaunch != null) return
        val wantsPlay = if (sourcePreparation.isPending) sourcePreparation.playWhenReady else player.playWhenReady
        // stop() may retain playWhenReady=true (e.g. a failed resume on V7).
        if (wantsPlay && (sourcePreparation.isPending ||
                player.playbackState !in setOf(Player.STATE_IDLE, Player.STATE_ENDED))) pause()
        else resume()
    }

    /** Fade time advances only while playback is requested, including a buffering partner. */
    private suspend fun awaitFadePlayback() {
        while (!player.playWhenReady) delay(100)
    }

    fun next() {
        if (_state.value.stationLaunch != null) return
        if (radioActive) return   // live radio is a single continuous stream — no skipping
        val s = _state.value
        webServer.addLog("NAV", "next() pos=${player.currentPosition}ms idx=${s.queueIndex} userQueue=${s.userQueue.size}")
        fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        prefetchRequestedForSongId = null
        if (s.userQueue.isNotEmpty()) {
            val nextSong = s.userQueue.first()
            val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
            val newQueue = s.queue.toMutableList().also { it.add(insertIdx, nextSong) }
            _state.update { it.copy(queue = newQueue, userQueue = it.userQueue.drop(1)) }
            playQueueItem(insertIdx, skipFadeIn = true)
        } else {
            // Repeat All wraps on a manual skip too — otherwise pressing Next on the
            // last track just stops, which contradicts the mode being on.
            val target = if (s.repeatMode == RepeatMode.All && s.queueIndex + 1 >= s.queue.size) 0
                         else s.queueIndex + 1
            playQueueItem(target, skipFadeIn = true)
        }
    }
    fun prev() {
        if (_state.value.stationLaunch != null) return
        if (radioActive) return   // live radio — no skipping back
        val s = _state.value
        val pos = player.currentPosition
        webServer.addLog("NAV", "prev() pos=${pos}ms idx=${s.queueIndex}")
        fadeJob?.cancel(); crossfadeInProgress = false; player.volume = 1f; player.repeatMode = Player.REPEAT_MODE_OFF
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        prefetchRequestedForSongId = null
        // Past 10s, Prev restarts the current song (standard player behaviour);
        // only a quick double-press walks back to the previous track.
        if (pos > 10_000L) { player.seekTo(0L); _state.update { it.copy(progressMs = 0L) }; return }
        val prevIdx = s.queueIndex - 1
        if (prevIdx >= 0) playQueueItem(prevIdx, skipFadeIn = true) else player.seekTo(0L)
    }

    fun moveQueueItem(from: Int, to: Int) {
        val q = _state.value.queue.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val item = q.removeAt(from)
        q.add(to, item)
        val newIdx = when {
            from == _state.value.queueIndex -> to
            from < _state.value.queueIndex && to >= _state.value.queueIndex -> _state.value.queueIndex - 1
            from > _state.value.queueIndex && to <= _state.value.queueIndex -> _state.value.queueIndex + 1
            else -> _state.value.queueIndex
        }
        _state.update { it.copy(queue = q, queueIndex = newIdx) }
    }
    fun seekForward() { webServer.addLog("PLR", "seekForward pos=${player.currentPosition}"); player.seekTo((player.currentPosition + 15_000L).coerceAtMost(player.duration.coerceAtLeast(0L))) }
    fun seekBack()    { webServer.addLog("PLR", "seekBack pos=${player.currentPosition}"); player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L)) }

    /** Public warm-up for the next AUDIO song — used by AppShell while a VIDEO is playing (the
     *  audio engine's own N+1 prefetch is idle then, so a video→song advance would be cold). */
    fun prefetchAudio(song: Song) { if (!song.isMusicVideo) prefetchSong(song) }

    private fun prefetchSong(song: Song) {
        if (!_state.value.isFullStream) return
        // Music videos are decrypted+played by the video player, not the audio stream route —
        // prefetching one via /api/stream just 404s ("No playable asset"). The video player has
        // its own prefetch (MusicVideoViewModel.prefetch), triggered from AppShell.
        if (song.isMusicVideo) return
        // Warm lyrics for the upcoming song so they render the instant it starts.
        viewModelScope.launch {
            runCatching { repo.prefetchLyrics(song.id, song.title, song.artistName, song.durationMs / 1000) }
        }
        // Standalone: warm the in-app decrypt cache instead of pinging the (often
        // absent) proxy. decryptToFile writes clear_<id>.mp4, which the next play or
        // crossfade then reuses instantly.
        if (useStandalone() && (song.id !in proxyOnlySongIds || !serverPrefs.serverReachable)) {
            val generation=playGen
            viewModelScope.launch {
                repeat(2) { attempt ->
                    if(generation!=playGen) return@launch
                    try {
                        val clear=if(android.os.Build.VERSION.SDK_INT>23) decryptToFile(song,foreground=false) else null
                        val ready=clear!=null || systemAudio.prepare(song.id,foreground=false).entry!=null
                        webServer.addLog("PRE", "audio ${if(ready) "ready" else "not-ready"} song=${song.id} attempt=${attempt+1}")
                        if(ready) {prepareNextWithoutFade(song,generation);return@launch}
                    } catch(e:kotlinx.coroutines.CancellationException) {throw e}
                    catch(e:Exception) {webServer.addLog("PRE","audio failed song=${song.id} error=${e.javaClass.simpleName}")}
                    if(attempt==0) delay(2_000L)
                }
            }
            return
        }
        if (!serverPrefs.serverReachable) return
        val url = repo.prefetchUrl(song.id)
        webServer.addLog("PRE", "prefetch start song=${song.title} url=$url")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-Music-User-Token", mutPrefs.getMUT())
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                conn.disconnect()
                webServer.addLog("PRE", "prefetch response=$code song=${song.title}")
            } catch (e: Exception) {
                webServer.addLog("PRE", "prefetch error=${e.message} song=${song.title}")
            }
        }
    }

    // Add to end of user priority queue (plays before rest of playlist)
    fun addToQueue(song: Song) { if (!VideoPlaybackPolicy.allows(song)) return; _state.update { it.copy(userQueue = it.userQueue + song) }; prefetchSong(song) }

    // Insert at front of user priority queue (plays immediately next)
    fun playNext(song: Song) { if (!VideoPlaybackPolicy.allows(song)) return; _state.update { it.copy(userQueue = listOf(song) + it.userQueue) }; prefetchSong(song) }

    // Play a specific userQueue item immediately, removing it from the queue
    fun playFromUserQueue(idx: Int) {
        val s = _state.value
        val song = s.userQueue.getOrNull(idx) ?: return
        val newUserQueue = s.userQueue.toMutableList().also { it.removeAt(idx) }
        _state.update { it.copy(userQueue = newUserQueue) }
        hasPlayedSomething = true
        val insertIdx = (s.queueIndex + 1).coerceIn(0, s.queue.size)
        val newQueue = s.queue.toMutableList().also { it.add(insertIdx, song) }
        _state.update { it.copy(queue = newQueue) }
        playQueueItem(insertIdx, skipFadeIn = true)
    }

    // One shared in-flight fetch per song id: a song is often prefetched (as the next track) and then
    // asked for again via loadLyrics when it becomes current. Without this, both fire a full network
    // fetch for the same id at once (seen as two concurrent getLyrics in logcat). Both paths now await
    // the same Deferred.
    private val lyricsInFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<List<LyricLine>>>()

    private fun fetchLyricsShared(id: String, title: String, artist: String, durationSec: Long): kotlinx.coroutines.Deferred<List<LyricLine>> {
        lyricsCache[id]?.let { return kotlinx.coroutines.CompletableDeferred(it) }
        lyricsInFlight[id]?.let { return it }
        // Register before starting, including immediate failures/cache hits; always release the slot.
        val d = viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val lines = repo.getLyrics(id, title, artist, durationSec).getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    webServer.addLog("LYRICS", "fetch failed song=$id error=${error.javaClass.simpleName}")
                    emptyList()
                }
                if (lines.isNotEmpty()) lyricsCache[id] = lines
                lines
            } finally {
                if (lyricsInFlight[id] === currentCoroutineContext()[kotlinx.coroutines.Job]) lyricsInFlight.remove(id)
            }
        }
        lyricsInFlight[id] = d
        d.start()
        return d
    }

    private fun loadLyrics(songId: String) {
        lyricsJob?.cancel()
        lyricsFailureNotice.beginSong(songId)
        _state.update { it.copy(lyricsNotice=LyricsNoticePhase.Idle,lyricsNoticeSongId=songId,
            lyricsNoticeGeneration=lyricsFailureNotice.generation,emptyLyricsNoticeCompletedFor=null,
            exhaustedEmptyLyricsFor=null) }
        val generation = ++lyricsLoadGeneration
        lyricsCache[songId]?.let { cached ->
            if (_state.value.currentSong?.id == songId) _state.update { it.copy(lyrics = cached) }
            return
        }
        val song = _state.value.currentSong?.takeIf { it.id == songId } ?: return
        fun stillCurrent() = lyricsLoadGeneration == generation &&
            _state.value.currentSong?.id == songId && !_state.value.isLiveRadio && !song.isMusicVideo
        lyricsJob = viewModelScope.launch {
            fetchLyricsWithRetries(
                isCurrent = ::stillCurrent,
                fetch = {
                    val request=fetchLyricsShared(songId,song.title,song.artistName,song.durationMs / 1000)
                    try {request.await()} catch(e:kotlinx.coroutines.CancellationException) {request.cancel();throw e}
                },
                exhausted = {
                    if(stillCurrent() && _state.value.lyrics.isEmpty()) {
                        _state.update {it.copy(exhaustedEmptyLyricsFor=songId)}
                        lyricsFailureNotice.finishEmpty()
                        webServer.addLog("LYRICS","background retries finished song=$songId; notice eligible")
                    }
                },
                publish = { lines ->
                    _state.update { if (stillCurrent()) it.copy(lyrics = lines) else it }
                },
                report = { attempt, count, error ->
                    lyricsFailureNotice.report(attempt, count)
                    webServer.addLog("LYRICS", "song=$songId attempt=$attempt/3 lines=$count" +
                        (error?.let { " error=${it.javaClass.simpleName}" } ?: "") +
                        if (count == 0 && attempt == 3) " exhausted" else "")
                },
            )
        }
    }

    /** Warm the lyrics cache for an upcoming song so a track change shows them with no blank flash. */
    private fun prefetchLyrics(song: Song) {
        if (lyricsCache.containsKey(song.id) || lyricsInFlight.containsKey(song.id)) return
        fetchLyricsShared(song.id, song.title, song.artistName, song.durationMs / 1000)
    }

    private fun loadMotion(songId: String) {
        motionJob?.cancel()
        _state.update { it.copy(motionUrl = null) }
        if (!_state.value.motionArtworkEnabled) return   // off → don't even fetch the motion URL
        motionJob = viewModelScope.launch {
            repo.getMotion(songId).onSuccess { url ->
                if (_state.value.currentSong?.id == songId && _state.value.motionArtworkEnabled)
                    _state.update { it.copy(motionUrl = url) }
            }
        }
    }

    private fun enrichSongIds(songId: String) = viewModelScope.launch {
        repo.getSong(songId).onSuccess { enriched ->
            if (enriched.artistId == null && enriched.albumId == null) return@onSuccess
            _state.update { s ->
                val updatedQueue = s.queue.map { q -> if (q.id == songId) q.copy(artistId = enriched.artistId ?: q.artistId, albumId = enriched.albumId ?: q.albumId) else q }
                val updatedSong  = s.currentSong?.takeIf { it.id == songId }?.copy(artistId = enriched.artistId ?: s.currentSong.artistId, albumId = enriched.albumId ?: s.currentSong.albumId)
                s.copy(queue = updatedQueue, currentSong = updatedSong ?: s.currentSong, song = updatedSong ?: s.song)
            }
        }
    }

    suspend fun lookupSongIds(songId: String): Pair<String?, String?> =
        repo.getSong(songId).getOrNull()?.let { it.artistId to it.albumId } ?: (null to null)

    private var lastAutoSaveMs = 0L
    private var lastProgressPushMs = 0L

    private fun pollProgress() = viewModelScope.launch {
        while (true) {
            val now = System.currentTimeMillis()
            val playing = player.isPlaying
            val playState = player.playbackState
            // During a crossfade the UI already shows the NEXT song, so track that
            // player's position — otherwise the bar sits frozen on the old song.
            val cf = crossfadeExo
            val progressSource = if (crossfadeInProgress && cf != null && cf.playbackState == Player.STATE_READY) cf else player
            // Re-anchor the UI clock about once a second, not every 200ms. The loop must
            // stay fast to catch STATE_ENDED and the crossfade window promptly, but the
            // UI interpolates between anchors per frame (rememberSmoothProgressMs), so
            // pushing progress 5x/sec just recomposed everything reading PlayerState for
            // no visible gain.
            val sourcePos = pendingRestore?.posMs ?: progressSource.currentPosition
            if (awaitingSongStart != null) {
                // Just switched tracks — pin the clock at 0 until the new item is genuinely
                // attached and READY for this generation, so the old position/duration
                // doesn't linger on the bar during the source build.
                if (currentSourceReady(playGen) && !sourcePreparation.isPending) awaitingSongStart = null
                if (_state.value.progressMs != 0L) _state.update { it.copy(progressMs = 0L) }
            } else {
                // A seek while PAUSED moves the player but not the clock, and the throttle
                // below only runs while playing — so the bar would sit frozen at the old
                // position until playback resumed. Detect the jump and push it through.
                val jumped = kotlin.math.abs(sourcePos - _state.value.progressMs) > 1_000L
                if (playing || progressSource !== player || jumped) {
                    if (jumped || now - lastProgressPushMs >= 900L) {
                        lastProgressPushMs = now
                        _state.update { it.copy(progressMs = sourcePos) }
                    }
                }
            }
            // Buffering only counts as "loading" while we're mid-crossfade or actually
            // trying to play — a paused player sitting at IDLE isn't waiting on anything.
            val loading = (sourcePreparation.isPending && sourcePreparation.playWhenReady) ||
                ((playState == Player.STATE_BUFFERING) && player.playWhenReady)
            if (loading != _state.value.isLoading) _state.update { it.copy(isLoading = loading) }
            // Guard against a double-advance: after advanceQueue() the player keeps
            // reporting STATE_ENDED for a few ticks while the new (standalone) source builds.
            // awaitingSongStart stays set until the next item is genuinely playing, so we
            // don't skip a second time within a couple of seconds.
            if (playState == Player.STATE_ENDED && !crossfadeInProgress && awaitingSongStart == null) advanceQueue()
            // Auto-save position every 10s while playing so restore lands at the right spot
            if (playing && now - lastAutoSaveMs > 10_000L) { lastAutoSaveMs = now; saveState() }
            val timerEnd = _state.value.sleepTimerEndsAt
            if (timerEnd != null) {
                val remaining = timerEnd - System.currentTimeMillis()
                // Ease the volume down over the final 5s instead of a hard cut.
                if (remaining in 1..SLEEP_FADE_MS && playing) {
                    sleepFadeActive = true
                    player.volume = (remaining.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
                }
                if (remaining <= 0L) {
                    webServer.addLog("SLEEP", "timer fired at pos=${player.currentPosition}ms song=${_state.value.currentSong?.title}")
                    pause(); player.volume = 1f; sleepFadeActive = false
                    _state.update { it.copy(sleepTimerEndsAt = null, isPlaying = false) }
                }
            } else if (sleepFadeActive) {
                player.volume = 1f; sleepFadeActive = false   // timer cancelled mid-fade → restore
            }

            // HTTP prefetch N+2 at 2/3 through current song
            if (playing && playState == Player.STATE_READY && !crossfadeInProgress) {
                val dur = player.duration
                val pos = player.currentPosition
                val s = _state.value
                val n2Song = if (s.userQueue.size >= 2) s.userQueue[1]
                             else if (s.userQueue.size == 1) s.queue.getOrNull(s.queueIndex + 2)
                             else s.queue.getOrNull(s.queueIndex + 2)
                if (dur > 0 && pos >= dur * 3 / 4 && n2Song != null && s.isFullStream
                    && prefetchRequestedForSongId != n2Song.id) {
                    prefetchRequestedForSongId = n2Song.id
                    prefetchSong(n2Song)
                    webServer.addLog("PRE", "prefetch N+2 at pos=${pos}ms/${dur}ms song=${n2Song.title}")
                }
            }

            // Crossfade: old player fades out over 5s; new player fades IN only for the last 3s
            if (playing && playState == Player.STATE_READY && !crossfadeInProgress) {
                val dur = player.duration
                val pos = player.currentPosition
                val remaining = dur - pos
                val s = _state.value
                val nextIdx = s.queueIndex + 1
                // Repeat One replays the current song, so there is no "next" to fade
                // into — fading would swap in the wrong track entirely. Repeat All on
                // the last song wraps to index 0, but only if that isn't the same song.
                val wrapsToStart = s.repeatMode == RepeatMode.All &&
                    nextIdx >= s.queue.size && s.queue.size > 1
                val hasNext = s.repeatMode != RepeatMode.One &&
                    (s.userQueue.isNotEmpty() || nextIdx < s.queue.size || wrapsToStart)
                val configuredCrossfadeMs=crossfadeDurationMs
                if (dur > 0 && remaining in 1..configuredCrossfadeMs && hasNext &&
                    com.applemusicktv.data.CrossfadePreferences.isEnabled(s.crossfadeEnabled,configuredCrossfadeMs)) {
                    val nextSong = s.userQueue.firstOrNull()
                        ?: s.queue.getOrNull(nextIdx)
                        ?: s.queue.firstOrNull().takeIf { wrapsToStart }
                    if (cfWindowLoggedForSongId != s.queue.getOrNull(s.queueIndex)?.id) {
                        webServer.addLog("CFXO", "crossfade window: remaining=${remaining}ms next=${nextSong?.title} isFullStream=${s.isFullStream} enabled=${s.crossfadeEnabled} skip=${nextSong?.id == crossfadeSkipSongId}")
                    }
                    // Gapless: consecutive tracks off the same album were often mastered
                    // to run continuous (live records, mixes, segued sides). Fading them
                    // talks over the transition the artist built, so hand off cleanly
                    // instead. albumId is null on some library rows — fall back to name.
                    val cur = s.queue.getOrNull(s.queueIndex)
                    val sameAlbum = cur != null && nextSong != null &&
                        (if (cur.albumId != null && nextSong.albumId != null) cur.albumId == nextSong.albumId
                         else cur.albumName.isNotBlank() && cur.albumName == nextSong.albumName)
                    val consecutive = cur?.trackNumber != null && nextSong?.trackNumber != null &&
                        nextSong.trackNumber == cur.trackNumber + 1
                    val gapless = sameAlbum && consecutive && s.userQueue.isEmpty()
                    // Log once per song, not once per 200ms poll — this block re-evaluates
                    // for the whole crossfade window and each addLog is an HTTP POST.
                    if (cfWindowLoggedForSongId != cur?.id) {
                        cfWindowLoggedForSongId = cur?.id
                        if (gapless) {
                            webServer.addLog("CFXO", "gapless: same album consecutive tracks — no fade into ${nextSong?.title}")
                        }
                    }
                    if (nextSong != null && s.isFullStream && !gapless && nextSong.id != crossfadeSkipSongId) {
                        clearPreparedNext()
                        crossfadeInProgress = true
                        // buildCrossfadeExo has no DRM of its own, so in standalone mode
                        // the fade partner must get a Widevine source too — otherwise it
                        // fetches a proxy URL that may not even be reachable, errors out,
                        // and every transition silently degrades to a hard cut.
                        val cfStandalone = useStandalone()
                        val cfExo = buildCrossfadeExo().also { e ->
                            e.volume = 0f
                            if (!cfStandalone) {
                                e.setMediaItem(buildMediaItem(nextSong, repo.streamUrl(nextSong.id)))
                                e.prepare()
                                e.play()
                            }
                        }
                        val preFadeQueue = s.queue; val preFadeIdx = s.queueIndex; val preFadeUserQueue = s.userQueue
                        val newUserQueue = if (s.userQueue.isNotEmpty()) s.userQueue.drop(1) else s.userQueue
                        val newQueue = if (s.userQueue.isNotEmpty()) {
                            s.queue.toMutableList().also { it.add((s.queueIndex + 1).coerceIn(0, it.size), nextSong) }
                        } else s.queue
                        // Repeat All off the end of the queue wraps to 0; coerceIn would
                        // otherwise clamp to lastIndex and leave the index on the song
                        // we're fading *out* of.
                        val actualNextIdx = if (wrapsToStart && s.userQueue.isEmpty()) 0
                            else (s.queueIndex + 1).coerceIn(0, newQueue.lastIndex)
                        val errListener = object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                webServer.addLog("CFXO", "cfExo error: ${error.errorCodeName} — restoring old player")
                                fadeJob?.cancel()
                                crossfadeInProgress = false; crossfadeExo = null; cfExoErrListener = null
                                crossfadeSkipSongId = nextSong.id
                                cfExo.removeListener(this); cfExo.release()
                                player.volume = 1f
                                val prevSong = preFadeQueue.getOrNull(preFadeIdx)
                                _state.update { it.copy(
                                    currentSong = prevSong, song = prevSong, lyrics = emptyList(), motionUrl = null,
                                    queue = preFadeQueue, queueIndex = preFadeIdx, userQueue = preFadeUserQueue,
                                )}
                                if (prevSong != null) { loadLyrics(prevSong.id); loadMotion(prevSong.id) }
                            }
                        }
                        cfExoErrListener = errListener
                        cfExo.addListener(errListener)
                        crossfadeExo = cfExo
                        if (cfStandalone) {
                            viewModelScope.launch {
                                kotlinx.coroutines.yield() // Let queue/fade setup finish before failure recovery can run.
                                val src = buildStandaloneSource(nextSong)
                                if (crossfadeExo !== cfExo) return@launch
                                if (src == null && !serverPrefs.serverReachable) {
                                    errListener.onPlayerError(androidx.media3.common.PlaybackException(
                                        tr(R.string.msg_037), null,
                                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                                    return@launch
                                }
                                if (src != null) cfExo.setMediaSource(src)
                                else cfExo.setMediaItem(buildMediaItem(nextSong, repo.streamUrl(nextSong.id)))
                                cfExo.prepare()
                                cfExo.playWhenReady = player.playWhenReady
                            }
                        }
                        val oldPlayer = player
                        _state.update { it.copy(
                            currentSong = nextSong, song = nextSong, lyrics = emptyList(), motionUrl = null,
                            queue = newQueue, queueIndex = actualNextIdx, userQueue = newUserQueue,
                            progressMs = 0L,
                        )}
                        loadLyrics(nextSong.id); loadMotion(nextSong.id)
                        if (nextSong.artistId == null || nextSong.albumId == null) enrichSongIds(nextSong.id)
                        val fadeDurationMs = remaining.coerceIn(300L, configuredCrossfadeMs)
                        // Cancel the song-start fade-in first. On a track shorter than
                        // ~2x the crossfade length the two windows overlap, and without
                        // this both jobs drive the same player's volume — one up, one
                        // down — and the old job is orphaned when the field is reassigned.
                        fadeJob?.cancel()
                        fadeJob = viewModelScope.launch {
                            val steps = 50
                            val stepMs = fadeDurationMs / steps
                            val startVol = oldPlayer.volume
                            // Hand the beat bus to the incoming song once it's the louder
                            // one. Otherwise the visuals pulse to the outgoing song's tail
                            // (often a quiet outro) for the whole crossfade and read as dead.
                            var beatPromoted = false
                            for (i in 1..steps) {
                                awaitFadePlayback()
                                val frac = i.toFloat() / steps
                                oldPlayer.volume = (startVol * (1f - frac)).coerceAtLeast(0f)
                                cfExo.volume = frac.coerceAtMost(1f)
                                if (!beatPromoted && frac >= 0.5f && cfExo.playbackState == Player.STATE_READY) {
                                    promoteCrossfadeBeat(); beatPromoted = true
                                }
                                delay(stepMs)
                            }
                            if (!crossfadeInProgress || crossfadeExo == null) {
                                webServer.addLog("CFXO", "cfExo released during fade — aborting swap")
                                return@launch
                            }
                            // If cfExo is still buffering (cache not ready), give it a short grace
                            // period. The old song loops while we wait, so waiting long is worse
                            // than bailing — and if the server is down it's never coming.
                            if (cfExo.playbackState == Player.STATE_BUFFERING) {
                                val waitMs = if (serverPrefs.serverReachable) 8_000L else 0L
                                webServer.addLog("CFXO", "cfExo buffering after fade — waiting ${waitMs}ms (reachable=${serverPrefs.serverReachable})")
                                oldPlayer.volume = 1f
                                val deadline = System.currentTimeMillis() + waitMs
                                while (cfExo.playbackState == Player.STATE_BUFFERING
                                    && System.currentTimeMillis() < deadline
                                    && crossfadeInProgress) {
                                    delay(100)
                                    awaitFadePlayback()
                                }
                            }
                            awaitFadePlayback()
                            if (!crossfadeInProgress || crossfadeExo == null) {
                                webServer.addLog("CFXO", "cfExo released while waiting for ready — aborting")
                                return@launch
                            }
                            if (cfExo.playbackState == Player.STATE_READY) {
                                // Quick 1s crossfade if we had to wait
                                val qSteps = 10
                                repeat(qSteps) { i ->
                                    awaitFadePlayback()
                                    val f = (i + 1).toFloat() / qSteps
                                    oldPlayer.volume = (1f - f).coerceAtLeast(0f)
                                    cfExo.volume = f.coerceAtMost(1f)
                                    delay(100)
                                }
                                oldPlayer.removeListener(playerListener)
                                oldPlayer.volume = 0f; oldPlayer.stop(); oldPlayer.release()
                                cfExo.removeListener(errListener); cfExoErrListener = null
                                cfExo.addListener(playerListener)
                                player = cfExo; crossfadeExo = null; crossfadeInProgress = false
                                promoteCrossfadeBeat()
                                webServer.addLog("CFXO", "fade complete → cfExo isPlaying=${cfExo.isPlaying}")
                                mediaSession?.release(); mediaSession = buildMediaSession(cfExo)
                                _state.update { it.copy(isPlaying = cfExo.isPlaying) }
                                val sNow = _state.value
                                val prefetchNext = sNow.userQueue.firstOrNull() ?: sNow.queue.getOrNull(sNow.queueIndex + 1)
                                if (prefetchNext != null && prefetchRequestedForSongId != prefetchNext.id) {
                                    prefetchRequestedForSongId = prefetchNext.id; prefetchSong(prefetchNext)
                                }
                            } else {
                                webServer.addLog("CFXO", "cfExo state=${cfExo.playbackState} — giving up, playing next directly")
                                crossfadeInProgress = false; crossfadeExo = null; cfExoErrListener = null
                                try { cfExo.removeListener(errListener); cfExo.stop(); cfExo.release() } catch (_: Exception) {}
                                oldPlayer.volume = 1f
                                playQueueItem(_state.value.queueIndex, skipFadeIn = true)
                            }
                        }
                    }
                }
            }

            delay(if (playing && nowPlayingVisible) 200L else if (playing) 1_000L else 500L)
        }
    }

    private fun buildMediaItem(song: Song, uri: String) = MediaItem.Builder()
        .setMediaId(song.id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistName)
                .setAlbumTitle(song.albumName)
                .setArtworkUri(song.artworkUrl(600)?.let { android.net.Uri.parse(it) })
                .build()
        ).build()

    fun showNotification(title: String, body: String, id: Int = 42) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "am_alerts"
        if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, tr(R.string.msg_038), NotificationManager.IMPORTANCE_HIGH)
                    .also { it.description = tr(R.string.msg_039) }
            )
        }
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    private suspend fun checkAppleServiceStatus() {
        val result = repo.getAppleStatus().getOrNull() ?: return
        if (!result.ok) {
            val affected = result.services
                .filter { it.status != "operational" }
                .joinToString(", ") { it.name }
                .ifEmpty { "Apple Music" }
            webServer.addLog("SVCMON", tr(R.string.msg_040, affected))
            showNotification(
                title = tr(R.string.msg_041),
                body  = tr(R.string.msg_042, affected),
                id    = 43,
            )
        }
    }

    private fun showAuthNotification() {
        val url = webServer.serverUrl()
        showNotification(
            title = tr(R.string.msg_043),
            body  = if (url.isEmpty()) tr(webServer.setupHintId()) else tr(R.string.msg_044, url),
            id    = 42,
        )
    }

    override fun onCleared() {
        cancelOtherDecrypts(null)
        radioRetryJob?.cancel()
        audioDeviceCallback?.let {
            runCatching { context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(it) }
        }
        audioDeviceCallback = null
        saveState()
        if (bookmarkWriterDelegate.isInitialized()) bookmarkWriter.close()
        lyricsJob?.cancel()
        motionJob?.cancel()
        stationJob?.cancel()
        relatedQueueJob?.cancel()
        fadeJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        crossfadeExo?.let { cfExoErrListener?.let { l -> it.removeListener(l) }; it.stop(); it.release() }
        crossfadeExo = null; cfExoErrListener = null
        player.stop()
        player.clearMediaItems()
        player.release()
        diskCache.unprotect("audio")
        super.onCleared()
    }
}
