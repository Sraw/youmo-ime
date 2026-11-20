/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clip too big for the clipboard service once reached the crash handler, killing the keyboard's process too. */
class ClipboardEditActivityTest {

    @Test
    fun onlyAClipTheClipboardRefusesIsHandedOnNotThrown() {
        val refused = mutableListOf<RuntimeException>()
        // what ClipboardManager.setPrimaryClip rethrows a TransactionTooLargeException as
        val tooBig = RuntimeException("data parcel size 2000000 bytes")
        copyingClip({ refused += it }) { throw tooBig }
        assertEquals(listOf(tooBig), refused)
        var copied = false
        copyingClip({ refused += it }) { copied = true }
        assertTrue(copied)
        assertEquals(listOf(tooBig), refused)
    }
}
