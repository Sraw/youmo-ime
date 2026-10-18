/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneHandedTest {

    @Test
    fun aSideIsRemembered() {
        assertEquals(OneHanded.Left, OneHanded.sideToRemember(OneHanded.Left))
        assertEquals(OneHanded.Right, OneHanded.sideToRemember(OneHanded.Right))
    }

    @Test
    fun offKeepsTheLastSide() {
        assertNull(OneHanded.sideToRemember(OneHanded.Off))
    }
}
