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
 * The groups are collected the one way whatever the layout ([of]); the keyboard they make is the
 * built-in one with exactly those groups ([letters]: the 14 keys' G14, the 18 keys' G18), so neither
 * codes nor what was learnt change; any other grouping that covers a to z (a layout's own 17 keys)
 * one of its own ([NineKeyLetters.custom]), its letters' rows as written ([written]).
 */
object SceneGroups {
    /** [layout]'s groups, row by row as written, an included fragment's where it is included; a key's own letters, whatever its condition. */
    fun of(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): List<String> =
        keys(layout, fragments).mapNotNull { it.letters?.group }

    /** [layout]'s groups as [of] has them, each its rows apart by a space as written (`pq rs`): what [letters] takes for where a tap falls. */
    fun written(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): List<String> =
        keys(layout, fragments).mapNotNull { it.letters?.rows?.joinToString(" ") }

    /**
     * [layout]'s groups whose tap leans to the letter it fell nearest (their keys' `lean=`, 按落点加权,
     * 060-render-v2 §15): two letters or more, lean= not off. Whether the engine can take a tap's place
     * for the group's code is the engine's ([Keyboard.placesTaps][org.fcitx.fcitx5.android.engine.runtime.Keyboard.placesTaps]).
     */
    fun leaning(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): Set<String> =
        keys(layout, fragments).filter(Key::leans).mapNotNull { it.letters?.group }.toSet()

    /**
     * The groups whose tap leans on [letters]' keyboard as built in, its layout gone by or none read:
     * the 14 keys' groups of two letters, the 18 keys' none.
     */
    fun builtInLeaning(letters: NineKeyLetters): Set<String> =
        if (letters == NineKeyLetters.G14) letters.tag().split(',').filter { it.length >= 2 }.toSet() else emptySet()

    /**
     * The groups whose tap leans on [letters]' keyboard: its [layout]'s ([leaning]) where its groups are
     * the keyboard's; a layout of other groups is gone by ([mismatch]), and so is its lean=, as is one
     * not read (null): [builtInLeaning].
     */
    fun leaningOf(letters: NineKeyLetters, layout: Layout?, fragments: Map<String, Fragment> = emptyMap()): Set<String> =
        if (layout == null || mismatch(of(layout, fragments), letters) != null) builtInLeaning(letters) else leaning(layout, fragments)

    // [layout]'s keys row by row as written, an included fragment's where it is included
    private fun keys(layout: Layout, fragments: Map<String, Fragment>): List<Key> = layout.parts.flatMap { part ->
        when (part) {
            is Row -> listOf(part)
            is Include -> fragments[part.src]?.rows.orEmpty()
        }
    }.flatMap { row -> row.cells.filterIsInstance<Key>() }

    /**
     * The grouped keyboard [groups] make ([NineKeyLetters.custom]): the built-in one with exactly these
     * letters, else one of their own; null where they make none ([NineKeyLetters.problem]). Pure: it is
     * not the keyboard in use till the session typing on it says so ([NineKeyLetters.inUse]). Rows or
     * not ([of], [written]), the same keyboard (its keys and codes).
     */
    fun letters(groups: List<String>): NineKeyLetters? = if (NineKeyLetters.problem(groups) != null) null else NineKeyLetters.custom(groups)

    /** How a grouped keyboard takes the layout it shows ([taken]). */
    sealed interface Taken {
        /** The layout's groups are the keyboard's own ([taken]'s expected): its codes, record and wiring as they always were. */
        data object BuiltIn : Taken

        /** A layout's own groups ([NineKeyLetters.custom] of [written], rows kept), typed as such: pinyin's own keyboard. */
        class Own(val letters: NineKeyLetters, val written: List<String>) : Taken

        /** Groups the keyboard cannot type, [why]: the layout gone by whole, the keyboard's built-in one drawn and typed. */
        class Refused(val why: String) : Taken
    }

    /**
     * How the grouped keyboard read as [expected] (G14, G18) takes [layout] (060-render-p3 T9b): its own
     * groups ([Taken.BuiltIn]); a grouping of its own where [own] (pinyin's keyboards, which a layout's
     * own keyboard types on) and the groups make one other than the built-in ones ([Taken.Own]); otherwise
     * [Taken.Refused] with [mismatch]'s reason: groups that make no keyboard, the other built-in one's,
     * any of 双拼's keyboards.
     */
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

    /**
     * What is wrong with [groups] for a keyboard the engine reads as [expected]: null where they are its
     * groups, in order; otherwise why (the keyboard then goes by [expected], its codes and record as they were).
     */
    fun mismatch(groups: List<String>, expected: NineKeyLetters): String? {
        val made = letters(groups)
        return when {
            made == expected -> null
            made == null -> "the layout's groups ${groups.joinToString(",")} make no keyboard (${NineKeyLetters.problem(groups)}); ${expected.tag()} kept"
            else -> "the layout's groups are ${made.tag()}, the keyboard's ${expected.tag()}; ${expected.tag()} kept"
        }
    }
}
