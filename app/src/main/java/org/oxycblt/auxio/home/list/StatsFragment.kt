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
import androidx.lifecycle.lifecycleScope
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
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.stats.PlayCountRepository
import org.oxycblt.auxio.ui.ViewBindingFragment
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
        playCountRepository.addListener(this)
        refresh()
    }

    override fun onDestroyBinding(binding: FragmentHomeStatsBinding) {
        super.onDestroyBinding(binding)
        playCountRepository.removeListener(this)
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
        val bucketCount =
            when (range) {
                Range.WEEK -> 7
                Range.MONTH -> 30
                else -> 12
            }
        // Where the range starts: midnight (or the 1st) of the first bar.
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (bucketsAreMonths) {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.add(Calendar.MONTH, -(bucketCount - 1))
        } else {
            calendar.add(Calendar.DAY_OF_YEAR, -(bucketCount - 1))
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
            return Stats(0, 0, 0, FloatArray(bucketCount), labels, listOf(), listOf())
        }

        // How many times each song was played in the range, and the listening time per bar.
        val playsBySong = mutableMapOf<Song, Int>()
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
            }
            at.timeInMillis = playedAt
            val bucket =
                if (bucketsAreMonths) {
                    at.get(Calendar.YEAR) * 12 + at.get(Calendar.MONTH) - startMonths
                } else {
                    ((playedAt - startMs) / DAY_MS).toInt()
                }
            if (bucket in 0 until bucketCount) bars[bucket] += song.durationMs.toFloat()
        }
        if (range == Range.ALL) {
            // All time also includes plays counted before the play log existed.
            for ((uidString, entry) in playCountRepository.allCounts()) {
                val song = Music.UID.fromString(uidString)?.let { library.findSong(it) } ?: continue
                playsBySong[song] = entry.count
            }
        }

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
        listOf(
                binding.statsArtistsHeader,
                binding.statsArtists,
                binding.statsAlbumsHeader,
                binding.statsAlbums,
            )
            .forEach { it.isVisible = hasPlays }

        val inflater = LayoutInflater.from(context)
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
        L.d("Showed ${stats.plays} plays for $range")
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TOP_ARTISTS = 5
        const val TOP_ALBUMS = 3
    }
}
