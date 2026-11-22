/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.face
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.gradient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SkinIconsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF808080.toInt()

    // 100 opaque pixels, [off] of them far from the rest
    private fun mostly(off: Int) = Pixels(10, 10, IntArray(100) { if (it < off) 0xFF000000.toInt() else white })

    @Test
    fun oneColourIsNinetyFivePercentOfItsOpaquePixels() {
        assertEquals(white, SkinIcons.mono(mostly(0)))
        assertEquals(Pixels.argbOf(0xFF, 242, 242, 242), SkinIcons.mono(mostly(5)))
        assertNull(SkinIcons.mono(mostly(6)))
        // transparent and half-transparent pixels are not counted
        assertEquals(grey, SkinIcons.mono(face(20, 20, 4, grey)))
        assertNull(SkinIcons.mono(Pixels(4, 4)))
        assertNull(SkinIcons.mono(gradient(20, 20, white, 0xFF000000.toInt())))
    }

    @Test
    fun aPressedIconIsTheSameAColourOrItsOwn() {
        val normal = face(40, 40, 8, white)
        assertEquals(SkinIcons.Pressed.Same, SkinIcons.pressed(SkinIcon(normal, null)))
        assertEquals(SkinIcons.Pressed.Same, SkinIcons.pressed(SkinIcon(normal, face(40, 40, 8, white))))
        assertEquals(SkinIcons.Pressed.Color(grey), SkinIcons.pressed(SkinIcon(normal, face(40, 40, 8, grey))))
        // another shape, or not of one colour
        assertEquals(SkinIcons.Pressed.Image, SkinIcons.pressed(SkinIcon(normal, face(40, 40, 4, grey))))
        assertEquals(SkinIcons.Pressed.Image, SkinIcons.pressed(SkinIcon(normal, gradient(40, 40, white, grey))))
    }

    @Test
    fun fitShrinksTheLongerSideOnly() {
        val small = face(100, 50, 0, white)
        assertSame(small, SkinIcons.fit(small))
        val big = SkinIcons.fit(face(512, 256, 0, white))
        assertEquals(256 to 128, big.width to big.height)
        val tall = SkinIcons.fit(face(100, 400, 0, white), 200)
        assertEquals(50 to 200, tall.width to tall.height)
    }

    @Test
    fun namesAndKeys() {
        assertEquals("emoji", SkinIcons.byToolbarName("toolbarMenuItem_Face"))
        assertEquals("choose-keyboard", SkinIcons.byToolbarName("toolbarMenuItem_KEYBOARD"))
        assertEquals("cursor-move", SkinIcons.byToolbarName("toolbarMenuItem_Edit"))
        assertEquals("clipboard", SkinIcons.byToolbarName("toolbarMenuItem_Clipboard"))
        assertEquals("input-method-settings", SkinIcons.byToolbarName("toolbarMenuItem_Settings"))
        assertEquals("theme", SkinIcons.byToolbarName("toolbarMenuItem_Skin"))
        assertEquals("floating", SkinIcons.byToolbarName("toolbarMenuItem_Float"))
        assertNull(SkinIcons.byToolbarName("toolbarMenuItem_Game"))
        assertEquals("enter", SkinIcons.byBaiduCode["F39"])
        assertEquals("key.enter", SkinIcons.keyOf("search"))
        assertEquals("key[role=shift]", SkinIcons.keyOf("shift-locked"))
        assertNull(SkinIcons.keyOf("emoji"))
        // every name a key is styled by is one the app has
        assertEquals(emptyList(), SkinIcons.NAMES.filter { it.isBlank() })
        assertEquals(true, SkinIcons.byBaiduCode.values.all { it in SkinIcons.NAMES })
    }
}
