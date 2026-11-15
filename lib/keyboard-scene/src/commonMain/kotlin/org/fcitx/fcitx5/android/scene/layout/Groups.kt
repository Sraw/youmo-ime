/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

/**
 * What a grouped keyboard's letters must be (ime-core's NineKeyLetters.problem, moved here so the layout check
 * asks it on the phone and in the layout maker alike: 061-custom-layout §3.1).
 */
object Groups {
    /** The most letters a key types. */
    const val MAX = 5

    /**
     * The most groups a keyboard of its own has: its codes' block in ime-core (NineKeyLetters.CUSTOM_BLOCK), and the
     * product's limit past it (§3.3: 26 letters need no more).
     */
    const val CUSTOM_MAX = 30

    /**
     * What is wrong with [groups] (each a key's letters, rows apart by a space) for a keyboard of their own: a to z each
     * on some key (ü as v), a key 1 to [MAX] letters of a to z and ;, none twice, at most [CUSTOM_MAX] keys; null where
     * nothing is.
     */
    @Suppress("ReturnCount")
    fun problem(groups: List<String>): String? {
        if (groups.isEmpty() || groups.size > CUSTOM_MAX) return "${groups.size} keys, not 1 to $CUSTOM_MAX"
        for (g in groups) {
            val letters = g.replace(" ", "")
            if (letters.isEmpty() || letters.length > MAX) return "\"$g\" is not 1 to $MAX letters"
            if (letters.any { it !in 'a'..'z' && it != ';' } || g.split(' ').any { it.isEmpty() }) return "\"$g\" is not letters in rows"
            if (letters.toSet().size != letters.length) return "\"$g\" has a letter twice"
        }
        val missing = ('a'..'z').filter { c -> groups.none { c in it } }
        return if (missing.isEmpty()) null else "no key types ${missing.joinToString("")}"
    }
}
