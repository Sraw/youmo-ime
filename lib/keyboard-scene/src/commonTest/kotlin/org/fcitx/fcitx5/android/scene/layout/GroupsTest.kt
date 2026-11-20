/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// a grouped keyboard's letters (ime-core's NineKeyLetters.problem: the layout check asks it on the page too)
class GroupsTest {
    private val k14 = listOf("q", "we", "rt", "yu", "io", "p", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

    @Test
    fun eachKeyOneToFiveLettersAndTheMissingApart() {
        assertNull(Groups.shape(k14))
        assertNull(Groups.shape(k14 + listOf("pq rs", "ab;")), "a letter on two keys, rows, ;")
        assertEquals("0 keys, not 1 to 30", Groups.shape(emptyList()))
        assertEquals("31 keys, not 1 to 30", Groups.shape(k14 + List(16) { "a" }))
        assertEquals("\"abcdef\" is not 1 to 5 letters", Groups.shape(k14 + "abcdef"))
        assertEquals("\"\" is not 1 to 5 letters", Groups.shape(k14 + ""))
        assertEquals("\"aB\" is not letters in rows", Groups.shape(k14 + "aB"))
        assertEquals("\"a  b\" is not letters in rows", Groups.shape(k14 + "a  b"))
        assertEquals("\"aba\" has a letter twice", Groups.shape(k14 + "aba"))
        assertNull(Groups.shape(k14 - "we"), "letters on no key are no shape's")
        assertEquals("ew", Groups.missing(k14 - "we"))
        assertEquals("", Groups.missing(k14))
    }
}
