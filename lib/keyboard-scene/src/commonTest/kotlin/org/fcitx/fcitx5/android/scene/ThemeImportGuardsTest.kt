/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The guards on an imported theme's files, names, counts and values. */
class ThemeImportGuardsTest {
    private val source = testData("material-light.xml")

    private fun problems(s: String) = ThemeReader.theme(s).problems().map { it.message }

    // an imported theme's guards, one test each: a font or icon named twice is a problem, as a colour is
    @Test
    fun aFontOrIconNamedTwiceIsAProblem() {
        assertTrue(problems(source.replace("</theme>", "<font name=\"f\" src=\"a.ttf\"/><font name=\"f\" src=\"b.ttf\"/></theme>")).any { it.contains("font f is given twice") })
        assertTrue(problems(source.replace("</theme>", "<icon name=\"i\" src=\"a.png\"/><icon name=\"i\" src=\"b.png\"/></theme>")).any { it.contains("icon i is given twice") })
        assertTrue(problems(source.replace("</theme>", "<font name=\"f\" src=\"a.ttf\"/><font name=\"g\" src=\"b.ttf\"/></theme>")).none { it.contains("given twice") })
    }

    // at most 4 fonts and 200 colours: the fifth and the 201st are problems
    @Test
    fun fontsAndColoursAreCounted() {
        val fonts = (1..5).joinToString("") { "<font name=\"f$it\" src=\"f$it.ttf\"/>" }
        assertTrue(problems(source.replace("</theme>", "$fonts</theme>")).any { it.contains("over 4 fonts") })
        val colours = (1..201).joinToString("") { "<color name=\"c$it\" value=\"#000000\"/>" }
        assertTrue(problems(source.replace("<colors>", "<colors>$colours")).any { it.contains("over 200 colours") })
    }

    // a file's name: dots alone or a control character are none; a name with dots in it is; image()'s file checked so
    @Test
    fun aFilesNameIsNotDotsAloneNorControls() {
        for (bad in listOf(".", "..", "...", "a\u0001b.png")) assertTrue(!ThemeReader.FILE.matches(bad), bad)
        for (good in listOf("a.png", "a.b.png", ".a.png")) assertTrue(ThemeReader.FILE.matches(good), good)
        assertNotNull(Values.fill("image(k.png, 0 0 0 0)"))
        assertNull(Values.fill("image(.., 0 0 0 0)"))
    }

    // decimals in sp=, picture() and linear()
    @Test
    fun decimalsReadInSizesPicturesAndGradients() {
        assertNotNull(Values.read("size", "15.25sp"))
        assertNotNull(Values.fill("picture(2.5dp, 72.5%)"))
        assertNotNull(Values.fill("linear(22.5deg, #000000 0%, #FFFFFF 100%)"))
    }
}
