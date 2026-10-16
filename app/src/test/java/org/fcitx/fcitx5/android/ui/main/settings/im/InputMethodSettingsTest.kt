/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Choice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Flags
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Item
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Section
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Toggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's own input methods, each of which has a page: handwriting too, which a list of them by hand left out. */
internal val appInputMethods = InputMethodSettings.BUILT_IN

internal fun List<Item>.flat(): List<Item> = flatMap { if (it is Section) it.items.flat() else listOf(it) }

internal fun item(page: String, key: String) = InputMethodSettings.of(page)!!.flat().first {
    (it is Choice && it.key == key) || (it is Toggle && it.key == key)
}

/** The pages as items, which need no Android: see InputMethodSettingsScreenTest for them as preferences. */
class InputMethodSettingsTest {

    @Test
    fun everyInputMethodOfTheAppHasAPageAndOthersNone() {
        appInputMethods.forEach { assertNotNull(it, InputMethodSettings.of(it)) }
        assertNull(InputMethodSettings.of("keyboard-de"))
    }

    // appInputMethods is BUILT_IN itself: held here to the names androidengine.cpp registers (and
    // English), by hand, so that one BUILT_IN leaves out is not skipped by every check above
    @Test
    fun builtInIsEveryInputMethodTheEngineRegisters() {
        val registered = setOf(
            "engine-pinyin", "engine-shuangpin", "engine-t9", "engine-wubi", "engine-cangjie",
            "engine-ziranma", "engine-erbi", "engine-wubipinyin", "engine-handwriting", "keyboard-us"
        )
        assertEquals(registered, InputMethodSettings.BUILT_IN.toSet())
        assertEquals(registered.size, InputMethodSettings.BUILT_IN.size)
    }

    // a ListPreference shows nothing for a value not among its own: whatever fcitx stored (out of
    // range, an old enum name, nothing at all) shows as one of them
    @Test
    fun anyValueStoredShowsAsOneOfTheChoices() {
        val stored = listOf("", "x", "-5", "-1", "0", "1", "4", "7", "100", "Fast", "Ziranma", "Alt")
        appInputMethods.forEach { page ->
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
        // 1, as libime's settings may have it, makes no phrase here: off, not up to 2
        assertEquals("0", length.nearest("1"))
        assertEquals("2", length.nearest("2"))
        val after = item("engine-wubi", "SaveAutoPhraseAfter") as Choice
        assertEquals("0", after.nearest("-1"))
        assertEquals("3", after.nearest("3"))
    }

    // 九键 greys the options it has no use for, and only on its own page: the others share them
    @Test
    fun nineKeysGreyOnlyWhatTheyHaveNoUseFor() {
        fun unused(page: String) = InputMethodSettings.of(page)!!.flat().flatMap {
            when (it) {
                is Toggle -> if (it.unused != 0) listOf(it.key) else emptyList<String>()
                is Flags -> it.unused.map { key -> "${it.path}/$key" }
                else -> emptyList<String>()
            }
        }.toSet()
        val fuzzy = EngineSettings.FUZZY
        assertEquals(setOf("$fuzzy/${EngineSettings.TYPOS}", EngineSettings.LATIN_WORDS, "$fuzzy/${Fuzzy.V_U.name}"), unused(Engines.T9))
        assertEquals(emptySet<String>(), unused(Engines.PINYIN))
        assertEquals(emptySet<String>(), unused(Engines.SHUANGPIN))
        // its reason under each pair greyed, as under each switch
        val pairs = InputMethodSettings.of(Engines.T9)!!.flat().filterIsInstance<Flags>().single()
        assertEquals(R.string.im_not_for_t9, pairs.unusedNote)
        // 双拼 types its letters as its scheme has them: no Latin words to read in them
        assertTrue(InputMethodSettings.of(Engines.SHUANGPIN)!!.flat().none { it is Toggle && it.key == EngineSettings.LATIN_WORDS })
    }

    // the labels are the app's, the pairs and a table's options the engine's: one it adds gets a row
    @Test
    fun everyFuzzyPairAndTableOptionOfTheEngineHasARow() {
        val fuzzy = InputMethodSettings.of(Engines.PINYIN)!!.flat().filterIsInstance<Flags>().single()
        assertEquals(EngineSettings.FUZZY, fuzzy.path)
        assertEquals(Fuzzy.entries.map { it.name }.sorted(), fuzzy.flags.map { it.first }.sorted())
        val table = InputMethodSettings.of("engine-wubi")!!.flat().map { if (it is Toggle) it.key else (it as Choice).key }
        // the table's own, and those every Chinese input method shares with pinyin (EngineTablePage)
        val shared = listOf(EngineSettings.PAGE_SIZE, EngineSettings.PREDICTION, EngineSettings.MODEL_LEVEL)
        assertEquals((TableSettings.KEYS + shared).sorted(), table.sorted())
    }

    // its name starts as a table's does ("engine-"): without a page of its own it would get a table's
    @Test
    fun handwritingHasItsOwnPageWithTheOptionsEveryChineseInputMethodShares() {
        val keys = InputMethodSettings.of(Engines.HANDWRITING)!!.flat().map {
            when (it) {
                is Toggle -> it.key
                is InputMethodSettings.Titled -> it.key
                else -> error("$it")
            }
        }
        assertEquals(
            listOf(AppPrefs.HANDWRITING_PAUSE, EngineSettings.PAGE_SIZE, EngineSettings.PREDICTION, EngineSettings.MODEL_LEVEL).sorted(),
            keys.sorted(),
        )
    }
}
