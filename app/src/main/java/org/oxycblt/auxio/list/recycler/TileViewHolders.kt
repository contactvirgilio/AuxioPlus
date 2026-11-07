/*
 * Copyright (c) 2026 Auxio Project
 * TileViewHolders.kt is part of Auxio.
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

package org.oxycblt.auxio.list.recycler

import android.view.View
import org.oxycblt.auxio.databinding.ItemAlbumTileBinding
import org.oxycblt.auxio.databinding.ItemAlbumTileSmallBinding
import org.oxycblt.auxio.image.CoverView
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.util.inflater

/** A [SelectionIndicatorAdapter.ViewHolder] that shows an item as a cover tile with two lines. */
class TileViewHolder private constructor(private val binding: ItemAlbumTileBinding) :
    SelectionIndicatorAdapter.ViewHolder(binding.root) {
    fun <T> bind(
        item: T,
        listener: SelectableListListener<T>,
        name: CharSequence,
        info: CharSequence,
        bindCover: (CoverView) -> Unit,
    ) {
        listener.bind(item, this, menuButton = binding.tileMenu)
        bindCover(binding.tileImage)
        binding.tileName.text = name
        binding.tileInfo.text = info
    }

    override fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean) {
        binding.root.isSelected = isActive
        binding.tileImage.setPlaying(isPlaying)
    }

    override fun updateSelectionIndicator(isSelected: Boolean) {
        binding.root.isActivated = isSelected
    }

    companion object {
        const val VIEW_TYPE = 0x4101

        fun from(parent: View) =
            TileViewHolder(ItemAlbumTileBinding.inflate(parent.context.inflater))
    }
}

/**
 * A [SelectionIndicatorAdapter.ViewHolder] that shows an item as a small cover tile with a short
 * name. A long press selects it, which is also how its menu is reached.
 */
class SmallTileViewHolder private constructor(private val binding: ItemAlbumTileSmallBinding) :
    SelectionIndicatorAdapter.ViewHolder(binding.root) {
    fun <T> bind(
        item: T,
        listener: SelectableListListener<T>,
        name: CharSequence,
        bindCover: (CoverView) -> Unit,
    ) {
        listener.bind(item, this)
        binding.root.setOnLongClickListener {
            listener.onSelect(item)
            true
        }
        bindCover(binding.tileImage)
        binding.tileName.text = name
    }

    override fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean) {
        binding.root.isSelected = isActive
        binding.tileImage.setPlaying(isPlaying)
    }

    override fun updateSelectionIndicator(isSelected: Boolean) {
        binding.root.isActivated = isSelected
    }

    companion object {
        const val VIEW_TYPE = 0x4102

        fun from(parent: View) =
            SmallTileViewHolder(ItemAlbumTileSmallBinding.inflate(parent.context.inflater))
    }
}
