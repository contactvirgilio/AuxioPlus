/*
 * Copyright (c) 2024 Auxio Project
 * HomeGenerator.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import javax.inject.Inject
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.playback.stats.PlayCountRepository
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

interface HomeGenerator {
    fun attach()

    fun release()

    fun empty(): Boolean

    fun songs(): List<Song>

    fun albums(): List<Album>

    fun artists(): List<Artist>

    fun genres(): List<Genre>

    fun playlists(): List<Playlist>

    /** The most played songs that are in the library, most played first. */
    fun top(): List<TopSong>

    /** When play counting started, as unix time in milliseconds. */
    fun topSinceMs(): Long

    fun tabs(): List<MusicType>

    interface Invalidator {
        fun invalidateEmpty() {}

        fun invalidateMusic(type: MusicType, instructions: UpdateInstructions)

        fun invalidateTabs()
    }

    interface Factory {
        fun create(invalidator: Invalidator): HomeGenerator
    }
}

class HomeGeneratorFactoryImpl
@Inject
constructor(
    private val homeSettings: HomeSettings,
    private val listSettings: ListSettings,
    private val musicRepository: MusicRepository,
    private val playCountRepository: PlayCountRepository,
) : HomeGenerator.Factory {
    override fun create(invalidator: HomeGenerator.Invalidator): HomeGenerator =
        HomeGeneratorImpl(
            invalidator,
            homeSettings,
            listSettings,
            musicRepository,
            playCountRepository,
        )
}

private class HomeGeneratorImpl(
    private val invalidator: HomeGenerator.Invalidator,
    private val homeSettings: HomeSettings,
    private val listSettings: ListSettings,
    private val musicRepository: MusicRepository,
    private val playCountRepository: PlayCountRepository,
) :
    HomeGenerator,
    HomeSettings.Listener,
    ListSettings.Listener,
    MusicRepository.UpdateListener,
    PlayCountRepository.Listener {
    override fun attach() {
        homeSettings.registerListener(this)
        listSettings.registerListener(this)
        musicRepository.addUpdateListener(this)
        playCountRepository.addListener(this)
    }

    override fun onPlayCountsChanged() {
        invalidator.invalidateMusic(MusicType.TOP, UpdateInstructions.Diff)
    }

    override fun onTabsChanged() {
        invalidator.invalidateTabs()
    }

    override fun onHideCollaboratorsChanged() {
        // Changes in the hide collaborator setting will change the artist contents
        // of the library, consider it a library update.
        L.d("Collaborator setting changed, forwarding update")
        invalidator.invalidateMusic(MusicType.ARTISTS, UpdateInstructions.Diff)
    }

    override fun onSongSortChanged() {
        super.onSongSortChanged()
        invalidator.invalidateMusic(MusicType.SONGS, UpdateInstructions.Replace(0))
    }

    override fun onAlbumSortChanged() {
        super.onAlbumSortChanged()
        invalidator.invalidateMusic(MusicType.ALBUMS, UpdateInstructions.Replace(0))
    }

    override fun onArtistSortChanged() {
        super.onArtistSortChanged()
        invalidator.invalidateMusic(MusicType.ARTISTS, UpdateInstructions.Replace(0))
    }

    override fun onGenreSortChanged() {
        super.onGenreSortChanged()
        invalidator.invalidateMusic(MusicType.GENRES, UpdateInstructions.Replace(0))
    }

    override fun onPlaylistSortChanged() {
        super.onPlaylistSortChanged()
        invalidator.invalidateMusic(MusicType.PLAYLISTS, UpdateInstructions.Replace(0))
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        invalidator.invalidateEmpty()

        val library = musicRepository.library
        if (changes.deviceLibrary && library != null) {
            L.d("Refreshing library")
            // Get the each list of items in the library to use as our list data.
            // Applying the preferred sorting to them.
            invalidator.invalidateMusic(MusicType.SONGS, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.ALBUMS, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.ARTISTS, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.GENRES, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.TOP, UpdateInstructions.Diff)
        }

        if (changes.userLibrary && library != null) {
            L.d("Refreshing playlists")
            invalidator.invalidateMusic(MusicType.PLAYLISTS, UpdateInstructions.Diff)
        }
    }

    override fun release() {
        playCountRepository.removeListener(this)
        musicRepository.removeUpdateListener(this)
        listSettings.unregisterListener(this)
        homeSettings.unregisterListener(this)
    }

    override fun empty() = musicRepository.library?.empty() ?: true

    override fun songs() =
        musicRepository.library?.let { listSettings.songSort.songs(it.songs) } ?: emptyList()

    override fun albums() =
        musicRepository.library?.let { listSettings.albumSort.albums(it.albums) } ?: emptyList()

    override fun artists() =
        musicRepository.library?.let { deviceLibrary ->
            val sorted = listSettings.artistSort.artists(deviceLibrary.artists)
            if (homeSettings.shouldHideCollaborators) {
                sorted.filter { it.explicitAlbums.isNotEmpty() }
            } else {
                sorted
            }
        } ?: emptyList()

    override fun genres() =
        musicRepository.library?.let { listSettings.genreSort.genres(it.genres) } ?: emptyList()

    override fun playlists() =
        musicRepository.library?.let { listSettings.playlistSort.playlists(it.playlists) }
            ?: emptyList()

    override fun top(): List<TopSong> {
        val library = musicRepository.library ?: return emptyList()
        // Songs that are no longer in the library are skipped, so ask for some spare entries.
        return playCountRepository
            .top(TOP_LIMIT * 2)
            .mapNotNull { (uid, entry) -> library.findSong(uid)?.let { it to entry.count } }
            .take(TOP_LIMIT)
            .mapIndexed { i, (song, plays) -> TopSong(song, i + 1, plays) }
    }

    override fun topSinceMs() = playCountRepository.sinceMs

    override fun tabs() = homeSettings.homeTabs.filterIsInstance<Tab.Visible>().map { it.type }
}

/**
 * A song in the Top 50 tab.
 *
 * @param song The song.
 * @param rank Its place in the list, starting at 1.
 * @param plays How many times it has been played.
 */
data class TopSong(val song: Song, val rank: Int, val plays: Int)

private const val TOP_LIMIT = 50
