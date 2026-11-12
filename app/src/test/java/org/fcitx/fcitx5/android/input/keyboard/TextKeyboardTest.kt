/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bubble of a key of the full keyboard: what it commits (a letter's swipe and long press are the resolver's, KeyResolverTest). */
class TextKeyboardTest {

    /** An address's keys commit as they are: under Chinese the full stop's bubble showed 。 while it typed . */
    @Test
    fun anAddressKeysBubbleShowsWhatItCommits() {
        val zh = mapOf("." to "。", "/" to "、")
        val punctuation = { p: String -> zh.getOrDefault(p, p) }
        val shift = { c: String -> c.uppercase() }
        assertEquals(".", previewOf(".", KeyRef.scene("dot", 0), shift, punctuation))
        assertEquals("/", previewOf("/", KeyRef.scene("slash", 0), shift, punctuation))
        assertEquals(".com", previewOf(".com", KeyRef.scene("domain", 0), shift, punctuation))
        // the full stop beside the space is the input method's, and a letter is as Shift has it
        assertEquals("。", previewOf(".", KeyRef.scene("period", 0), shift, punctuation))
        assertEquals("Q", previewOf("q", KeyRef.scene("q", 0), shift, punctuation))
    }
}
