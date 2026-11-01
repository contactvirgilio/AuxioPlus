/*
 * Copyright (c) 2026 Auxio Project
 * TopSongListFragment.kt is part of Auxio.
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
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.R as MR
import dagger.hilt.android.AndroidEntryPoint
import java.util.Date
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeTopBinding
import org.oxycblt.auxio.databinding.ItemTopSongBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.home.TopSong
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/** A [ListFragment] that shows the most played [Song]s, most played first. */
@AndroidEntryPoint
class TopSongListFragment : ListFragment<Song, FragmentHomeTopBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val topAdapter = TopSongAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeTopBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeTopBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.topRecycler.apply {
            id = R.id.home_top_recycler
            adapter = topAdapter
        }

        collectImmediately(homeModel.topList, ::updateTop)
        collectImmediately(homeModel.topSinceMs, ::updateSince)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeTopBinding) {
        super.onDestroyBinding(binding)
        binding.topRecycler.adapter = null
    }

    override fun onRealClick(item: Song) {
        // Play through the Top 50 from the chosen song.
        playbackModel.playFromList(item, homeModel.topList.value.map { it.song })
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, homeModel.playWith)
    }

    private fun updateTop(top: List<TopSong>) {
        val binding = requireBinding()
        topAdapter.update(top, homeModel.topInstructions.consume())
        binding.topEmpty.isInvisible = top.isNotEmpty()
        binding.topRecycler.isInvisible = top.isEmpty()
        binding.topSince.isInvisible = top.isEmpty()
        // The selection and playing indicators are tied to list items, so refresh them.
        updateSelection(listModel.selected.value)
        updatePlayback(
            playbackModel.song.value,
            playbackModel.parent.value,
            playbackModel.isPlaying.value,
        )
    }

    private fun updateSince(sinceMs: Long) {
        if (sinceMs <= 0) return
        val date = DateFormat.getMediumDateFormat(requireContext()).format(Date(sinceMs))
        requireBinding().topSince.text = getString(R.string.fmt_top_since, date)
    }

    private fun updateSelection(selection: List<Music>) {
        val selected = selection.filterIsInstanceTo(mutableSetOf<Song>())
        val selectedTop = homeModel.topList.value.filterTo(mutableSetOf()) { it.song in selected }
        topAdapter.setSelected(selectedTop)
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        val playing = homeModel.topList.value.find { it.song == song }
        topAdapter.setPlaying(playing.takeIf { parent == null }, isPlaying)
    }

    /**
     * A [SelectionIndicatorAdapter] that shows [TopSong]s using [TopSongViewHolder].
     *
     * @param listener An [SelectableListListener] to bind interactions to.
     */
    private class TopSongAdapter(private val listener: SelectableListListener<Song>) :
        SelectionIndicatorAdapter<TopSong, TopSongViewHolder>(TopSongViewHolder.DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TopSongViewHolder.from(parent)

        override fun onBindViewHolder(holder: TopSongViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }

    /** A [SelectionIndicatorAdapter.ViewHolder] that shows a [TopSong] with its rank and plays. */
    private class TopSongViewHolder(private val binding: ItemTopSongBinding) :
        SelectionIndicatorAdapter.ViewHolder(binding.root) {
        fun bind(top: TopSong, listener: SelectableListListener<Song>) {
            listener.bind(top.song, this, menuButton = binding.topMenu)
            binding.topCover.bind(top.song)
            binding.topName.text = top.song.name.resolve(binding.context)
            binding.topInfo.text =
                binding.context.getString(
                    R.string.fmt_top_song_info,
                    top.song.artists.resolveNames(binding.context),
                    binding.context.getPlural(R.plurals.fmt_play_count, top.plays),
                )
            binding.topRank.text = top.rank.toString()
            // Highlight the top three.
            binding.topRank.setTextColor(
                binding.context.getAttrColorCompat(
                    if (top.rank <= 3) androidx.appcompat.R.attr.colorPrimary
                    else MR.attr.colorOnSurfaceVariant
                )
            )
        }

        override fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean) {
            binding.root.isSelected = isActive
            binding.topCover.setPlaying(isPlaying)
        }

        override fun updateSelectionIndicator(isSelected: Boolean) {
            binding.root.isActivated = isSelected
        }

        companion object {
            fun from(parent: View) =
                TopSongViewHolder(ItemTopSongBinding.inflate(parent.context.inflater))

            val DIFF_CALLBACK =
                object : DiffUtil.ItemCallback<TopSong>() {
                    override fun areItemsTheSame(oldItem: TopSong, newItem: TopSong) =
                        oldItem.song.uid == newItem.song.uid

                    override fun areContentsTheSame(oldItem: TopSong, newItem: TopSong) =
                        oldItem.rank == newItem.rank &&
                            oldItem.plays == newItem.plays &&
                            oldItem.song.name == newItem.song.name
                }
        }
    }
}
