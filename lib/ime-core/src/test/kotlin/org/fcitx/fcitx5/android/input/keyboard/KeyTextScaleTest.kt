/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTextScaleTest {

    @Test
    fun compactFloatingOrBothTakeTheSmaller() {
        assertEquals(1f, KeyTextScale.of(compact = false, floating = null), 0f)
        assertEquals(0.9f, KeyTextScale.of(compact = true, floating = null), 0f)
        assertEquals(0.85f, KeyTextScale.of(compact = false, floating = 0.85f), 0f)
        // the smaller, not 0.765
        assertEquals(0.85f, KeyTextScale.of(compact = true, floating = 0.85f), 0f)
        assertEquals(0.9f, KeyTextScale.of(compact = true, floating = 0.95f), 0f)
    }

    @Test
    fun dachenIsSmallerAgainOnlyWhenCompact() {
        assertEquals(1f, KeyTextScale.dachen(compact = false), 0f)
        // 20 × 0.9 × 0.9: 注音九键's 18 × 0.9
        assertEquals(18f * 0.9f, 20f * KeyTextScale.dachen(compact = true) * KeyTextScale.of(compact = true, floating = null), 0.01f)
    }
}
