/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 060-render-p3 A4: an animated picture read, written back; its blur warned of (not drawn), none without it
class AnimatedPictureTest {

    private val source = testData("material-light.xml")

    private fun with(attrs: String) =
        source.replace("</colors>", "</colors>\n<picture src=\"bg.webp\" $attrs><cut orientation=\"portrait\" file=\"p.png\"/></picture>")

    private fun warnings(s: String): List<String> {
        val t = ThemeReader.theme(s)
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

    @Test
    fun anAnimatedPictureReads() {
        val read = ThemeReader.theme(with("animated=\"true\" brightness=\"80%\"")).ok()
        assertTrue(assertNotNull(read.picture).animated)
        assertEquals(emptyList(), warnings(with("animated=\"true\"")))
        assertFalse(assertNotNull(ThemeReader.theme(with("animated=\"false\"")).ok().picture).animated)
        assertFalse(assertNotNull(ThemeReader.theme(with("")).ok().picture).animated)
        assertTrue(warnings(with("animated=\"true\" blur=\"4dp\"")).single().contains("not blurred"))
        assertEquals(emptyList(), warnings(with("blur=\"4dp\"")))
        val again = ThemeReader.theme(ThemeWriter.write(read)).ok()
        assertTrue(assertNotNull(again.picture).animated)
    }

    // over an animated picture a key showing it is see-through: its blur not drawn, never lighter than the board
    @Test
    fun aKeysPictureOverAnAnimatedOneIsWarnedOf() {
        fun keys(fill: String, animated: Boolean) = warnings(
            with("animated=\"$animated\" brightness=\"60%\"").replaceFirst("<style ", "<style select=\"key\" fill=\"$fill\"/>\n<style "),
        )
        assertTrue(keys("picture(4dp, 50%)", true).single().contains("blur"))
        assertTrue(keys("picture(0dp, 90%)", true).single().contains("no lighter"))
        assertEquals(emptyList(), keys("picture(0dp, 50%)", true))
        assertEquals(emptyList(), keys("picture(4dp, 90%)", false))
    }
}
