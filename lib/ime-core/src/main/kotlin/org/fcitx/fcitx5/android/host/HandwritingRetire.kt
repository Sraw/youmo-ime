/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** 0.6.0's 手写 input method, gone: first in a group, pinyin takes its place; elsewhere dropped. Run at every profile read, so old backups too. */
object HandwritingRetire {

    const val OLD = InputMethodCatalog.HANDWRITING_RETIRED

    /** [moved]: pinyin put first where handwriting was. */
    data class Outcome(val items: List<String>, val defaultIm: String, val moved: Boolean)

    /** Null where it has none. */
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

    /** [lost]: handwriting was traditional and nothing took that over. */
    data class Traditional(val enabled: Set<String>, val lost: Boolean)

    /** Null where it has none; where pinyin took handwriting's place ([moved]) it takes its 繁体 too. */
    fun traditional(enabled: Set<String>, moved: Boolean): Traditional? {
        if (OLD !in enabled) return null
        val rest = enabled - OLD
        return if (moved) Traditional(rest + InputMethodCatalog.PINYIN, false) else Traditional(rest, true)
    }
}
