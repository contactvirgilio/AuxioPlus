/*
 * Copyright (c) 2021 Auxio Project
 * MusicViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.playlist.ExportConfig
import org.oxycblt.musikr.playlist.ExternalPlaylistManager
import timber.log.Timber as L

/**
 * A [ViewModel] providing data specific to the music loading process.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class MusicViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val listSettings: ListSettings,
    private val musicRepository: MusicRepository,
    private val autoBackup: AutoBackup,
) : ViewModel(), MusicRepository.UpdateListener, MusicRepository.IndexingListener {
    private val externalPlaylistManager = ExternalPlaylistManager.from(context)

    private val _indexingState = MutableStateFlow<IndexingState?>(null)

    /** The current music loading state, or null if no loading is going on. */
    val indexingState: StateFlow<IndexingState?> = _indexingState

    private val _statistics = MutableStateFlow<Statistics?>(null)

    /** [Statistics] about the last completed music load. */
    val statistics: StateFlow<Statistics?>
        get() = _statistics

    private val _playlistDecision = MutableEvent<PlaylistDecision>()

    /**
     * A [PlaylistDecision] command that is awaiting a view capable of responding to it. Null if
     * none currently.
     */
    val playlistDecision: Event<PlaylistDecision>
        get() = _playlistDecision

    private val _playlistMessage = MutableEvent<PlaylistMessage>()
    val playlistMessage: Event<PlaylistMessage>
        get() = _playlistMessage

    private val _playlistBackupState = MutableStateFlow<PlaylistBackupState?>(null)

    /**
     * The state of the current (or last failed) export/import of all playlists, or null if none is
     * running and the last one succeeded.
     */
    val playlistBackupState: StateFlow<PlaylistBackupState?>
        get() = _playlistBackupState

    init {
        musicRepository.addUpdateListener(this)
        musicRepository.addIndexingListener(this)
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
        musicRepository.removeIndexingListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (!changes.deviceLibrary) return
        val library = musicRepository.library ?: return
        _statistics.value =
            Statistics(
                library.songs.size,
                library.albums.size,
                library.artists.size,
                library.genres.size,
                library.songs.sumOf { it.durationMs },
                library.songs.sumOf { it.size },
            )
        L.d("Updated statistics: ${_statistics.value}")
        // Do the monthly/bi-monthly backup now that there's a library to back up, if it's due.
        viewModelScope.launch(Dispatchers.IO) { autoBackup.runIfDue() }
    }

    override fun onIndexingStateChanged() {
        _indexingState.value = musicRepository.indexingState
    }

    /** Requests that the music library should be re-loaded while leveraging the cache. */
    fun refresh() {
        L.d("Refreshing library")
        musicRepository.requestIndex(true)
    }

    /** Requests that the music library be re-loaded without the cache. */
    fun rescan() {
        L.d("Rescanning library")
        musicRepository.requestIndex(false)
    }

    /**
     * Create a new generic [Playlist].
     *
     * @param name The name of the new [Playlist]. If null, the user will be prompted for one.
     * @param songs The [Song]s to be contained in the new playlist.
     * @param reason The reason why a new playlist is being created. For all intensive purposes, you
     *   do not need to specify this.
     */
    fun createPlaylist(
        name: String? = null,
        songs: List<Song> = listOf(),
        reason: PlaylistDecision.New.Reason = PlaylistDecision.New.Reason.NEW,
    ) {
        if (name != null) {
            L.d("Creating $name with ${songs.size} songs]")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.createPlaylist(name, songs)
                val message =
                    when (reason) {
                        PlaylistDecision.New.Reason.NEW -> PlaylistMessage.NewPlaylistSuccess
                        PlaylistDecision.New.Reason.ADD -> PlaylistMessage.AddSuccess
                        PlaylistDecision.New.Reason.IMPORT -> PlaylistMessage.ImportSuccess
                    }
                _playlistMessage.put(message)
            }
        } else {
            L.d("Launching creation dialog for ${songs.size} songs")
            _playlistDecision.put(PlaylistDecision.New(songs, null, reason))
        }
    }

    /**
     * Import a playlist from a file [Uri]. Errors pushed to [playlistMessage].
     *
     * @param uri The [Uri] of the file to import. If null, the user will be prompted with a file
     *   picker.
     * @param target The [Playlist] to import to. If null, a new playlist will be created. Note the
     *   [Playlist] will not be renamed to the name of the imported playlist.
     * @see ExternalPlaylistManager
     */
    fun importPlaylist(uri: Uri? = null, target: Playlist? = null) {
        if (uri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val importedPlaylist = externalPlaylistManager.import(uri)
                if (importedPlaylist == null) {
                    L.e("Could not import playlist")
                    _playlistMessage.put(PlaylistMessage.ImportFailed)
                    return@launch
                }

                val library = musicRepository.library ?: return@launch
                val songs =
                    importedPlaylist.paths.mapNotNull {
                        it.firstNotNullOfOrNull(library::findSongByPath)
                    }

                if (songs.isEmpty()) {
                    L.e("No songs found")
                    _playlistMessage.put(PlaylistMessage.ImportFailed)
                    return@launch
                }

                if (target !== null) {
                    if (importedPlaylist.name != null && importedPlaylist.name != target.name.raw) {
                        _playlistDecision.put(
                            PlaylistDecision.Rename(
                                target,
                                importedPlaylist.name,
                                songs,
                                PlaylistDecision.Rename.Reason.IMPORT,
                            )
                        )
                    } else {
                        musicRepository.rewritePlaylist(target, songs)
                        _playlistMessage.put(PlaylistMessage.ImportSuccess)
                    }
                } else {
                    _playlistDecision.put(
                        PlaylistDecision.New(
                            songs,
                            importedPlaylist.name,
                            PlaylistDecision.New.Reason.IMPORT,
                        )
                    )
                }
            }
        } else {
            L.d("Launching import picker")
            _playlistDecision.put(PlaylistDecision.Import(target))
        }
    }

    /**
     * Export a [Playlist] to a file [Uri]. Errors pushed to [playlistMessage].
     *
     * @param playlist The [Playlist] to export.
     * @param uri The [Uri] to export to. If null, the user will be prompted for one.
     */
    fun exportPlaylist(playlist: Playlist, uri: Uri? = null, config: ExportConfig? = null) {
        if (uri != null && config != null) {
            L.d("Exporting playlist to $uri")
            viewModelScope.launch(Dispatchers.IO) {
                if (externalPlaylistManager.export(playlist, uri, config)) {
                    _playlistMessage.put(PlaylistMessage.ExportSuccess)
                } else {
                    _playlistMessage.put(PlaylistMessage.ExportFailed)
                }
            }
        } else {
            L.d("Launching export dialog")
            _playlistDecision.put(PlaylistDecision.Export(playlist))
        }
    }

    /**
     * Export every playlist into one zip file at [uri], using relative paths. Progress and failures
     * are pushed to [playlistBackupState]; nothing is shown when it finishes successfully.
     *
     * @param uri The [Uri] of the zip file to write.
     */
    fun exportAllPlaylists(uri: Uri) {
        if (_playlistBackupState.value is PlaylistBackupState.Running) return
        val operation = PlaylistBackupState.Operation.EXPORT
        _playlistBackupState.value = PlaylistBackupState.Running(operation, 0, 0)
        viewModelScope.launch(Dispatchers.IO) {
            val library = musicRepository.library
            if (library == null) {
                L.e("No library to export playlists from")
                _playlistBackupState.value = PlaylistBackupState.Failed(operation)
                return@launch
            }
            val playlists = library.playlists.sortedBy { it.name.raw.lowercase() }
            L.d("Exporting ${playlists.size} playlists to $uri")
            val config = ExportConfig(absolute = false, windowsPaths = false)
            val exported =
                externalPlaylistManager.exportAll(playlists, uri, config) { done, total ->
                    _playlistBackupState.value = PlaylistBackupState.Running(operation, done, total)
                }
            _playlistBackupState.value =
                if (exported) {
                    null
                } else {
                    L.e("Could not export playlists")
                    PlaylistBackupState.Failed(operation)
                }
        }
    }

    /**
     * Import playlists from several files at once. Each file can be a zip made by
     * [exportAllPlaylists] (or any zip of M3U files) or a single M3U file. A playlist whose name
     * already exists is overwritten instead of duplicated. Progress and failures are pushed to
     * [playlistBackupState]; nothing is shown when it finishes successfully.
     *
     * @param uris The [Uri]s of the files to read.
     * @param nameSingleFile Whether a single M3U file should be handed to [importPlaylist] instead,
     *   so the user can still name it.
     */
    fun importPlaylistFiles(uris: List<Uri>, nameSingleFile: Boolean = true) {
        if (uris.isEmpty() || _playlistBackupState.value is PlaylistBackupState.Running) return
        val operation = PlaylistBackupState.Operation.IMPORT
        viewModelScope.launch(Dispatchers.IO) {
            val zipUris = uris.filter { externalPlaylistManager.isZip(it) }
            if (nameSingleFile && uris.size == 1 && zipUris.isEmpty()) {
                L.d("Single playlist file picked, importing it normally")
                importPlaylist(uris.first())
                return@launch
            }

            // Reading the files has no meaningful progress, so start indeterminate.
            _playlistBackupState.value = PlaylistBackupState.Running(operation, 0, 0)
            val importedPlaylists =
                uris.flatMap { uri ->
                    if (uri in zipUris) {
                        externalPlaylistManager.importAll(uri) ?: listOf()
                    } else {
                        listOfNotNull(externalPlaylistManager.import(uri))
                    }
                }
            if (importedPlaylists.isEmpty() || musicRepository.library == null) {
                L.e("Could not import playlists from $uris")
                _playlistBackupState.value = PlaylistBackupState.Failed(operation)
                return@launch
            }

            val total = importedPlaylists.size
            val fallbackName = context.getString(R.string.lbl_imported_playlist)
            // Names created during this import, so duplicates inside the zip don't overwrite
            // each other.
            val createdNames = mutableSetOf<String>()
            var restored = 0
            _playlistBackupState.value = PlaylistBackupState.Running(operation, 0, total)
            importedPlaylists.forEachIndexed { index, imported ->
                // The library changes after every playlist write, so re-fetch it each time.
                val library = musicRepository.library
                if (library != null) {
                    // Exact paths first. If the zip was moved since it was exported, the relative
                    // paths point at the wrong place, so fall back to matching the end of the path
                    // (folder and file name) against the library.
                    var tails: Map<String, Song?>? = null
                    val songs =
                        imported.paths.mapNotNull { possible ->
                            possible.firstNotNullOfOrNull(library::findSongByPath)
                                ?: possible.firstNotNullOfOrNull { path ->
                                    val index =
                                        tails
                                            ?: library.songs
                                                .groupBy { pathTail(it.path) }
                                                .mapValues { (_, same) -> same.singleOrNull() }
                                                .also { tails = it }
                                    index[pathTail(path)]
                                }
                        }
                    if (songs.isNotEmpty()) {
                        val name = imported.name ?: fallbackName
                        val existing =
                            if (name in createdNames) null
                            else library.playlists.find { it.name.raw == name }
                        if (existing != null) {
                            musicRepository.rewritePlaylist(existing, songs)
                        } else {
                            musicRepository.createPlaylist(name, songs)
                        }
                        createdNames.add(name)
                        ++restored
                    } else {
                        L.w("No songs found for playlist ${imported.name}, skipping")
                    }
                }
                _playlistBackupState.value =
                    PlaylistBackupState.Running(operation, index + 1, total)
            }

            _playlistBackupState.value =
                if (restored > 0) {
                    L.d("Imported $restored of $total playlists")
                    null
                } else {
                    L.e("None of the playlists matched any songs")
                    PlaylistBackupState.Failed(operation)
                }
        }
    }

    /** The last three parts of a path (album folder, file, and the folder above), in lower case. */
    private fun pathTail(path: org.oxycblt.musikr.fs.Path) =
        path.components.components.takeLast(3).joinToString("/").lowercase()

    /** A file name for a new zip of all playlists, e.g "Auxio-playlists-2026-10-30_14-40.zip". */
    fun newPlaylistBackupName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        return "Auxio-playlists-$date.zip"
    }

    /** Back up the playlists and Top 50 to the backup folder now, then call [onDone] on main. */
    fun backUpNow(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { autoBackup.run() }
            onDone()
        }
    }

    /** Hide the error shown after a failed export/import of all playlists. */
    fun dismissPlaylistBackupError() {
        if (_playlistBackupState.value is PlaylistBackupState.Failed) {
            _playlistBackupState.value = null
        }
    }

    /**
     * Rename the given playlist.
     *
     * @param playlist The [Playlist] to rename,
     * @param name The new name of the [Playlist]. If null, the user will be prompted for a name.
     * @param applySongs The songs to apply to the playlist after renaming. If empty, no songs will
     *   be applied. This argument is internal and does not need to be specified in normal use.
     * @param reason The reason why the playlist is being renamed. This argument is internal and
     *   does not need to be specified in normal use.
     */
    fun renamePlaylist(
        playlist: Playlist,
        name: String? = null,
        applySongs: List<Song> = listOf(),
        reason: PlaylistDecision.Rename.Reason = PlaylistDecision.Rename.Reason.ACTION,
    ) {
        if (name != null) {
            L.d("Renaming $playlist to $name")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.renamePlaylist(playlist, name)
                if (applySongs.isNotEmpty()) {
                    musicRepository.rewritePlaylist(playlist, applySongs)
                }
                val message =
                    when (reason) {
                        PlaylistDecision.Rename.Reason.ACTION -> PlaylistMessage.RenameSuccess
                        PlaylistDecision.Rename.Reason.IMPORT -> PlaylistMessage.ImportSuccess
                    }
                _playlistMessage.put(message)
            }
        } else {
            L.d("Launching rename dialog for $playlist")
            _playlistDecision.put(PlaylistDecision.Rename(playlist, null, applySongs, reason))
        }
    }

    /**
     * Delete a [Playlist].
     *
     * @param playlist The playlist to delete.
     * @param rude Whether to immediately delete the playlist or prompt the user first. This should
     *   be false at almost all times. This argument is internal and does not need to be specified
     *   in normal use.
     */
    fun deletePlaylist(playlist: Playlist, rude: Boolean = false) {
        if (rude) {
            L.d("Deleting $playlist")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.deletePlaylist(playlist)
                _playlistMessage.put(PlaylistMessage.DeleteSuccess)
            }
        } else {
            L.d("Launching deletion dialog for $playlist")
            _playlistDecision.put(PlaylistDecision.Delete(playlist))
        }
    }

    /**
     * Add a [Song] to a [Playlist].
     *
     * @param song The [Song] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(song: Song, playlist: Playlist? = null) {
        L.d("Adding $song to playlist")
        addToPlaylist(listOf(song), playlist)
    }

    /**
     * Add an [Album] to a [Playlist].
     *
     * @param album The [Album] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(album: Album, playlist: Playlist? = null) {
        L.d("Adding $album to playlist")
        addToPlaylist(listSettings.albumSongSort.songs(album.songs), playlist)
    }

    /**
     * Add an [Artist] to a [Playlist].
     *
     * @param artist The [Artist] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(artist: Artist, playlist: Playlist? = null) {
        L.d("Adding $artist to playlist")
        addToPlaylist(listSettings.artistSongSort.songs(artist.songs), playlist)
    }

    /**
     * Add a [Genre] to a [Playlist].
     *
     * @param genre The [Genre] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(genre: Genre, playlist: Playlist? = null) {
        L.d("Adding $genre to playlist")
        addToPlaylist(listSettings.genreSongSort.songs(genre.songs), playlist)
    }

    /**
     * Add [Song]s to a [Playlist].
     *
     * @param songs The [Song]s to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(songs: List<Song>, playlist: Playlist? = null) {
        if (playlist != null) {
            L.d("Adding ${songs.size} songs to $playlist")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.addToPlaylist(songs, playlist)
                _playlistMessage.put(PlaylistMessage.AddSuccess)
            }
        } else {
            L.d("Launching addition dialog for songs=${songs.size}")
            _playlistDecision.put(PlaylistDecision.Add(songs))
        }
    }

    /**
     * Non-manipulated statistics bound the last successful music load.
     *
     * @param songs The amount of [Song]s that were loaded.
     * @param albums The amount of [Album]s that were created.
     * @param artists The amount of [Artist]s that were created.
     * @param genres The amount of [Genre]s that were created.
     * @param durationMs The total duration of all songs in the library, in milliseconds.
     */
    data class Statistics(
        val songs: Int,
        val albums: Int,
        val artists: Int,
        val genres: Int,
        val durationMs: Long,
        val totalSizeBytes: Long,
    )
}

