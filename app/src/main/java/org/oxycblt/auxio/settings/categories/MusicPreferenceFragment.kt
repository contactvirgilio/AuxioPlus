/*
 * Copyright (c) 2023 Auxio Project
 * MusicPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.recyclerview.widget.SimpleItemAnimator
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.PlaylistBackupState
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.ProgressPreference
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.musikr.playlist.ExternalPlaylistManager
import timber.log.Timber as L

/**
 * "Content" settings.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class MusicPreferenceFragment : BasePreferenceFragment(R.xml.preferences_music) {
    // Activity-scoped so an export/import keeps running if the user leaves this screen.
    private val musicModel: MusicViewModel by activityViewModels()
    @Inject lateinit var imageLoader: ImageLoader
    private var exportPlaylistsLauncher: ActivityResultLauncher<String>? = null
    private var importPlaylistsLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val createZip =
            ActivityResultContracts.CreateDocument(ExternalPlaylistManager.ZIP_MIME_TYPE)
        exportPlaylistsLauncher =
            registerForActivityResult(createZip) { uri ->
                if (uri == null) {
                    L.w("No URI returned from file picker")
                    return@registerForActivityResult
                }
                L.d("Exporting all playlists to $uri")
                musicModel.exportAllPlaylists(uri)
            }
        importPlaylistsLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isEmpty()) {
                    L.w("No URIs returned from file picker")
                    return@registerForActivityResult
                }
                L.d("Importing playlists from ${uris.size} files")
                musicModel.importPlaylistFiles(uris, nameSingleFile = false)
            }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?,
    ) =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            // The export/import progress rows update often, don't cross-fade them every time.
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectImmediately(musicModel.playlistBackupState, ::updatePlaylistBackupState)
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_separators)) {
            L.d("Navigating to separator dialog")
            findNavController().navigateSafe(MusicPreferenceFragmentDirections.separatorsSettings())
        }
    }

    override fun onSetupPreference(preference: Preference) {
        if (preference.key == getString(R.string.set_key_cover_mode)) {
            L.d("Configuring cover mode setting")
            preference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    L.d("Cover mode changed, reloading music")
                    musicModel.refresh()
                    true
                }
        }
        if (preference.key == getString(R.string.set_key_square_covers)) {
            L.d("Configuring square cover setting")
            preference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    L.d("Cover mode changed, resetting image memory cache")
                    imageLoader.memoryCache?.clear()
                    true
                }
        }
        if (preference.key == getString(R.string.set_key_export_playlists)) {
            L.d("Configuring export playlists setting")
            preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    if (musicModel.playlistBackupState.value !is PlaylistBackupState.Running) {
                        requireNotNull(exportPlaylistsLauncher) {
                                "Export playlists launcher was not available"
                            }
                            .launch(musicModel.newPlaylistBackupName())
                    }
                    true
                }
        }
        if (preference.key == getString(R.string.set_key_import_playlists)) {
            L.d("Configuring import playlists setting")
            preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    if (musicModel.playlistBackupState.value !is PlaylistBackupState.Running) {
                        requireNotNull(importPlaylistsLauncher) {
                                "Import playlists launcher was not available"
                            }
                            .launch(ExternalPlaylistManager.IMPORT_MIME_TYPES)
                    }
                    true
                }
        }
        if (preference.key == getString(R.string.set_key_with_hidden)) {
            L.d("Configuring ignore hidden files setting")
            preference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    L.d("Ignore hidden files setting changed, reloading music")
                    musicModel.refresh()
                    true
                }
        }
    }

    private fun updatePlaylistBackupState(state: PlaylistBackupState?) {
        val exportPreference =
            findPreference<ProgressPreference>(getString(R.string.set_key_export_playlists))
        val importPreference =
            findPreference<ProgressPreference>(getString(R.string.set_key_import_playlists))
        if (exportPreference == null || importPreference == null) return

        // Only one export/import can run at a time.
        val running = state is PlaylistBackupState.Running
        val operation = state?.operation
        updateProgressPreference(exportPreference, PlaylistBackupState.Operation.EXPORT, state)
        updateProgressPreference(importPreference, PlaylistBackupState.Operation.IMPORT, state)
        exportPreference.isSelectable = !running
        importPreference.isSelectable = !running
        exportPreference.isEnabled = !running || operation == PlaylistBackupState.Operation.EXPORT
        importPreference.isEnabled = !running || operation == PlaylistBackupState.Operation.IMPORT
    }

    private fun updateProgressPreference(
        preference: ProgressPreference,
        operation: PlaylistBackupState.Operation,
        state: PlaylistBackupState?,
    ) {
        if (state == null || state.operation != operation) {
            preference.showIdle()
            return
        }
        when (state) {
            is PlaylistBackupState.Running -> {
                val title =
                    when (operation) {
                        PlaylistBackupState.Operation.EXPORT -> R.string.set_exporting_playlists
                        PlaylistBackupState.Operation.IMPORT -> R.string.set_importing_playlists
                    }
                preference.showProgress(getString(title), state.done, state.total)
            }
            is PlaylistBackupState.Failed -> {
                val error =
                    when (operation) {
                        PlaylistBackupState.Operation.EXPORT -> R.string.err_export_playlists_failed
                        PlaylistBackupState.Operation.IMPORT -> R.string.err_import_playlists_failed
                    }
                preference.showError(getString(error))
            }
        }
    }
}
