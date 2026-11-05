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
 * Saves a zip of all playlists and a CSV of the Top 50 into a user-chosen "Auxio" folder, either on
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
) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val externalPlaylistManager = ExternalPlaylistManager.from(context)
    private val running = AtomicBoolean(false)

    /** The "Auxio" folder backups are written to, or null if none was chosen yet. */
    private val folder: Uri?
        get() =
            preferences
                .getString(context.getString(R.string.set_key_backup_folder), null)
                ?.let(Uri::parse)

    /** A readable name of the chosen folder for the settings summary, or null if none. */
    val folderLabel: String?
        get() =
            folder?.let { DocumentsContract.getDocumentId(it).substringAfter(':', "") }
                ?.ifEmpty { "Auxio" }

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
     * Use [tree], picked by the user, as the place for backups. An "Auxio" folder is created inside
     * it, unless the picked folder already is one.
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
            val auxio =
                if (nameOf(root).equals(FOLDER_NAME, ignoreCase = true)) {
                    root
                } else {
                    findChild(tree, root, FOLDER_NAME)
                        ?: DocumentsContract.createDocument(
                            context.contentResolver,
                            root,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            FOLDER_NAME,
                        )
                }
            if (auxio == null) {
                L.e("Could not create the Auxio folder in $tree")
                return false
            }
            preferences.edit {
                putString(context.getString(R.string.set_key_backup_folder), auxio.toString())
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
                    val zip = createFile(target, "application/zip", "Auxio-playlists-$date.zip")
                    val playlistsOk =
                        zip != null &&
                            externalPlaylistManager.exportAll(
                                playlists,
                                zip,
                                ExportConfig(absolute = false, windowsPaths = false),
                            )
                    val csv = createFile(target, "text/csv", "Auxio-top50-$date.csv")
                    val topOk = csv != null && writeTop(csv, library)
                    playlistsOk && topOk
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

    private fun writeTop(uri: Uri, library: Library): Boolean {
        val rows =
            playCountRepository
                .top(TOP_LIMIT * 2)
                .mapNotNull { (uid, entry) -> library.findSong(uid)?.let { it to entry.count } }
                .take(TOP_LIMIT)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: return false
        output.bufferedWriter().use { writer ->
            writer.write("rank,title,artist,plays\n")
            rows.forEachIndexed { i, (song, plays) ->
                val title = song.name.resolve(context)
                val artist = song.artists.firstOrNull()?.name?.resolve(context).orEmpty()
                writer.write("${i + 1},${csv(title)},${csv(artist)},$plays\n")
            }
        }
        return true
    }

    private fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

    private fun createFile(folder: Uri, mime: String, name: String): Uri? =
        DocumentsContract.createDocument(context.contentResolver, folder, mime, name)

    private fun nameOf(document: Uri): String? =
        context.contentResolver
            .query(
                document,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun findChild(tree: Uri, parent: Uri, name: String): Uri? {
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(parent),
            )
        context.contentResolver
            .query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
            ?.use {
                while (it.moveToNext()) {
                    if (it.getString(1).equals(name, ignoreCase = true)) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                    }
                }
            }
        return null
    }

    private companion object {
        const val FOLDER_NAME = "Auxio"
        const val KEY_LAST = "auxio_backup_last"
        const val KEY_FAILED = "auxio_backup_failed"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TOP_LIMIT = 50
    }
}
