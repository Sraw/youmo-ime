/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParityFileTest {
    @Test
    fun aStepIsWrittenAsItsOpAndArgument() {
        assertEquals("type nihao", Step("type", "nihao").toString())
        assertEquals("space", Step("space").toString())
    }

    // a sequence ParityReplay could only half run is refused as it is made, not partway through the replay
    @Test
    fun whatNoDeviceRunCouldHaveDoneIsRefused() = assertRows(
        "type needs the keys" to { refused { Step("type") } },
        "space takes no argument" to { refused { Step("space", "1") } },
        "no such op" to { refused { Step("swipe", "a") } },
        "no such field kind: the device tool's editor had these four only" to { refused { Sequence("s", "pinyin", "phone", listOf()) } },
    )

    private fun refused(make: () -> Any) {
        assertThrows(IllegalArgumentException::class.java) { make() }
    }

    @Test
    fun aPasswordIsMaskedACharacterAPoint() {
        assertEquals("•••", State.mask("a😀b"))
    }
}
