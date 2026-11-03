/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharCardLayoutTest {

    @Test
    fun twoColumnsDockedInLandscapeAlone() {
        assertTrue(CharCardLayout.twoColumns(landscape = true, floating = false))
        assertFalse(CharCardLayout.twoColumns(landscape = true, floating = true))
        assertFalse(CharCardLayout.twoColumns(landscape = false, floating = false))
        assertFalse(CharCardLayout.twoColumns(landscape = false, floating = true))
        assertEquals(3, CharCardLayout.readingRows(true))
        assertEquals(4, CharCardLayout.readingRows(false))
    }

    @Test
    fun halfTheScreenOrTheLeast() {
        assertEquals(400, CharCardLayout.width(800, 320))
        assertEquals(320, CharCardLayout.width(500, 320))
        assertEquals(600, CharCardLayout.width(900, 320, twoColumns = true))
    }
}
