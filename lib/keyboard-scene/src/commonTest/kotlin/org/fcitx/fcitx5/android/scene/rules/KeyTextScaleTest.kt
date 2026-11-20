/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyTextScaleTest {

    @Test
    fun compactFloatingOrBothTakeTheSmaller() {
        assertEquals(1.0, KeyTextScale.of(compact = false, floating = null))
        assertEquals(0.9, KeyTextScale.of(compact = true, floating = null))
        assertEquals(0.85, KeyTextScale.of(compact = false, floating = 0.85))
        // the smaller, not 0.765
        assertEquals(0.85, KeyTextScale.of(compact = true, floating = 0.85))
        assertEquals(0.9, KeyTextScale.of(compact = true, floating = 0.95))
    }
}
