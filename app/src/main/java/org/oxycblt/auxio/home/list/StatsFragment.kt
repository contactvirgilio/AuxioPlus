/*
 * Copyright (c) 2026 Auxio Project
 * StatsFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.home.list

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeStatsBinding
import org.oxycblt.auxio.databinding.ItemStatsAlbumBinding
import org.oxycblt.auxio.databinding.ItemStatsArtistBinding
import org.oxycblt.auxio.databinding.ItemTopSongBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.stats.PlayCountRepository
import org.oxycblt.auxio.playback.stats.showTrophy
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/** The Activity tab: how much was listened to, and the most played artists and albums. */
@AndroidEntryPoint
class StatsFragment :
    ViewBindingFragment<FragmentHomeStatsBinding>(), PlayCountRepository.Listener {
    @Inject lateinit var playCountRepository: PlayCountRepository
    @Inject lateinit var musicRepository: MusicRepository
    private val homeModel: HomeViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
    }
    private var trackLimit = 5
    private var lastStats: Stats? = null
    // What the lists are showing right now, so a refresh only touches what changed.
    private var shownLibrary: Any? = null
    private var shownTracks: List<Pair<Song, Int>>? = null
    private var shownArtists: List<Pair<Artist, Int>>? = null
    private var shownAlbums: List<Pair<Album, Int>>? = null
    private var range = Range.MONTH
    private var lastRefreshAt = 0L

    private enum class Range {
        WEEK,
        MONTH,
        YEAR,
        ALL,
    }

    private class Stats(
        val totalMs: Long,
        val plays: Int,
        val songs: Int,
        val bars: FloatArray,
        val labels: List<String>,
        val tracks: List<Pair<Song, Int>>,
        val artists: List<Pair<Artist, Int>>,
        val albums: List<Pair<Album, Int>>,
    )

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeStatsBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeStatsBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.statsScroll.id = R.id.home_stats_scroll
        binding.statsRanges.check(chipFor(range))
        binding.statsRanges.setOnCheckedStateChangeListener { _, checked ->
            val id = checked.firstOrNull() ?: return@setOnCheckedStateChangeListener
            range =
                when (id) {
                    R.id.stats_range_week -> Range.WEEK
                    R.id.stats_range_year -> Range.YEAR
                    R.id.stats_range_all -> Range.ALL
                    else -> Range.MONTH
                }
            refresh()
        }
        trackLimit = preferences.getInt(KEY_TRACK_LIMIT, 5).takeIf { it in TRACK_LIMITS } ?: 5
        binding.statsTracksCount.check(
            when (trackLimit) {
                25 -> R.id.stats_tracks_25
                50 -> R.id.stats_tracks_50
                else -> R.id.stats_tracks_5
            }
        )
        binding.statsTracksCount.setOnCheckedStateChangeListener { _, checked ->
            trackLimit =
                when (checked.firstOrNull()) {
                    R.id.stats_tracks_25 -> 25
                    R.id.stats_tracks_50 -> 50
                    else -> 5
                }
            preferences.edit().putInt(KEY_TRACK_LIMIT, trackLimit).apply()
            lastStats?.let { showTracks(it) }
        }
        playCountRepository.addListener(this)
        refresh()
    }

    override fun onDestroyBinding(binding: FragmentHomeStatsBinding) {
        super.onDestroyBinding(binding)
        playCountRepository.removeListener(this)
        forgetShown()
    }

    override fun onResume() {
        super.onResume()
        // The library may have changed while another screen was on top.
        // Opening the tab already refreshed a moment ago, don't do the same work twice.
        if (binding != null && SystemClock.elapsedRealtime() - lastRefreshAt > 1000) refresh()
    }

    override fun onPlayCountsChanged() {
        if (binding != null && isResumed) refresh()
    }

    private fun forgetShown() {
        shownTracks = null
        shownArtists = null
        shownAlbums = null
    }

    private fun chipFor(range: Range) =
        when (range) {
            Range.WEEK -> R.id.stats_range_week
            Range.MONTH -> R.id.stats_range_month
            Range.YEAR -> R.id.stats_range_year
            Range.ALL -> R.id.stats_range_all
        }

    private fun refresh() {
        val chosen = range
        lastRefreshAt = SystemClock.elapsedRealtime()
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.Default) { compute(chosen) }
            if (binding != null && chosen == range) show(stats)
        }
    }

    private fun compute(range: Range): Stats {
        val library = musicRepository.library
        val bucketsAreMonths = range == Range.YEAR || range == Range.ALL
        // Where the range starts: this Monday, the 1st of this month, 1 January, or for all time
        // a year back (only used for the bars).
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val bucketCount =
            when (range) {
                Range.WEEK -> 7
                Range.MONTH -> calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                else -> 12
            }
        when (range) {
            Range.WEEK -> {
                // Weeks start on Monday.
                val sinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
                calendar.add(Calendar.DAY_OF_YEAR, -sinceMonday)
            }
            Range.MONTH -> calendar.set(Calendar.DAY_OF_MONTH, 1)
            Range.YEAR -> {
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
            }
            Range.ALL -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.add(Calendar.MONTH, -(bucketCount - 1))
            }
        }
        val startMs = calendar.timeInMillis
        val startMonths = calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)

        val labelFormat =
            SimpleDateFormat(if (bucketsAreMonths) "MMMMM" else "EEEEE", Locale.getDefault())
        val labels =
            List(bucketCount) { i ->
                if (range == Range.MONTH) {
                    ""
                } else {
                    val bucket = Calendar.getInstance().apply { timeInMillis = startMs }
                    if (bucketsAreMonths) bucket.add(Calendar.MONTH, i)
                    else bucket.add(Calendar.DAY_OF_YEAR, i)
                    labelFormat.format(Date(bucket.timeInMillis))
                }
            }
        if (library == null) {
            return Stats(0, 0, 0, FloatArray(bucketCount), labels, listOf(), listOf(), listOf())
        }

        // How many times each song was played in the range, and the listening time per bar.
        val playsBySong = mutableMapOf<Song, Int>()
        val lastPlayed = mutableMapOf<Song, Long>()
        val bars = FloatArray(bucketCount)
        val songCache = mutableMapOf<String, Song?>()
        val at = Calendar.getInstance()
        for ((uidString, playedAt) in playCountRepository.playsSince(startMs)) {
            val song =
                songCache.getOrPut(uidString) {
                    Music.UID.fromString(uidString)?.let { library.findSong(it) }
                } ?: continue
            if (range != Range.ALL) {
                playsBySong[song] = (playsBySong[song] ?: 0) + 1
                if (playedAt > (lastPlayed[song] ?: 0L)) lastPlayed[song] = playedAt
            }
            at.timeInMillis = playedAt
            val bucket =
                if (bucketsAreMonths) {
                    at.get(Calendar.YEAR) * 12 + at.get(Calendar.MONTH) - startMonths
                } else {
                    // Count calendar days, so a clock change doesn't push a play into the next bar.
                    val day = (at.clone() as Calendar)
                    day.set(Calendar.HOUR_OF_DAY, 0)
                    day.set(Calendar.MINUTE, 0)
                    day.set(Calendar.SECOND, 0)
                    day.set(Calendar.MILLISECOND, 0)
                    Math.round((day.timeInMillis - startMs) / DAY_MS.toDouble()).toInt()
                }
            if (bucket in 0 until bucketCount) bars[bucket] += song.durationMs.toFloat()
        }
        if (range == Range.ALL) {
            // All time also includes plays counted before the play log existed.
            for ((uidString, entry) in playCountRepository.allCounts()) {
                val song = Music.UID.fromString(uidString)?.let { library.findSong(it) } ?: continue
                playsBySong[song] = entry.count
                lastPlayed[song] = entry.lastPlayedMs
            }
        }

        val tracks =
            playsBySong.entries
                .sortedWith(
                    compareByDescending<Map.Entry<Song, Int>> { it.value }
                        .thenByDescending { lastPlayed[it.key] ?: 0L }
                )
                .take(TRACK_LIMITS.max())
                .map { it.key to it.value }
        val artists =
            playsBySong.entries
                .groupBy({ it.key.artists.firstOrNull() }, { it.value })
                .mapNotNull { (artist, plays) -> artist?.let { it to plays.sum() } }
                .sortedByDescending { it.second }
                .take(TOP_ARTISTS)
        val albums =
            playsBySong.entries
                .groupBy({ it.key.album }, { it.value })
                .map { (album, plays) -> album to plays.sum() }
                .sortedByDescending { it.second }
                .take(TOP_ALBUMS)
        return Stats(
            totalMs = playsBySong.entries.sumOf { it.key.durationMs * it.value.toLong() },
            plays = playsBySong.values.sum(),
            songs = playsBySong.size,
            bars = bars,
            labels = labels,
            tracks = tracks,
            artists = artists,
            albums = albums,
        )
    }

    private fun show(stats: Stats) {
        val binding = requireBinding()
        val context = requireContext()
        val minutes = stats.totalMs / 60_000
        binding.statsTime.text =
            if (minutes >= 60) getString(R.string.fmt_stats_time, minutes / 60, minutes % 60)
            else getString(R.string.fmt_stats_minutes, minutes)
        binding.statsCounts.text =
            getString(
                R.string.fmt_stats_counts,
                context.getPlural(R.plurals.fmt_play_count, stats.plays),
                context.getPlural(R.plurals.fmt_different_songs, stats.songs),
            )
        binding.statsChart.setData(stats.bars, stats.labels)
        val hasPlays = stats.plays > 0
        binding.statsEmpty.isVisible = !hasPlays
        lastStats = stats
        listOf(
                binding.statsTracksHeader,
                binding.statsTracksCount,
                binding.statsTracks,
                binding.statsArtistsHeader,
                binding.statsArtists,
                binding.statsAlbumsHeader,
                binding.statsAlbums,
            )
            .forEach { it.isVisible = hasPlays }

        // After the library reloads everything is rebuilt, since names and covers may differ.
        val library = musicRepository.library
        if (library !== shownLibrary) {
            shownLibrary = library
            forgetShown()
        }
        showTracks(stats)
        val inflater = LayoutInflater.from(context)
        if (stats.artists != shownArtists) {
            shownArtists = stats.artists
            binding.statsArtists.removeAllViews()
            val top = stats.artists.firstOrNull()?.second ?: 1
            stats.artists.forEachIndexed { i, (artist, plays) ->
                val row = ItemStatsArtistBinding.inflate(inflater, binding.statsArtists, false)
                row.statsRank.text = (i + 1).toString()
                row.statsName.text = artist.name.resolve(context)
                row.statsPlays.text = context.getPlural(R.plurals.fmt_play_count, plays)
                row.statsShare.setProgressCompat(plays * 100 / top, false)
                binding.statsArtists.addView(row.root)
            }
        }
        if (stats.albums != shownAlbums) {
            shownAlbums = stats.albums
            binding.statsAlbums.removeAllViews()
            stats.albums.forEach { (album, plays) ->
                val row = ItemStatsAlbumBinding.inflate(inflater, binding.statsAlbums, false)
                row.statsCover.bind(album)
                row.statsName.text = album.name.resolve(context)
                row.statsInfo.text =
                    getString(
                        R.string.fmt_top_song_info,
                        album.artists.resolveNames(context),
                        context.getPlural(R.plurals.fmt_play_count, plays),
                    )
                binding.statsAlbums.addView(row.root)
            }
        }
        L.d("Showed ${stats.plays} plays for $range")
    }

    /** Fill the Top tracks list with as many songs as the chips ask for. */
    private fun showTracks(stats: Stats) {
        val binding = binding ?: return
        val context = requireContext()
        val inflater = LayoutInflater.from(context)
        val shown = stats.tracks.take(trackLimit)
        val songs = shown.map { it.first }
        val before = shownTracks
        if (shown == before) return
        shownTracks = shown
        fun info(song: Song, plays: Int) =
            context.getString(
                R.string.fmt_top_song_info,
                song.artists.first().name.resolve(context),
                context.getPlural(R.plurals.fmt_play_count, plays),
            )
        // The same songs in the same order: only the play counts need touching.
        if (
            before != null &&
                binding.statsTracks.childCount == shown.size &&
                before.map { it.first } == songs
        ) {
            shown.forEachIndexed { i, (song, plays) ->
                if (before[i].second != plays) {
                    ItemTopSongBinding.bind(binding.statsTracks.getChildAt(i)).topInfo.text =
                        info(song, plays)
                }
            }
            return
        }
        binding.statsTracks.removeAllViews()
        shown.forEachIndexed { i, (song, plays) ->
            val row = ItemTopSongBinding.inflate(inflater, binding.statsTracks, false)
            row.topCover.bind(song)
            row.topName.text = song.name.resolve(context)
            row.topName.showTrophy(song)
            row.topInfo.text = info(song, plays)
            row.topRank.text = (i + 1).toString()
            row.topRank.setTextColor(
                context.getAttrColorCompat(
                    if (i < 3) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            row.root.setOnClickListener { playbackModel.playFromList(song, songs) }
            row.topMenu.setOnClickListener {
                listModel.openMenu(R.menu.song, song, homeModel.playWith)
            }
            binding.statsTracks.addView(row.root)
        }
    }

    private companion object {
        const val KEY_TRACK_LIMIT = "auxio_stats_track_limit"
        val TRACK_LIMITS = listOf(5, 25, 50)
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TOP_ARTISTS = 5
        const val TOP_ALBUMS = 3
    }
}
