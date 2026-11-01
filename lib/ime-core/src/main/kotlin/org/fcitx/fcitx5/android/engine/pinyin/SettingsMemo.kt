/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * The last [capacity] tables [make] made, by the settings that shape them: a segmenter's tables
 * depend on those alone (and the syllables, fixed), yet every session made anew after any setting
 * changed made them again, most of the time it took (S15 N2: [SpellingIndex] 78%, [T9Segmenter]
 * 19%). Never stale, as the key is all a table is made of: nothing to drop. [make] may keep of the
 * key it is given only what never changes (T9Segmenter's NineKeyLetters, a value), and must not
 * change it. Locked: the evaluation's threads share them.
 */
internal class SettingsMemo<K : Any, V : Any>(private val capacity: Int, private val make: (K) -> V) {
    private val held = object : LinkedHashMap<K, V>(capacity + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }

    /** What [make] makes of [key]: the one made before, if it is still held. */
    @Synchronized
    operator fun get(key: K): V = held[key] ?: make(key).also { held[key] = it }

    /** How many are held. */
    internal val size: Int
        @Synchronized get() = held.size
}
