/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a table's keyboard asks of the engine (Engines.tableKeyboard), and its 拼 key (Keyboard.LOOKUP) through the engine. */
class EnginesTableKeyboardTest : EnginesFixture() {

    private val wubi = InputMethodCatalog.WUBI

    @Test
    fun aTableTellsItsRootsWhatTheyShowAndWhetherItsLookUpIsOn() {
        Engines(::load, null).use { engines ->
            val keyboard = engines.tableKeyboard(wubi)!!
            assertSame(TableRoots.WUBI86, keyboard.roots)
            assertEquals(KeyLabels.NONE, keyboard.labels)
            assertFalse(keyboard.lookingUp)
            // its keys are letters alone
            assertTrue(keyboard.keys.toString(), keyboard.keys.isEmpty())
            // the 拼 key: on, and so told, for the next keyboard asking too
            engines.onEvent(wubi, EngineEvent.CHAR, Keyboard.LOOKUP)
            assertTrue(engines.tableKeyboard(wubi)!!.lookingUp)
            assertTrue("你" in engines.type(wubi, "ni").candidates)
            engines.onEvent(wubi, EngineEvent.RESET, 0)
            // a setting changed, the sessions made anew: still on
            engines.settings = EngineSettings(tables = mapOf("Wubi" to TableSettings(keyLabels = KeyLabels.ROOTS)))
            val again = engines.tableKeyboard(wubi)!!
            assertTrue(again.lookingUp)
            assertEquals(KeyLabels.ROOTS, again.labels)
            // not a table
            assertNull(engines.tableKeyboard(InputMethodCatalog.PINYIN))
        }
    }

    // asked as the keyboard shows, before the input method's activate made its session: its roots
    // alone, its table not read, so one that cannot be read throws nothing here (the host latches it
    // as it answers); the same answer asked again
    @Test
    fun beforeItsSessionATableTellsItsRootsWithoutReadingIt() {
        Engines(::load, null).use { engines ->
            val cangjie = engines.tableKeyboard(InputMethodCatalog.CANGJIE)!!
            assertSame(TableRoots.CANGJIE, cangjie.roots)
            assertEquals(KeyLabels.NAMES, cangjie.labels)
            assertTrue(cangjie.keys.isEmpty())
            assertTrue(loaded.toString(), loaded.none { it.endsWith("cj.data") })
            assertSame(cangjie, engines.tableKeyboard(InputMethodCatalog.CANGJIE))
        }
    }

    // the 拼 key's pinyin made as it first looks up: a table typed in by its codes alone has none
    @Test
    fun aTablesPinyinIsMadeAsItFirstLooksUp() {
        Engines(::load, null).use { engines ->
            engines.onEvent(wubi, EngineEvent.RESET, 0)
            engines.type(wubi, "wq")
            assertEquals(0, engines.factory.lookUpsMade)
            engines.onEvent(wubi, EngineEvent.RESET, 0)
            engines.onEvent(wubi, EngineEvent.CHAR, Keyboard.LOOKUP)
            assertTrue("你" in engines.type(wubi, "ni").candidates)
            assertEquals(1, engines.factory.lookUpsMade)
        }
    }
}
