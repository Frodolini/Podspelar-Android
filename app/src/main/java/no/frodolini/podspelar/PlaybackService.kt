package no.frodolini.podspelar

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

const val SEEK_BACK_MS = 15_000L
const val SEEK_FORWARD_MS = 30_000L

/**
 * Tenesta som spelar av lyden. Ho lever vidare når appen ikkje er framme, og gir
 * kontrollar i varsellinja, på låseskjermen og på headset/bil.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Poddar ligg ofte bak fleire vidaresendingar (statistikktenester), også frå http til https.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)     // pause når headsettet blir kopla frå
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val buttons = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setDisplayName("15 sekund tilbake")
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setDisplayName("30 sekund fram")
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .build(),
        )
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(open)
            .setMediaButtonPreferences(buttons)
            .build()
    }

    private val callback = object : MediaSession.Callback {
        // «Førre/neste» gir inga meining med éin episode om gongen. Når dei er borte,
        // får varselet hopp 15 s tilbake og 30 s fram i staden.
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        // Lydadressa blir ikkje send med frå appen til tenesta av seg sjølv, så ho
        // blir lagd i requestMetadata og sett inn att her.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
            )
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(tick)
            if (isPlaying) handler.postDelayed(tick, 5_000) else savePosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                player.currentMediaItem?.let { Store.setPos(it.mediaId, 0, player.duration.coerceAtLeast(0), true) }
            }
        }

        // Når ein ny episode tek over, hugs kor langt den førre kom.
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val oldId = old.mediaItem?.mediaId ?: return
            if (oldId != new.mediaItem?.mediaId && old.positionMs > 0) {
                val wasDone = Store.positions[oldId]?.done == true && old.positionMs < 2_000
                if (!wasDone) Store.setPos(oldId, old.positionMs, 0, false)
            }
        }
    }

    private fun savePosition() {
        val item = player.currentMediaItem ?: return
        if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) return
        val dur = player.duration
        Store.setPos(item.mediaId, player.currentPosition, if (dur == C.TIME_UNSET) 0 else dur, false)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        savePosition()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Appen si kopling til avspelingstenesta. Skjermane les tilstanden herfrå. */
object Playback {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var isPlaying by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var currentId by mutableStateOf<String?>(null)
    var posMs by mutableLongStateOf(0L)
    var durMs by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)

    fun connect(ctx: Context) {
        if (future != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            if (future !== f) return@addListener
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error = "Klarte ikkje spela av episoden (${e.errorCodeName})"
                }
            })
            refresh()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun disconnect() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    /** Blir kalla jamleg frå skjermen medan appen er framme. */
    fun refresh() {
        val c = controller ?: return
        isPlaying = c.isPlaying
        loading = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady
        currentId = c.currentMediaItem?.mediaId
        if (currentId != null) {
            posMs = c.currentPosition.coerceAtLeast(0)
            durMs = c.duration.let { if (it == C.TIME_UNSET) 0 else it }
        }
    }

    fun play(e: Episode) {
        val c = controller ?: return
        error = null
        if (c.currentMediaItem?.mediaId == e.id && c.playbackState != Player.STATE_IDLE) {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
            return
        }
        Store.rememberLast(e)
        val saved = Store.positions[e.id]
        val start = if (saved != null && !saved.done) saved.posMs else 0L
        // Nedlasta episodar blir spela frå fila, resten blir strøymde.
        val file = Store.fileFor(e)
        val uri = if (Store.isDownloaded(e) && file.exists()) Uri.fromFile(file) else Uri.parse(e.audioUrl)
        val item = MediaItem.Builder()
            .setMediaId(e.id)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setArtist(e.podcastTitle)
                    .setArtworkUri(e.artUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()
            )
            .build()
        posMs = start
        durMs = saved?.durMs ?: (e.durationSec * 1000L)
        c.setMediaItem(item, start)
        c.setPlaybackSpeed(Store.speed)
        c.prepare()
        c.play()
    }

    /** Spel av / pause for den episoden som er framme i spelaren. */
    fun toggle() {
        val c = controller ?: return
        if (c.currentMediaItem == null || c.playbackState == Player.STATE_IDLE) {
            Store.last?.let { play(it) }
        } else if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        posMs = ms
    }

    fun back() { controller?.seekBack(); refresh() }
    fun forward() { controller?.seekForward(); refresh() }

    fun setSpeed(s: Float) {
        Store.rememberSpeed(s)
        controller?.setPlaybackSpeed(s)
    }
}
