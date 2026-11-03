/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The roots tables show on their keys (仓颉's, 王码五笔 86's) and what the keyboard makes of them. */
class TableRootsTest {

    @Test
    fun cangjieHasOneRootAKeyAndSpellsItsCodesInThem() {
        val roots = TableRoots.CANGJIE
        assertEquals(('a'..'z').toList(), roots.names.keys.sorted())
        assertEquals("日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜重", ('a'..'z').joinToString("") { roots.names.getValue(it) })
        assertTrue(roots.names.all { (k, v) -> roots.all[k] == listOf(v) })
        // one character for one, the wildcard as typed: the cursor counts the same
        assertEquals("日月*", roots.spell("ab*"))
    }

    @Test
    fun wubiHasItsKeyNamesFirstAndEachRootOnOneKey() {
        val roots = TableRoots.WUBI86
        val keys = ('a'..'y').toList()
        assertEquals(keys, roots.names.keys.sorted())
        assertEquals(keys, roots.all.keys.sorted())
        assertEquals("工子又大月土王目水日口田山已火之金白木禾立女人纟言", keys.joinToString("") { roots.names.getValue(it) })
        // a key's name is its first root (王旁青头…: 王 first), and no root is on two keys
        for (k in keys) assertEquals("$k", roots.names.getValue(k), roots.all.getValue(k).first())
        val all = roots.all.values.flatten()
        assertEquals(all.toSet().size, all.size)
        // its code stays letters
        assertEquals("ggll", roots.spell("ggll"))
    }

    @Test
    fun theKeysShowWhatTheSettingSays() {
        fun keyboard(labels: KeyLabels, roots: TableRoots? = TableRoots.WUBI86) = TableKeyboard("engine-wubi", emptySet(), false, labels, roots)
        assertNull(keyboard(KeyLabels.NONE).label('g'))
        assertEquals("王", keyboard(KeyLabels.NAMES).label('g'))
        assertEquals("王龶戋\n五一", keyboard(KeyLabels.ROOTS).label('g'))
        assertEquals("口川", keyboard(KeyLabels.ROOTS).label('k'))
        // the long press shows them only while the keys show all of them
        assertNull(keyboard(KeyLabels.NAMES).rootsOf('g'))
        assertEquals(listOf("王", "龶", "戋", "五", "一"), keyboard(KeyLabels.ROOTS).rootsOf('g'))
        // Z has none: the letter
        assertNull(keyboard(KeyLabels.ROOTS).label('z'))
        // no roots, nothing to show whatever is set
        assertNull(keyboard(KeyLabels.ROOTS, roots = null).label('g'))
        assertTrue(keyboard(KeyLabels.NAMES).labelled)
        assertTrue(!keyboard(KeyLabels.NAMES, roots = null).labelled)
    }

    @Test
    fun atCompactAllTheRootsAreShownAsTheirNames() {
        fun keyboard(labels: KeyLabels) = TableKeyboard("engine-wubi", setOf(';'), true, labels, TableRoots.WUBI86)
        val compact = keyboard(KeyLabels.ROOTS).atHeight(compact = true)
        assertEquals(keyboard(KeyLabels.NAMES), compact)
        assertEquals("王", compact.label('g'))
        assertNull(compact.rootsOf('g'))
        // out of 紧凑 as set; names and letters alike at both
        assertEquals(keyboard(KeyLabels.ROOTS), keyboard(KeyLabels.ROOTS).atHeight(compact = false))
        for (labels in listOf(KeyLabels.NONE, KeyLabels.NAMES)) {
            assertEquals(keyboard(labels), keyboard(labels).atHeight(compact = true))
            assertEquals(keyboard(labels), keyboard(labels).atHeight(compact = false))
        }
    }

    @Test
    fun anExtraKeyIsOneTheFaceHasNowhereElse() {
        val slots = listOf(';', '*')
        // 二笔: , and . are the bottom row's, ' / * on the corners; ; is left
        val erbi = TableKeyboard("engine-erbi", setOf(';', '\'', ',', '.', '/', '*'), false, KeyLabels.NONE, null)
        assertEquals(';', erbi.extraKey(slots, setOf(',', '.', '\'', '/', '*')))
        // 仓颉 with its roots on the keys: the corners show letters, * is its own key
        val cangjie = TableKeyboard("engine-cangjie", setOf('*'), false, KeyLabels.NAMES, TableRoots.CANGJIE)
        assertEquals('*', cangjie.extraKey(slots, setOf(',', '.')))
        // its swipes shown again: none
        assertNull(cangjie.extraKey(slots, setOf(',', '.', '*')))
        // 五笔's codes are letters
        assertNull(TableKeyboard("engine-wubi", emptySet(), false, KeyLabels.NONE, TableRoots.WUBI86).extraKey(slots, emptySet()))
    }

    @Test
    fun theSettingReadsBackAndHoldsOnlyWhereATableHasRoots() {
        for (l in KeyLabels.entries) assertEquals(l, KeyLabels.of(l.key))
        assertNull(KeyLabels.of("Big"))
        val set = TableSettings(keyLabels = KeyLabels.ROOTS)
        assertEquals(KeyLabels.ROOTS, set.applyTo(TableOptions.WUBI).keyLabels)
        assertEquals(KeyLabels.NONE, set.applyTo(TableOptions.ZIRANMA).keyLabels)
        // 仓颉's preset shows them, its preedit in roots
        assertEquals("日月", TableOptions.CANGJIE.shown("ab"))
        assertEquals("ab", TableOptions.CANGJIE.copy(keyLabels = KeyLabels.NONE).shown("ab"))
        assertEquals("ab", TableOptions.WUBI.copy(keyLabels = KeyLabels.ROOTS).shown("ab"))
    }
}
