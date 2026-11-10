/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.theme.LookFixtures.PALETTE
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePackCheckTest {

    // the default look as 4.0: [before] ahead of its first style (a picture, fonts), [styles] after its
    // styles, [after] at its end (icons), [meta] before its colours, [colors] over its colours
    private fun theme(
        styles: String = "",
        meta: String = "",
        before: String = "",
        after: String = "",
        colors: Map<String, String> = emptyMap(),
    ): Theme {
        var xml = LookTheme.xml(LookDefaults.look(PALETTE), "t", false)
        colors.forEach { (name, value) ->
            xml = xml.replace(Regex("""<color name="$name" value="[^"]*"/>"""), """<color name="$name" value="$value"/>""")
        }
        val first = xml.indexOf("<style ")
        xml = xml.substring(0, first) + before + xml.substring(first)
        xml = xml.replace("<colors>", "$meta<colors>").replace("</theme>", "$styles$after</theme>")
        return when (val t = ThemeReader.theme(xml)) {
            is Parsed.Ok -> t.value
            is Parsed.Failed -> error(t.problems.toString())
        }
    }

    @Test
    fun theFilesATheme4NamesAreEachByWhatTheyAre() {
        val t = theme(
            styles = """<style select="key.enter" fill="image(e.png, 1 2 3 4)"/><style select="key:pressed" fill="image(p.png, 1 1 1 1, 2x)"/>""",
            meta = """<meta author="a" preview="pre.png"/>""",
            before = """<picture src="src.jpg"><cut orientation="portrait" file="p.webp"/><cut orientation="landscape" file="l.webp"/></picture><font name="f" src="f.ttf"/>""",
            after = """<icon name="i" src="i.svg"/>""",
        )
        val f = ThemePackCheck.files(t)
        assertEquals(setOf("p.webp", "l.webp"), f.pictures)
        assertEquals("src.jpg", f.source)
        assertEquals(setOf("e.png", "p.png"), f.images)
        assertEquals(setOf("f.ttf"), f.fonts)
        assertEquals(setOf("i.svg"), f.icons)
        assertEquals("pre.png", f.preview)
        assertEquals(setOf("p.webp", "l.webp", "src.jpg", "e.png", "p.png", "f.ttf", "i.svg", "pre.png"), f.all)
        assertEquals(ThemePackCheck.Files(emptySet(), null, emptySet(), emptySet(), emptySet(), null), ThemePackCheck.files(theme()))
    }

    @Test
    fun hidingTheKeysOneCannotTypeWithoutIsFound() {
        assertEquals(emptyList<Int>(), ThemePackCheck.hidesRequired(theme()))
        fun found(style: String) = ThemePackCheck.hidesRequired(theme(styles = style)).size
        // every key, the function keys, the space, by role or by name: found
        assertEquals(1, found("""<style select="key" hidden="true"/>"""))
        assertEquals(1, found("""<style select="key.function" hidden="true"/>"""))
        assertEquals(1, found("""<style select="key[role=space] label" hidden="true"/>"""))
        assertEquals(1, found("""<style select="*/backspace" hidden="true"/>"""))
        assertEquals(1, found("""<style select="qwerty/shift icon" hidden="true"/>"""))
        assertEquals(1, found("""<style select="qwerty/*" hidden="true"/>"""))
        assertEquals(1, found("""<style select="key.letter, key.enter" hidden="true"/>"""))
        // the letters, a key of no such role, a mark, or not hidden: not
        assertEquals(0, found("""<style select="key.letter" hidden="true"/>"""))
        assertEquals(0, found("""<style select="qwerty/q" hidden="true"/>"""))
        assertEquals(0, found("""<style select="*/comma" hidden="true"/>"""))
        // a key whose face is an image of its own (as 3.0 drew one, converted): its label hidden is no blank key
        assertEquals(0, found("""<style select="*/enter" fill="image(e.png, 0 0 0 0, 3x)"/><style select="*/enter label" hidden="true"/>"""))
        // an image only when pressed, or on another key: still blank
        assertEquals(1, found("""<style select="*/enter:pressed" fill="image(e.png, 0 0 0 0, 3x)"/><style select="*/enter label" hidden="true"/>"""))
        assertEquals(1, found("""<style select="*/space" fill="image(e.png, 0 0 0 0, 3x)"/><style select="*/enter label" hidden="true"/>"""))
        assertEquals(0, found("""<style select="key mark" hidden="true"/>"""))
        assertEquals(0, found("""<style select="key" hidden="false"/>"""))
    }

    @Test
    fun wordsHardToReadOnTheirKeysAreFoundByVersion() {
        assertEquals(emptyList<Boolean>(), ThemePackCheck.lowContrast(theme(colors = mapOf("keyText" to "#000000", "key" to "#ffffff"))))
        assertEquals(listOf(false), ThemePackCheck.lowContrast(theme(colors = mapOf("keyText" to "#777777", "key" to "#888888"))))
    }
}
