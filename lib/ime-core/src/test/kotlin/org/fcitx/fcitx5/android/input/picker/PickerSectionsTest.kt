/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerSectionsTest {

    private val sections = PickerSections(listOf(listOf("，"), listOf("，", "。", "？"), listOf("→")))

    @Test
    fun eachCategoryIsAHeaderThenItsItems() {
        assertEquals(
            listOf("H0", "，", "H1", "，", "。", "？", "H2", "→"),
            sections.rows.map { if (it.header) "H${it.category}" else it.text }
        )
    }

    @Test
    fun aTabScrollsToItsHeader() {
        assertEquals(listOf(0, 2, 6), (0..2).map(sections::startOf))
    }

    @Test
    fun theTopRowNamesTheCurrentTab() {
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 2, 2), sections.rows.indices.map(sections::categoryAt))
    }

    @Test
    fun oneCategoryShownAloneHasNoHeaderAndKeepsItsNumber() {
        val one = PickerSections(listOf(listOf("，"), listOf("，", "。", "？"), listOf("→")), only = 1)
        assertEquals(listOf("，", "。", "？"), one.rows.map { it.text })
        assertEquals(listOf(1, 1, 1), one.rows.map { it.category })
    }

    @Test
    fun nothingUsedRecentlyLeavesNoEmptyHeader() {
        val noRecent = PickerSections(listOf(emptyList(), listOf("，", "。")))
        assertEquals(3, noRecent.rows.size)
        // its tab goes to the top, where the next category starts
        assertEquals(0, noRecent.startOf(0))
        assertEquals(0, noRecent.startOf(1))
        assertEquals(1, noRecent.categoryAt(0))
    }

    @Test
    fun noRowIsTheFirstCategory() {
        assertEquals(0, PickerSections.Empty.categoryAt(-1))
        assertEquals(0, sections.categoryAt(sections.rows.size))
    }
}
