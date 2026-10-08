/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.preference.ListPreference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.modified.hasDefault
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Choice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Item
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Section
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Toggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InputMethodSettingsTest {

    private val names = listOf(
        "engine-pinyin", "engine-shuangpin", "engine-wubi", "engine-cangjie", "engine-ziranma", "engine-erbi",
        "engine-wubipinyin", "keyboard-us",
    )

    private fun List<Item>.flat(): List<Item> = flatMap { if (it is Section) it.items.flat() else listOf(it) }

    private fun item(page: String, key: String) = InputMethodSettings.of(page)!!.flat().first {
        (it is Choice && it.key == key) || (it is Toggle && it.key == key)
    }

    // the app's own strings are not on the unit-test classpath (isIncludeAndroidResources): "#<id>" stands in
    private class PlaceholderStrings(base: Context) : ContextWrapper(base) {
        private val res = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = placeholder(id)
            override fun getString(id: Int): String = placeholder(id)
            override fun getString(id: Int, vararg formatArgs: Any?): String = placeholder(id)
            override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = placeholder(id)
        }

        override fun getResources(): Resources = res
    }

    private fun screen(page: String): PreferenceScreen {
        val ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        return InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(page)!!, RawConfig()) {}
    }

    private fun PreferenceGroup.lists(): List<ListPreference> = (0 until preferenceCount).flatMap {
        when (val pref = getPreference(it)) {
            is ListPreference -> listOf(pref)
            is PreferenceGroup -> pref.lists()
            else -> emptyList()
        }
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

    // a list's dialog looks its preference up by key: one without a key crashed on a tap
    @Test
    fun everyChoiceOnAPageIsFoundByItsOwnKey() {
        names.forEach { page ->
            val screen = screen(page)
            val lists = screen.lists()
            assertEquals(page, InputMethodSettings.of(page)!!.flat().count { it is Choice }, lists.size)
            lists.forEach {
                assertNotNull(page, it.key)
                assertSame("$page ${it.key}", it, screen.findPreference<ListPreference>(it.key))
            }
        }
    }

    // a dialog message would leave the list's dialog with no choices in it
    @Test
    fun anExplanationIsUnderTheValueNotInTheDialog() {
        names.forEach { page -> screen(page).lists().forEach { assertNull("$page ${it.key}", it.dialogMessage) } }
        val length = item("engine-wubi", "AutoPhraseLength") as Choice
        val pref = screen("engine-wubi").findPreference<ListPreference>(length.key)!!
        assertEquals("${pref.entry}\n${placeholder(length.message)}", pref.summary?.toString())
    }

    // a list's dialog offers Default only with a default to go back to, which these lists have not
    @Test
    fun aChoiceHasNoDefaultSoItsDialogOffersNone() {
        names.forEach { page -> screen(page).lists().forEach { assertFalse("$page ${it.key}", it.hasDefault()) } }
        val given = screen("keyboard-us").lists().first().apply { setDefaultValue("5") }
        assertTrue(given.hasDefault())
    }

    private companion object {
        fun placeholder(id: Int) = "#$id"
    }
}
