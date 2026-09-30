/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanClipboardTextTest {

    @Test
    fun withoutRulesTheTextPassesThrough() {
        assertEquals("dirty", cleanClipboardText("dirty", null))
    }

    @Test
    fun theCleanerIsApplied() {
        assertEquals("clean", cleanClipboardText("dirty") { "clean" })
    }

    @Test
    fun aThrowingCleanerFallsBackToTheOriginalText() {
        assertEquals("dirty", cleanClipboardText("dirty") { error("bad rule") })
    }
}
