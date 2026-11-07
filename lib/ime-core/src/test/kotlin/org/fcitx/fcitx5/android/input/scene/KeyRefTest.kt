/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRefTest {

    @Test
    fun aViewsKeyIsItsId() {
        assertEquals(42, KeyRef.view(42).viewId)
        assertEquals(KeyRef.view(42), KeyRef.view(42))
        assertNotEquals(KeyRef.view(42), KeyRef.view(43))
    }

    @Test
    fun aViewOfNoIdIsStillAViewsKey() {
        // a picker's keys have no id: View.NO_ID, the same -1 as a scene's viewId
        assertEquals(KeyRef.NO_VIEW, KeyRef.view(-1).viewId)
        assertFalse(KeyRef.view(-1).isScene)
        assertFalse(KeyRef.view(42).isScene)
        assertTrue(KeyRef.scene("q", 0).isScene)
        assertTrue(KeyRef.scene("g", 1).isScene)
    }

    @Test
    fun aScenesKeyIsNoViews() {
        assertEquals(KeyRef.NO_VIEW, KeyRef.scene("q", 0).viewId)
        // a name that only looks like a view's is none either
        assertEquals(KeyRef.NO_VIEW, KeyRef("view:x").viewId)
    }

    @Test
    fun aSplitRowsTwoCopiesAreTwoKeys() {
        assertEquals(KeyRef("g"), KeyRef.scene("g", 0))
        assertEquals(KeyRef("g", copy = true), KeyRef.scene("g", 1))
        assertNotEquals(KeyRef.scene("g", 0), KeyRef.scene("g", 1))
    }
}
