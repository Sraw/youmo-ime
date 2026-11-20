/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/** What table [im]'s keyboard shows: its non-letter code [keys] (二笔's `;',./`, a wildcard), whether 拼 is on, and its [roots] per [labels]. */
data class TableKeyboard(
    val im: String,
    val keys: Set<Char>,
    val lookingUp: Boolean,
    val labels: KeyLabels,
    val roots: TableRoots?,
) {
    val labelled: Boolean get() = labels != KeyLabels.NONE && roots != null

    /** What [letter]'s key shows over it, null for the letter: its name, or its roots in two lines past [ROOTS_A_LINE] (8 on one line were unreadable). */
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

    /** At 紧凑 height two lines of roots are unreadable: names instead, the setting kept. */
    fun atHeight(compact: Boolean): TableKeyboard = if (compact && labels == KeyLabels.ROOTS) copy(labels = KeyLabels.NAMES) else this

    fun rootsOf(letter: Char): List<String>? = if (labels == KeyLabels.ROOTS) roots?.all?.get(letter) else null

    /** Which of [slots] to show beside the letters: one of [keys] the face has nowhere else ([onFace]), or none. */
    fun extraKey(slots: List<Char>, onFace: Set<Char>): Char? = slots.firstOrNull { it in keys && it !in onFace }

    private companion object {
        const val ROOTS_A_LINE = 3
    }
}