/**
 * Navigation command for when a [Playlist] must have some operation performed on it by the user.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
sealed interface PlaylistDecision {
    /**
     * Navigate to a dialog that allows a user to pick a name for a new [Playlist].
     *
     * @param songs The [Song]s to contain in the new [Playlist].
     * @param template An existing playlist name that should be editable in the opened dialog. If
     *   null, a placeholder should be created and shown as a hint instead.
     * @param reason The reason why a new playlist is being created. For all intensive purposes, you
     *   do not need to specify this.
     */
    data class New(val songs: List<Song>, val template: String?, val reason: Reason) :
        PlaylistDecision {
        enum class Reason {
            NEW,
            ADD,
            IMPORT,
        }
    }

    /**
     * Navigate to a file picker to import a playlist from.
     *
     * @param target The [Playlist] to import to. If null, then the file imported will create a new
     *   playlist.
     */
    data class Import(val target: Playlist?) : PlaylistDecision

    /**
     * Navigate to a dialog that allows a user to rename an existing [Playlist].
     *
     * @param playlist The playlist to act on.
     */
    data class Rename(
        val playlist: Playlist,
        val template: String?,
        val applySongs: List<Song>,
        val reason: Reason,
    ) : PlaylistDecision {
        enum class Reason {
            ACTION,
            IMPORT,
        }
    }

    /**
     * Navigate to a dialog that allows the user to export a [Playlist].
     *
     * @param playlist The [Playlist] to export.
     */
    data class Export(val playlist: Playlist) : PlaylistDecision

    /**
     * Navigate to a dialog that confirms the deletion of an existing [Playlist].
     *
     * @param playlist The playlist to act on.
     */
    data class Delete(val playlist: Playlist) : PlaylistDecision

    /**
     * Navigate to a dialog that allows the user to add [Song]s to a [Playlist].
     *
     * @param songs The [Song]s to add to the chosen [Playlist].
     */
    data class Add(val songs: List<Song>) : PlaylistDecision
}

/**
 * The state of exporting or importing all playlists at once.
 *
 * @see MusicViewModel.exportAllPlaylists
 * @see MusicViewModel.importPlaylistFiles
 */
sealed interface PlaylistBackupState {
    val operation: Operation

    /**
     * The operation is running.
     *
     * @param done The number of playlists handled so far.
     * @param total The total number of playlists, or 0 if not known yet.
     */
    data class Running(override val operation: Operation, val done: Int, val total: Int) :
        PlaylistBackupState

    /** The last run of the operation failed. */
    data class Failed(override val operation: Operation) : PlaylistBackupState

    enum class Operation {
        EXPORT,
        IMPORT,
    }
}

sealed interface PlaylistMessage {
    val stringRes: Int

    data object NewPlaylistSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_created
    }

    data object ImportSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_imported
    }

    data object ImportFailed : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.err_import_failed
    }

    data object RenameSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_renamed
    }

    data object DeleteSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_deleted
    }

    data object AddSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_added
    }

    data object ExportSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_exported
    }

    data object ExportFailed : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.err_export_failed
    }
}
