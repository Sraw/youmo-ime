/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.junit.Assert.assertEquals
import org.junit.Test

class LearnedItemsStrokesTest {

    private fun strokes(keys: String) = Code(Kinds.table(StrokeLookup.BLOCK_TABLE), keys)

    // a character blocked under the strokes typed (笔画查字) is named as a strokes block, not as a table's
    @Test
    fun aStrokesBlockIsNamedSo() {
        assertEquals(R.string.learning_kind_strokes, LearnedItemsFragment.kind(strokes("hs")))
    }

    // its keys are shown as the strokes the keys draw; a record with anything else in it (hand-edited, damaged)
    // is shown as stored rather than failing the whole list
    @Test
    fun aStrokesBlocksKeysAreShownAsStrokes() {
        assertEquals("一丨丿丶乛", LearnedItemsFragment.keys(strokes("hspnz")))
        assertEquals("hx", LearnedItemsFragment.keys(strokes("hx")))
    }
}
