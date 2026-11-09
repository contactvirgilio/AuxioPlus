/*
 * Copyright (c) 2026 Auxio Project
 * AutoBackup.kt is part of Auxio.
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
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.stats.PlayCountRepository
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.playlist.ExportConfig
import org.oxycblt.musikr.playlist.ExternalPlaylistManager
import timber.log.Timber as L

/**
 * Saves one zip with all playlists, the favorites and the activity (play counts and history) into a folder the user chose, either on
 * request or every month or two. There is no background service: a due backup runs the next time
 * the app loads its library.
 */
@Singleton
class AutoBackup
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playCountRepository: PlayCountRepository,
    private val favoritesRepository: FavoritesRepository,
) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val externalPlaylistManager = ExternalPlaylistManager.from(context)
    private val running = AtomicBoolean(false)

    /** The folder backups are written to, or null if none was chosen yet. */
    private val folder: Uri?
        get() =
            preferences
                .getString(context.getString(R.string.set_key_backup_folder), null)
                ?.let(Uri::parse)

    /** A readable name of the chosen folder for the settings summary, or null if none. */
    val folderLabel: String?
        get() =
            folder?.let { DocumentsContract.getDocumentId(it).substringAfter(':', "") }
                ?.substringAfterLast('/')
                ?.ifEmpty { "Storage" }

    /** How many days should pass between backups, or 0 if automatic backups are off. */
    private val intervalDays: Int
        get() =
            when (preferences.getInt(context.getString(R.string.set_key_backup_interval), 0)) {
                1 -> 30
                2 -> 61
                else -> 0
            }

    /** When the last backup finished, in unix time milliseconds, or 0 if never. */
    val lastBackupMs: Long
        get() = preferences.getLong(KEY_LAST, 0L)

    /** Whether the last backup attempt failed. */
    val lastFailed: Boolean
        get() = preferences.getBoolean(KEY_FAILED, false)

    /**
     * Use [tree], picked by the user, as the place for backups. Files are written straight into it.
     *
     * @return True if the folder is ready to be written to, false otherwise.
     */
    fun chooseFolder(tree: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val root =
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            preferences.edit {
                putString(context.getString(R.string.set_key_backup_folder), root.toString())
            }
            true
        } catch (e: Exception) {
            L.e("Could not use $tree as the backup folder: $e")
            false
        }
    }

    /** Run a backup if automatic backups are on and the last one is old enough. */
    suspend fun runIfDue() {
        val days = intervalDays
        if (days == 0 || folder == null) return
        if (System.currentTimeMillis() - lastBackupMs < days * DAY_MS) return
        run()
    }

    /**
     * Back up now.
     *
     * @return True if everything was written, false otherwise.
     */
    suspend fun run(): Boolean {
        val target = folder ?: return false
        val library = musicRepository.library ?: return false
        if (!running.compareAndSet(false, true)) return false
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val ok =
                try {
                    val playlists = library.playlists.sortedBy { it.name.raw.lowercase() }
                    val zip = createFile(target, "application/zip", "Auxio-backup-$date.zip")
                    zip != null &&
                        externalPlaylistManager.exportAll(
                            playlists,
                            zip,
                            ExportConfig(absolute = false, windowsPaths = false),
                            extras = { out -> writeExtras(out, library) },
                        )
                } catch (e: Exception) {
                    L.e("Backup failed: $e")
                    false
                }
            preferences.edit {
                putBoolean(KEY_FAILED, !ok)
                if (ok) putLong(KEY_LAST, System.currentTimeMillis())
            }
            L.d("Backup finished, success = $ok")
            return ok
        } finally {
            running.set(false)
        }
    }

    /** Add the favorites and the activity to the backup zip. */
    private fun writeExtras(zip: ZipOutputStream, library: Library) {
        fun pathOf(song: org.oxycblt.musikr.Song) =
            "/" + song.path.components.components.joinToString("/")

        // Favorites, newest first, one song path per line with the title as a comment.
        zip.putNextEntry(ZipEntry(BackupRestorer.FAVORITES_ENTRY))
        val favorites = StringBuilder("${BackupRestorer.FAVORITES_HEADER} 1\n")
        for (uid in favoritesRepository.uids()) {
            val song = org.oxycblt.musikr.Music.UID.fromString(uid)?.let { library.findSong(it) }
            if (song != null) {
                favorites.append("# ").append(song.name.resolve(context).replace('\n', ' ')).append('\n')
                favorites.append(pathOf(song)).append('\n')
            }
        }
        zip.write(favorites.toString().toByteArray())
        zip.closeEntry()

        // The palettes the user has found in the visualizer.
        zip.putNextEntry(ZipEntry(BackupRestorer.PALETTES_ENTRY))
        val palettes = StringBuilder("${BackupRestorer.PALETTES_HEADER} 1\n")
        for (id in org.oxycblt.auxio.playback.visualizer.PaletteCollection.get(context).seen()) {
            palettes.append(id).append('\n')
        }
        zip.write(palettes.toString().toByteArray())
        zip.closeEntry()

        // Activity: every counted play, and every song's total count.
        zip.putNextEntry(ZipEntry(BackupRestorer.ACTIVITY_ENTRY))
        val activity = StringBuilder("${BackupRestorer.ACTIVITY_HEADER} 1\n")
        val paths = mutableMapOf<String, String?>()
        fun pathFor(uid: String) =
            paths.getOrPut(uid) {
                org.oxycblt.musikr.Music.UID.fromString(uid)?.let { library.findSong(it) }?.let(::pathOf)
            }
        for ((uid, at) in playCountRepository.playsSince(0)) {
            pathFor(uid)?.let { activity.append("play,").append(at).append(',').append(it).append('\n') }
        }
        for ((uid, entry) in playCountRepository.allCounts()) {
            pathFor(uid)?.let {
                activity
                    .append("count,")
                    .append(entry.count)
                    .append(',')
                    .append(entry.lastPlayedMs)
                    .append(',')
                    .append(it)
                    .append('\n')
            }
        }
        zip.write(activity.toString().toByteArray())
        zip.closeEntry()
    }

    private fun createFile(folder: Uri, mime: String, name: String): Uri? =
        DocumentsContract.createDocument(context.contentResolver, folder, mime, name)

    private companion object {
        const val KEY_LAST = "auxio_backup_last"
        const val KEY_FAILED = "auxio_backup_failed"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
