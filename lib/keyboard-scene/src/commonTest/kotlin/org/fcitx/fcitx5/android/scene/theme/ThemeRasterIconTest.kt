/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.icons
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Fixtures.problems
import org.fcitx.fcitx5.android.scene.Fixtures.theme
import org.fcitx.fcitx5.android.scene.Fixtures.warnings
import org.fcitx.fcitx5.android.scene.Fixtures.withStyle
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.engine.Paint
import org.fcitx.fcitx5.android.scene.engine.Shift
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A theme's PNG and WebP icons, their headers, and `content`, the icon a key draws in a state. */
class ThemeRasterIconTest {

    @Test
    fun pngAndStillWebpIconsAreReadAndTintIsTheirs() {
        val t = theme("""<icon name="backspace" src="b.png"/>""", """<icon name="shift" src="s.WEBP" tint="true"/>""")
        assertEquals(mapOf("backspace" to "b.png", "shift" to "s.WEBP"), t.icons)
        assertEquals(setOf("shift"), t.tintedIcons)
        assertTrue(problems(withStyle("""<icon name="backspace" src="b.svg" tint="true"/>""")).single().contains("tint="))
        // another suffix is SVG's, as 0.6.2 read it: its content is checked, not its name
        assertEquals("back.icon", theme("""<icon name="backspace" src="back.icon"/>""").icons["backspace"])
        assertTrue(problems(withStyle("""<icon name="backspace" src="b.png" tint="yes"/>""")).single().contains("tint="))
        // written back as read
        assertEquals(t.tintedIcons, ThemeReader.theme(ThemeWriter.write(t)).ok().tintedIcons)
    }

    @Test
    fun theHeaderIsReadNotThePixels() {
        for (side in listOf(1, 64, 256, 300)) {
            for (b in listOf(png(side, side), vp8(side, side), vp8l(side, side), vp8x(side, side, animated = false))) {
                val h = RasterIcon.header(b)
                assertEquals(side to side, h?.width to h?.height, "$side")
                assertFalse(h!!.animated)
            }
        }
        assertEquals(300 to 1, RasterIcon.header(png(300, 1))?.let { it.width to it.height })
        assertEquals(2 to 257, RasterIcon.header(vp8l(2, 257))?.let { it.width to it.height })
        assertTrue(RasterIcon.header(vp8x(4, 4, animated = true))!!.animated)
        assertNull(RasterIcon.header(png(4, 4).copyOf(20)))
        assertNull(RasterIcon.header("GIF89a".encodeToByteArray() + ByteArray(40)))
        // a side not positive: no image, so no side to check
        assertNull(RasterIcon.header(png(0x80000000.toInt(), 1)))
        assertNull(RasterIcon.header(png(1, 0)))
        assertNull(RasterIcon.header(vp8(0, 0)))
        assertTrue(RasterIcon.iconProblem(png(-1, -1))!!.contains("not a PNG"))
    }

    @Test
    fun anIconPastItsLimitsIsTold() {
        assertNull(RasterIcon.iconProblem(png(256, 256)))
        assertNull(RasterIcon.iconProblem(vp8(256, 10)))
        assertTrue(RasterIcon.iconProblem(png(257, 10))!!.contains("over 256 px"))
        assertTrue(RasterIcon.iconProblem(vp8x(16, 16, animated = true))!!.contains("animated"))
        assertTrue(RasterIcon.iconProblem(png(16, 16) + ByteArray(129 * 1024))!!.contains("over 128 KB"))
        assertNull(RasterIcon.iconProblem(png(16, 16) + ByteArray(128 * 1024 - png(16, 16).size)))
        assertTrue(RasterIcon.iconProblem(ByteArray(64))!!.contains("not a PNG"))
    }

    @Test
    fun contentIsAnIconOfTheAppsOrTheThemes() {
        assertTrue(problems(withStyle("""<style select="key[role=backspace] icon" content="text(x)"/>""")).first().contains("is not a value of content"))
        assertTrue(problems(withStyle("""<style select="key[role=backspace] icon" content="icon(nope)"/>""")).first().contains("icon(nope)"))
        val own = withStyle("""<style select="key[role=backspace]:pressed icon" content="icon(backspace-pressed)"/>""", """<icon name="backspace-pressed" src="p.png"/>""")
        // named by content alone, it is drawn
        assertEquals(emptyList<String>(), warnings(own).filter { !it.contains("min-app") })
        assertEquals(
            listOf("content= on a key's label does nothing: it is read from its icon"),
            warnings(withStyle("""<style select="key[role=backspace] label" content="icon(undo)"/>""")).filter { !it.contains("min-app") },
        )
        assertEquals(
            listOf("content= on a .letter key does nothing: it has no icon"),
            warnings(withStyle("""<style select="key.letter icon" content="icon(undo)"/>""")).filter { !it.contains("min-app") },
        )
    }

