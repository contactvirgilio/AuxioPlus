package org.oxycblt.auxio.radio

import android.app.Activity
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.radio.databinding.DialogStationBinding

/** Asks for a stream URL and a name, to add a new favourite or edit an existing one. */
object StationDialog {
    fun show(activity: Activity, existing: Station?) {
        val binding = DialogStationBinding.inflate(activity.layoutInflater)
        existing?.let {
            binding.stationNameInput.setText(it.name)
            binding.stationUrlInput.setText(it.url)
            binding.stationLogoInput.setText(it.logo)
        }
        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(if (existing == null) R.string.lbl_add_url else R.string.lbl_edit)
                .setView(binding.root)
                .setPositiveButton(R.string.lbl_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val url = binding.stationUrlInput.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                binding.stationUrlLayout.error = activity.getString(R.string.err_url)
                return@setOnClickListener
            }
            val name =
                binding.stationNameInput.text.toString().trim().ifBlank {
                    android.net.Uri.parse(url).host ?: url
                }
            val logo = binding.stationLogoInput.text.toString().trim()
            if (existing == null) {
                Favorites.add(Station(Station.newId(), name, url, logo))
            } else {
                Favorites.update(existing.copy(name = name, url = url, logo = logo))
            }
            dialog.dismiss()
        }
    }
}
