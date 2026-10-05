/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The symbol picker as one list: each category that has anything, a header and then its items.
 * A tab scrolls to where its category starts, and shows as current while the list's top row is
 * one of the category's.
 */
class PickerSections(lists: List<List<String>>) {

    class Row(val text: String, val category: Int, val header: Boolean)

    val rows: List<Row>

    /** an empty category (nothing used recently yet) starts where the next one does */
    private val starts: IntArray

    init {
        val rows = mutableListOf<Row>()
        starts = IntArray(lists.size) { i ->
            val start = rows.size
            if (lists[i].isNotEmpty()) {
                rows.add(Row("", i, header = true))
                lists[i].mapTo(rows) { Row(it, i, header = false) }
            }
            start
        }
        this.rows = rows
    }

    fun startOf(category: Int): Int = starts[category]

    /** the category of the row at [position]; the first for none (an empty list) */
    fun categoryAt(position: Int): Int = rows.getOrNull(position)?.category ?: 0

    companion object {
        val Empty = PickerSections(emptyList())
    }
}
