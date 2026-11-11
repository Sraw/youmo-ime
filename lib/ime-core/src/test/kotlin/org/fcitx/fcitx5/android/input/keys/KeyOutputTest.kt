/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyOutputTest {
    @Test
    fun eachCommitHasOneOutput() {
        assertEquals(KeyOutput.Press("."), KeyOutput.of(Item(".")))
        assertEquals(KeyOutput.Press("😀"), KeyOutput.of(Item("😀")))
        assertEquals(KeyOutput.Text(".com"), KeyOutput.of(Item(".com")))
        assertEquals(KeyOutput.Text("."), KeyOutput.of(Item(".", Commit.AsIs)))
        assertEquals(KeyOutput.Keypad('2'), KeyOutput.of(Item("2", Commit.Keypad('2'))))
        assertEquals(KeyOutput.Action(SceneAction.Placeholder("tone-5")), KeyOutput.of(Item("˙", Commit.Action("action:tone-5"))))
        assertNull(KeyOutput.of(Item("x", Commit.Action("layout:"))))
    }
}
