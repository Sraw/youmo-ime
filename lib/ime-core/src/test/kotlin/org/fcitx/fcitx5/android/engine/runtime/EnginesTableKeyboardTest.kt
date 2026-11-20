/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** What a table's keyboard asks of the engine (Engines.tableKeyboard), and its 拼 key (Keyboard.LOOKUP) through the engine. */
class EnginesTableKeyboardTest : EnginesFixture() {

    private val wubi = InputMethodCatalog.WUBI

    // 仓颉, which keeps its 拼 key: one character, the fixture's 五笔 and pinyin for the rest
    private fun withCangjie(path: String): ByteBuffer = if (path == "${InputMethodCatalog.TABLE_DIR}/cj.data") {
        ByteBuffer.wrap(CodeTable.Builder().header("键码", "abcdefghijklmnopqrstuvwxyz").header("码长", "5").entry("onf", "你").build().toByteArray())
    } else {
        load(path)
    }

    @Test
    fun aTableTellsItsRootsWhatTheyShowAndWhetherItsLookUpIsOn() {
        Engines(::withCangjie, null).use { engines ->
            val keyboard = engines.tableKeyboard(wubi)!!
            assertSame(TableRoots.WUBI86, keyboard.roots)
            assertEquals(KeyLabels.NONE, keyboard.labels)
            assertFalse(keyboard.lookingUp)
            assertTrue(keyboard.keys.toString(), keyboard.keys.isEmpty())
            // 五笔 has no 拼 key (its z looks up): the key's char turns nothing on
            engines.onEvent(wubi, EngineEvent.CHAR, Keyboard.LOOKUP)
            assertFalse(engines.tableKeyboard(wubi)!!.lookingUp)
            // 仓颉's 拼 key: on, and so told, for the next keyboard asking too
            val cangjie = InputMethodCatalog.CANGJIE
            engines.onEvent(cangjie, EngineEvent.CHAR, Keyboard.LOOKUP)
            assertTrue(engines.tableKeyboard(cangjie)!!.lookingUp)
            assertTrue("你" in engines.type(cangjie, "ni").candidates)
            engines.onEvent(cangjie, EngineEvent.RESET, 0)
            engines.settings = EngineSettings(tables = mapOf("Wubi" to TableSettings(keyLabels = KeyLabels.ROOTS)))
            assertTrue(engines.tableKeyboard(cangjie)!!.lookingUp)
            assertEquals(KeyLabels.ROOTS, engines.tableKeyboard(wubi)!!.labels)
            assertNull(engines.tableKeyboard(InputMethodCatalog.PINYIN))
        }
    }

    // the host latches an unreadable table as it answers, so nothing throws here
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
        val cangjie = InputMethodCatalog.CANGJIE
        Engines(::withCangjie, null).use { engines ->
            engines.onEvent(cangjie, EngineEvent.RESET, 0)
            engines.type(cangjie, "wq")
            assertEquals(0, engines.factory.lookUpsMade)
            engines.onEvent(cangjie, EngineEvent.RESET, 0)
            engines.onEvent(cangjie, EngineEvent.CHAR, Keyboard.LOOKUP)
            assertTrue("你" in engines.type(cangjie, "ni").candidates)
            assertEquals(1, engines.factory.lookUpsMade)
        }
    }
}
