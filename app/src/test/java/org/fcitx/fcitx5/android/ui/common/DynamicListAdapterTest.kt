/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// the adapter's observers are the framework's
@RunWith(RobolectricTestRunner::class)
class DynamicListAdapterTest {

    @Test
    fun itemsAddedTogetherAreOneInsertionEachToldToTheListener() {
        val adapter = object : DynamicListAdapter<String>(listOf("a")) {
            override fun showEntry(x: String) = x
        }
        val inserted = mutableListOf<Pair<Int, Int>>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                inserted += positionStart to itemCount
            }
        })
        val added = mutableListOf<Pair<Int, String>>()
        adapter.addOnItemChangedListener(object : OnItemChangedListener<String> {
            override fun onItemAdded(idx: Int, item: String) {
                added += idx to item
            }
        })
        adapter.addItems(listOf("b", "c"))
        adapter.addItems(emptyList())
        assertEquals(listOf("a", "b", "c"), adapter.entries)
        assertEquals(listOf(1 to 2), inserted)
        assertEquals(listOf(1 to "b", 2 to "c"), added)
    }

    @Test
    fun itemsAddedTogetherAreOneBatchToEachListener() {
        val adapter = object : DynamicListAdapter<String>(listOf("a")) {
            override fun showEntry(x: String) = x
        }
        val batches = mutableListOf<List<Pair<Int, String>>>()
        val single = mutableListOf<Pair<Int, String>>()
        // as BaseDynamicListUi's: a Snackbar for each item would hold the main thread
        adapter.addOnItemChangedListener(object : OnItemChangedListener<String> {
            override fun onItemAdded(idx: Int, item: String) {
                single += idx to item
            }

            override fun onItemAddedBatch(indexed: List<Pair<Int, String>>) {
                batches += indexed
            }
        })
        val added = mutableListOf<Pair<Int, String>>()
        adapter.addOnItemChangedListener(object : OnItemChangedListener<String> {
            override fun onItemAdded(idx: Int, item: String) {
                added += idx to item
            }
        })
        adapter.addItems(listOf("b", "c"))
        adapter.addItems(emptyList())
        assertEquals(listOf(listOf(1 to "b", 2 to "c")), batches)
        assertEquals(emptyList<Pair<Int, String>>(), single)
        // a listener with no batch of its own still hears of each item
        assertEquals(listOf(1 to "b", 2 to "c"), added)
    }
}
