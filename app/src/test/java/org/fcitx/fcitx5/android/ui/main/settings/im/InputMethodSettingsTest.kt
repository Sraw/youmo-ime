/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Choice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Item
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Section
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Toggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMethodSettingsTest {

    private val names = listOf(
        "engine-pinyin", "engine-shuangpin", "engine-wubi", "engine-cangjie", "engine-ziranma", "engine-erbi",
        "engine-wubipinyin", "keyboard-us",
    )

    private fun List<Item>.flat(): List<Item> = flatMap { if (it is Section) it.items.flat() else listOf(it) }

    private fun item(page: String, key: String) = InputMethodSettings.of(page)!!.flat().first {
        (it is Choice && it.key == key) || (it is Toggle && it.key == key)
    }

    @Test
    fun everyInputMethodOfTheAppHasAPageAndOthersNone() {
        names.forEach { assertNotNull(it, InputMethodSettings.of(it)) }
        assertNull(InputMethodSettings.of("keyboard-de"))
    }

    // a ListPreference shows nothing for a value not among its own: whatever fcitx stored (out of
    // range, an old enum name, nothing at all) shows as one of them
    @Test
    fun anyValueStoredShowsAsOneOfTheChoices() {
        val stored = listOf("", "x", "-5", "-1", "0", "1", "4", "7", "100", "Fast", "Ziranma", "Alt")
        names.forEach { page ->
            InputMethodSettings.of(page)!!.flat().filterIsInstance<Choice>().forEach { choice ->
                stored.forEach { v ->
                    assertTrue("$page ${choice.key} $v", choice.nearest(v) in choice.values)
                }
                choice.values.forEach { assertEquals("$page ${choice.key}", it, choice.nearest(it)) }
            }
        }
    }

    @Test
    fun aTablesOrderByUseIsFreqOrFastAndWrittenAsFreq() {
        val order = item("engine-wubi", "OrderPolicy") as Toggle
        assertTrue(order.read("Fast"))
        assertTrue(order.read("Freq"))
        assertFalse(order.read("No"))
        assertEquals("Freq", order.write(true))
        assertEquals("No", order.write(false))
    }

    @Test
    fun aPhraseLengthBelowZeroIsTheLongestAndAnyOtherTheNearest() {
        val length = item("engine-wubi", "AutoPhraseLength") as Choice
        assertEquals("-1", length.nearest("-1"))
        assertEquals("6", length.nearest("8"))
        assertEquals("0", length.nearest("0"))
        val after = item("engine-wubi", "SaveAutoPhraseAfter") as Choice
        assertEquals("0", after.nearest("-1"))
        assertEquals("3", after.nearest("3"))
    }
}
