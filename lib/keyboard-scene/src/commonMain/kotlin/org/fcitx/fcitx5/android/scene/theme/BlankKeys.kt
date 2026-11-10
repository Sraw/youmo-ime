/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * The keys without which nothing can be typed or put right, and the styles that would leave them blank: the
 * app refuses such a pack on import (ThemePackCheck), the theme maker before it saves one (YoumoEngine).
 * Here, so the two cannot disagree.
 */
object BlankKeys {

    /** The keys a theme must not hide the labels (`hidden`) of. */
    val REQUIRED_ROLES = setOf("shift", "backspace", "space", "enter")

    // the kinds those keys are of: a selector of another kind (letter, alt-text) never matches one
    private val REQUIRED_KINDS = setOf("function", "space", "enter")

    // what `hidden` hides of a key: its words and icon, not a mark or a hint alone
    private val HIDING_PARTS = setOf(null, "label", "icon")

    // flat draws no faces of function keys by design (3.0's face=null), so a key's image is not asked of it there
    private const val FLAT = "flat"

    /**
     * The lines of the styles that hide the labels of a key of [REQUIRED_ROLES] (or may: `key { hidden }`). A hide
     * aimed at one key by its name or role whose resting face, the fill that wins the cascade on each board it is
     * named on and in each scene the theme styles, is an image is not: the image is what the key shows, as theme
     * 3.0 drew a key with an image and its label hidden, and a 3.0 theme the app takes must stay one it takes once
     * converted. An image that shows nothing (all transparent, a body that does not decode) still passes: only its
     * header is read at import, as 3.0 read it.
     */
    fun hidesRequired(theme: Theme): List<Int> = theme.styles.filter { style ->
        (style.values["hidden"] as? Value.Flag)?.on == true && style.selectors.any { s ->
            s.widget == "key" && s.reserved == null && s.part in HIDING_PARTS && s.classes.all { it in REQUIRED_KINDS } &&
                s.roles.all { it in REQUIRED_ROLES } && (s.starRole == null || s.starRole in REQUIRED_ROLES) &&
                (s.key == null || s.key in REQUIRED_ROLES) && !imageFaced(theme, s)
        }
    }.map { it.line }

    // [s] names one key, and on every board, scene and kind it can be in, the fill winning the cascade for its
    // resting face is an image
    private fun imageFaced(theme: Theme, s: Selector): Boolean {
        val name = s.key ?: s.starRole ?: return false
        val selectors = theme.styles.flatMap { it.selectors }
        val boards = if (s.board != null) setOf(s.board) else selectors.filter { it.key == name }.mapNotNull { it.board }.toSet() + ANY_BOARD
        val scenes = listOf(emptySet<String>()) + selectors.flatMap { it.scenes }.toSet().minus(FLAT).map { setOf(it) }
        return boards.all { board ->
            scenes.all { scene ->
                REQUIRED_KINDS.all { kind -> restingFill(theme, Selector.Target("key", board, name, name, setOf(kind)), scene) is Fill.Image }
            }
        }
    }

    // a board no style names: the key as every board but those has it
    private const val ANY_BOARD = "\u0000"

    private fun restingFill(theme: Theme, target: Selector.Target, scene: Set<String>): Fill? = theme.styles
        .mapNotNull { style ->
            val fill = (style.values["fill"] as? Value.OfFill)?.fill ?: return@mapNotNull null
            style.selectors.filter { it.selects(target) && scene.containsAll(it.scenes) && it.reserved == null }
                .maxOfOrNull { it.specificity }?.let { Triple(it, style.order, fill) }
        }
        .maxWithOrNull(compareBy({ it.first }, { it.second }))?.third
}
