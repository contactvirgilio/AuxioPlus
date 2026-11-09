/*
 * Copyright (c) 2026 Auxio Project
 * BackupRestorer.kt is part of Auxio.
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.playback.stats.PlayCountRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.playlist.ExternalPlaylistManager
import timber.log.Timber as L

/**
 * Reads backups made by [AutoBackup] and puts their parts back: playlists, favorites and
 * activity (play counts and the play log). A backup is one zip, but its parts also work as single
 * files.
 */
@Singleton
class BackupRestorer
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playCountRepository: PlayCountRepository,
) {
    private val externalPlaylistManager = ExternalPlaylistManager.from(context)

    /** Plays from a backup: song paths with when they were played, and total counts per path. */
    class Activity(
        val plays: List<Pair<String, Long>>,
        val counts: List<Triple<String, Int, Long>>,
    )

    /**
     * What was found in the picked files.
     *
     * @property playlistUris The files holding playlists: M3U files and zips with M3U files.
     * @property playlists How many playlists those hold, as far as known.
     * @property favorites Paths of the favorite songs, newest first.
     * @property activity The play history, if there was any.
     * @property choice Whether a backup zip was among the files, so the user can pick which parts
     *   to restore.
     */
    class Contents(
        val playlistUris: List<Uri>,
        val playlists: Int,
        val favorites: List<String>,
        val activity: Activity?,
        val palettes: List<String>,
        val choice: Boolean,
    ) {
        val isEmpty: Boolean
            get() =
                playlistUris.isEmpty() &&
                    favorites.isEmpty() &&
                    activity == null &&
                    palettes.isEmpty()
    }

    suspend fun read(uris: List<Uri>): Contents {
        val playlistUris = mutableListOf<Uri>()
        var playlists = 0
        val favorites = mutableListOf<String>()
        val plays = mutableListOf<Pair<String, Long>>()
        val counts = mutableListOf<Triple<String, Int, Long>>()
        var hasActivity = false
        val palettes = mutableListOf<String>()
        var choice = false
        for (uri in uris) {
            try {
                if (externalPlaylistManager.isZip(uri)) {
                    val parts = readZip(uri)
                    if (parts.playlists > 0) {
                        playlistUris += uri
                        playlists += parts.playlists
                    }
                    parts.favorites?.let {
                        favorites += parseFavorites(it)
                        choice = true
                    }
                    parts.activity?.let {
                        parseActivity(it, plays, counts)
                        hasActivity = true
                        choice = true
                    }
                    parts.palettes?.let {
                        palettes += parseFavorites(it)
                        choice = true
                    }
                } else {
                    val text = readText(uri) ?: continue
                    val header = text.lineSequence().firstOrNull().orEmpty()
                    when {
                        header.startsWith(FAVORITES_HEADER) -> favorites += parseFavorites(text)
                        header.startsWith(PALETTES_HEADER) -> palettes += parseFavorites(text)
                        header.startsWith(ACTIVITY_HEADER) -> {
                            parseActivity(text, plays, counts)
                            hasActivity = true
                        }
                        else -> {
                            playlistUris += uri
                            playlists++
                        }
                    }
                }
            } catch (e: Exception) {
                L.e("Could not read $uri: $e")
            }
        }
        return Contents(
            playlistUris,
            playlists,
            favorites,
            if (hasActivity) Activity(plays, counts) else null,
            palettes,
            choice,
        )
    }

    /** @return How many songs were newly added to the favorites. */
    fun restoreFavorites(paths: List<String>): Int {
        val songs = resolve(paths).filterNotNull()
        return favoritesRepository.addAll(songs.map { it.uid })
    }

    /** @return How many palettes were newly added to the collection. */
    fun restorePalettes(ids: List<String>) =
        org.oxycblt.auxio.playback.visualizer.PaletteCollection.get(context).merge(ids)

    /** @return How many plays were added to the play log. */
    fun restoreActivity(activity: Activity): Int {
        val uids = resolve(activity.plays.map { it.first } + activity.counts.map { it.first })
        // resolve() keeps the order of the paths it was given.
        val playSongs = uids.take(activity.plays.size)
        val countSongs = uids.drop(activity.plays.size)
        val plays =
            activity.plays.mapIndexedNotNull { i, (_, at) ->
                playSongs[i]?.let { it.uid.toString() to at }
            }
        val counts = mutableMapOf<String, PlayCountRepository.Entry>()
        activity.counts.forEachIndexed { i, (_, count, last) ->
            countSongs[i]?.let {
                counts[it.uid.toString()] = PlayCountRepository.Entry(count, last)
            }
        }
        return playCountRepository.restore(counts, plays)
    }

    /** Find the library song for each path, by the last three parts of the path. */
    private fun resolve(paths: List<String>): List<Song?> {
        val library = musicRepository.library ?: return paths.map { null }
        val index = library.songs.groupBy { tail(it.path) }.mapValues { (_, s) -> s.singleOrNull() }
        return paths.map { index[tailOf(it)] }
    }

    private fun tail(path: Path) =
        path.components.components.takeLast(3).joinToString("/").lowercase()

    private fun tailOf(path: String) =
        path
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .takeLast(3)
            .joinToString("/")
            .lowercase()

    private class ZipParts(
        val playlists: Int,
        val favorites: String?,
        val activity: String?,
        val palettes: String?,
    )

    private fun readZip(uri: Uri): ZipParts {
        var playlists = 0
        var favorites: String? = null
        var activity: String? = null
        var palettes: String? = null
        val input =
            context.contentResolver.openInputStream(uri) ?: return ZipParts(0, null, null, null)
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').trim('/').lowercase()
                if (!entry.isDirectory) {
                    when {
                        name == FAVORITES_ENTRY -> favorites = String(zip.readBytes())
                        name == ACTIVITY_ENTRY -> activity = String(zip.readBytes())
                        name == PALETTES_ENTRY -> palettes = String(zip.readBytes())
                        name.endsWith(".m3u") || name.endsWith(".m3u8") -> playlists++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ZipParts(playlists, favorites, activity, palettes)
    }

    private fun readText(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            // Only look at files that start with text. Large binary files are not backups.
            val bytes = input.readBytes()
            if (bytes.size > MAX_TEXT_BYTES) null else String(bytes)
        }

    private fun parseFavorites(text: String) =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    private fun parseActivity(
        text: String,
        plays: MutableList<Pair<String, Long>>,
        counts: MutableList<Triple<String, Int, Long>>,
    ) {
        for (line in text.lineSequence()) {
            when {
                line.startsWith("play,") -> {
                    val parts = line.split(",", limit = 3)
                    val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
                    plays += parts[2] to at
                }
                line.startsWith("count,") -> {
                    val parts = line.split(",", limit = 4)
                    val count = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val last = parts.getOrNull(2)?.toLongOrNull() ?: continue
                    counts += Triple(parts[3], count, last)
                }
            }
        }
    }

    companion object {
        const val FAVORITES_HEADER = "#auxio-favorites"
        const val ACTIVITY_HEADER = "#auxio-activity"
        const val PALETTES_HEADER = "#auxio-palettes"
        const val PALETTES_ENTRY = "auxio/palettes.txt"
        const val FAVORITES_ENTRY = "auxio/favorites.txt"
        const val ACTIVITY_ENTRY = "auxio/activity.csv"
        private const val MAX_TEXT_BYTES = 50 * 1024 * 1024
    }
}
