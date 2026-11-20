/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.fcitx.fcitx5.android.core.HostApi

object PunctuationManager {

    // the keyboard reloads the table on every focus, over JNI
    private val kept = HashMap<String, List<PunctuationMapEntry>>()

    // bumped by every forget: an overlapping read may hold the old table, and is not kept
    private var changes = 0

    suspend fun load(fcitx: HostApi, lang: String): List<PunctuationMapEntry> {
        val (known, seen) = synchronized(this) { kept[lang] to changes }
        if (known != null) return known
        val entries = fcitx.punctuationMap(lang)
        synchronized(this) { if (changes == seen) kept[lang] = entries }
        return entries
    }

    /** For when fcitx rereads its tables: it starts or reloads its config. */
    fun forget() = synchronized(this) {
        changes++
        kept.clear()
    }

    /** Any config reload rereads the tables, so what [load] kept goes with it. */
    fun forgettingOnReload(fcitx: HostApi): HostApi = object : HostApi by fcitx {
        override suspend fun reloadConfig() {
            fcitx.reloadConfig()
            forget()
        }
    }

    /** Fcitx reads its tables as it starts, so what [load] kept goes. */
    inline fun starting(start: () -> Unit) {
        start()
        forget()
    }
}
