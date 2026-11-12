/*
 * Copyright (c) 2026 Auxio Project
 * PlayCounter.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.stats

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * Watches playback and counts a play once a song has actually been listened to for half of its
 * length or four minutes, whichever comes first. Skipping or seeking past a song doesn't count, and
 * listening to it again (including on repeat) counts again.
 */
class PlayCounter
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playCountRepository: PlayCountRepository,
) : PlaybackStateManager.Listener {
    private var scope: CoroutineScope? = null
    private var tickJob: Job? = null

    private var currentUid: Music.UID? = null
    private var listenedMs = 0L
    private var lastPositionMs = 0L
    private var counted = false

    fun attach() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        playbackManager.addListener(this)
        startNewListen(playbackManager.currentSong)
        updateTicking(playbackManager.progression.isPlaying)
    }

    fun release() {
        playbackManager.removeListener(this)
        scope?.cancel()
        scope = null
        tickJob = null
    }

    override fun onIndexMoved(index: Int) {
        startNewListen(playbackManager.currentSong)
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        if (change.type == QueueChange.Type.SONG) {
            startNewListen(playbackManager.currentSong)
        }
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        startNewListen(playbackManager.currentSong)
    }

    override fun onProgressionChanged(progression: Progression) {
        // Catch up on the time listened before pausing, or note where playback resumed from.
        tick()
        updateTicking(progression.isPlaying)
    }

    override fun onSessionEnded() {
        startNewListen(null)
        updateTicking(false)
    }

    private fun updateTicking(isPlaying: Boolean) {
        if (isPlaying) {
            if (tickJob?.isActive == true) return
            tickJob =
                scope?.launch {
                    while (isActive) {
                        delay(nextTickMs())
                        tick()
                    }
                }
        } else {
            tickJob?.cancel()
            tickJob = null
        }
    }

    /**
     * How long to wait before looking at playback again. Usually [TICK_MS], but sooner when the
     * song is about to have been listened to for long enough, so short songs still count on time.
     */
    private fun nextTickMs(): Long {
        val song = playbackManager.currentSong
        if (counted || song == null || song.uid != currentUid) return TICK_MS
        return (thresholdFor(song) - listenedMs).coerceIn(MIN_TICK_MS, TICK_MS)
    }

    private fun thresholdFor(song: Song) =
        (song.durationMs / 2).coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)

    private fun startNewListen(song: Song?) {
        currentUid = song?.uid
        listenedMs = 0
        counted = false
        lastPositionMs = playbackManager.progression.calculateElapsedPositionMs()
    }

    private fun tick() {
        val song = playbackManager.currentSong
        if (song == null) {
            currentUid = null
            return
        }
        if (song.uid != currentUid) {
            startNewListen(song)
            return
        }

        val position = playbackManager.progression.calculateElapsedPositionMs()
        val delta = position - lastPositionMs
        lastPositionMs = position
        if (delta < 0 && position < RESTART_WINDOW_MS) {
            // Back at the start of the song, by repeating it or seeking back: a new listen.
            listenedMs = 0
            counted = false
            return
        }
        // Only count time that actually played, not jumps from seeking forward.
        if (delta in 1..MAX_TICK_ADVANCE_MS) {
            listenedMs += delta
        }

        if (!counted && listenedMs >= thresholdFor(song)) {
            counted = true
            playCountRepository.recordPlay(song.uid)
        }
    }

    private companion object {
        const val TICK_MS = 5000L
        const val MIN_TICK_MS = 250L
        // Both leave room for a late tick.
        const val MAX_TICK_ADVANCE_MS = 8000L
        const val RESTART_WINDOW_MS = 10_000L
        const val MIN_THRESHOLD_MS = 1000L
        const val MAX_THRESHOLD_MS = 4 * 60 * 1000L
    }
}
