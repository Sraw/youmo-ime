/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinError.Reason
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.sogou
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import org.fcitx.fcitx5.android.scene.skin.SogouFixtures.Sealed
import org.fcitx.fcitx5.android.scene.skin.SogouFixtures.container
import org.fcitx.fcitx5.android.scene.skin.SogouFixtures.payload
import org.fcitx.fcitx5.android.scene.skin.SogouFixtures.seal
import org.fcitx.fcitx5.android.scene.skin.SogouFixtures.u32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SogouContainerTest {
    private fun open(b: ByteArray, platform: SkinPlatform = Sealed()) = SogouContainer.open(b, platform)

    private fun reason(b: ByteArray, platform: SkinPlatform = Sealed()) = assertFailsWith<SkinError> { open(b, platform) }.reason

    private fun entries(vararg e: Pair<String, ByteArray>) = e.toList()

    @Test
    fun theHeaderTellsAContainer() {
        assertTrue(SogouContainer.isContainer("Skin\u0003".encodeToByteArray()))
        assertFalse(SogouContainer.isContainer("Ski".encodeToByteArray()))
        assertFalse(SogouContainer.isContainer("PK\u0003\u0004".encodeToByteArray()))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason("PK\u0003\u0004junk".encodeToByteArray()))
    }

    @Test
    fun filesComeOutByTheirNames() {
        val p = Sealed()
        val files = open(container(mapOf("phoneTheme.ini" to text("[General]"), "1080\\day\\键.png" to byteArrayOf(1, 2), "/a..png" to byteArrayOf(3))), p)
        assertEquals(listOf("phoneTheme.ini", "1080/day/键.png", "a..png"), files.keys.toList())
        assertContentEquals(byteArrayOf(1, 2), files.getValue("1080/day/键.png"))
        // the whole of what may be unpacked is all the platform may inflate
        assertEquals(SkinLimits.MAX_TOTAL_BYTES.toInt(), p.asked)
    }

    @Test
    fun theFirstOfANameTwiceAndNoFolders() {
        val files = SogouContainer.entries(payload(entries("a/b.ini" to byteArrayOf(1), "a\\b.ini" to byteArrayOf(2), "a/" to ByteArray(0), "c" to byteArrayOf(3))))
        assertEquals(setOf("a/b.ini", "c"), files.keys)
        assertContentEquals(byteArrayOf(1), files.getValue("a/b.ini"))
    }

    @Test
    fun namesOutOfTheSkinAreRefused() {
        for (name in listOf("../evil.ini", "a\\..\\b", "a/../b", "a\u0001b", "x".repeat(261))) {
            assertEquals(Reason.UNSAFE_NAME, reason(container(mapOf(name to byteArrayOf(1)))), name)
        }
    }

    @Test
    fun anIndexThatDoesNotHoldIsBroken() {
        val two = entries("a" to byteArrayOf(1, 2, 3), "b" to byteArrayOf(4))
        val ok = payload(two)
        // past the end, an index not of words, a data length past the end, nothing in it
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(payload(two) { listOf(it[0], ok.size.toLong()) })))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(ok.copyOf().also { u32(6).copyInto(it, 4) })))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(ok.copyOf().also { u32(99).copyInto(it, ok.size - 5) })))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(ok.copyOf(ok.size - 1))))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(payload(emptyList()))))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(ByteArray(6))))
        // an odd length of a name is no UTF-16: the entry said by its place
        val odd = assertFailsWith<SkinError> { open(seal(ok.copyOf().also { u32(3).copyInto(it, 16) })) }
        assertEquals(Reason.UNSAFE_NAME to "#1", odd.reason to odd.detail)
    }

    @Test
    fun whatDoesNotOpenIsSaidSo() {
        val sealed = container(mapOf("a" to byteArrayOf(1)))
        // no cipher on this platform, a stated length not the payload's, one past the limit
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(sealed, SkinFixtures.Platform()))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(payload(entries("a" to byteArrayOf(1))), stated = 3)))
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(seal(payload(entries("a" to byteArrayOf(1))), stated = SkinLimits.MAX_TOTAL_BYTES + 1)))
    }

    @Test
    fun limitsHoldAsForAZip() {
        val many = payload(entries("a" to byteArrayOf(1)), SkinLimits.MAX_ENTRIES + 1) { o -> List(SkinLimits.MAX_ENTRIES + 1) { o[0] } }
        assertEquals(Reason.TOO_MANY_FILES, assertFailsWith<SkinError> { SogouContainer.entries(many) }.reason)
        // one copy each time the index names the same data, so offsets that overlap cannot grow it unbounded
        val mb = 1024 * 1024
        val times = (SkinLimits.MAX_TOTAL_BYTES / mb + 1).toInt()
        val again = payload(entries("a" to ByteArray(mb)), times) { o -> List(times) { o[0] } }
        assertEquals(Reason.UNPACKED_TOO_BIG, assertFailsWith<SkinError> { SogouContainer.entries(again) }.reason)
        // a ZIP's other files taken from what is left: the platform inflates no more, the files add up to no more
        val p = Sealed()
        val two = entries("a" to ByteArray(600), "b" to ByteArray(600))
        // too big, not unreadable: the payload opens, only what is left is short of it
        assertEquals(Reason.UNPACKED_TOO_BIG, assertFailsWith<SkinError> { SogouContainer.open(seal(payload(two)), p, budget = 1000) }.reason)
        assertEquals(1000, p.asked)
        assertEquals(Reason.UNPACKED_TOO_BIG, assertFailsWith<SkinError> { SogouContainer.entries(payload(two), budget = 1000) }.reason)
        val big = payload(entries("a" to ByteArray(SkinLimits.MAX_ENTRY_BYTES.toInt() + 1)))
        assertEquals(Reason.UNPACKED_TOO_BIG, assertFailsWith<SkinError> { SogouContainer.entries(big) }.reason)
    }

    @Test
    fun aPhoneSkinInsideConvertsAndAComputerOneIsSaidSo() {
        val phone = open(container(sogou()))
        assertEquals("Warm Orange", SkinFixtures.convert(phone).name)
        val pc = open(container(mapOf("skin.ini" to text("[General]\nname=pc"), "bg.png" to byteArrayOf(1))))
        assertEquals(Reason.SOGOU_PC, assertFailsWith<SkinError> { SkinFixtures.convert(pc) }.reason)
    }
}
