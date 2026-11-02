/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeLookTest {

    companion object {
        val PALETTE = Palette(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21,
        )

        fun slice(file: String) = NineSlice(file, Insets(24, 24, 24, 24), 3)

        val PICTURE = Picture(
            src = "t-pic-src.jpg",
            portrait = Crop("t-pic-port.png", Box(0, 300, 1440, 1800), 0),
            landscape = Crop("t-pic-land.png", Box(0, 900, 1440, 1500), 90),
            brightness = 70,
            blurDp = 0,
        )

        /** A look naming a file of every kind: the picture's three, kinds' images, single keys', two fonts. */
        val FULL = LookDefaults.look(PALETTE, PICTURE).let {
            it.copy(
                keys = it.keys.copy(
                    letter = KindStyle(KeyFill.Picture(12, 90), KeyPressed.Darken),
                    space = KindStyle(KeyFill.Image(slice("t-key-space.png")), KeyPressed.Image(slice("t-key-space-down.png"))),
                    enter = KindStyle(KeyFill.Color, KeyPressed.Color(-10577930)),
                    overrides = linkedMapOf(
                        "qwerty/w" to KeyOverride(KeyFill.Image(slice("t-key-a1.png")), null, KeyLabel.Hide),
                        "zhuyin/q" to KeyOverride(KeyFill.Image(slice("t-key-a1.png")), null, KeyLabel.Show),
                        "*/backspace" to KeyOverride(null, KeyPressed.Color(-1499549), KeyLabel.Show),
                    ),
                ),
                text = it.text.copy(keyFont = "t-font-keys.ttf"),
            )
        }
    }

    @Test
    fun theNewColoursAreWhatWasDrawnInTheirPlace() {
        // the border in the shadow's colour, corner text and toolbar icons as the function keys' text
        assertEquals(14, PALETTE.keyBorderColor)
        assertEquals(10, PALETTE.keyHintColor)
        assertEquals(10, PALETTE.toolbarIconColor)
    }

    @Test
    fun aLookListsEachFileOnce() {
        assertEquals(
            listOf("t-pic-src.jpg", "t-pic-port.png", "t-pic-land.png", "t-key-space.png", "t-key-space-down.png", "t-key-a1.png", "t-font-keys.ttf"),
            FULL.files(),
        )
        assertEquals(emptyList<String>(), LookDefaults.look(PALETTE).files())
    }

    @Test
    fun filesMapBothWaysAndNothingElseChanges() {
        val paths = FULL.mapFiles { "/dir/$it" }
        assertEquals(FULL.files().map { "/dir/$it" }, paths.files())
        assertEquals(FULL, paths.mapFiles { it.removePrefix("/dir/") })
        assertEquals(FULL.palette, paths.palette)
        assertEquals("/dir/t-pic-land.png", paths.picture!!.landscape!!.file)
        assertNull(LookDefaults.look(PALETTE).mapFiles { "x" }.picture)
    }

    @Test
    fun valuesOutOfRangeDoNotRead() {
        val keys = LookDefaults.KEYS
        assertThrows(IllegalArgumentException::class.java) { keys.copy(radiusDp = 25) }
        assertThrows(IllegalArgumentException::class.java) { keys.copy(borderDp = 4) }
        assertThrows(IllegalArgumentException::class.java) { keys.copy(shadowDp = -1) }
        assertThrows(IllegalArgumentException::class.java) { Gap(25, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { PICTURE.copy(brightness = 101) }
        assertThrows(IllegalArgumentException::class.java) { PICTURE.copy(blurDp = 26) }
        assertThrows(IllegalArgumentException::class.java) { KeyFill.Picture(-1, 50) }
        assertThrows(IllegalArgumentException::class.java) { BarBase.Picture(0, 200) }
        assertThrows(IllegalArgumentException::class.java) { Crop("f", null, 45) }
        assertThrows(IllegalArgumentException::class.java) { NineSlice("f", Insets(0, 0, 0, 0), 0) }
        assertThrows(IllegalArgumentException::class.java) { Insets(-1, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { LookDefaults.TEXT.copy(keyWeight = 500) }
        assertThrows(IllegalArgumentException::class.java) { LookDefaults.TEXT.copy(keyScale = 84) }
        // a single key's name not of the form board/key
        assertThrows(IllegalArgumentException::class.java) {
            keys.copy(overrides = mapOf("qwerty/" to KeyOverride(null, null, KeyLabel.Show)))
        }
    }

    @Test
    fun thePictureShownMustBeThere() {
        val plain = LookDefaults.look(PALETTE)
        assertThrows(IllegalArgumentException::class.java) { plain.copy(base = BoardBase.Picture) }
        assertThrows(IllegalArgumentException::class.java) { plain.copy(bar = BarBase.Picture(0, 100)) }
        assertThrows(IllegalArgumentException::class.java) {
            plain.copy(keys = plain.keys.copy(letter = KindStyle(KeyFill.Picture(0, 100), KeyPressed.Darken)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plain.copy(keys = plain.keys.copy(overrides = mapOf("qwerty/q" to KeyOverride(KeyFill.Picture(0, 100), null, KeyLabel.Show))))
        }
        assertEquals(BoardBase.Picture, LookDefaults.look(PALETTE, PICTURE).base)
        assertEquals(BoardBase.Color, plain.base)
    }

    @Test
    fun theDefaultsAreTheOldDefaultSettings() {
        val k = LookDefaults.KEYS
        assertTrue(k.blocks)
        assertEquals(listOf(4, 0, 1), listOf(k.radiusDp, k.borderDp, k.shadowDp))
        assertEquals(Gap(3, 7, 3, 4), k.gap)
        assertEquals(PressEffect.Tint, k.press)
        assertEquals(k.letter, k.kind(KeyKind.Letter))
        assertEquals(k.function, k.kind(KeyKind.Function))
        assertEquals(k.space, k.kind(KeyKind.Space))
        assertEquals(k.enter, k.kind(KeyKind.Enter))
        assertFalse(k.showsPicture())
        assertEquals("Color/Darken/Transparent/Color/Picture", listOf(KeyFill.Color, KeyPressed.Darken, BarBase.Transparent, BarBase.Color, BoardBase.Picture).joinToString("/"))
    }
}
