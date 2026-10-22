/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.picker.RecentItems
import timber.log.Timber

/**
 * A picker's 「最近」 ([RecentItems]), kept in the preferences under [type], oldest first, and when
 * each was used under [type]`.times`. [habits] is U-93's 「用我的输入习惯」, passed in each time.
 */
class RecentlyUsed(val type: String, val limit: Int) {

    companion object {
        // for backwords compatibility only
        const val DIR_NAME = "recently_used"
        const val PREFERENCE_NAME = "picker_recently_used"

        private const val TIMES = ".times"

        private val preferences
            get() = FcitxApplication.getInstance().directBootAwareContext.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

        // goes up as [clear] clears them all: each picker's own copy is read again
        @Volatile
        private var cleared = 0

        // clear runs on the fcitx thread, a pick on the main one: a pick between clear's save and
        // its cleared++ saved its stale copy back, and what was cleared came back (review R11)
        private val lock = Any()

        /**
         * Forgets what every picker used at [from] (ms since the epoch) or later, all of it for 0:
         * cleared with what was learned (U-88 清掉最近, U-86 清空学到的).
         */
        fun clear(from: Long): Unit = synchronized(lock) {
            val prefs = preferences
            val types = prefs.all.keys.filterNot { it.endsWith(TIMES) }
            for (type in types) {
                val items = RecentItems(Int.MAX_VALUE).apply { load(loadItems(prefs, type), loadTimes(prefs, type)) }
                if (items.clearSince(from)) save(prefs, type, items)
            }
            cleared++
        }

        private fun loadItems(prefs: SharedPreferences, type: String): List<String> {
            val rawValue = prefs.getString(type, "") ?: ""
            if (rawValue.isEmpty()) {
                return emptyList()
            }
            return try {
                Json.decodeFromString<List<String>>(rawValue)
            } catch (_: Exception) {
                prefs.edit {
                    remove(type)
                }
                emptyList()
            }
        }

        // none for those an older build kept: as of long ago
        private fun loadTimes(prefs: SharedPreferences, type: String): List<Long> =
            prefs.getString(type + TIMES, null)?.let { runCatching { Json.decodeFromString<List<Long>>(it) }.getOrNull() }.orEmpty()

        private fun save(prefs: SharedPreferences, type: String, items: RecentItems) {
            prefs.edit {
                putString(type, Json.encodeToString<List<String>>(items.stored))
                putString(type + TIMES, Json.encodeToString<List<Long>>(items.storedTimes))
            }
        }
    }

    private val sharedPreferences = preferences

    private val recent = RecentItems(limit)
    private var read = -1

    // [recent], read again if [clear] ran since
    private fun fresh(): RecentItems {
        if (read != cleared) {
            recent.load(if (read == -1) migrate() ?: loadItems(sharedPreferences, type) else loadItems(sharedPreferences, type), loadTimes(sharedPreferences, type))
            read = cleared
        }
        return recent
    }

    /** What is shown, the newest first: nothing while the habits are off. */
    fun items(habits: Boolean): List<String> = synchronized(lock) { fresh().shown(habits) }

    /** [item] used now: kept, unless the habits are off. */
    fun insert(item: String, habits: Boolean) = synchronized(lock) {
        if (fresh().use(item, System.currentTimeMillis(), habits)) save(sharedPreferences, type, recent)
    }

    fun migrate(): List<String>? {
        val dir = FcitxApplication.getInstance().directBootAwareContext.filesDir.resolve(DIR_NAME)
        val file = dir.resolve(type)
        if (file.exists()) {
            try {
                val lines = file.readLines()
                // save to sharedPreferences before deleting old file
                sharedPreferences.edit {
                    putString(type, Json.encodeToString<List<String>>(lines))
                }
                file.delete()
                if (dir.list()?.isEmpty() == true) {
                    dir.delete()
                }
                return lines
            } catch (e: Exception) {
                Timber.w("Failed to migrate RecentlyUsed(type=$type)")
                Timber.w(e)
                return null
            }
        }
        return null
    }
}