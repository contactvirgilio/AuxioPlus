/*
 * Copyright (c) 2023 Auxio Project
 * ExternalPlaylistManager.kt is part of Auxio.
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
 
package org.oxycblt.musikr.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.path.DocumentPathFactory
import org.oxycblt.musikr.fs.saf.contentResolverSafe
import org.oxycblt.musikr.playlist.m3u.M3U

/**
 * Generic playlist file importing abstraction.
 *
 * @see ImportedPlaylist
 * @see M3U
 * @author Alexander Capehart (OxygenCobalt)
 */
interface ExternalPlaylistManager {
    /**
     * Import the playli L.d("Unable to extract bit rate field") st file at the given [uri].
     *
     * @param uri The [Uri] of the playlist file to import.
     * @return An [ImportedPlaylist] containing the paths to the files listed in the playlist file,
     *   or null if the playlist could not be imported.
     */
    suspend fun import(uri: Uri): ImportedPlaylist?

    /**
     * Export the given [playlist] to the given [uri].
     *
     * @param playlist The playlist to export.
     * @param uri The [Uri] to export the playlist to.
     * @param config The configuration to use when exporting the playlist.
     * @return True if the playlist was successfully exported, false otherwise.
     */
    suspend fun export(playlist: Playlist, uri: Uri, config: ExportConfig): Boolean

