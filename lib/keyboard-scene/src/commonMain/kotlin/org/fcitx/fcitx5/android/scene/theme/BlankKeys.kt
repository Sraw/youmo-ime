/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.engine.Env
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
        override fun toString() = "$board/$key" + scenes.sorted().joinToString("") { " @$it" } + states.joinToString("") { ":" + it.xml }
    }

    // the scenes of the keyboard's form, each an Env flag; any other a theme names (im=…, field=…) is typed
    private val FORMS = listOf("compact", "landscape", "floating", "split", "one-handed", "dark", "flat")

    // the scene sets tried at most: every set of the scenes a theme names, up to this many of them; past it the
    // sets of at most MAX_TOGETHER (a crafted theme of many scenes must not make an import take minutes)
    private const val MAX_ALL = 8
    private const val MAX_TOGETHER = 3

    /** The keys [theme] leaves blank on [boards], the first [limit] found. */
    fun find(theme: Theme, boards: List<Board>, limit: Int = 1): List<Blank> {
        // the versions share their styles (only colours differ): one is asked
        val cascades = Cascades(theme)
        val out = ArrayList<Blank>()
        for (scenes in sceneSets(theme)) {
            val env = env(scenes)
            val builder = SceneBuilder(theme, env, FixedMeasurer, cascades = cascades)
            for (b in boards) {
                for ((key, states) in builder.blanks(b.layout, b.fragments, REQUIRED_ROLES)) {
                    out += Blank(b.layout.id, key.key.id, scenes, states)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    // every set of the scenes [theme]'s selectors name (none among them)
    private fun sceneSets(theme: Theme): List<Set<String>> {
        val named = theme.styles.flatMap { s -> s.selectors.flatMap { it.scenes } }.toSet().sorted()
        val most = if (named.size <= MAX_ALL) named.size else MAX_TOGETHER
        var sets = listOf(emptySet<String>())
        for (n in named) sets = sets + sets.filter { it.size < most }.map { it + n }
        return sets
    }

    private fun env(scenes: Set<String>) = Env(
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
    )

    // a phone's keyboard: the sizes do not change what is styled, only that the keys are laid out
    private const val WIDTH = 1080.0
    private const val HEIGHT = 700.0
    private const val DENSITY = 2.75
}
