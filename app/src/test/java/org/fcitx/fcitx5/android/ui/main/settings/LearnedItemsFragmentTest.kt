/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.junit.Assert.assertEquals
import org.junit.Test

class LearnedItemsFragmentTest {

    private fun kind(kind: String) = LearnedItemsFragment.kind(Code(kind, "ab"))

    // a pin says which keyboard it is of, by the kind's prefix: each kind Kinds makes, named
    @Test
    fun eachKeyboardsKindIsNamed() {
        assertEquals(R.string.learning_kind_full, kind(Kinds.FULL))
        assertEquals(R.string.learning_kind_handwriting, kind(Kinds.HANDWRITING))
        assertEquals(R.string.learning_kind_shuangpin, kind(Kinds.shuangpin("Ziranma")))
        assertEquals(R.string.learning_kind_nine_keys, kind(Kinds.nineKeys("pinyin")))
        assertEquals(R.string.learning_kind_nine_keys, kind(Kinds.nineKeysMultiTap("pinyin")))
        assertEquals(R.string.learning_kind_table, kind(Kinds.table("wbx")))
    }
}
