/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.host.NineKeysMerge.OLD
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.FULL
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.KEYS14
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.NINE_KEYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4's 九键拼音 merged into pinyin (I1 §4, Q2): each 0.4 profile as 0.5 reads it. */
class NineKeysMergeTest {

    private data class Case(
        val what: String,
        val items: List<String>,
        val default: String,
        val pinyin: ImKeyboard,
        val expected: NineKeysMerge.Outcome?,
    )

    private fun out(items: List<String>, default: String, pinyin: ImKeyboard, both: Boolean) =
        NineKeysMerge.Outcome(items, default, pinyin, both)

    private val cases = listOf(
        Case("pinyin alone: nothing", listOf(PINYIN, ENGLISH), PINYIN, FULL, null),
        Case("pinyin alone on the 14 keys: nothing", listOf(PINYIN, ENGLISH), PINYIN, KEYS14, null),
        Case(
            "九键拼音 alone: pinyin in its place, on the nine keys",
            listOf(OLD, ENGLISH), OLD, FULL,
            out(listOf(PINYIN, ENGLISH), PINYIN, NINE_KEYS, both = false),
        ),
        Case(
            "九键拼音 alone, not the default: its place",
            listOf(ENGLISH, OLD, WUBI), ENGLISH, FULL,
            out(listOf(ENGLISH, PINYIN, WUBI), ENGLISH, NINE_KEYS, both = false),
        ),
        // the list's order decides, never the default (I1 Q2)
        Case(
            "both, 九键拼音 the default but later: the 26 keys kept, at the earlier place",
            listOf(PINYIN, ENGLISH, OLD), OLD, FULL,
            out(listOf(PINYIN, ENGLISH), PINYIN, FULL, both = true),
        ),
        Case(
            "both, 九键拼音 first, pinyin the default: the nine keys",
            listOf(OLD, PINYIN, ENGLISH), PINYIN, FULL,
            out(listOf(PINYIN, ENGLISH), PINYIN, NINE_KEYS, both = true),
        ),
        Case(
            "both, pinyin the default though later: the nine keys over the 14",
            listOf(OLD, ENGLISH, PINYIN), PINYIN, KEYS14,
            out(listOf(PINYIN, ENGLISH), PINYIN, NINE_KEYS, both = true),
        ),
        Case(
            "both, neither the default, 九键拼音 first: the nine keys",
            listOf(OLD, PINYIN, ENGLISH), ENGLISH, FULL,
            out(listOf(PINYIN, ENGLISH), ENGLISH, NINE_KEYS, both = true),
        ),
        Case(
            "both, neither the default, pinyin first: the 26 keys kept",
            listOf(SHUANGPIN, PINYIN, OLD), SHUANGPIN, FULL,
            out(listOf(SHUANGPIN, PINYIN), SHUANGPIN, FULL, both = true),
        ),
    )

    @Test
    fun eachZeroFourGroupReadsAsZeroFiveHasIt() {
        assertRows(cases, { it.what }) { c -> assertEquals(c.expected, NineKeysMerge.group(c.items, c.default, c.pinyin)) }
    }

    @Test
    fun aMergedGroupMergesNoMore() {
        cases.mapNotNull { c -> NineKeysMerge.group(c.items, c.default, c.pinyin) }.forEach { o ->
            assertNull(NineKeysMerge.group(o.items, o.defaultIm, o.pinyin))
        }
    }

    private val available = setOf(PINYIN, SHUANGPIN, ENGLISH, WUBI, ImGroup.KEYBOARD_US)

    // contract: two groups as 0.4.0 writes its profile, which mutation cannot see: the current one
    // with both, 九键拼音 first and given a layout
    private val zeroFour = """
        [Groups/0]
        Name=默认
        Default Layout=us
        DefaultIM=$PINYIN

        [Groups/0/Items/0]
        Name=$OLD
        Layout=us

        [Groups/0/Items/1]
        Name=$PINYIN

        [Groups/0/Items/2]
        Name=$ENGLISH

        [Groups/1]
        Name=其他
        Default Layout=us
        DefaultIM=$WUBI

        [Groups/1/Items/0]
        Name=$WUBI

        [GroupOrder]
        0=默认
        1=其他

    """.trimIndent()

