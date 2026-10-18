/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerDataTest {

    /** A group with no tab would be dropped from the emoji panel whole, and nothing would say so. */
    @Test
    fun eachEmojiGroupHasATab() {
        assertEquals(EmojiData.GROUPS.toSet(), PickerData.EmojiCategories.keys)
    }
}
