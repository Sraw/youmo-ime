/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputViewShownTest {

    @Test
    fun aShowTellsTheViewVisibleOnlyAfterAHideTheWindowStayedThrough() = assertRows(
        "a hide the window stayed through is undone once" to {
            val s = InputViewShown()
            s.hidden()
            assertTrue(s.shown(windowVisible = true))
            assertFalse("told once", s.shown(windowVisible = true))
        },
        "a window that went away tells itself" to {
            val s = InputViewShown()
            s.hidden()
            assertFalse(s.shown(windowVisible = false))
            assertFalse(s.shown(windowVisible = true))
        },
        // the view was never told GONE
        "a show without a hide tells nothing (another field of the same app)" to {
            assertFalse(InputViewShown().shown(windowVisible = true))
        },
    )
}
