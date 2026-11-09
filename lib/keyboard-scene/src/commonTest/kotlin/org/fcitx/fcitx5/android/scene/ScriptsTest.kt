/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.Scripts
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
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
    fun aSpaceWithNoNameIsStillSaid() {
        // an input method with no name (or a scene built before it is known): TalkBack says "Space", not nothing
        assertEquals("a11y_key_space", Expand.spoken(KeyAttrs(role = "space"), false, "", null, Texts()))
        assertEquals("a11y_key_space", Expand.spoken(KeyAttrs(role = "space"), false, null, null, Texts()))
        assertEquals("Space, 拼音", Expand.spoken(KeyAttrs(role = "space"), false, "拼音", null, Texts()))
    }
}
