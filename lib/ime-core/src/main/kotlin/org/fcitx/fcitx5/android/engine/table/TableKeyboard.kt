/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/**
 * What table [im]'s letters keyboard shows of it, the engine's to say (Engines.tableKeyboard): the
 * [keys] of its codes that are no letter (二笔's `;',./`, a wildcard `*`), whether its 拼 key is on
 * ([lookingUp], [org.fcitx.fcitx5.android.engine.session.Action.LookUp]), and its [roots] on the
 * letters as [labels] says.
 */
data class TableKeyboard(
    val im: String,
    val keys: Set<Char>,
    val lookingUp: Boolean,
    val labels: KeyLabels,
    val roots: TableRoots?,
) {
    /** Whether the letters show roots ([label]): their corner then shows the letter, not its swipe. */
    val labelled: Boolean get() = labels != KeyLabels.NONE && roots != null

    /**
     * What [letter]'s key shows over it, null for the letter itself: its name, or all its roots, more
     * than [ROOTS_A_LINE] in two lines (one, 8 of them were too small to read on a phone's key).
     */
    fun label(letter: Char): String? {
        val roots = roots ?: return null
        return when (labels) {
            KeyLabels.NONE -> null
            KeyLabels.NAMES -> roots.names[letter]
            KeyLabels.ROOTS -> roots.all[letter]?.let { all ->
                if (all.size <= ROOTS_A_LINE) all.joinToString("") else all.chunked((all.size + 1) / 2).joinToString("\n") { it.joinToString("") }
            }
        }
    }

    /** What [letter]'s long press shows instead of its symbols, large: all its roots, while the keys show them. */
    fun rootsOf(letter: Char): List<String>? = if (labels == KeyLabels.ROOTS) roots?.all?.get(letter) else null

    /**
     * Which of the keyboard's [slots] (the keys it may show beside the letters, first wanted first)
     * to show: one of [keys] the face has nowhere else ([onFace]: the bottom row's, and the letters'
     * corners where they show their swipes, a swipe typing it), or none.
     */
    fun extraKey(slots: List<Char>, onFace: Set<Char>): Char? = slots.firstOrNull { it in keys && it !in onFace }

    private companion object {
        const val ROOTS_A_LINE = 3
    }
}
