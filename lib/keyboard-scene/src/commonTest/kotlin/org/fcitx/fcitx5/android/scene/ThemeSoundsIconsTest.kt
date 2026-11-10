/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 060-theme-sounds-icons, a theme read: its `<sounds>`, and its own `<icon>`s named by `icon()`. */
class ThemeSoundsIconsTest {

    private val source = testData("material-light.xml")

    private fun problems(s: String) = ThemeReader.theme(s).problems().map { it.message }

    private fun withStyle(style: String) = source.replace("</theme>", "$style\n</theme>")

    private fun warnings(s: String): List<String> {
        val t = ThemeReader.theme(s)
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

    // 060-theme-sounds-icons §1.1: <sounds>, last; a class a <sound>, a later version's warned of and kept
    @Test
    fun soundsAreRead() {
        fun with(sounds: String) = source.replace("</theme>", "$sounds\n</theme>")
        val t = ThemeReader.theme(with("<sounds><sound key=\"letter\" src=\"tap.wav\"/><sound key=\"future\" src=\"f.ogg\" volume=\"50%\"/><sound key=\"space\" src=\"s.mp3\"/></sounds>"))
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        assertEquals(listOf("letter" to "tap.wav", "future" to "f.ogg", "space" to "s.mp3"), t.value.soundFiles)
        assertEquals(mapOf(SoundKey.LETTER to "tap.wav", SoundKey.SPACE to "s.mp3"), t.value.sounds)
        assertEquals(
            listOf("<sound volume=> is not supported by this version, ignored", "<sound key=\"future\"> is not supported by this version, ignored"),
            t.warnings().map { it.message },
        )
        // its other version keeps them
        assertEquals(t.value.soundFiles, t.value.variant(!t.value.dark).soundFiles)
        assertEquals(listOf("<sounds x=> is not supported by this version, ignored"), warnings(with("<sounds x=\"1\"/>")))
        assertTrue(warnings(with("<sounds/>")).isEmpty())
        // twice, a class twice, over 16, a missing attribute, a bad file name, another element: refused
        assertEquals(listOf("<sounds> given twice"), problems(with("<sounds/><sounds/>")))
        assertEquals(
            listOf("the sound letter is given twice"),
            problems(with("<sounds><sound key=\"letter\" src=\"a.wav\"/><sound key=\"letter\" src=\"b.wav\"/></sounds>")),
        )
        val many = (1..17).joinToString("") { "<sound key=\"k$it\" src=\"$it.wav\"/>" }
        assertEquals(listOf("over 16 sounds"), problems(with("<sounds>$many</sounds>")))
        assertEquals(listOf("<sound>: src= is required"), problems(with("<sounds><sound key=\"letter\"/></sounds>")))
        assertEquals(listOf("<sound>: key= is required"), problems(with("<sounds><sound src=\"a.wav\"/></sounds>")))
        assertTrue(problems(with("<sounds><sound key=\"letter\" src=\"a/b.wav\"/></sounds>")).single().contains("src="))
        assertTrue(problems(with("<sounds><sound key=\"Letter!\" src=\"a.wav\"/></sounds>")).single().contains("key="))
        assertEquals(listOf("<noise> is not allowed in <sounds>"), problems(with("<sounds><noise/></sounds>")))
        // a <sound> of its own, out of <sounds>: no longer reserved
        assertTrue(problems(with("<sound key=\"letter\" src=\"a.wav\"/>")).single().contains("<sound> is not allowed in <theme>"))
        // last: after <icon>
        val icon = "<icon name=\"backspace\" src=\"b.svg\"/>"
        assertTrue(problems(with("<sounds/>$icon")).single().contains("<icon> is out of order"))
        assertTrue(ThemeReader.theme(with("$icon<sounds/>")) is Parsed.Ok)
    }

    // 060-theme-sounds-icons §2.1: icon() names the app's icons or the theme's own; an <icon> drawn nowhere warned of
    @Test
    fun aThemesOwnIcons() {
        val star = "<icon name=\"star\" src=\"star.svg\"/>"
        val t = ThemeReader.theme(withStyle("<style select=\"*/space\" space-content=\"icon(star)\"/>$star"))
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        assertTrue(t.warnings().isEmpty(), "${t.warnings()}")
        // replacing the app's: drawn, no warning
        assertTrue(warnings(source.replace("</theme>", "<icon name=\"backspace\" src=\"b.svg\"/>\n</theme>")).isEmpty())
        // a new name nothing names
        assertEquals(
            listOf("<icon name=\"star\"> is no icon of the app's and no icon() names it: it is not drawn"),
            warnings(source.replace("</theme>", "$star\n</theme>")),
        )
    }
}
