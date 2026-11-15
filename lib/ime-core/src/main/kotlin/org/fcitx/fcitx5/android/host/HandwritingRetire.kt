/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/**
 * 0.6.0's 手写 (`engine-handwriting`), an input method of its own, gone in 0.6.1: handwriting is the
 * toolbar's 手写, written over the letters of whichever input method is on (061-handwriting-toolbar §6).
 * Where it was first of a group, pinyin takes its place (moved there, or added where it was not on), so
 * the keyboard a user opens to is a Chinese one still; elsewhere it is dropped. Run at every read of the
 * profile ([Profile.retireHandwriting]), as [NineKeysMerge]: a 0.6.0 backup restored is read the same way.
 */
object HandwritingRetire {

    /** 0.6.0's handwriting input method, as a user's profile may still name it. */
    const val OLD = InputMethodCatalog.HANDWRITING_RETIRED

    /** A group after it: its [items] and [defaultIm]; [moved], pinyin put first where handwriting was. */
    data class Outcome(val items: List<String>, val defaultIm: String, val moved: Boolean)

    /** The group of [items] and [defaultIm] without [OLD]; null where it has none. */
    fun group(items: List<String>, defaultIm: String): Outcome? {
        if (OLD !in items) return null
        val moved = items.first() == OLD
        val rest = items.filter { it != OLD }
        val out = when {
            moved -> listOf(InputMethodCatalog.PINYIN) + rest.filter { it != InputMethodCatalog.PINYIN }
            rest.isEmpty() -> listOf(InputMethodCatalog.PINYIN)
            else -> rest
        }
        // the default by ImGroup's rule again: one that was handwriting falls to the second
        val default = ImGroup.of("", out, defaultIm.takeIf { it != OLD }.orEmpty()).defaultInputMethod
        return Outcome(out, default, moved)
    }

    /** chttrans' `EnabledIM` without [OLD] ([enabled]); [lost], handwriting was turned traditional and nothing took that over. */
    data class Traditional(val enabled: Set<String>, val lost: Boolean)

    /**
     * chttrans' list [enabled] without [OLD] (no input method of that name any more); null where it has none.
     * Where pinyin took handwriting's first place ([moved]) it takes its 繁体 too, the user writing on the same
     * first keyboard as before (the user's Q12); elsewhere what is written follows the input method written over.
     */
    fun traditional(enabled: Set<String>, moved: Boolean): Traditional? {
        if (OLD !in enabled) return null
        val rest = enabled - OLD
        return if (moved) Traditional(rest + InputMethodCatalog.PINYIN, false) else Traditional(rest, true)
    }
}
