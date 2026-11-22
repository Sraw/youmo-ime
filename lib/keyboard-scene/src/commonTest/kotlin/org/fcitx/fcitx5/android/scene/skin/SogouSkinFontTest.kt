/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convert
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.sogou
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SogouSkinFontTest {
    // the fixture's skin with [fonts] instead of its own, its TYPEFACE as [typeface] (none: no line)
    private fun skin(fonts: Map<String, ByteArray>, typeface: String? = null): MutableMap<String, ByteArray> {
        val files = sogou(periods = 1)
        files.remove("default/font.ttf")
        files.putAll(fonts)
        val ini = "1080/status1/phoneSkin.ini"
        files[ini] = text(files.getValue(ini).decodeToString().replace("TYPEFACE=font.ttf\n", typeface?.let { "TYPEFACE=$it\n" }.orEmpty()))
        return files
    }

    private fun fontOf(c: SkinConversion): ByteArray? =
        (c.files.firstOrNull { it.name.startsWith("skin-") } as? SkinOutput.Bytes)?.bytes

    @Test
    fun theSkinsOwnFontIsTakenUnnamed() {
        val c = convert(skin(mapOf("res_typeface/Cute.TTF" to text("cute"), "default/Roboto-Regular.ttf" to text("system"))))
        assertContains(c.themeXml, """<font name="skin" src="skin-cute.ttf"/>""")
        assertContentEquals(text("cute"), fontOf(c))
        assertFalse(SkinNote.FONT in c.notes)
    }

    @Test
    fun theNamedOneBeforeOthersAndTheFirstByName() {
        val fonts = mapOf("fonts/b.otf" to text("b"), "fonts/a.ttf" to text("a"), "res_typeface/c.ttf" to text("c"))
        assertContentEquals(text("c"), fontOf(convert(skin(fonts, typeface = "c.ttf"))))
        assertContentEquals(text("a"), fontOf(convert(skin(fonts))))
        // named elsewhere than the letters' style
        val other = skin(fonts)
        SogouFixtures.phoneSkin(other, "[TextStyle_Candidate_Big]\nTYPEFACE=b.otf\n")
        assertContentEquals(text("b"), fontOf(convert(other)))
    }

    @Test
    fun thePhonesOwnFontsAreNotCarried() {
        val c = convert(skin(mapOf("default/arial.ttf" to text("a"), "default/DROIDSANSFALLBACK.ttf" to text("d"), "fonts/NotoSansCJK.otf" to text("n")), typeface = "Roboto-Regular.ttf"))
        assertFalse("<font " in c.themeXml)
        // a system font named and not there is the phone's anyway
        assertFalse(SkinNote.FONT in c.notes)
        // named for the letters, the phone's: not another of the skin's (a candidate bar's separators, say)
        val sep = convert(skin(mapOf("res_typeface/sep.ttf" to text("s")), typeface = "Roboto-Regular.ttf"))
        assertFalse("<font " in sep.themeXml)
        assertFalse(SkinNote.FONT in sep.notes)
    }

    @Test
    fun aFontTooBigOrMissingIsSaid() {
        val big = convert(skin(mapOf("res_typeface/big.ttf" to ByteArray(8 * 1024 * 1024 + 1))))
        assertFalse("<font " in big.themeXml)
        assertTrue(SkinNote.FONT in big.notes)
        val missing = convert(skin(emptyMap(), typeface = "gone.ttf"))
        assertFalse("<font " in missing.themeXml)
        assertTrue(SkinNote.FONT in missing.notes)
        assertFalse(SkinNote.FONT in convert(skin(emptyMap())).notes)
    }
}
