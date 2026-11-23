/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the cursor goes before the editor's Shift+Ctrl+Home / End, which a real EditText's run shows in KeyShortcutsHostTest. */
class SelectToEdgeTest {

    private fun row(why: String, start: Int, end: Int, toStart: Boolean, at: Int?) =
        "$why: $start..$end to the ${if (toStart) "start" else "end"}" to { assertEquals(at, SelectToEdge.collapseAt(start, end, toStart)) }

    // Shift alone would keep the anchor, the near end where the selection was made towards the far one
    @Test
    @Uat("KB-116")
    fun theCursorIsPutOnTheFarEndOnlyForASelection() = assertRows(
        row("a cursor is the anchor already", 3, 3, toStart = true, at = null),
        row("a cursor is the anchor already", 3, 3, toStart = false, at = null),
        row("made forwards, its end kept", 3, 7, toStart = true, at = 7),
        row("made backwards, its end kept", 7, 3, toStart = true, at = 7),
        row("made forwards, its start kept", 3, 7, toStart = false, at = 3),
        row("made backwards, its start kept", 7, 3, toStart = false, at = 3),
        row("not known: left alone", -1, 3, toStart = false, at = null),
        row("not known: left alone", 3, -1, toStart = true, at = null),
    )
}
