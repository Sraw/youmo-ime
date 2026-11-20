/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.host.HandwritingRetire.OLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.0's handwriting input method gone: each 0.6.0 group as read now. */
class HandwritingRetireTest {

    private data class Case(val what: String, val items: List<String>, val default: String, val expected: HandwritingRetire.Outcome?)

    private val cases = listOf(
        Case("no handwriting: nothing", listOf(PINYIN, ENGLISH), ENGLISH, null),
        Case(
            "handwriting first, pinyin later: pinyin moved first",
            listOf(OLD, ENGLISH, PINYIN), ENGLISH,
            HandwritingRetire.Outcome(listOf(PINYIN, ENGLISH), ENGLISH, moved = true),
        ),
        Case(
            "handwriting first, no pinyin: pinyin added first",
            listOf(OLD, WUBI, ENGLISH), WUBI,
            HandwritingRetire.Outcome(listOf(PINYIN, WUBI, ENGLISH), WUBI, moved = true),
        ),
        Case(
            "handwriting in the middle: dropped, the rest kept",
            listOf(WUBI, OLD, ENGLISH), ENGLISH,
            HandwritingRetire.Outcome(listOf(WUBI, ENGLISH), ENGLISH, moved = false),
        ),
        Case(
            "handwriting alone: pinyin",
            listOf(OLD), OLD,
            HandwritingRetire.Outcome(listOf(PINYIN), PINYIN, moved = true),
        ),
        // ImGroup's rule: the default not the first, the second if it is
        Case(
            "handwriting the default, not first: the second is",
            listOf(PINYIN, OLD, ENGLISH), OLD,
            HandwritingRetire.Outcome(listOf(PINYIN, ENGLISH), ENGLISH, moved = false),
        ),
    )

    @Test
    @Uat("PR-55", "PR-56")
    fun eachGroupWithoutHandwriting() = assertRows(cases, { it.what }) { c ->
        assertEquals(c.expected, HandwritingRetire.group(c.items, c.default))
    }

    @Test
    @Uat("PR-58")
    fun traditionalHandwritingGoesToPinyinWhereItTookHandwritingsPlace() {
        assertNull(HandwritingRetire.traditional(setOf(PINYIN), moved = true))
        // pinyin first now: it writes 繁体 as handwriting did, though it was simplified itself
        assertEquals(HandwritingRetire.Traditional(setOf(WUBI, PINYIN), lost = false), HandwritingRetire.traditional(setOf(OLD, WUBI), moved = true))
        // elsewhere: nothing takes it over, the user told
        assertEquals(HandwritingRetire.Traditional(setOf(WUBI), lost = true), HandwritingRetire.traditional(setOf(OLD, WUBI), moved = false))
    }

    @Test
    fun aProfileRetiresHandwritingInEveryGroupAndReadsBackTheSame() {
        val available = setOf(PINYIN, ENGLISH, WUBI, OLD)
        val text = "[Groups/0]\nName=a\nDefault Layout=us\nDefaultIM=$ENGLISH\n" +
            "[Groups/0/Items/0]\nName=$OLD\n[Groups/0/Items/1]\nName=$ENGLISH\n" +
            "[Groups/1]\nName=b\nDefault Layout=us\nDefaultIM=$OLD\n" +
            "[Groups/1/Items/0]\nName=$WUBI\n[Groups/1/Items/1]\nName=$OLD\n" +
            "[GroupOrder]\n0=a\n1=b\n"
        val retired = Profile.load(text, available, "D").retireHandwriting()
        assertTrue(retired.changed)
        assertTrue(retired.moved)
        assertEquals(listOf(PINYIN, ENGLISH), retired.profile.groups.first { it.name == "a" }.items.map { it.name })
        assertEquals(listOf(WUBI), retired.profile.groups.first { it.name == "b" }.items.map { it.name })
        assertEquals(WUBI, retired.profile.groups.first { it.name == "b" }.defaultInputMethod)
        val again = Profile.load(retired.profile.text(), available, "D")
        assertEquals(retired.profile.text(), again.text())
        assertFalse(again.retireHandwriting().changed)
    }

}
