/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyIdTest {
    @Test
    fun splitsAtTheFirstSlash() {
        val id = KeyId("zyfull//")
        assertEquals("zyfull", id.form)
        assertEquals("/", id.key)
        assertEquals("g14" to "qw", KeyId("g14/qw").let { it.form to it.key })
    }

    @Test
    fun aBareLayoutNameIsAFunctionKeys() {
        assertEquals(KeyId("fn/comma"), KeyId.of("comma"))
        assertTrue(KeyId.of("backspace").isFunction)
        assertEquals(KeyId("full/q"), KeyId.of("full/q"))
        assertFalse(KeyId.of("full/q").isFunction)
        assertEquals(KeyId.of("space"), KeyId.function("space"))
    }
}
