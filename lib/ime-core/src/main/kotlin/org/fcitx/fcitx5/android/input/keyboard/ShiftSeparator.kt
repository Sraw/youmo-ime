/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** The full keyboard's Shift is 分词 while pinyin or 双拼 is typed ([TYPED]); Shift again once nothing is typed. Not English, tables or 注音. */
object ShiftSeparator {
    /** As the 14 keys' 分词 sends it. */
    const val TYPED = "'"

    // the input methods whose syllables `'` splits on the full keyboard
    private val SPLITTING = setOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN)

    /** Another input method's answer is none of this one's typing (a switch tells both at once). */
    fun on(shown: String, answered: String, composing: Boolean): Boolean = composing && answered == shown && shown in SPLITTING
}
