/*
 * Copyright (c) 2026 Auxio Project
 * FavoritesRepository.kt is part of Auxio.
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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.oxycblt.musikr.Music
import timber.log.Timber as L

/**
 * The songs marked with a heart. Kept in memory for quick reads and written through to a small
 * database. This is not a playlist: it is only shown in the Favorites tab. Listeners are always
 * called on the main thread.
 */
@Singleton
class FavoritesRepository @Inject constructor(@ApplicationContext context: Context) {
    interface Listener {
        /** Called when favorites have been loaded or changed. */
        fun onFavoritesChanged()
    }

    private val database = FavoritesDatabase(context)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    /** Song UID strings and when they were hearted (unix time in milliseconds). */
    @Volatile private var favorites: Map<String, Long> = emptyMap()

    init {
        scope.launch {
            val loaded = database.readAll()
            synchronized(lock) { favorites = loaded + favorites }
            L.d("Loaded ${favorites.size} favorites")
            notifyListeners()
        }
    }

    fun isFavorite(uid: Music.UID) = favorites.containsKey(uid.toString())

    /** The UID strings of all favorites, most recently added first. */
    fun uids(): List<String> =
        favorites.entries.sortedByDescending { it.value }.map { it.key }

    /**
     * Add the song to the favorites, or remove it if it already is one.
     *
     * @return Whether the song is a favorite now.
     */
    fun toggle(uid: Music.UID): Boolean {
        val key = uid.toString()
        val nowFavorite: Boolean
        synchronized(lock) {
            nowFavorite = !favorites.containsKey(key)
            favorites =
                if (nowFavorite) favorites + (key to System.currentTimeMillis())
                else favorites - key
        }
        scope.launch {
            if (nowFavorite) database.add(key, System.currentTimeMillis()) else database.remove(key)
        }
        notifyListeners()
        return nowFavorite
    }

    /**
     * Add all of [uids] to the favorites. Songs that already are favorites keep their place.
     *
     * @return How many songs were newly added.
     */
    fun addAll(uids: Collection<Music.UID>): Int {
        val now = System.currentTimeMillis()
        val added = mutableListOf<String>()
        synchronized(lock) {
            val updated = favorites.toMutableMap()
            // Keep the playlist's order: the first song ends up the most recent.
            for ((i, uid) in uids.withIndex()) {
                val key = uid.toString()
                if (updated.containsKey(key)) continue
                updated[key] = now - i
                added += key
            }
            favorites = updated
        }
        if (added.isEmpty()) return 0
        // One write for the whole lot instead of one per song.
        val rows = added.mapIndexed { i, key -> key to now - i }
        scope.launch { database.addAll(rows) }
        notifyListeners()
        return added.size
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.forEach { it.onFavoritesChanged() } }
    }

    private class FavoritesDatabase(context: Context) :
        SQLiteOpenHelper(context, "auxio_favorites.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE favorites (uid TEXT PRIMARY KEY NOT NULL, " +
                    "added_at INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        fun readAll(): Map<String, Long> {
            val result = mutableMapOf<String, Long>()
            try {
                readableDatabase.query("favorites", arrayOf("uid", "added_at"), null, null, null, null, null).use {
                    while (it.moveToNext()) result[it.getString(0)] = it.getLong(1)
                }
            } catch (e: Exception) {
                L.e("Could not read favorites: $e")
            }
            return result
        }

        fun add(uid: String, addedAt: Long) {
            try {
                val values = ContentValues().apply {
                    put("uid", uid)
                    put("added_at", addedAt)
                }
                writableDatabase.insertWithOnConflict(
                    "favorites",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            } catch (e: Exception) {
                L.e("Could not save favorite: $e")
            }
        }

        fun addAll(rows: List<Pair<String, Long>>) {
            val db =
                try {
                    writableDatabase
                } catch (e: Exception) {
                    L.e("Could not save favorites: $e")
                    return
                }
            try {
                db.beginTransaction()
                for ((uid, addedAt) in rows) {
                    val values = ContentValues().apply {
                        put("uid", uid)
                        put("added_at", addedAt)
                    }
                    db.insertWithOnConflict(
                        "favorites",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                L.e("Could not save favorites: $e")
            } finally {
                try {
                    db.endTransaction()
                } catch (e: Exception) {}
            }
        }

        fun remove(uid: String) {
            try {
                writableDatabase.delete("favorites", "uid = ?", arrayOf(uid))
            } catch (e: Exception) {
                L.e("Could not remove favorite: $e")
            }
        }
    }
}
