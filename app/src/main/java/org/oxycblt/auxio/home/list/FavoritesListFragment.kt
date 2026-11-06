/*
 * Copyright (c) 2026 Auxio Project
 * FavoritesListFragment.kt is part of Auxio.
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
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.recycler.SongViewHolder
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/** A [ListFragment] that shows the favorite [Song]s, newest first. */
@AndroidEntryPoint
class FavoritesListFragment : ListFragment<Song, FragmentHomeListBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val songAdapter = FavoriteAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_favorites_recycler
            adapter = songAdapter
        }
        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_heart_filled_24)
            contentDescription = getString(R.string.lbl_favorites)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_favorites)
        binding.homeNoMusicAction.isVisible = false

        collectImmediately(homeModel.favoritesList, ::updateSongs)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeRecycler.adapter = null
    }

    override fun onRealClick(item: Song) {
        // Play through the favorites from the chosen song.
        playbackModel.playFromList(item, homeModel.favoritesList.value)
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, homeModel.playWith)
    }

    private fun updateSongs(songs: List<Song>) {
        val binding = requireBinding()
        songAdapter.update(songs, homeModel.favoritesInstructions.consume())
        binding.homeRecycler.isInvisible = songs.isEmpty()
        binding.homeNoMusic.isInvisible = songs.isNotEmpty()
        updateSelection(listModel.selected.value)
        updatePlayback(
            playbackModel.song.value,
            playbackModel.parent.value,
            playbackModel.isPlaying.value,
        )
    }

    private fun updateSelection(selection: List<Music>) {
        songAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        songAdapter.setPlaying(song.takeIf { parent == null }, isPlaying)
    }

    private class FavoriteAdapter(private val listener: SelectableListListener<Song>) :
        SelectionIndicatorAdapter<Song, SongViewHolder>(SongViewHolder.DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SongViewHolder.from(parent)

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
