/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard

/**
 * The keys without which nothing can be typed or put right, and whether a theme leaves one blank: its label
 * hidden and its face no image. Asked of the engine itself ([SceneBuilder.blanks], the cascade that draws the
 * key) on every board the theme can be shown on and in every scene its styles tell apart, so it is what the
 * keyboard would draw, not a guess at it. The app refuses such a pack on import (ThemePackCheck), the theme maker
 * before it saves one (YoumoEngine): one rule, here.
 *
 * An image that shows nothing (all transparent, a body that does not decode) still passes: only its header is
 * read at import, as 3.0 read it.
 */
object BlankKeys {

    /** The keys, by role, a theme must not leave blank. */
    val REQUIRED_ROLES = setOf("shift", "backspace", "space", "enter")

    /** A board the theme may be shown on: the app's own, or a pack's (`layouts/`), with the fragments it includes. */
    class Board(val layout: Layout, val fragments: Map<String, Fragment> = emptyMap())

    /** A key left blank: on [board], its id [key], in [scenes], in [states]. */
    data class Blank(val board: String, val key: String, val scenes: Set<String>, val states: Set<KeyState>) {
        override fun toString() = if (board == "*") "too many scenes together to check" else "$board/$key" + scenes.sorted().joinToString("") { " @$it" } + states.joinToString("") { ":" + it.xml }
    }

    // the scenes of the keyboard's form, each an Env flag; any other a theme names (im=…, field=…) is typed
    private val FORMS = listOf("compact", "landscape", "floating", "split", "one-handed", "dark", "flat")

    /** Said in place of the blanks when a theme's key styles name too many scenes or states together to try them all. */
    val TOO_MANY = Blank("*", "*", emptySet(), emptySet())

    /** Bumped when what is found changes: a pack checked under another rule is checked again (the app's stamp). */
    const val RULE = 3

    // the scene sets tried at most; a theme past it is refused (TOO_MANY), never let through half asked
    private const val MAX_SCENE_SETS = 512

    // at most this many styles decide a key: its face, its words hidden, its icon hidden
    private const val DECIDING = 3

    /**
     * The keys [theme] leaves blank on [boards], the first [limit] found; [TOO_MANY] alone where the scenes to try
     * are too many.
     *
     * Exact, not sampled: the styles that win a key's face and hide its words and icon in some environment win
     * them too in the scenes and states those three styles name together (a narrower environment: whatever beats
     * them there matches in the wider one too). So the scene sets tried are the unions of at most three of the
     * key styles' (those setting `fill` or `hidden`), and the state sets likewise; sets that cannot be at once
     * (two input methods, floating and split, one-handed with either) are not. Each board is asked as the app draws
     * it, its own `@keyboard=` scene on ([Env.board]); a set naming another board's is not asked of it.
     *
     * Stricter than the keyboard, not looser: a required key is tried in every state the styles name, even one its
     * role never enters (Return `:locked`), so a theme hiding it there alone is refused.
     */
    fun find(theme: Theme, boards: List<Board>, limit: Int = 1): List<Blank> {
        val deciding = theme.styles.filter { "fill" in it.values || "hidden" in it.values }
            .flatMap { it.selectors }.filter { it.widget == "key" }
        val sceneSets = unions(deciding.map { it.scenes }.toSet(), ::possible) ?: return listOf(TOO_MANY)
        val stateSets = unions(deciding.map { sel -> sel.states.mapNotNullTo(HashSet()) { KeyState.of(it) } - KeyState.Pressed }.toSet())
            ?: return listOf(TOO_MANY)
        // the versions share their styles (only colours differ): one is asked
        val cascades = Cascades(theme)
        val out = ArrayList<Blank>()
        // the scenes as each board is drawn in them: its own @keyboard= always on, so a set of none and the set of
        // its own are the same scene there, asked once
        val asked = HashSet<Pair<String, Set<String>>>()
        // the keys expanded once a board and form, not once a scene (only its form changes what is expanded)
        val expanded = HashMap<List<Any>, List<ExpandedKey>>()
        for (scenes in sceneSets) {
            val named = scenes.firstOrNull { it.startsWith(KEYBOARD) }?.removePrefix(KEYBOARD)
            val rest = scenes.filterTo(HashSet()) { !it.startsWith(KEYBOARD) }
            for (b in boards) {
                // a board this scene set names not, or its scenes asked already
                if ((named != null && named != b.layout.id) || !asked.add(b.layout.id to rest)) continue
                val builder = SceneBuilder(theme, env(rest, b.layout.id), FixedMeasurer, cascades = cascades)
                for ((key, states) in builder.blanks(b.layout, b.fragments, REQUIRED_ROLES, stateSets, expanded)) {
                    out += Blank(b.layout.id, key.key.id, scenes, states)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    // every union of at most DECIDING of [sets] that is [possible], the empty one first; null past MAX_SCENE_SETS,
    // found as they are made (a theme of many scenes stops here, not after making them all)
    private fun <T> unions(sets: Set<Set<T>>, possible: (Set<T>) -> Boolean = { true }): List<Set<T>>? {
        val each = sets.filter { it.isNotEmpty() && possible(it) }
        val out = linkedSetOf(emptySet<T>())
        var last: List<Set<T>> = listOf(emptySet())
        repeat(DECIDING) {
            val next = ArrayList<Set<T>>()
            for (u in last) {
                for (s in each) {
                    val v = u + s
                    if (possible(v) && out.add(v)) {
                        next += v
                        if (out.size > MAX_SCENE_SETS) return null
                    }
                }
            }
            last = next
        }
        return out.toList()
    }

    // what the app can be in at once: one value of each typed scene; floating, split and one-handed apart
    // (Env.isOneHanded: neither floating nor split; a floating keyboard is never split)
    private fun possible(scenes: Set<String>): Boolean {
        val typed = scenes.filter { '=' in it }
        if (typed.map { it.substringBefore('=') }.toSet().size < typed.size) return false
        return scenes.count { it == "floating" || it == "split" || it == "one-handed" } <= 1
    }

    private const val KEYBOARD = "keyboard="

    private fun env(scenes: Set<String>, board: String) = Env(
        width = WIDTH,
        height = HEIGHT,
        density = DENSITY,
        landscape = "landscape" in scenes,
        compact = "compact" in scenes,
        floating = if ("floating" in scenes) 1.0 else null,
        split = "split" in scenes,
        oneHanded = if ("one-handed" in scenes) SplitKeyboard.Dock.LEFT else SplitKeyboard.Dock.FULL,
        dark = "dark" in scenes,
        flat = "flat" in scenes,
        typed = scenes.filterTo(HashSet()) { it !in FORMS },
        board = board,
    )

    // a phone's keyboard: the sizes do not change what is styled, only that the keys are laid out
    private const val WIDTH = 1080.0
    private const val HEIGHT = 700.0
    private const val DENSITY = 2.75
}
