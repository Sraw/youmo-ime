/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.Row

/**
 * A layout's letter groups (its keys' `letters=`, 060-render-v2 §15) as the engine reads a grouped
 * keyboard: [NineKeyLetters], whose codes, learning record and wiring a keyboard's taps go by.
 *
 * The groups are collected the one way whatever the layout ([of]); which keyboard they make is the
 * built-in one with exactly those groups ([letters]: the 14 keys' G14, the 18 keys' G18), so neither
 * codes nor what was learnt change. Any other grouping (a layout's own 17 keys) makes none yet: codes
 * of its own, its engine settings and its record are P3's.
 */
object SceneGroups {
    /** [layout]'s groups, row by row as written, an included fragment's where it is included; a key's own letters, whatever its condition. */
    fun of(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): List<String> =
        keys(layout, fragments).mapNotNull { it.letters?.group }

    /**
     * [layout]'s groups whose tap leans to the letter it fell nearest (their keys' `lean=`, 按落点加权,
     * 060-render-v2 §15): two letters or more, lean= not off. Whether the engine can take a tap's place
     * for the group's code is the engine's ([Keyboard.placesTaps][org.fcitx.fcitx5.android.engine.runtime.Keyboard.placesTaps]).
     */
    fun leaning(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): Set<String> =
        keys(layout, fragments).filter(Key::leans).mapNotNull { it.letters?.group }.toSet()

    // [layout]'s keys row by row as written, an included fragment's where it is included
    private fun keys(layout: Layout, fragments: Map<String, Fragment>): List<Key> = layout.parts.flatMap { part ->
        when (part) {
            is Row -> listOf(part)
            is Include -> fragments[part.src]?.rows.orEmpty()
        }
    }.flatMap { row -> row.cells.filterIsInstance<Key>() }

    /** The grouped keyboard [groups] make: the built-in one with exactly these; null for none, or any other (P3). */
    fun letters(groups: List<String>): NineKeyLetters? = if (groups.isEmpty()) null else NineKeyLetters.grouped(groups.joinToString(","))

    /**
     * What is wrong with [groups] for a keyboard the engine reads as [expected]: null where they are its
     * groups, in order; otherwise why (the keyboard then goes by [expected], its codes and record as they were).
     */
    fun mismatch(groups: List<String>, expected: NineKeyLetters): String? {
        val made = letters(groups)
        return when {
            made == expected -> null
            made == null -> "the layout's groups ${groups.joinToString(",")} are no grouped keyboard the engine has (P3); ${expected.tag()} kept"
            else -> "the layout's groups are ${made.tag()}, the keyboard's ${expected.tag()}; ${expected.tag()} kept"
        }
    }
}
