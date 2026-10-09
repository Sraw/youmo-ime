/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/** The phrase and table editors save only when this says they changed; a missed change is lost from disk. */
class NaiveDustmanTest {

    @Test
    fun anEntryDeletedSavedAndAddedBackIsAChange() {
        val dustman = NaiveDustman<String>()
        dustman.reset(mapOf("a" to "A"))
        dustman.remove("a")
        assertTrue(dustman.dirty)
        // saved without it
        dustman.reset(emptyMap())
        dustman.addOrUpdate("a", "A")
        assertTrue(dustman.dirty)
    }
}
