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
 * A layout's letter groups as the engine reads a grouped keyboard: exactly the built-in groups in any order is the
 * built-in [NineKeyLetters] (codes and learning unchanged); another grouping of a to z is one of its own.
 */
object SceneGroups {
    /** An included fragment's where it is included; a key's own letters, whatever its condition. */
    fun of(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): List<String> =
        keys(layout, fragments).mapNotNull { it.letters?.group }

    /** Each its rows apart by a space (`pq rs`): what [letters] takes for where a tap falls. */
    fun written(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): List<String> =
        keys(layout, fragments).mapNotNull { it.letters?.rows?.joinToString(" ") }

    /** Which key each group is, where two keys have the same letters. */
    fun ids(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): List<String> =
        keys(layout, fragments).filter { it.letters != null }.map { it.id }

    /** Groups of two letters or more whose `lean=` is not off; whether the engine places taps is [Keyboard.placesTaps][org.fcitx.fcitx5.android.engine.runtime.Keyboard.placesTaps]. */
    fun leaning(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): Set<String> =
        keys(layout, fragments).filter(Key::leans).mapNotNull { it.letters?.group }.toSet()

    /** With no layout read: the 14 and 18 keys' two-letter groups (the engine does not place 双拼's 18-key taps, [org.fcitx.fcitx5.android.engine.runtime.Keyboard.pinyin18Tap]). */
    fun builtInLeaning(letters: NineKeyLetters): Set<String> =
        if (letters == NineKeyLetters.G14 || letters == NineKeyLetters.G18) letters.tag().split(',').filter { it.length >= 2 }.toSet() else emptySet()

    /** A layout of other groups ([mismatch]) or none read: [builtInLeaning]. */
    fun leaningOf(letters: NineKeyLetters, layout: Layout?, fragments: Map<String, Fragment> = emptyMap()): Set<String> =
        if (layout == null || mismatch(of(layout, fragments), letters) != null) builtInLeaning(letters) else leaning(layout, fragments)

    private fun keys(layout: Layout, fragments: Map<String, Fragment>): List<Key> = layout.parts.flatMap { part ->
        when (part) {
            is Row -> listOf(part)
            is Include -> fragments[part.src]?.rows.orEmpty()
        }
    }.flatMap { row -> row.cells.filterIsInstance<Key>() }

    /** Null where they make none ([NineKeyLetters.problem]); pure: not in use till the session says so ([NineKeyLetters.inUse]). */
    fun letters(groups: List<String>): NineKeyLetters? = if (NineKeyLetters.problem(groups) != null) null else NineKeyLetters.custom(groups)

    sealed interface Taken {
        /** Its codes, record and wiring as they always were. */
        data object BuiltIn : Taken

        /** Typed as pinyin's own keyboard. */
        class Own(val letters: NineKeyLetters, val written: List<String>) : Taken

        /** The layout gone by whole, the built-in keyboard drawn and typed. */
        class Refused(val why: String) : Taken
    }

    /** [own]: pinyin's keyboards, which a layout's own keyboard types on; else [Taken.Refused] with [mismatch]'s reason. */
    fun taken(layout: Layout, expected: NineKeyLetters, own: Boolean, fragments: Map<String, Fragment> = emptyMap()): Taken {
        val groups = of(layout, fragments)
        val why = mismatch(groups, expected) ?: return Taken.BuiltIn
        val made = letters(groups)
        return when {
            made == null || made == NineKeyLetters.G14 || made == NineKeyLetters.G18 -> Taken.Refused(why)
            !own -> Taken.Refused("$why (a layout's own groups are pinyin's alone)")
            else -> written(layout, fragments).let { Taken.Own(NineKeyLetters.custom(it), it) }
        }
    }

    /** Null where they are its groups in any order; else why (the keyboard then goes by [expected]). */
    fun mismatch(groups: List<String>, expected: NineKeyLetters): String? {
        val made = letters(groups)
        return when {
            made == expected -> null
            made == null -> "the layout's groups ${groups.joinToString(",")} make no keyboard (${NineKeyLetters.problem(groups)}); ${expected.tag()} kept"
            else -> "the layout's groups are ${made.tag()}, the keyboard's ${expected.tag()}; ${expected.tag()} kept"
        }
    }
}
