/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/** The emoji picker as one list of headed categories; a tab is current while the top row is its category's. The symbol panel shows [only] one, no header. */
class PickerSections(lists: List<List<String>>, only: Int? = null) {

    class Row(val text: String, val category: Int, val header: Boolean)

    val rows: List<Row>

    /** An empty category starts where the next one does. */
    private val starts: IntArray

    init {
        val rows = mutableListOf<Row>()
        starts = IntArray(lists.size) { i ->
            val start = rows.size
            if (only == null && lists[i].isNotEmpty()) {
                rows.add(Row("", i, header = true))
                lists[i].mapTo(rows) { Row(it, i, header = false) }
            } else if (i == only) {
                lists[i].mapTo(rows) { Row(it, i, header = false) }
            }
            start
        }
        this.rows = rows
    }

    fun startOf(category: Int): Int = starts[category]

    /** The first for an empty list. */
    fun categoryAt(position: Int): Int = rows.getOrNull(position)?.category ?: 0

    companion object {
        val Empty = PickerSections(emptyList())
    }
}
