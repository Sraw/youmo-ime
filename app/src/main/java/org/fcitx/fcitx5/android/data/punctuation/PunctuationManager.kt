/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.getPunctuationConfig
import org.fcitx.fcitx5.android.core.savePunctuationConfig

object PunctuationManager {

    // what load read, by language: the keyboard reloads the table on every focus, over JNI
    private val kept = HashMap<String, List<PunctuationMapEntry>>()

    // moved by every save and forget: a read it overlapped may hold the old table, and is not kept
    private var changes = 0

    fun parseRawConfig(raw: RawConfig): List<PunctuationMapEntry> {
        val items = raw.findByName("cfg")?.findByName(ENTRIES)?.subItems ?: return emptyList()
        return items.map { PunctuationMapEntry(it) }
    }

    suspend fun load(fcitx: FcitxAPI, lang: String): List<PunctuationMapEntry> {
        val (known, seen) = synchronized(this) { kept[lang] to changes }
        if (known != null) return known
        val raw = fcitx.getPunctuationConfig(lang)
        val entries = parseRawConfig(raw)
        synchronized(this) { if (changes == seen) kept[lang] = entries }
        return entries
    }

    suspend fun save(fcitx: FcitxAPI, lang: String, entries: List<PunctuationMapEntry>) {
        val cfg = RawConfig(
            arrayOf(
                RawConfig(ENTRIES, entries.mapIndexed { i, it -> it.toRawConfig(i) }.toTypedArray())
            )
        )
        try {
            fcitx.savePunctuationConfig(lang, cfg)
        } finally {
            // a save cancelled on its way back has reached fcitx all the same
            forget()
        }
    }

    /** Drops what [load] kept, for when fcitx reads its tables again: it starts or reloads its config. */
    fun forget() = synchronized(this) {
        changes++
        kept.clear()
    }

    /**
     * [fcitx] as the daemon hands it to its connections: any reload of its config reads the
     * tables again, so what [load] kept goes with it.
     */
    fun forgettingOnReload(fcitx: FcitxAPI): FcitxAPI = object : FcitxAPI by fcitx {
        override suspend fun reloadConfig() {
            fcitx.reloadConfig()
            forget()
        }
    }

    /** Runs [start], which starts fcitx: it reads its tables as it starts, so what [load] kept goes. */
    inline fun starting(start: () -> Unit) {
        start()
        forget()
    }

    const val ENTRIES = "Entries"
    const val KEY = "Key"
    const val MAPPING = "Mapping"
    const val ALT_MAPPING = "AltMapping"
}
