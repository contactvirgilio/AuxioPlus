package org.oxycblt.auxio.radio

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import org.oxycblt.auxio.radio.visualizer.VisualizerAudioProcessor
import org.oxycblt.auxio.radio.visualizer.VisualizerBuffer

/** Plays the streams in the background, with the notification and lock screen controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    override fun onCreate() {
        super.onCreate()
        val renderers =
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(VisualizerAudioProcessor(VisualizerBuffer)))
                        .build()
            }
        val http =
            DefaultHttpDataSource.Factory()
                .setUserAgent(RadioBrowser.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
        val player =
            ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    NowPlaying.isPlaying = isPlaying
                    if (isPlaying) retries = 0
                }

                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    NowPlaying.title = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Streams drop now and then. Try again a few times before giving up.
                    if (retries++ < MAX_RETRIES) {
                        handler.postDelayed(
                            {
                                player.prepare()
                                player.play()
                            },
                            RETRY_DELAY_MS * retries,
                        )
                    }
                }
            }
        )
        session = MediaSession.Builder(this, player).setCallback(Callback()).build()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            scope.future {
                retries = 0
                mediaItems
                    .map { item ->
                        val uri = item.requestMetadata.mediaUri?.toString() ?: item.mediaId
                        item.buildUpon().setUri(StreamResolver.resolve(uri)).build()
                    }
                    .toMutableList()
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        NowPlaying.isPlaying = false
        super.onDestroy()
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
    }
}

/** Shared playback state for the visualizer, which runs in the same process. */
object NowPlaying {
    @Volatile var isPlaying = false

    /** What the station says is playing right now, if anything. */
    @Volatile var title: String? = null
}
