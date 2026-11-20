/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * The last [capacity] values [make] built, keyed by the settings that alone shape them: rebuilding after
 * any setting change was most of a session's creation time. Never stale; [make] must not keep mutable
 * parts of the key. Locked: evaluation threads share them.
 */
internal class SettingsMemo<K : Any, V : Any>(private val capacity: Int, private val make: (K) -> V) {
    private val held = object : LinkedHashMap<K, V>(capacity + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }

    @Synchronized
    operator fun get(key: K): V = held[key] ?: make(key).also { held[key] = it }

    internal val size: Int
        @Synchronized get() = held.size
}
