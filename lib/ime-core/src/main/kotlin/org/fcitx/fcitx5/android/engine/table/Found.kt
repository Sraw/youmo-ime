/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/**
 * A code's candidates at a [TableSession]'s ranking positions: the [saved] phrases, the table's [entries]
 * (the first [coded] by code, the rest by pinyin), then the other input methods' words ([others]).
 */
internal class Found(val saved: List<TableSession.Item>, val entries: IntArray, val coded: Int, val others: List<TableSession.Item>) {
    private val sharedFrom = saved.size + entries.size
    val size get() = sharedFrom + others.size

    // the merged dictionaries' words of longer codes, as far as read ([MergedOrder]): at [size] on
    private val tail = ArrayList<TableSession.Item>()

    fun addTail(item: TableSession.Item): Int = (size + tail.size).also { tail += item }

    fun item(at: Int): TableSession.Item? = when {
        at < saved.size -> saved[at]
        at >= size -> tail[at - size]
        at >= sharedFrom -> others[at - sharedFrom]
        else -> null
    }

    fun spelt(at: Int) = at in saved.size + coded until sharedFrom

    fun entry(at: Int) = entries[at - saved.size]

    fun text(at: Int, table: TableDictionary) = item(at)?.text ?: table.text(entry(at))
}
