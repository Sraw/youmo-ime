/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Choice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Flags
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

internal val appInputMethods = InputMethodSettings.BUILT_IN

internal fun List<Item>.flat(): List<Item> = flatMap { if (it is Section) it.items.flat() else listOf(it) }

/** Whatever its kind: a kind added later is not skipped. */
internal fun keys(page: String): List<String> = InputMethodSettings.of(page)!!.flat().map {
    when (it) {
        is Toggle -> it.key
        is Flags -> it.path
        is InputMethodSettings.Titled -> it.key
        is Section -> error("$it")
    }
}

internal fun item(page: String, key: String) = InputMethodSettings.of(page)!!.flat().first {
    (it is Choice && it.key == key) || (it is Toggle && it.key == key)
}

class InputMethodSettingsTest {

    @Test
    fun everyInputMethodOfTheAppHasAPageAndOthersNone() {
        appInputMethods.forEach { assertNotNull(it, InputMethodSettings.of(it)) }
        assertNull(InputMethodSettings.of("keyboard-de"))
        assertNull(InputMethodSettings.of(InputMethodCatalog.HANDWRITING_RETIRED))
        // a route saved before opens 通用输入, where 停笔时间 went
        assertEquals(SettingsRoute.SharedInput, SettingsRoute.now(SettingsRoute.InputMethodConfig("手写", InputMethodCatalog.HANDWRITING_RETIRED)))
        val pinyin = SettingsRoute.InputMethodConfig("拼音", InputMethodCatalog.PINYIN)
        assertEquals(pinyin, SettingsRoute.now(pinyin))
        // a route 0.4 saved to 九键拼音 opens pinyin's page, not the tables' its engine- name would give
        assertSame(InputMethodSettings.of(InputMethodCatalog.PINYIN), InputMethodSettings.of(InputMethodCatalog.T9))
    }

    // held by hand to the names androidengine.cpp registers, so one BUILT_IN omits is not skipped everywhere
    @Test
    fun builtInIsEveryInputMethodTheEngineRegisters() {
        val registered = setOf(
            "engine-pinyin", "engine-shuangpin", "engine-wubi", "engine-cangjie",
            "engine-ziranma", "engine-erbi", "engine-wubipinyin", "keyboard-us"
        )
        assertEquals(registered, InputMethodSettings.BUILT_IN.toSet())
        assertEquals(registered.size, InputMethodSettings.BUILT_IN.size)
    }

