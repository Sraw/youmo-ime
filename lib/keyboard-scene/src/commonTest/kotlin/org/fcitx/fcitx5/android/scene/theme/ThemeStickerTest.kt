/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Fixtures.ops
import org.fcitx.fcitx5.android.scene.Fixtures.problems
import org.fcitx.fcitx5.android.scene.Fixtures.theme
import org.fcitx.fcitx5.android.scene.Fixtures.warnings
import org.fcitx.fcitx5.android.scene.Fixtures.withStyle
import org.fcitx.fcitx5.android.scene.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `sticker`: a theme's image over a key's face, under its words. */
class ThemeStickerTest {

    private fun sticker(t: Theme, key: String, pressed: Boolean = false) =
        ops(Fixtures.scene(Fixtures.portrait, theme = t), key, pressed).filterIsInstance<Op.Sticker>().singleOrNull()

    @Test
    fun aPngAStillWebpOrNone() {
        val t = theme("""<style select="qwerty/q" sticker="st-q.png"/>""", """<style select="qwerty/w" sticker="st-w.webp"/>""")
        assertEquals("st-q.png", sticker(t, "q")?.file)
        assertEquals("st-w.webp", sticker(t, "w")?.file)
        assertNull(sticker(t, "e"))
        assertNull(sticker(theme("""<style select="key" sticker="all.png"/>""", """<style select="qwerty/q" sticker="none"/>"""), "q"))
        // a file 0.6.0–0.6.2 took for a reserved sticker: warned of and dropped, the pack still read
        val gif = withStyle("""<style select="key" sticker="st.gif"/>""")
        assertEquals(listOf("sticker=\"st.gif\" is no PNG or WebP: ignored"), warnings(gif))
        assertNull(sticker(ThemeReader.theme(gif).ok(), "q"))
        assertTrue(problems(withStyle("""<style select="key" sticker="a/b.png"/>""")).first().contains("is not a value of sticker"))
        val many = (0..RasterIcon.MAX_STICKERS).map { """<style select="qwerty/q:pressed" sticker="s$it.png"/>""" }
        assertEquals(listOf("over ${RasterIcon.MAX_STICKERS} sticker files"), problems(withStyle(*many.toTypedArray())))
        assertTrue(problems(withStyle(*many.dropLast(1).toTypedArray())).isEmpty())
    }

    @Test
    fun aPackOf062WithAReservedStickerStillReads() {
        // as 0.6.2's reserved.xml wrote it, the file never in the pack
        val t = theme("""<style select="key" sticker="cat.png"/>""")
        assertEquals("cat.png", sticker(t, "q")?.file)
        assertEquals("cat.png", ThemeReader.theme(ThemeWriter.write(t)).ok().let { sticker(it, "q")?.file })
    }

    @Test
    fun aKeysWholeFaceHasOne() {
        assertEquals(
            listOf("sticker= on a key's label does nothing: it is the key's, over its face"),
            warnings(withStyle("""<style select="qwerty/q label" sticker="q.png"/>""")).filter { !it.contains("min-app") },
        )
        assertEquals(
            listOf("sticker= on popup does nothing: only a key has one"),
            warnings(withStyle("""<style select="popup" sticker="q.png"/>""")).filter { !it.contains("min-app") },
        )
    }

    @Test
    fun aStateWithoutItsOwnHasTheNormalOne() {
        val t = theme("""<style select="qwerty/q" sticker="q.png"/>""", """<style select="qwerty/w" sticker="w.png"/>""", """<style select="qwerty/w:pressed" sticker="w-p.png"/>""")
        assertEquals("q.png", sticker(t, "q", pressed = true)?.file)
        assertEquals("w.png", sticker(t, "w")?.file)
        assertEquals("w-p.png", sticker(t, "w", pressed = true)?.file)
    }

    @Test
    fun overTheFaceUnderTheWordsStandingInForAHiddenLabel() {
        val shown = ops(Fixtures.scene(Fixtures.portrait, theme = theme("""<style select="qwerty/q" sticker="q.png"/>""")), "q")
        val at = shown.indexOfFirst { it is Op.Sticker }
        assertTrue(at > 0 && shown.take(at).none { it is Op.Text || it is Op.Icon } && shown.drop(at + 1).any { it is Op.Text }, "$shown")
        // its label drawn: none stands in
        assertEquals(emptyList(), (shown[at] as Op.Sticker).standIn)
        val t = theme("""<style select="qwerty/q" sticker="q.png"/>""", """<style select="qwerty/q label" hidden="true"/>""")
        val hidden = ops(Fixtures.scene(Fixtures.portrait, theme = t), "q")
        val s = hidden.filterIsInstance<Op.Sticker>().single()
        assertTrue(hidden.none { it is Op.Text && it.text == "q" }, "$hidden")
        assertEquals(listOf("q"), s.standIn.filterIsInstance<Op.Text>().map { it.text })
        assertTrue(s.box.width > 0 && s.box.height > 0)
    }

    @Test
    fun anIconKeysStandInIsItsIcon() {
        val t = theme("""<style select="*/shift" sticker="shift.png"/>""", """<style select="*/shift label" hidden="true"/>""")
        val ops = ops(Fixtures.scene(Fixtures.portrait, theme = t), "shift")
        val s = ops.filterIsInstance<Op.Sticker>().single()
        assertEquals(listOf("shift"), s.standIn.filterIsInstance<Op.Icon>().map { it.name })
        assertTrue(ops.none { it is Op.Icon }, "$ops")
    }

    @Test
    fun aShiftWithAStickerIsNotBlank() {
        val boards = listOf(BlankKeys.Board(Fixtures.qwerty))
        val hidden = arrayOf("""<style select="*/shift icon" hidden="true"/>""", """<style select="*/shift label" hidden="true"/>""")
        assertEquals(listOf("qwerty/shift"), BlankKeys.find(theme(*hidden), boards).map { "${it.board}/${it.key}" })
        assertEquals(emptyList<BlankKeys.Blank>(), BlankKeys.find(theme(*hidden, """<style select="*/shift" sticker="shift.png"/>"""), boards))
        // none where the hiding is: blank there
        val dark = theme(*hidden, """<style select="*/shift" sticker="shift.png"/>""", """<style select="@dark */shift" sticker="none"/>""")
        assertEquals(listOf(setOf("dark")), BlankKeys.find(dark, boards).map { it.scenes })
    }

    @Test
    fun aStickerIsOf063ButNoneIsNot() {
        assertTrue(ThemeFeatures.since063(theme("""<style select="qwerty/q" sticker="q.png"/>""")))
        assertTrue(!ThemeFeatures.since063(theme("""<style select="qwerty/q" sticker="none"/>""")))
    }
}
