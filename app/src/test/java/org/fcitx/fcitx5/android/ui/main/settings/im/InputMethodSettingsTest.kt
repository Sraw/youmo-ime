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

/** The app's own input methods, each of which has a page: handwriting too, which a list of them by hand left out. */
internal val appInputMethods = InputMethodSettings.BUILT_IN

internal fun List<Item>.flat(): List<Item> = flatMap { if (it is Section) it.items.flat() else listOf(it) }

/** Every row's key on [page], whatever its kind: a kind added later is not skipped. */
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

/** The pages as items, which need no Android: see InputMethodSettingsScreenTest for them as preferences. */
class InputMethodSettingsTest {

    @Test
    fun everyInputMethodOfTheAppHasAPageAndOthersNone() {
        appInputMethods.forEach { assertNotNull(it, InputMethodSettings.of(it)) }
        assertNull(InputMethodSettings.of("keyboard-de"))
        // a route 0.4 saved to its 九键拼音 opens pinyin's page, not the tables' it would fall to by its engine- name
        assertSame(InputMethodSettings.of(InputMethodCatalog.PINYIN), InputMethodSettings.of(InputMethodCatalog.T9))
    }

    // appInputMethods is BUILT_IN itself: held here to the names androidengine.cpp registers (and
    // English), by hand, so that one BUILT_IN leaves out is not skipped by every check above; the
    // nine keys' engine-t9 is pinyin's keyboard since I1, a session with no page of its own
    @Test
    fun builtInIsEveryInputMethodTheEngineRegisters() {
        val registered = setOf(
            "engine-pinyin", "engine-shuangpin", "engine-wubi", "engine-cangjie",
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
        // 自动造词 is gone (U-28 C46): an older config's two keys are left, read by nothing
        for (im in InputMethodCatalog.TABLES.keys) {
            assertEquals(im, emptyList<String>(), keys(im).filter { it == "AutoPhraseLength" || it == "SaveAutoPhraseAfter" })
        }
    }

    /** Every row's key on the shared page, as [keys] for an input method's. */
    private fun sharedKeys(): List<String> = InputMethodSettings.SHARED.flat().map {
        when (it) {
            is Toggle -> it.key
            is Flags -> it.path
            is InputMethodSettings.Titled -> it.key
            is Section -> error("$it")
        }
    }

    // what every input method shares is on its own page, and on no input method's
    @Test
    fun theSharedOptionsAreOnTheSharedPageAlone() {
        val shared = listOf(
            AppPrefs.KEY_DRAWING, AppPrefs.AUTO_PAIRS, AppPrefs.CALCULATION_REPLACES_SUM, InputMethodSettings.ADDRESSES,
            EngineSettings.PREDICTION, EngineSettings.EMOJI, EngineSettings.BUILT_IN_PHRASES, EngineSettings.MODEL_LEVEL, EngineSettings.CANDIDATE_READING, InputMethodSettings.PUNCTUATION,
            "${EngineSettings.FUZZY}/${EngineSettings.TYPOS}", EngineSettings.FUZZY,
        )
        assertEquals(shared, sharedKeys())
        appInputMethods.forEach { page ->
            assertEquals(page, emptySet<String>(), keys(page).toSet() intersect shared.toSet())
            // nor the page size, the physical keyboard's
            assertFalse(page, EngineSettings.PAGE_SIZE in keys(page))
        }
        assertEquals(listOf(EngineSettings.PAGE_SIZE), InputMethodSettings.HARDWARE_KEYBOARD.flat().map { (it as Choice).key })
    }

    // the nine keys' own (a section 九键 on the page of each keyboard they are, 11-01): one pref each
    private val nineKeys = listOf(AppPrefs.T9_MULTI_TAP, InputMethodSettings.T9_LETTERS, InputMethodSettings.T9_SYMBOLS)

    // each page's own, and first a way to the shared page
    @Test
    fun eachPageHasItsOwnOptionsAfterTheWayToTheSharedOnes() {
        val own = mapOf(
            InputMethodCatalog.PINYIN to listOf(EngineSettings.CHAIZI, EngineSettings.LATIN_WORDS, AppPrefs.PINYIN_KEYBOARD) + nineKeys,
            InputMethodCatalog.SHUANGPIN to listOf(EngineSettings.SHUANGPIN_PROFILE, AppPrefs.SHUANGPIN_KEYBOARD, InputMethodSettings.T9_SYMBOLS),
            InputMethodCatalog.HANDWRITING to listOf(AppPrefs.HANDWRITING_PAUSE),
            InputMethodCatalog.ENGLISH to listOf(
                AppPrefs.ENGLISH_KEYBOARD, AppPrefs.T9_ENGLISH_TYPING, *nineKeys.toTypedArray(),
                EngineSettings.ENGLISH_SUGGEST, AppPrefs.ENGLISH_AUTO_CAPS, InputMethodSettings.ENGLISH_WORDS,
            ),
        ) + InputMethodCatalog.TABLES.mapValues { (_, method) ->
            // what the keys show, where a table has roots on them (仓颉, 五笔)
            TableSettings.KEYS.toList() + InputMethodSettings.RESET_TABLE_ORDER + listOfNotNull(TableSettings.KEY_LABELS.takeIf { method.options.roots != null })
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

    // said under each: the input methods it is no use to, as the engine has them
    @Test
    fun eachSharedOptionSaysWhichInputMethodsItIsNoUseTo() {
        val rows = InputMethodSettings.SHARED.flat()
        fun notFor(key: String): List<String> = when (val row = rows.single { (it is Toggle && it.key == key) || (it is InputMethodSettings.Titled && it.key == key) || (it is Flags && it.path == key) }) {
            is Toggle -> row.notFor
            is InputMethodSettings.Titled -> row.notFor
            else -> error("$row")
        }
        // a finger drawn on the keyboard: one list whose choices reach different input methods, said in the docs, not under it
        assertEquals(emptyList<String>(), notFor(AppPrefs.KEY_DRAWING))
        assertEquals(emptyList<String>(), notFor(AppPrefs.AUTO_PAIRS))
        assertEquals(emptyList<String>(), notFor(AppPrefs.CALCULATION_REPLACES_SUM))
        // the Chinese input methods' section says English for all of it: under a row, only an exception within it
        assertEquals(emptyList<String>(), notFor(EngineSettings.PREDICTION))
        assertEquals(listOf(InputMethodCatalog.HANDWRITING), notFor(EngineSettings.EMOJI))
        assertEquals(listOf(InputMethodCatalog.HANDWRITING), notFor(EngineSettings.BUILT_IN_PHRASES))
        assertEquals(emptyList<String>(), notFor(EngineSettings.MODEL_LEVEL))
        assertEquals(emptyList<String>(), notFor(InputMethodSettings.PUNCTUATION))
        assertEquals(listOf(InputMethodCatalog.T9, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.PINYIN_KEYS, InputMethodCatalog.SHUANGPIN_KEYS18, InputMethodCatalog.SHUANGPIN_KEYS14, InputMethodCatalog.SHUANGPIN_NINE, InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN), notFor("${EngineSettings.FUZZY}/${EngineSettings.TYPOS}"))
        // u and v are both on 8 (9 keys' 双拼 too): the pair alone is said to be no use to the nine keys, the others are
        val fuzzy = rows.filterIsInstance<Flags>().single()
        assertEquals(mapOf(Fuzzy.V_U.name to listOf(InputMethodCatalog.T9, InputMethodCatalog.SHUANGPIN_NINE)), fuzzy.notFor)
        // each a name the app has: one it does not know would be said by its id
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

    // the labels are the app's, the pairs and a table's options the engine's: one it adds gets a row
    @Test
    fun everyFuzzyPairAndTableOptionOfTheEngineHasARow() {
        val fuzzy = InputMethodSettings.SHARED.flat().filterIsInstance<Flags>().single()
        assertEquals(EngineSettings.FUZZY, fuzzy.path)
        assertEquals(Fuzzy.entries.map { it.name }.sorted(), fuzzy.flags.map { it.first }.sorted())
        assertEquals((TableSettings.KEYS + TableSettings.KEY_LABELS).sorted(), keys("engine-wubi").filter { it != InputMethodSettings.SHARED_LINK && it != InputMethodSettings.RESET_TABLE_ORDER }.sorted())
    }

    // the page's main choice, right under the way to the shared page
    @Test
    fun pinyinsKeyboardIsItsFirstOwnRow() {
        assertEquals(AppPrefs.PINYIN_KEYBOARD, keys(InputMethodCatalog.PINYIN)[1])
    }

    // the hardware keyboard still types full pinyin and reads both: said not for the nine, 18 and 14 keys, not hidden
    @Test
    fun pinyinsOwnOptionsSayTheyAreNoUseToThe14Keys() {
        val rows = InputMethodSettings.of(InputMethodCatalog.PINYIN)!!.flat().filterIsInstance<Toggle>().associateBy { it.key }
        val keys = listOf(InputMethodCatalog.T9, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.PINYIN_KEYS, InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN)
        assertEquals(keys, rows.getValue(EngineSettings.CHAIZI).notFor)
        assertEquals(keys, rows.getValue(EngineSettings.LATIN_WORDS).notFor)
    }
}
