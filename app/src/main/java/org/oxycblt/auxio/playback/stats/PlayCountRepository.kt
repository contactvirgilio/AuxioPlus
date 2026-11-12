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
 * Stores how many times each song has been played, for the Activity tab.
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
                database.log(key, now)
            }
            L.d("Counted a play of $uid")
            notifyListeners()
        }
    }

    /**
     * Every play counted since [sinceMs], from the play log. Plays counted before the log existed
     * are not in it.
     *
     * @return Pairs of song UID strings and when they were played (unix time in milliseconds).
     */
    fun playsSince(sinceMs: Long): List<Pair<String, Long>> =
        synchronized(lock) { database.readLog(sinceMs) }

    /**
     * Merge plays from a backup into the current ones. Plays already in the log are skipped, and
     * the larger of the two counts is kept for every song.
     *
     * @param restoredCounts Total play counts by song UID string.
     * @param restoredPlays Plays as song UID strings and when they were played.
     * @return How many plays were added to the log.
     */
    fun restore(
        restoredCounts: Map<String, Entry>,
        restoredPlays: List<Pair<String, Long>>,
    ): Int {
        val added: Int
        synchronized(lock) {
            val known = database.readLog(0).toHashSet()
            val fresh = restoredPlays.filter { known.add(it) }
            database.logAll(fresh)
            added = fresh.size
            val merged = counts.toMutableMap()
            for ((uid, entry) in restoredCounts) {
                val current = merged[uid]
                val result =
                    if (current == null) entry
                    else
                        Entry(
                            maxOf(current.count, entry.count),
                            maxOf(current.lastPlayedMs, entry.lastPlayedMs),
                        )
                merged[uid] = result
                database.write(uid, result)
            }
            counts = merged
        }
        notifyListeners()
        return added
    }

    /** Every song's total play count, including plays from before the play log existed. */
    fun allCounts(): Map<String, Entry> = counts

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        Trophies.update(counts)
        mainHandler.post { listeners.forEach { it.onPlayCountsChanged() } }
    }

    private class PlayCountDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COLUMN_UID TEXT PRIMARY KEY NOT NULL, " +
                    "$COLUMN_COUNT INTEGER NOT NULL, $COLUMN_LAST_PLAYED INTEGER NOT NULL)"
            )
            createLog(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createLog(db)
        }

        private fun createLog(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $LOG_TABLE ($COLUMN_UID TEXT NOT NULL, " +
                    "$COLUMN_PLAYED_AT INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS log_time ON $LOG_TABLE ($COLUMN_PLAYED_AT)"
            )
        }

        fun log(uid: String, playedAtMs: Long) {
            try {
                val values =
                    ContentValues().apply {
                        put(COLUMN_UID, uid)
                        put(COLUMN_PLAYED_AT, playedAtMs)
                    }
                writableDatabase.insert(LOG_TABLE, null, values)
            } catch (e: Exception) {
                L.e("Could not log play: $e")
            }
        }

        fun logAll(plays: List<Pair<String, Long>>) {
            val db = writableDatabase
            try {
                db.beginTransaction()
                for ((uid, playedAt) in plays) {
                    val values =
                        ContentValues().apply {
                            put(COLUMN_UID, uid)
                            put(COLUMN_PLAYED_AT, playedAt)
                        }
                    db.insert(LOG_TABLE, null, values)
                }
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                L.e("Could not restore play log: $e")
            } finally {
                try {
                    db.endTransaction()
                } catch (e: Exception) {}
            }
        }

        fun readLog(sinceMs: Long): List<Pair<String, Long>> {
            val result = mutableListOf<Pair<String, Long>>()
            try {
                readableDatabase
                    .query(
                        LOG_TABLE,
                        arrayOf(COLUMN_UID, COLUMN_PLAYED_AT),
                        "$COLUMN_PLAYED_AT >= ?",
                        arrayOf(sinceMs.toString()),
                        null,
                        null,
                        null,
                    )
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            result += cursor.getString(0) to cursor.getLong(1)
                        }
                    }
            } catch (e: Exception) {
                L.e("Could not read play log: $e")
            }
            return result
        }

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
        const val DATABASE_VERSION = 2
        const val TABLE = "play_counts"
        const val COLUMN_UID = "uid"
        const val COLUMN_COUNT = "count"
        const val COLUMN_LAST_PLAYED = "last_played"
        const val LOG_TABLE = "play_log"
        const val COLUMN_PLAYED_AT = "played_at"
    }
}
