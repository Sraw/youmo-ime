/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.theme.SoundKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** 060-theme-sounds-icons §1.1: which `<sound key>` class a key plays. */
class SoundKeyTest {

    @Test
    fun soundKeys() {
        assertEquals(SoundKey.SPACE, SoundKey.of("space", "function"))
        assertEquals(SoundKey.BACKSPACE, SoundKey.of("delete", "function"))
        assertEquals(SoundKey.ENTER, SoundKey.of("return", "letter"))
        assertEquals(SoundKey.ENTER, SoundKey.of("standard", "enter"))
        assertEquals(SoundKey.SPACE, SoundKey.of("standard", "space"))
        assertEquals(SoundKey.FUNCTION, SoundKey.of("standard", "function"))
        assertEquals(SoundKey.LETTER, SoundKey.of("standard", "letter"))
        assertEquals(SoundKey.LETTER, SoundKey.of("standard", "anything"))
        assertEquals(SoundKey.BACKSPACE, SoundKey.of("backspace"))
        assertEquals(null, SoundKey.of("future"))
    }
}
