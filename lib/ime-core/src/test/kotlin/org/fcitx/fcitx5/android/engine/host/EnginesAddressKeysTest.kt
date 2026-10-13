/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

/**
 * A table's keys no letter in an address field ([Engines.readsAsKey], told [Engines.tableKeys]):
 * while something is typed a key where the table takes it, else text. Taken as text, 二笔's `;`
 * cut the code typed.
 */
class EnginesAddressKeysTest : EnginesFixture() {

    // 二笔's keys, its punctuation within codes but for its / (、), as in its table
    private val erbi = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxyz;',./")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("a;", "了")
        .entry("ab", "的")
        .entry("/", "、")
        .build()
        .toByteArray()

    private fun typedIn(im: String, typed: String) = Engines({ path ->
        if (path == ERBI_DATA) {
            loaded += path
            ByteBuffer.wrap(erbi)
        } else {
            load(path)
        }
    }, null).apply {
        onEvent(im, EngineEvent.RESET, 0)
        typed.forEach { onEvent(im, EngineEvent.CHAR, it.code) }
    }

    // as the keyboard asks, whether something is typed told as it tells it
    private fun Engines.takesAsKey(im: String, key: Char, composing: Boolean) =
        Engines.readsAsKey(im, EngineSettings().scheme, key.code, composing) { tableKeys(im) }

    @Test
    fun aTablesKeyIsAKeyWhileACodeIsTyped() {
        // ; and * go on with the code, [ ends it, as the table takes them
        for ((key, taken) in listOf(';' to true, '*' to true, '[' to false, '_' to false)) {
            typedIn(ERBI, "a").use { engines ->
                assertEquals("$key", taken, engines.takesAsKey(ERBI, key, composing = true))
                assertEquals("$key", taken, engines.onEvent(ERBI, EngineEvent.CHAR, key.code).handled)
            }
        }
        // with nothing typed each is text, though the table reads it: [ would start a lookup, * a code, / be 、
        typedIn(ERBI, "").use { engines ->
            assertEquals(setOf('[', '*', '/'), engines.tableKeys(ERBI).intersect(setOf('[', '*', '/')))
            for (key in "[*/;") assertFalse("$key", engines.takesAsKey(ERBI, key, composing = false))
        }
    }

    // 五笔's pinyin lookup reads ' as the pinyins do; a code typed hands it on, as with nothing typed
    @Test
    fun theLookUpsSeparatorIsAKeyThereAlone() {
        for ((typed, taken) in listOf("" to false, "vb" to false, "zni" to true)) {
            typedIn(Engines.WUBI, typed).use { engines ->
                assertEquals(typed, taken, engines.takesAsKey(Engines.WUBI, '\'', composing = typed.isNotEmpty()))
                assertEquals(typed, taken, engines.onEvent(Engines.WUBI, EngineEvent.CHAR, '\''.code).handled)
            }
        }
    }

    // asked on the keyboard's behalf, which waits: no table is read to answer, and with nothing typed
    // nothing asked, as the keyboard asks first off fcitx's thread
    @Test
    fun anotherInputMethodOrATableWithNoSessionHasNoTableKeys() {
        typedIn(Engines.PINYIN, "ni").use { engines ->
            assertEquals(emptySet<Char>(), engines.tableKeys(Engines.PINYIN))
            assertEquals(emptySet<Char>(), engines.tableKeys(ERBI))
            assertFalse(ERBI_DATA in loaded)
        }
        assertFalse(Engines.readsAsKey(ERBI, EngineSettings().scheme, ';'.code, composing = false) { error("asked") })
    }

    private companion object {
        const val ERBI = "engine-erbi"
        const val ERBI_DATA = Engines.TABLE_DIR + "/erbi.data"
    }
}
