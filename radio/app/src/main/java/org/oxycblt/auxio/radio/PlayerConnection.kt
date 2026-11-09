package org.oxycblt.auxio.radio

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.activity.ComponentActivity

/**
 * Connects a screen to [PlaybackService] while it is started. [onChanged] runs whenever anything
 * about playback changes, and once when connected.
 */
class PlayerConnection(
    private val activity: ComponentActivity,
    private val onChanged: (MediaController) -> Unit,
) : DefaultLifecycleObserver {
    private var future: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set

    private val listener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                controller?.let(onChanged)
            }
        }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val f = MediaController.Builder(activity, token).buildAsync()
        future = f
        f.addListener(
            {
                if (future !== f) return@addListener
                val c = runCatching { f.get() }.getOrNull() ?: return@addListener
                controller = c
                c.addListener(listener)
                onChanged(c)
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        controller?.removeListener(listener)
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
    }

    /** Play [stations], starting at [index]. Next and previous then move through the list. */
    fun play(stations: List<Station>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(stations.map { it.toMediaItem() }, index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun togglePlaying() {
        val c = controller ?: return
        if (c.isPlaying || c.playWhenReady) {
            c.pause()
        } else {
            // A live stream that was paused for a while is stale, so jump back to live.
            if (c.isCurrentMediaItemLive) c.seekToDefaultPosition()
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()

    fun previous() = controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()

    /** The station that is loaded, as far as the favourites or [fallback] know it. */
    fun currentStation(fallback: Station? = null): Station? {
        val item = controller?.currentMediaItem ?: return null
        Favorites.byId(item.mediaId)?.let { return it }
        if (fallback?.id == item.mediaId) return fallback
        val name = item.mediaMetadata.station ?: item.mediaMetadata.artist ?: return null
        return Station(
            id = item.mediaId,
            name = name.toString(),
            url = item.requestMetadata.mediaUri?.toString() ?: item.localConfiguration?.uri?.toString().orEmpty(),
            logo = item.mediaMetadata.artworkUri?.toString().orEmpty(),
        )
    }
}

/** A one-line status for the mini player and the full screen pill. */
fun MediaController.statusText(activity: ComponentActivity): String {
    val title = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
    return when {
        playerError != null -> activity.getString(R.string.lbl_error)
        playbackState == Player.STATE_BUFFERING && playWhenReady ->
            activity.getString(R.string.lbl_buffering)
        isPlaying -> title ?: activity.getString(R.string.lbl_playing)
        else -> activity.getString(R.string.lbl_stopped)
    }
}
