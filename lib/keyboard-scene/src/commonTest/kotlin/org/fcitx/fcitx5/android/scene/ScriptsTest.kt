/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Scripts
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptsTest {

    private fun runs(text: String) = Scripts.runs(text, "key", "han", "latin")

    @Test
    fun aMarkStaysWithTheCharacterItChanges() {
        // a keycap emoji: 1, a variation selector, the enclosing keycap; split, the keycap is drawn apart from its digit
        assertEquals(listOf("1️⃣" to "latin"), runs("1️⃣"))
        // e and a combining acute: one glyph, not an e and a lone accent in the key's font
        assertEquals(listOf("é" to "latin"), runs("é"))
        // an ideograph's variant: the selector stays with it, in the Han font
        assertEquals(listOf("葛󠄀" to "han"), runs("葛󠄀"))
        // a mark between scripts goes with the one before; the next script still starts its own run
        assertEquals(listOf("á" to "latin", "拼" to "han"), runs("á拼"))
    }

    @Test
    fun theFullWidthBlocksHalfWidthKanaAndHangulAreNotHan() {
        // ！Ａ and ｢ (a half-width CJK bracket) are a CJK font's; ｶ and ﾡ (half-width katakana, Hangul) are not (F6589)
        assertEquals(Scripts.Script.Han, Scripts.of(0xFF01))
        assertEquals(Scripts.Script.Han, Scripts.of(0xFF21))
        assertEquals(Scripts.Script.Han, Scripts.of(0xFF62))
        assertEquals(Scripts.Script.Han, Scripts.of(0xFFE5))
        assertEquals(Scripts.Script.Other, Scripts.of(0xFF76))
        assertEquals(Scripts.Script.Other, Scripts.of(0xFFA1))
    }
}