    // a ListPreference shows nothing for a value not its own
    @Test
    fun anyValueStoredShowsAsOneOfTheChoices() {
        val stored = listOf("", "x", "-5", "-1", "0", "1", "4", "7", "100", "Fast", "Ziranma", "Alt")
        appInputMethods.forEach { page ->
            (InputMethodSettings.of(page)!! + InputMethodSettings.SHARED + InputMethodSettings.HARDWARE_KEYBOARD).flat().filterIsInstance<Choice>().forEach { choice ->
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
    fun aTableHasNoAutoPhraseSettings() {
        // 自动造词 is gone: an older config's two keys are left, read by nothing
        for (im in InputMethodCatalog.TABLES.keys) {
            assertEquals(im, emptyList<String>(), keys(im).filter { it == "AutoPhraseLength" || it == "SaveAutoPhraseAfter" })
        }
    }

    private fun sharedKeys(): List<String> = InputMethodSettings.SHARED.flat().map {
        when (it) {
            is Toggle -> it.key
            is Flags -> it.path
            is InputMethodSettings.Titled -> it.key
            is Section -> error("$it")
        }
    }

    @Test
    fun theSharedOptionsAreOnTheSharedPageAlone() {
        val shared = listOf(
            AppPrefs.KEY_DRAWING, AppPrefs.STROKE_LIFT, EngineSettings.STROKE_SINGLES, AppPrefs.AUTO_PAIRS, AppPrefs.CALCULATION_REPLACES_SUM, InputMethodSettings.ADDRESSES, AppPrefs.HANDWRITING_PAUSE,
            EngineSettings.PREDICTION, EngineSettings.EMOJI, EngineSettings.BUILT_IN_PHRASES, EngineSettings.MODEL_LEVEL, EngineSettings.CANDIDATE_READING,
            "${EngineSettings.FUZZY}/${EngineSettings.TYPOS}", EngineSettings.FUZZY,
        )
        assertEquals(shared, sharedKeys())
        appInputMethods.forEach { page ->
            assertEquals(page, emptySet<String>(), keys(page).toSet() intersect shared.toSet())
            assertFalse(page, EngineSettings.PAGE_SIZE in keys(page))
        }
        assertEquals(listOf(EngineSettings.PAGE_SIZE), InputMethodSettings.HARDWARE_KEYBOARD.flat().map { (it as Choice).key })
    }

    private val nineKeys = listOf(AppPrefs.T9_MULTI_TAP, InputMethodSettings.T9_LETTERS)

    @Test
    fun eachPageHasItsOwnOptionsAfterTheWayToTheSharedOnes() {
        val own = mapOf(
            InputMethodCatalog.PINYIN to listOf(EngineSettings.CHAIZI, EngineSettings.LATIN_WORDS, AppPrefs.PINYIN_KEYBOARD) + nineKeys,
            InputMethodCatalog.SHUANGPIN to listOf(EngineSettings.SHUANGPIN_PROFILE, AppPrefs.SHUANGPIN_KEYBOARD, EngineSettings.SHUANGPIN_KEYS_SHOWN),
            InputMethodCatalog.ENGLISH to listOf(
                AppPrefs.ENGLISH_KEYBOARD, AppPrefs.T9_ENGLISH_TYPING, *nineKeys.toTypedArray(),
                EngineSettings.ENGLISH_SUGGEST, AppPrefs.ENGLISH_AUTO_CAPS, InputMethodSettings.ENGLISH_WORDS,
            ),
        ) + InputMethodCatalog.TABLES.mapValues { (_, method) ->
            TableSettings.KEYS.toList() + InputMethodSettings.RESET_TABLE_ORDER + AppPrefs.TABLE_KEYBOARD_LAYOUT + listOfNotNull(TableSettings.KEY_LABELS.takeIf { method.options.roots != null })
        }
        assertEquals(appInputMethods.toSet(), own.keys)
        appInputMethods.forEach { page ->
            val keys = keys(page)
            assertEquals(page, InputMethodSettings.SHARED_LINK, keys.first())
            assertEquals(page, own.getValue(page).sorted(), keys.drop(1).sorted())
            val link = InputMethodSettings.of(page)!!.first() as InputMethodSettings.Page
            assertEquals(SettingsRoute.SharedInput, link.route)
        }
    }

    @Test
    fun eachSharedOptionSaysWhichInputMethodsItIsNoUseTo() {
        val rows = InputMethodSettings.SHARED.flat()
        fun notFor(key: String): List<String> = when (val row = rows.single { (it is Toggle && it.key == key) || (it is InputMethodSettings.Titled && it.key == key) || (it is Flags && it.path == key) }) {
            is Toggle -> row.notFor
            is InputMethodSettings.Titled -> row.notFor
            else -> error("$row")
        }
        // one list whose choices reach different input methods: said in the docs, not under it
        assertEquals(emptyList<String>(), notFor(AppPrefs.KEY_DRAWING))
        assertEquals(emptyList<String>(), notFor(AppPrefs.AUTO_PAIRS))
        assertEquals(emptyList<String>(), notFor(AppPrefs.CALCULATION_REPLACES_SUM))
        // the section says English for all of it: under a row, only an exception
        assertEquals(emptyList<String>(), notFor(EngineSettings.PREDICTION))
        assertEquals(emptyList<String>(), notFor(EngineSettings.EMOJI))
        assertEquals(emptyList<String>(), notFor(EngineSettings.BUILT_IN_PHRASES))
        assertEquals(emptyList<String>(), notFor(EngineSettings.MODEL_LEVEL))
        assertEquals(emptyList<String>(), notFor("${EngineSettings.FUZZY}/${EngineSettings.TYPOS}"))
        // u and v are both on 8: only that pair is no use to the nine keys
        val fuzzy = rows.filterIsInstance<Flags>().single()
        assertEquals(mapOf(Fuzzy.V_U.name to listOf(InputMethodCatalog.T9, InputMethodCatalog.SHUANGPIN_NINE)), fuzzy.notFor)
        // an unknown one would be said by its id
        val named = appInputMethods.toSet() + InputMethodCatalog.T9 + InputMethodCatalog.PINYIN_KEYS18 + InputMethodCatalog.PINYIN_KEYS +
            InputMethodCatalog.SHUANGPIN_KEYS18 + InputMethodCatalog.SHUANGPIN_KEYS14 + InputMethodCatalog.SHUANGPIN_NINE +
            InputMethodCatalog.ZHUYIN_NINE + InputMethodCatalog.ZHUYIN
        rows.forEach { row ->
            val all = when (row) {
                is Toggle -> row.notFor
                is InputMethodSettings.Titled -> row.notFor
                is Flags -> row.notFor.values.flatten()
                else -> emptyList()
            }
            assertTrue("$all", named.containsAll(all))
        }
    }

    // one the engine adds gets a row
    @Test
    fun everyFuzzyPairAndTableOptionOfTheEngineHasARow() {
        val fuzzy = InputMethodSettings.SHARED.flat().filterIsInstance<Flags>().single()
        assertEquals(EngineSettings.FUZZY, fuzzy.path)
        assertEquals(Fuzzy.entries.map { it.name }.sorted(), fuzzy.flags.map { it.first }.sorted())
        assertEquals((TableSettings.KEYS + TableSettings.KEY_LABELS).sorted(), keys("engine-wubi").filter { it !in listOf(InputMethodSettings.SHARED_LINK, InputMethodSettings.RESET_TABLE_ORDER, AppPrefs.TABLE_KEYBOARD_LAYOUT) }.sorted())
    }

    @Test
    fun pinyinsKeyboardIsItsFirstOwnRow() {
        assertEquals(AppPrefs.PINYIN_KEYBOARD, keys(InputMethodCatalog.PINYIN)[1])
    }

    @Test
    fun pinyinsOwnOptionsSayTheyAreNoUseToThe14Keys() {
        val rows = InputMethodSettings.of(InputMethodCatalog.PINYIN)!!.flat().filterIsInstance<Toggle>().associateBy { it.key }
        val keys = listOf(InputMethodCatalog.T9, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.PINYIN_KEYS, InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN)
        assertEquals(keys, rows.getValue(EngineSettings.CHAIZI).notFor)
        assertEquals(keys, rows.getValue(EngineSettings.LATIN_WORDS).notFor)
    }
}
