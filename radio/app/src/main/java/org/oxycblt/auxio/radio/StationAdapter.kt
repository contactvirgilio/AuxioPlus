package org.oxycblt.auxio.radio

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.radio.databinding.ItemStationBinding

/**
 * The station cards. In the favourites list the button opens a menu, in search results it is a
 * heart that adds or removes the station.
 */
class StationAdapter(
    private val favoritesList: Boolean,
    private val onClick: (Station, Int) -> Unit,
    private val onButton: (Station, android.view.View) -> Unit,
) : ListAdapter<Station, StationAdapter.Holder>(Diff) {
    var playingId: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { i, s -> if (s.id == old || s.id == value) notifyItemChanged(i) }
        }

    class Holder(val binding: ItemStationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val station = getItem(position)
        val b = holder.binding
        b.stationName.text = station.name
        b.stationInfo.text = station.info.ifBlank { station.url }
        b.stationInfo.isVisible = true
        b.stationPlaying.isVisible = station.id == playingId
        Logos.load(b.stationLogo, b.stationLetter, station)
        b.root.setOnClickListener {
            val i = holder.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) onClick(getItem(i), i)
        }
        if (favoritesList) {
            b.stationButton.setIconResource(R.drawable.ic_more)
            b.stationButton.contentDescription = b.root.context.getString(R.string.lbl_more)
        } else {
            val fav = Favorites.contains(station)
            b.stationButton.setIconResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            b.stationButton.contentDescription =
                b.root.context.getString(if (fav) R.string.lbl_unfavorite else R.string.lbl_favorite)
        }
        b.stationButton.setOnClickListener { view ->
            val i = holder.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) onButton(getItem(i), view)
        }
    }

    private object Diff : DiffUtil.ItemCallback<Station>() {
        override fun areItemsTheSame(a: Station, b: Station) = a.id == b.id

        override fun areContentsTheSame(a: Station, b: Station) = a == b
    }
}
