/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/**
 * The full keyboard's Shift while pinyin or 双拼 is typed (0.6.1): 分词, as the 14 and 18 keys' own key and the
 * nine keys' 1 are then, typing pinyin's syllable separator ([TYPED]); Shift again once nothing is typed, in the
 * state it was left in. Not English's, a table's (五笔…), nor 注音's (a keyboard of its own, with its own 分词).
 */
object ShiftSeparator {
    /** What it types: pinyin's syllable separator, as the 14 keys' 分词 sends it. */
    const val TYPED = "'"

    // the input methods whose syllables `'` splits on the full keyboard
    private val SPLITTING = setOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN)

    /**
     * Whether Shift is 分词 now: [shown], the keyboard's input method, is pinyin or 双拼, and the engine's last answer
     * (EngineState.syllables: its input method [answered]) has something [composing]. Another input method's answer
     * is none of this one's typing (a switch tells the old one's and the new one's at once).
     */
    fun on(shown: String, answered: String, composing: Boolean): Boolean = composing && answered == shown && shown in SPLITTING
}
