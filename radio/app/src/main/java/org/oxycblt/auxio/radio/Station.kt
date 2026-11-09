package org.oxycblt.auxio.radio

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A radio station: a stream URL with a name, and whatever else is known about it. */
data class Station(
    val id: String,
    val name: String,
    val url: String,
    val logo: String = "",
    /** A short line under the name, e.g. "MP3 128k · Lisbon, Portugal". */
    val info: String = "",
) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("name", name).put("url", url).put("logo", logo).put("info", info)

    fun toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            // A controller can't pass the URI itself to the service, so it goes in here too.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    // The stream's own song titles fill in the title as they arrive.
                    .setArtist(name)
                    .setStation(name)
                    .setArtworkUri(logo.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject) =
            Station(
                id = o.optString("id").ifBlank { newId() },
                name = o.optString("name"),
                url = o.optString("url"),
                logo = o.optString("logo"),
                info = o.optString("info"),
            )
    }
}

/** The favourite stations, in the user's order, kept in shared preferences. */
object Favorites {
    private const val PREFS = "favorites"
    private const val KEY = "stations"
    private lateinit var appContext: Context
    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val raw = prefs().getString(KEY, null) ?: return
        _stations.value =
            runCatching {
                    val array = JSONArray(raw)
                    List(array.length()) { Station.fromJson(array.getJSONObject(it)) }
                }
                .getOrDefault(emptyList())
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(list: List<Station>) {
        _stations.value = list
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs().edit { putString(KEY, array.toString()) }
    }

    /** Whether a station is a favourite, matched by its stream URL. */
    fun contains(station: Station) = _stations.value.any { it.id == station.id || it.url == station.url }

    fun add(station: Station) {
        if (!contains(station)) save(_stations.value + station)
    }

    fun remove(station: Station) {
        save(_stations.value.filterNot { it.id == station.id || it.url == station.url })
    }

    /** Add or remove, and return whether it is now a favourite. */
    fun toggle(station: Station): Boolean {
        val added = !contains(station)
        if (added) add(station) else remove(station)
        return added
    }

    fun update(station: Station) {
        save(_stations.value.map { if (it.id == station.id) station else it })
    }

    fun move(from: Int, to: Int) {
        val list = _stations.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save(list)
    }

    fun byId(id: String?) = _stations.value.firstOrNull { it.id == id }
}
