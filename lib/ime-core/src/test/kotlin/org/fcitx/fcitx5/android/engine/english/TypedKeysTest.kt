/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key and where it was tapped keep in step however the word is edited. */
class TypedKeysTest {

    private fun at(dx: Float) = Touch(dx, 0f, 40f, 56f)

    @Test
    fun eachKeyKeepsItsPlaceThroughEdits() {
        val keys = TypedKeys()
        keys.insert(0, 'd', at(0.1f))
        keys.insert(1, 't', null)
        keys.insert(1, 'o', at(0.3f))
        keys.insert(2, '\'', null)
        assertEquals("do't", keys.toString())
        assertArrayEquals(arrayOf(at(0.1f), at(0.3f), null), keys.keyTouches())
        keys.deleteAt(0)
        assertArrayEquals(arrayOf(at(0.3f), null), keys.keyTouches())
        keys.set("hello")
        assertEquals("hello", keys.toString())
        assertNull(keys.keyTouches())
        keys.clear()
        assertEquals(0, keys.length)
        assertNull(keys.keyTouches())
    }

    @Test
    fun theNineKeysShiftGoesWithItsKey() {
        val keys = TypedKeys()
        keys.append('4', upper = true, touch = null)
        keys.append('3', upper = false, touch = at(-0.45f))
        assertTrue(keys.upper[0])
        assertFalse(keys.upper[1])
        assertArrayEquals(arrayOf(null, at(-0.45f)), keys.keyTouches())
        keys.deleteAt(0)
        assertFalse(keys.upper[0])
        keys.append('6', upper = true, touch = null)
        keys.clear()
        assertTrue(keys.upper.isEmpty)
    }

    @Test
    fun aLaterKeysShiftMovesWithItAsKeysBeforeItComeAndGo() {
        val keys = TypedKeys()
        keys.append('4', upper = false, touch = null)
        keys.append('3', upper = true, touch = null)
        keys.deleteAt(0)
        assertEquals("3", keys.toString())
        assertEquals(listOf(0), keys.upper.stream().toArray().toList())
        // a key put in before it: the 3 and its Shift one on, the new key without
        keys.insert(0, '9', null)
        assertEquals("93", keys.toString())
        assertEquals(listOf(1), keys.upper.stream().toArray().toList())
        keys.set("hi")
        assertTrue(keys.upper.isEmpty)
    }
}