    @Test
    fun contentChangesTheIconInItsStateAlone() {
        val t = theme(
            """<style select="key[role=backspace]:pressed icon" content="icon(undo)"/>""",
            """<style select="key[role=shift]:shifted icon" content="icon(caps)"/>""",
            """<icon name="caps" src="caps.png" tint="true"/>""",
        )
        val s = Fixtures.scene(Fixtures.portrait, theme = t)
        assertEquals("backspace", icons(s, "backspace").first().name)
        assertEquals("undo", pressedIcons(s, "backspace").first().name)
        // the built-in shift-once is the theme's content, a raster drawn by its alpha
        val once = Fixtures.scene(Fixtures.portrait.copy(shift = Shift.Once), theme = t)
        val shift = icons(once, "shift").single()
        assertEquals("caps" to (true to true), shift.name to (shift.raster to shift.tintMask))
        assertEquals(listOf("shift"), icons(s, "shift").map { it.name })
        assertFalse(icons(s, "shift").single().raster)
    }

    @Test
    fun returnsOwnIconComesBeforeContent() {
        val both = theme(
            """<style select="key[role=enter] icon" content="icon(undo)" return-icon-newline="icon(redo)"/>""",
        )
        assertEquals(listOf("redo"), icons(Fixtures.scene(Fixtures.portrait, theme = both), "enter").map { it.name })
        assertEquals(listOf("redo"), pressedIcons(Fixtures.scene(Fixtures.portrait, theme = both), "enter").map { it.name })
        val content = theme("""<style select="key[role=enter]:pressed icon" content="icon(undo)"/>""")
        val s = Fixtures.scene(Fixtures.portrait, theme = content)
        assertEquals(listOf("enter"), icons(s, "enter").map { it.name })
        assertEquals(listOf("undo"), pressedIcons(s, "enter").map { it.name })
    }

    @Test
    fun contentOnTheSpacesIconIsTheStatesOwn() {
        val t = theme(
            """<style select="*/space" space-content="icon(language)"/>""",
            """<style select="*/space:pressed icon" content="icon(undo)"/>""",
        )
        val s = Fixtures.scene(Fixtures.portrait, theme = t)
        // its glyph's, not its marks'
        assertEquals("language", icons(s, "space").first().name)
        assertEquals("undo", pressedIcons(s, "space").first().name)
    }

    @Test
    fun anIconMadeOutsideTheSceneIsARasterAsInIt() {
        val t = theme("""<icon name="caps" src="caps.png" tint="true"/>""", """<icon name="shift" src="s.webp"/>""")
        val box = Box(0.0, 0.0, 24.0, 24.0)
        val caps = Paint.icon(t, "caps", box, Argb(0xFF000000.toInt()))
        val shift = Paint.icon(t, "shift", box, Argb(0xFF000000.toInt()))
        assertEquals(listOf(true to true, true to false), listOf(caps.raster to caps.tintMask, shift.raster to shift.tintMask))
        assertFalse(Paint.icon(t, "backspace", box, Argb(0xFF000000.toInt())).raster)
    }

    @Test
    fun contentIsWrittenBackAsRead() {
        val t = theme("""<style select="key[role=backspace]:pressed icon" content="icon(undo)"/>""")
        val again = ThemeReader.theme(ThemeWriter.write(t)).ok()
        assertEquals(t.styles.map { it.values }, again.styles.map { it.values })
    }

    @Test
    fun aRasterIconOrContentIsOf063() {
        assertFalse(ThemeFeatures.since063(Fixtures.materialLight))
        assertFalse(ThemeFeatures.since063(theme("""<icon name="backspace" src="b.svg"/>""")))
        assertTrue(ThemeFeatures.since063(theme("""<icon name="backspace" src="b.png"/>""")))
        assertTrue(ThemeFeatures.since063(theme("""<style select="key[role=backspace] icon" content="icon(undo)"/>""")))
    }

    private fun pressedIcons(s: Scene, id: String) = Fixtures.ops(s, id, pressed = true).filterIsInstance<Op.Icon>()

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun be32(v: Int) = bytes(v ushr 24, v ushr 16 and 0xFF, v ushr 8 and 0xFF, v and 0xFF)
    private fun le16(v: Int) = bytes(v and 0xFF, v ushr 8 and 0xFF)
    private fun le24(v: Int) = bytes(v and 0xFF, v ushr 8 and 0xFF, v ushr 16 and 0xFF)
    private fun le32(v: Int) = bytes(v and 0xFF, v ushr 8 and 0xFF, v ushr 16 and 0xFF, v ushr 24 and 0xFF)

    private fun png(w: Int, h: Int) =
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + "IHDR".encodeToByteArray() + be32(w) + be32(h) + bytes(8, 6, 0, 0, 0)

    private fun riff(chunk: String, data: ByteArray) =
        "RIFF".encodeToByteArray() + le32(4 + 8 + data.size) + "WEBP".encodeToByteArray() + chunk.encodeToByteArray() + le32(data.size) + data

    private fun vp8(w: Int, h: Int) = riff("VP8 ", bytes(0, 0, 0, 0x9D, 0x01, 0x2A) + le16(w) + le16(h))

    private fun vp8l(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return riff("VP8L", bytes(0x2F) + le32(bits))
    }

    private fun vp8x(w: Int, h: Int, animated: Boolean) = riff("VP8X", bytes(if (animated) 0x02 else 0, 0, 0, 0) + le24(w - 1) + le24(h - 1))
}
