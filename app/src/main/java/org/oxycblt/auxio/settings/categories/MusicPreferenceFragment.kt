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

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.recyclerview.widget.SimpleItemAnimator
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.music.AutoBackup
import org.oxycblt.auxio.music.BackupRestorer
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
    @Inject lateinit var autoBackup: AutoBackup
    @Inject lateinit var backupRestorer: BackupRestorer
    private var backupFolderLauncher: ActivityResultLauncher<Uri?>? = null
    private var importPlaylistsLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backupFolderLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri == null) {
                    L.w("No folder returned from folder picker")
                    return@registerForActivityResult
                }
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { autoBackup.chooseFolder(uri) }
                    if (!ok) {
                        Toast.makeText(
                                requireContext(),
                                R.string.err_backup_folder,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                    updateBackupPreferences()
                }
            }
        importPlaylistsLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isEmpty()) {
                    L.w("No URIs returned from file picker")
                    return@registerForActivityResult
                }
                L.d("Restoring from ${uris.size} files")
                startRestore(uris)
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
        updateBackupPreferences()
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
        if (preference.key == getString(R.string.set_key_import_playlists)) {
            L.d("Configuring import playlists setting")
            preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    if (musicModel.playlistBackupState.value !is PlaylistBackupState.Running) {
                        requireNotNull(importPlaylistsLauncher) {
                                "Import playlists launcher was not available"
                            }
                            .launch(ExternalPlaylistManager.IMPORT_MIME_TYPES + RESTORE_EXTRA_MIME_TYPES)
                    }
                    true
                }
        }
        if (preference.key == getString(R.string.set_key_backup_folder)) {
            preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    requireNotNull(backupFolderLauncher) { "Folder launcher was not available" }
                        .launch(null)
                    true
                }
        }
        if (preference.key == getString(R.string.set_key_backup_now)) {
            preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    runBackupNow(preference)
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

    private fun updateBackupPreferences() {
        findPreference<Preference>(getString(R.string.set_key_backup_folder))?.summary =
            autoBackup.folderLabel?.let { getString(R.string.set_backup_folder_set, it) }
                ?: getString(R.string.set_backup_folder_desc)
        findPreference<Preference>(getString(R.string.set_key_backup_now))?.summary =
            when {
                autoBackup.lastFailed -> getString(R.string.err_backup_failed)
                autoBackup.lastBackupMs > 0 ->
                    getString(
                        R.string.set_backup_last,
                        DateUtils.getRelativeTimeSpanString(autoBackup.lastBackupMs),
                    )
                else -> getString(R.string.set_backup_now_desc)
            }
    }

    private fun runBackupNow(preference: Preference) {
        if (autoBackup.folderLabel == null) {
            Toast.makeText(requireContext(), R.string.set_backup_need_folder, Toast.LENGTH_SHORT)
                .show()
            return
        }
        preference.isEnabled = false
        preference.summary = getString(R.string.set_backup_running)
        // Not tied to the screen so leaving it doesn't cancel the backup halfway.
        musicModel.backUpNow {
            preference.isEnabled = true
            if (isAdded) updateBackupPreferences()
        }
    }

    private fun startRestore(uris: List<Uri>) {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { backupRestorer.read(uris) }
            if (found.isEmpty) {
                Toast.makeText(requireContext(), R.string.err_restore_nothing, Toast.LENGTH_SHORT)
                    .show()
            } else if (found.choice) {
                showRestoreChoice(found)
            } else {
                runRestore(found, playlists = true, favorites = true, activity = true, palettes = true)
            }
        }
    }

    /** A backup zip was picked: restore everything, or only some parts of it. */
    private fun showRestoreChoice(found: BackupRestorer.Contents) {
        val labels = mutableListOf<String>()
        val parts = mutableListOf<Int>()
        if (found.playlistUris.isNotEmpty()) {
            labels += getString(R.string.set_restore_playlists, found.playlists)
            parts += PART_PLAYLISTS
        }
        if (found.favorites.isNotEmpty()) {
            labels += getString(R.string.set_restore_favorites, found.favorites.size)
            parts += PART_FAVORITES
        }
        found.activity?.let {
            labels += getString(R.string.set_restore_activity, it.plays.size)
            parts += PART_ACTIVITY
        }
        if (found.palettes.isNotEmpty()) {
            labels += getString(R.string.set_restore_palettes, found.palettes.size)
            parts += PART_PALETTES
        }
        val checked = BooleanArray(labels.size) { true }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_restore_choose)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.set_restore_go) { _, _ ->
                val chosen = parts.filterIndexed { i, _ -> checked[i] }
                runRestore(
                    found,
                    playlists = PART_PLAYLISTS in chosen,
                    favorites = PART_FAVORITES in chosen,
                    activity = PART_ACTIVITY in chosen,
                    palettes = PART_PALETTES in chosen,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runRestore(
        found: BackupRestorer.Contents,
        playlists: Boolean,
        favorites: Boolean,
        activity: Boolean,
        palettes: Boolean,
    ) {
        if (palettes && found.palettes.isNotEmpty()) {
            backupRestorer.restorePalettes(found.palettes)
        }
        if (playlists && found.playlistUris.isNotEmpty()) {
            // Shows its own progress on the restore button.
            musicModel.importPlaylistFiles(found.playlistUris, nameSingleFile = false)
        }
        val wantsFavorites = favorites && found.favorites.isNotEmpty()
        val wantsActivity = activity && found.activity != null
        if (!wantsFavorites && !wantsActivity) return
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val (addedFavorites, addedPlays) =
                withContext(Dispatchers.IO) {
                    val f = if (wantsFavorites) backupRestorer.restoreFavorites(found.favorites) else 0
                    val p =
                        found.activity?.takeIf { wantsActivity }?.let(backupRestorer::restoreActivity)
                            ?: 0
                    f to p
                }
            Toast.makeText(
                    context,
                    context.getString(R.string.fmt_restored, addedFavorites, addedPlays),
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    private fun updatePlaylistBackupState(state: PlaylistBackupState?) {
        val importPreference =
            findPreference<ProgressPreference>(getString(R.string.set_key_import_playlists))
                ?: return

        // Only one restore can run at a time.
        val running = state is PlaylistBackupState.Running
        updateProgressPreference(importPreference, PlaylistBackupState.Operation.IMPORT, state)
        importPreference.isSelectable = !running
        importPreference.isEnabled = !running || state?.operation == PlaylistBackupState.Operation.IMPORT
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

    private companion object {
        const val PART_PLAYLISTS = 1
        const val PART_FAVORITES = 2
        const val PART_ACTIVITY = 3
        const val PART_PALETTES = 4
        val RESTORE_EXTRA_MIME_TYPES = arrayOf("text/csv", "text/plain", "text/comma-separated-values")
    }
}
