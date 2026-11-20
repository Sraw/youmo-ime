/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editorinfo

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Text and number flags share bits (CAP_CHARACTERS and NUMBER_FLAG_SIGNED are both 0x1000): a field lists only its own class's. */
@RunWith(RobolectricTestRunner::class)
class EditorInfoParserTest {

    private fun flagsOf(inputType: Int): Set<String> =
        EditorInfoParser.parse(EditorInfo().also { it.inputType = inputType }).getValue("inputType")
            .substringAfter("flags=").substringBefore("\nvariation=")
            .split("\n  ").filter { it.isNotEmpty() }.toSet()

    @Test
    fun aNumberFieldListsNumberFlagsOnly() {
        assertEquals(
            setOf("TYPE_NUMBER_FLAG_SIGNED", "TYPE_NUMBER_FLAG_DECIMAL"),
            flagsOf(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            ),
        )
    }

    @Test
    fun aTextFieldListsTextFlagsOnly() {
        assertEquals(
            setOf("TYPE_TEXT_FLAG_CAP_CHARACTERS", "TYPE_TEXT_FLAG_MULTI_LINE"),
            flagsOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            ),
        )
    }
}
