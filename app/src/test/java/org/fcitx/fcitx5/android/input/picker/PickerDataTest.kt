/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerDataTest {

    /** A group with no tab would be dropped from the emoji panel whole, and nothing would say so. */
    @Test
    fun eachEmojiGroupHasATab() {
        assertEquals(EmojiData.GROUPS.toSet(), PickerData.EmojiCategories.keys)
    }

    // the symbol panel's pick kept by the category's id (PAN-29): each its own, found again by it; one no longer listed, none
    @Test
    @Uat("PAN-29")
    fun everySymbolCategoryIsFoundAgainByItsId() {
        val categories = listOf(PickerData.RecentlyUsedCategory) + PickerData.Symbol.map { it.first }
        assertRows(
            categories.indices.map { i -> i to "${categories[i].id}: back to its own place" } +
                listOf(-1 to "gone (renamed, removed): none", -2 to "none kept (empty): none"),
            { it.second },
        ) { (i, _) ->
            val id = when (i) { -1 -> "gone"; -2 -> ""; else -> categories[i].id }
            assertEquals(i.takeIf { it >= 0 }, PickerWindow.categoryOf(categories, id))
        }
        assertEquals("ids are never empty nor shared", categories.size, categories.map { it.id }.filter { it.isNotEmpty() }.toSet().size)
    }

    // user 2026-11-13: 英文 without the digits (no use there), with the English comma and full stop it lacked
    @Test
    @Uat("PAN-29")
    fun theEnglishTabHasNoDigitsButTheEnglishCommaAndFullStop() {
        val english = PickerData.Symbol.first { it.first.name == R.string.picker_ascii }.second.toList()
        assertRows(
            ("0123456789".map { it.toString() to false } + listOf("," to true, "." to true, "!" to true, "?" to true)),
            { (s, there) -> if (there) "$s: an English mark" else "$s: a digit, gone" },
        ) { (s, there) -> assertEquals(s, there, s in english) }
        assertEquals("no symbol twice", english.size, english.toSet().size)
    }
}
