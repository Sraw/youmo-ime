/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A tab whose category filtering emptied at the end of the list went to a row past it, and showed
 * the recently used as current; a skin tone's transform reran its code points, the RGI set and a
 * glyph check on every bind as the list scrolled. Under the app, whose preferences the adapter
 * and its keys read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PickerGridAdapterTest {

    /** Hides [HIDDEN], marks what an item commits as, and counts how often it works that out. */
    private class CountingPolicy : PickerPolicy {
        var key = 0
        var transforms = 0
        override fun filter(raw: String) = raw != HIDDEN
        override fun transform(raw: String) = "$raw!".also { transforms++ }
        override fun popup(raw: String): KeyDef.Popup.Keyboard? = null
        override fun invalidateKey(): Any = key
    }

    private val grid by lazy {
        PickerGridView(RuntimeEnvironment.getApplication(), ThemePreset.PixelDark, PickerGridView.Density.Medium)
    }

    private fun adapter(policy: PickerPolicy, vararg data: Pair<PickerData.Category, Array<String>>) =
        PickerGridAdapter(
            grid, KeyActionListener { _, _ -> }, PopupActionListener {}, data.toList(),
            "PickerGridAdapterTest", bordered = false, policy = policy
        ).apply { rebuild() }

    @Test
    fun aTabWhoseItemsAreAllHiddenAtTheEndGoesToTheLastCategoryWithAny() {
        val adapter = adapter(
            CountingPolicy(),
            PickerData.Category("a") to arrayOf("x", "y"),
            PickerData.Category("b") to arrayOf(HIDDEN)
        )
        assertEquals(adapter.startOf(1), adapter.startOf(2))
        assertEquals("the tab shown, not the recently used", 1, adapter.categoryAt(adapter.startOf(2)))
    }

    @Test
    fun anItemIsTransformedOnceForTheKeyNotOnEachBind() {
        val policy = CountingPolicy()
        val adapter = adapter(policy, PickerData.Category("a") to arrayOf("x"))
        // nothing used yet: the header, then "x"
        val holder = adapter.onCreateViewHolder(grid, adapter.getItemViewType(1))
        repeat(3) { adapter.onBindViewHolder(holder, 1) }
        assertEquals(1, policy.transforms)
        assertEquals("x!", (holder.itemView as TextKeyView).mainText.text.toString())
        // another key, as another skin tone picked since: worked out again
        policy.key = 1
        adapter.rebuild()
        adapter.onBindViewHolder(holder, 1)
        assertEquals(2, policy.transforms)
    }

    // F1969: emoji.data missing, the emoji picker has the recently used alone and opened past it
    @Test
    fun aPickerWithNoDataOpensOnTheRecentlyUsed() {
        val adapter = adapter(CountingPolicy())
        val first = PickerWindow.firstShown(adapter.categories.size)
        assertEquals(0, first)
        assertEquals(0, adapter.categoryAt(adapter.startOf(first)))
    }

    @Test
    fun aPickerWithDataOpensOnItsFirstCategory() {
        val adapter = adapter(CountingPolicy(), PickerData.Category("a") to arrayOf("x"))
        assertEquals(1, PickerWindow.firstShown(adapter.categories.size))
    }

    companion object {
        private const val HIDDEN = "hidden"
    }
}