    /**
     * Export every playlist in [playlists] into one zip file at [uri], one M3U file per playlist.
     * Relative paths are resolved against the folder the zip is saved in, so the M3U files also
     * work when the zip is extracted next to where it was saved.
     *
     * @param playlists The playlists to export.
     * @param uri The [Uri] of the zip file to write.
     * @param config The configuration to use when exporting each playlist.
     * @param onProgress Called with (playlists written, total playlists) as the export proceeds.
     * @return True if the zip was successfully written, false otherwise.
     */
    suspend fun exportAll(
        playlists: List<Playlist>,
        uri: Uri,
        config: ExportConfig,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean

    /**
     * Read every M3U playlist inside the zip file at [uri]. Relative paths are resolved against the
     * folder the zip is stored in.
     *
     * @param uri The [Uri] of the zip file to read.
     * @return The [ImportedPlaylist]s found in the zip (possibly empty), or null if the zip could
     *   not be read.
     */
    suspend fun importAll(uri: Uri): List<ImportedPlaylist>?

    /**
     * Whether the file at [uri] is a zip file, judged by its contents rather than its name or mime
     * type, which file managers often get wrong.
     *
     * @param uri The [Uri] of the file to check.
     * @return True if the file starts like a zip file, false otherwise or if it can't be read.
     */
    suspend fun isZip(uri: Uri): Boolean

    companion object {
        /** The mime type used for playlist backup zips. */
        const val ZIP_MIME_TYPE = "application/zip"

        /**
         * The mime types to offer when picking playlist files to import: M3U files and zips of
         * them. File managers label these inconsistently, so several variants are listed.
         */
        val IMPORT_MIME_TYPES =
            arrayOf(
                M3U.MIME_TYPE,
                "audio/mpegurl",
                "application/x-mpegurl",
                "application/vnd.apple.mpegurl",
                ZIP_MIME_TYPE,
                "application/x-zip-compressed",
                "application/x-zip",
                "application/octet-stream",
            )

        fun from(context: Context): ExternalPlaylistManager =
            ExternalPlaylistManagerImpl(
                context,
                DocumentPathFactory.from(context),
                M3U.from(context),
            )
    }
}

/**
 * Configuration to use when exporting playlists.
 *
 * @property absolute Whether or not to use absolute paths when exporting. If not, relative paths
 *   will be used.
 * @property windowsPaths Whether or not to use Windows-style paths when exporting (i.e prefixed
 *   with C:\\ and using \). If not, Unix-style paths will be used (i.e prefixed with /).
 * @see ExternalPlaylistManager.export
 */
data class ExportConfig(val absolute: Boolean, val windowsPaths: Boolean)

/**
 * A playlist that has been imported.
 *
 * @property name The name of the playlist. May be null if not provided.
 * @property paths The paths of the files in the playlist.
 * @see ExternalPlaylistManager
 * @see M3U
 */
data class ImportedPlaylist(val name: String?, val paths: List<PossiblePaths>)

typealias PossiblePaths = List<Path>

private class ExternalPlaylistManagerImpl(
    private val context: Context,
    private val documentPathFactory: DocumentPathFactory,
    private val m3u: M3U,
) : ExternalPlaylistManager {
    override suspend fun import(uri: Uri): ImportedPlaylist? {
        val filePath =
            documentPathFactory.unpackDocumentUri(uri)
                ?: Path(Volume.ThirdParty(uri), Components.root())

        return try {
            context.contentResolverSafe.openInputStream(uri)?.use {
                val imported = m3u.read(it, filePath.directory) ?: return null
                val name = imported.name
                if (name != null) {
                    return imported
                }
                // Strip extension
                val fileName = filePath.name ?: return imported
                val split = fileName.split(".")
                var newName = split[0]
                // Replace delimiters with space
                newName = newName.replace(Regex("[_-]"), " ")
                // Replace long stretches of whitespace with one space
                newName = newName.replace(Regex("\\s+"), " ")
                return ImportedPlaylist(newName, imported.paths)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun export(playlist: Playlist, uri: Uri, config: ExportConfig): Boolean {
        val filePath =
            documentPathFactory.unpackDocumentUri(uri)
                ?: Path(Volume.ThirdParty(uri), Components.root())
        val workingDirectory =
            if (config.absolute) {
                Path(filePath.volume, Components.parseUnix("/"))
            } else {
                filePath.directory
            }
        return try {
            val outputStream = context.contentResolverSafe.openOutputStream(uri) ?: return false
            outputStream.use {
                m3u.write(playlist, it, workingDirectory, config)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun exportAll(
        playlists: List<Playlist>,
        uri: Uri,
        config: ExportConfig,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Boolean {
        val filePath = documentPathFactory.unpackDocumentUri(uri) ?: return false
        // Every M3U sits at the root of the zip, so relative paths are made relative to the
        // folder the zip itself lives in.
        val workingDirectory =
            if (config.absolute) {
                Path(filePath.volume, Components.parseUnix("/"))
            } else {
                filePath.directory
            }
        onProgress(0, playlists.size)
        val written =
            try {
                // "wt" truncates, so overwriting an existing, larger zip can't leave junk behind.
                val outputStream =
                    context.contentResolverSafe.openOutputStream(uri, "wt") ?: return false
                ZipOutputStream(outputStream.buffered()).use { zip ->
                    val usedNames = mutableSetOf<String>()
                    playlists.forEachIndexed { index, playlist ->
                        zip.putNextEntry(ZipEntry(uniqueEntryName(playlist, usedNames)))
                        m3u.write(playlist, zip, workingDirectory, config)
                        zip.closeEntry()
                        onProgress(index + 1, playlists.size)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        if (!written) {
            // Don't leave a half-written zip behind.
            try {
                DocumentsContract.deleteDocument(context.contentResolverSafe, uri)
            } catch (e: Exception) {}
        }
        return written
    }

    override suspend fun importAll(uri: Uri): List<ImportedPlaylist>? {
        val filePath = documentPathFactory.unpackDocumentUri(uri) ?: return null
        val zipDirectory = filePath.directory
        return try {
            val inputStream = context.contentResolverSafe.openInputStream(uri) ?: return null
            val imported = mutableListOf<ImportedPlaylist>()
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryPath = entry.name.replace('\\', '/').trim('/')
                    val entryFileName = entryPath.substringAfterLast('/')
                    if (!entry.isDirectory && isPlaylistFile(entryFileName)) {
                        // Resolve relative paths as if the zip had been extracted where it is.
                        val folder = Components.parseUnix(entryPath.substringBeforeLast('/', ""))
                        val workingDirectory =
                            Path(zipDirectory.volume, zipDirectory.components.child(folder))
                        // Read the entry fully so the M3U reader can't run past it.
                        val bytes = zip.readBytes()
                        val playlist = m3u.read(ByteArrayInputStream(bytes), workingDirectory)
                        if (playlist != null) {
                            imported.add(playlist.withFallbackName(entryFileName))
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            imported
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun isZip(uri: Uri): Boolean =
        try {
            context.contentResolverSafe.openInputStream(uri)?.use { stream ->
                val header = ByteArray(ZIP_MAGIC.size)
                var read = 0
                while (read < header.size) {
                    val n = stream.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
                read == header.size && header.contentEquals(ZIP_MAGIC)
            } ?: false
        } catch (e: Exception) {
            false
        }

    private fun uniqueEntryName(playlist: Playlist, usedNames: MutableSet<String>): String {
        val safeName = playlist.name.raw.replace(UNSAFE_ENTRY_NAME_REGEX, "_")
        val base = safeName.trim().trim('.').ifEmpty { "Playlist" }
        var name = "$base.m3u"
        var counter = 2
        // Zip entries are case-sensitive, but most filesystems they get extracted to aren't.
        while (!usedNames.add(name.lowercase())) {
            name = "$base ($counter).m3u"
            ++counter
        }
        return name
    }

    private fun isPlaylistFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8")
    }

    private fun ImportedPlaylist.withFallbackName(fileName: String): ImportedPlaylist {
        if (name != null) {
            return this
        }
        // Strip extension
        var newName = fileName.substringBeforeLast('.')
        // Replace delimiters with space
        newName = newName.replace(Regex("[_-]"), " ")
        // Replace long stretches of whitespace with one space
        newName = newName.replace(Regex("\\s+"), " ").trim()
        return ImportedPlaylist(newName.ifEmpty { null }, paths)
    }

    private companion object {
        // Characters that aren't allowed in file names on common filesystems.
        val UNSAFE_ENTRY_NAME_REGEX = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

        // Every zip file with at least one entry starts with a local file header.
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
