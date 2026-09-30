/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TransformClipboardTextTest {

    @Test
    fun withNothingEnabledTheTextPassesThrough() {
        assertEquals("text", transformClipboardText("text", null, null))
    }

    @Test
    fun urlCleaningRunsBeforeIpcTransformers() {
        assertEquals("[clean]", transformClipboardText("dirty", { "clean" }, { "[$it]" }))
    }

    @Test
    fun aThrowingCleanerFallsBackToTheOriginalText() {
        assertEquals("[dirty]", transformClipboardText("dirty", { error("bad rule") }, { "[$it]" }))
    }
}
