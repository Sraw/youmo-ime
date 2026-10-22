/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearningMarkerTest {
    @Test
    fun oneMarkAtATimeNotHereFirst() {
        assertNull(LearningMarker.of(learnsHere = true, habits = true))
        assertEquals(LearningMarker.HABITS_OFF, LearningMarker.of(learnsHere = true, habits = false))
        assertEquals(LearningMarker.NOT_HERE, LearningMarker.of(learnsHere = false, habits = false))
        assertEquals(LearningMarker.NOT_HERE, LearningMarker.of(learnsHere = false, habits = true))
    }
}