    @Test
    fun aZeroFourProfileReadsMergedAndWritesWithoutTheOldName() {
        val loaded = Profile.load(zeroFour, available + OLD, "Default")
        val merged = loaded.mergeNineKeys(FULL)
        assertTrue(merged.changed)
        assertTrue(merged.both)
        assertEquals(NINE_KEYS, merged.pinyin)
        val group = merged.profile.currentGroup()
        assertEquals(listOf(PINYIN, ENGLISH), group.inputMethods)
        // fcitx's default is never the first (ImGroup.withDefault): pinyin first, English
        assertEquals(ENGLISH, group.defaultInputMethod)
        // the other group untouched
        assertEquals(loaded.groups[1], merged.profile.groups[1])
        val text = merged.profile.text()
        assertFalse(text, OLD in text)
        // what is written is read again as it is: the merge done once
        val again = Profile.load(text, available + OLD, "Default").mergeNineKeys(NINE_KEYS)
        assertFalse(again.changed)
        assertFalse(again.both)
        assertEquals(NINE_KEYS, again.pinyin)
        assertEquals(merged.profile.groups, again.profile.groups)
    }

    @Test
    fun aZeroFourBackupRestoredOverAMergedProfileMergesAgain() {
        // 0.5 has merged once, pinyin on the 26 keys since; the user restores a 0.4 backup: its profile
        // and its pinyin_keyboard (Full) come back, and the next read merges as the upgrade did
        val restored = Profile.load(zeroFour, available + OLD, "Default").mergeNineKeys(FULL)
        assertTrue(restored.changed)
        assertEquals(NINE_KEYS, restored.pinyin)
        assertEquals(listOf(PINYIN, ENGLISH), restored.profile.currentGroup().inputMethods)
    }

    @Test
    fun aLayoutGivenTheOldNameGoesToPinyinWhereItWasNotOn() {
        // 九键拼音 alone, with a layout of its own
        val merged = Profile.load(zeroFour.replace("Name=$PINYIN\n", "Name=$SHUANGPIN\n"), available + OLD, "Default").mergeNineKeys(FULL)
        assertEquals("us", merged.profile.current.items.first { it.name == PINYIN }.layout)
    }

    @Test
    fun theKeyboardIsTheCurrentGroupsAndBothIsAnyGroups() {
        // the current group has 九键拼音 alone (the nine keys), the other both with pinyin first (the 26).
        // Only the current group's says the keyboard, one preference for every group: a user of several
        // whose other group had 九键拼音 alone finds pinyin there on the current group's keyboard (accepted)
        val text = zeroFour
            .replace("Name=$PINYIN\n", "Name=$SHUANGPIN\n")
            .replace("[Groups/1/Items/0]\nName=$WUBI", "[Groups/1/Items/0]\nName=$PINYIN\n\n[Groups/1/Items/1]\nName=$OLD")
            .replace("DefaultIM=$WUBI", "DefaultIM=$PINYIN")
        val merged = Profile.load(text, available + OLD, "Default").mergeNineKeys(FULL)
        assertEquals(NINE_KEYS, merged.pinyin)
        assertTrue(merged.both)
        assertEquals(listOf(PINYIN), merged.profile.groups[1].items.map { it.name })
    }

    @Test
    fun aProfileWithoutTheOldNameIsLeftAsItIs() {
        val plain = Profile.load(zeroFour.replace(OLD, WUBI), available + OLD, "Default")
        val merged = plain.mergeNineKeys(KEYS14)
        assertFalse(merged.changed)
        assertEquals(KEYS14, merged.pinyin)
        assertEquals(plain.groups, merged.profile.groups)
    }

    @Test
    fun aCurrentGroupWithNeitherTakesTheKeyboardOfAGroupWithTheOldOneAlone() {
        // 九键拼音 alone in 默认, the current group (其他) has no pinyin of either kind: pinyin was typed on the nine keys
        val text = zeroFour
            .replace("Name=$PINYIN\n", "Name=$SHUANGPIN\n")
            .replace("0=默认\n1=其他", "0=其他\n1=默认")
        val merged = Profile.load(text, available + OLD, "Default").mergeNineKeys(FULL)
        assertTrue(merged.changed)
        assertEquals(NINE_KEYS, merged.pinyin)
    }
}
