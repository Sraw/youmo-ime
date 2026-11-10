/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** A theme layout's name as the keyboard list shows it: cut past 24 characters, never inside a character. */
class KeyboardLabelsNameTest {

    @Test
    fun aLongNameIsCutByCharactersNotUtf16Units() = assertRows(
        listOf(
            Triple("24 characters kept whole", "键".repeat(24), "键".repeat(24)),
            Triple("25 characters cut to 24", "键".repeat(25), "键".repeat(24) + "…"),
            // 24 emoji are 48 UTF-16 units, 24 characters: kept whole
            Triple("emoji counted as one character each", "😀".repeat(24), "😀".repeat(24)),
            Triple("an emoji past the 24th not cut in half", "a".repeat(23) + "😀😀", "a".repeat(23) + "😀…"),
            Triple("an extension-B character not cut in half", "𠀀".repeat(30), "𠀀".repeat(24) + "…"),
        ),
        { it.first },
    ) { (_, name, shown) -> assertEquals(shown, KeyboardLabels.shortName(name)) }
}
