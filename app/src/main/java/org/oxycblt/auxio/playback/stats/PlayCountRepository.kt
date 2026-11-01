/*
 * Copyright (c) 2026 Auxio Project
 * PlayCountRepository.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.stats

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.preference.PreferenceManager
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
 * Stores how many times each song has been played, for the Top 50 tab.
 *
 * Counts are kept in memory for quick reads and written through to a small database. Listeners are
 * always called on the main thread.
 */
@Singleton
class PlayCountRepository @Inject constructor(@ApplicationContext context: Context) {
    /** How often a song was played, and when it was last played (unix time in milliseconds). */
    data class Entry(val count: Int, val lastPlayedMs: Long)

    interface Listener {
        /** Called when play counts have been loaded or changed. */
        fun onPlayCountsChanged()
    }

    private val database = PlayCountDatabase(context)
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var counts: Map<String, Entry> = emptyMap()

    init {
        // Plays are only counted from when this feature first ran.
        if (!sharedPreferences.contains(KEY_SINCE)) {
            sharedPreferences.edit { putLong(KEY_SINCE, System.currentTimeMillis()) }
        }
        scope.launch {
            synchronized(lock) { counts = database.readAll() + counts }
            L.d("Loaded ${counts.size} play counts")
            notifyListeners()
        }
    }

    /** When play counting started, as unix time in milliseconds. */
    val sinceMs: Long
        get() = sharedPreferences.getLong(KEY_SINCE, System.currentTimeMillis())

    /**
     * The most played songs, most played first. Songs played equally often are ordered by which was
     * played most recently.
     *
     * @param limit The most entries to return.
     * @return Pairs of song UIDs and their [Entry].
     */
    fun top(limit: Int): List<Pair<Music.UID, Entry>> =
        counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Entry>> { it.value.count }
                    .thenByDescending { it.value.lastPlayedMs }
            )
            .asSequence()
            .mapNotNull { (uid, entry) -> Music.UID.fromString(uid)?.let { it to entry } }
            .take(limit)
            .toList()

    /**
     * Count one more play of the song with the given [uid].
     *
     * @param uid The [Music.UID] of the song that was played.
     */
    fun recordPlay(uid: Music.UID) {
        val key = uid.toString()
        scope.launch {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val entry = Entry((counts[key]?.count ?: 0) + 1, now)
                counts = counts + (key to entry)
                database.write(key, entry)
            }
            L.d("Counted a play of $uid")
            notifyListeners()
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.forEach { it.onPlayCountsChanged() } }
    }

    private class PlayCountDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COLUMN_UID TEXT PRIMARY KEY NOT NULL, " +
                    "$COLUMN_COUNT INTEGER NOT NULL, $COLUMN_LAST_PLAYED INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        fun readAll(): Map<String, Entry> {
            val result = mutableMapOf<String, Entry>()
            try {
                readableDatabase
                    .query(
                        TABLE,
                        arrayOf(COLUMN_UID, COLUMN_COUNT, COLUMN_LAST_PLAYED),
                        null,
                        null,
                        null,
                        null,
                        null,
                    )
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            result[cursor.getString(0)] = Entry(cursor.getInt(1), cursor.getLong(2))
                        }
                    }
            } catch (e: Exception) {
                L.e("Could not read play counts: $e")
            }
            return result
        }

        fun write(uid: String, entry: Entry) {
            try {
                val values =
                    ContentValues().apply {
                        put(COLUMN_UID, uid)
                        put(COLUMN_COUNT, entry.count)
                        put(COLUMN_LAST_PLAYED, entry.lastPlayedMs)
                    }
                writableDatabase.insertWithOnConflict(
                    TABLE,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            } catch (e: Exception) {
                L.e("Could not save play count: $e")
            }
        }
    }

    private companion object {
        const val KEY_SINCE = "auxio_play_counts_since"
        const val DATABASE_NAME = "auxio_play_counts.db"
        const val DATABASE_VERSION = 1
        const val TABLE = "play_counts"
        const val COLUMN_UID = "uid"
        const val COLUMN_COUNT = "count"
        const val COLUMN_LAST_PLAYED = "last_played"
    }
}
