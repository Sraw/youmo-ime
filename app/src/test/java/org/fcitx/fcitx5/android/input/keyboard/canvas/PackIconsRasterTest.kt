/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.RasterPacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.io.path.createTempDirectory

/** A theme's PNG or WebP icon: decoded off the main thread at the size drawn, within its cache, read with the board. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PackIconsRasterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = createTempDirectory("raster-icons").toFile()
    private var harness: KeyboardHarness? = null

    @Before
    fun atOnce() = RasterPacks.atOnce()

    @After
    fun putBack() {
        harness?.close()
        RasterPacks.putBack()
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }.path

    private fun raster(icons: PackIcons, file: String, w: Int, h: Int) = (icons.icon(file, w, h) as? PackIcons.Raster)?.bitmap

    @Test
    fun aPictureIsDecodedToFitItsBoxNeverPastThreeTimes() {
        val f = file("wide.png", RasterPacks.png(200, 100, Color.RED))
        val icons = PackIcons(mapOf("x" to f))
        raster(icons, f, 50, 50)!!.let { assertEquals(50 to 25, it.width to it.height) }
        raster(icons, f, 1000, 1000)!!.let { assertEquals(600 to 300, it.width to it.height) }
        // the same box is the same bitmap, not decoded again
        val decodes = PackIcons.RASTERS.decodes.get()
        raster(icons, f, 50, 50)
        assertEquals(decodes, PackIcons.RASTERS.decodes.get())
    }

    @Test
    fun theCacheStaysWithinItsLimitAndABoardOverItIsNotDecodedForever() {
        // 768 px a side decoded: 2.36 MB each, 4 MB kept
        val files = (0 until 3).map { file("big$it.png", RasterPacks.png(256, 256, Color.BLUE)) }
        val icons = PackIcons(files.associateBy { it })
        icons.readAhead(emptyList())
        assertNotNull(raster(icons, files[0], 768, 768))
        // drawn on this board: the newer is not kept for it
        assertNull(raster(icons, files[1], 768, 768))
        val decodes = PackIcons.RASTERS.decodes.get()
        assertNull(raster(icons, files[1], 768, 768))
        assertEquals(decodes, PackIcons.RASTERS.decodes.get())
        // the board built anew: the one it no longer draws makes room
        icons.readAhead(emptyList())
        assertNotNull(raster(icons, files[1], 768, 768))
        assertEquals(0, PackIcons.RASTERS.keptOf(files[0]))
        assertTrue(PackIcons.RASTERS.bytes <= 4L * 1024 * 1024)
    }

    @Test
    fun aLowMemoryPhoneKeepsHalf() {
        val files = (0 until 3).map { file("half$it.png", RasterPacks.png(256, 256, Color.BLUE)) }
        val icons = PackIcons(files.associateBy { it })
        // 512 px a side: 1 MB each
        fun kept() = files.count { raster(icons, it, 512, 512) != null }
        PackIcons.lowMemory = true
        assertEquals(2, kept())
        ScenePainter.forgetIcons()
        PackIcons.lowMemory = false
        assertEquals(3, kept())
    }

    @Test
    fun aMaskTakesTheIconColourAPictureKeepsItsOwnAtItsAlpha() {
        val f = file("red.png", RasterPacks.png(24, 24, Color.RED))
        val icon = PackIcons(mapOf("x" to f)).icon(f, SIDE, SIDE)!!
        fun middle(tint: Int, mask: Boolean): Int {
            val b = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            PackIconDrawable(icon, Argb(tint), SIDE, SIDE, mask).apply { setBounds(0, 0, SIDE, SIDE) }.draw(Canvas(b))
            return b.getPixel(SIDE / 2, SIDE / 2)
        }
        assertEquals(Color.BLUE, middle(Color.BLUE, mask = true))
        val own = middle(Color.argb(0x80, 0, 0, 0xFF), mask = false)
        assertEquals(0xFF, Color.red(own))
        assertEquals(0, Color.blue(own))
        assertEquals(0x80.toDouble(), Color.alpha(own).toDouble(), 2.0)
        assertEquals(0, Color.alpha(middle(Color.TRANSPARENT, mask = false)))
    }

    // a restored backup's pack was never checked: over 256 px or 128 KB is the app's icon, not a decode
    @Test
    fun aFileOverItsLimitsIsNotDecodedTheAppsIsDrawn() {
        val name = SceneIcons.names.sorted().first { SceneIcons.of(it) != null }
        val look = ThemeManager.shownLook(ThemeManager.activeTheme)
        fun drawn(icons: PackIcons): Bitmap {
            val b = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            ScenePainter(View(context), look, BoardImages.NONE, SceneFonts(look), icons)
                .draw(Canvas(b), listOf(Op.Icon(name, org.fcitx.fcitx5.android.scene.Box(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble()), Argb(Color.WHITE), raster = true)))
            return b
        }
        val app = drawn(PackIcons.EMPTY)
        for ((n, bytes) in listOf("wide.png" to RasterPacks.png(300, 300, Color.RED), "heavy.png" to RasterPacks.png(256, 256, Color.RED, noise = true))) {
            if (n == "heavy.png") assertTrue(bytes.size > 128 * 1024)
            val f = file(n, bytes)
            val icons = PackIcons(mapOf(name to f))
            assertTrue(drawn(icons).sameAs(app))
            assertFalse(icons.draws(f))
        }
    }

    private fun shown(theme: Theme): KeyboardHarness {
        ThemeManager.setNormalModeTheme(theme)
        return KeyboardHarness(theme = theme).start().also { harness = it }
    }

    private fun backspace(h: KeyboardHarness): KeyNode = h.keys().filterIsInstance<SceneShownKey>().first { it.node.role == "backspace" }.node

    private fun middleOf(theme: Theme, node: KeyNode, pressed: Boolean, queued: MutableList<Runnable>): Int {
        val look = ThemeManager.shownLook(theme)
        val icon = node.ops(pressed).filterIsInstance<Op.Icon>().single { it.raster }
        val b = Bitmap.createBitmap(icon.box.right.toInt() + 1, icon.box.bottom.toInt() + 1, Bitmap.Config.ARGB_8888)
        ThemeAssets.runner = { queued += it }
        ScenePainter(View(context), look, BoardImages.NONE, SceneFonts(look), PackIcons.of(theme)).draw(Canvas(b), node.ops(pressed))
        return b.getPixel(((icon.box.left + icon.box.right) / 2).toInt(), ((icon.box.top + icon.box.bottom) / 2).toInt())
    }

    // read with the board: the first press is the theme's, no app's icon first and no decode then
    @Test
    fun thePressedIconIsReadWithTheBoard() {
        val theme = RasterPacks.pack(
            """<style select="key[role=backspace]:pressed icon" content="icon(backspace-down)"/>""" +
                """<icon name="backspace" src="bs.png"/><icon name="backspace-down" src="bs-down.png"/>""",
            mapOf("bs.png" to RasterPacks.png(48, 48, Color.RED), "bs-down.png" to RasterPacks.png(48, 48, Color.GREEN)),
        )[1]
        val h = shown(theme)
        val node = backspace(h)
        assertTrue(node.ops(true).filterIsInstance<Op.Icon>().single { it.raster }.name == "backspace-down")
        val queued = ArrayList<Runnable>()
        assertEquals(Color.RED, middleOf(theme, node, pressed = false, queued) or OPAQUE)
        assertEquals(Color.GREEN, middleOf(theme, node, pressed = true, queued) or OPAQUE)
        assertTrue(queued.isEmpty())
    }

    // a state the board does not show yet (Shift locked) is read at the size of the key's icon now
    @Test
    fun anIconOnlyAnotherStateNamesIsReadAhead() {
        val theme = RasterPacks.pack(
            """<style select="key[role=shift]:locked icon" content="icon(caps)"/><icon name="caps" src="caps.png"/>""",
            mapOf("caps.png" to RasterPacks.png(48, 48, Color.GREEN)),
        )[1]
        val h = shown(theme)
        // the board laid out, its scene built
        assertTrue(h.keys().filterIsInstance<SceneShownKey>().any { it.node.role == "shift" })
        assertEquals(1, PackIcons.RASTERS.keptOf(PackIcons.of(theme).file("caps")!!))
    }

    private fun shiftReadAhead(rest: String, name: String) {
        val theme = RasterPacks.pack(rest, mapOf("caps.png" to RasterPacks.png(48, 48, Color.GREEN)))[1]
        val h = shown(theme)
        assertTrue(h.keys().filterIsInstance<SceneShownKey>().any { it.node.role == "shift" })
        assertEquals(1, PackIcons.RASTERS.keptOf(PackIcons.of(theme).file(name)!!))
    }

    @Test
    fun aStateIconByItsOwnNameIsReadAhead() = shiftReadAhead("""<icon name="shift-locked" src="caps.png"/>""", "shift-locked")

    @Test
    fun aStateIconSetOnTheWholeKeyIsReadAhead() = shiftReadAhead(
        """<style select="key[role=shift]:locked" content="icon(caps)"/><icon name="caps" src="caps.png"/>""", "caps",
    )

    @Test
    fun aStateIconIsReadForTheKeysItsSelectorPicks() {
        val h = shown(RasterPacks.pack("", emptyMap())[1])
        val nodes = h.keys().filterIsInstance<SceneShownKey>().map { it.node }
        val shift = nodes.first { it.role == "shift" }
        val other = backspace(h)
        val star = PackIcons.Ahead(null, emptySet(), "f", starRole = "shift")
        assertTrue(star.picks(shift))
        assertFalse(star.picks(other))
        assertTrue(PackIcons.Ahead(null, emptySet(), "f", classes = setOf(shift.kind)).picks(shift))
        assertFalse(PackIcons.Ahead(null, emptySet(), "f", classes = setOf("space")).picks(shift))
        // a layout's own class, which a node does not carry
        assertTrue(PackIcons.Ahead(null, emptySet(), "f", classes = setOf("mine")).picks(shift))
    }

    // the selector's role after a star picks Shift alone, not every key with an icon
    @Test
    fun aStarRoleReadsAheadForItsRoleAlone() {
        val theme = RasterPacks.pack(
            """<style select="*/shift:locked icon" content="icon(caps)"/><icon name="caps" src="caps.png"/>""",
            mapOf("caps.png" to RasterPacks.png(48, 48, Color.GREEN)),
        )[1]
        val h = shown(theme)
        val caps = PackIcons.of(theme).ahead.single()
        val picked = h.keys().filterIsInstance<SceneShownKey>().map { it.node }.filter { caps.picks(it) }
        assertEquals(setOf("shift"), picked.map { it.role }.toSet())
    }

    // enter showing text now, an icon for another action: read at an icon key's size
    @Test
    fun anIconOfAKeyShowingTextNowIsReadAhead() {
        val texts = org.fcitx.fcitx5.android.scene.theme.Values.RETURN_ACTIONS.filter { it != "search" }.joinToString(" ") { """return-icon-$it="text(OK)"""" }
        val theme = RasterPacks.pack(
            """<style select="key[role=enter]" $texts return-icon-search="icon(find)"/><icon name="find" src="find.png"/>""",
            mapOf("find.png" to RasterPacks.png(48, 48, Color.GREEN)),
        )[1]
        val h = shown(theme)
        val enter = h.keys().filterIsInstance<SceneShownKey>().first { it.node.role == "enter" }.node
        assertTrue(enter.ops(false).none { it is Op.Icon })
        assertEquals(1, PackIcons.RASTERS.keptOf(PackIcons.of(theme).file("find")!!))
    }

    // the settings' caps build no board: one drawn there still makes room once more are made
    @Test
    fun theSettingsCapsLetGoOfWhatWasDrawnBefore() {
        val files = (0 until 2).map { file("cap$it.png", RasterPacks.png(256, 256, Color.BLUE)) }
        val icons = PackIcons(files.associateBy { it })
        icons.readAhead(emptyList())
        assertNotNull(raster(icons, files[0], 768, 768))
        assertNull(raster(icons, files[1], 768, 768))
        org.fcitx.fcitx5.android.ui.main.settings.KeyCaps.key(
            android.view.ContextThemeWrapper(context, org.fcitx.fcitx5.android.R.style.Theme_FcitxAppTheme), "W", "2", marked = false, dimmed = false,
        )
        assertNotNull(raster(icons, files[1], 768, 768))
    }

    // a menu's size or a state's not decoded yet: the theme's scaled, not the app's, till its own is in
    @Test
    fun anotherSizeIsLentTillItsOwnIsIn() {
        val f = file("lend.png", RasterPacks.png(96, 96, Color.RED))
        val icons = PackIcons(mapOf("x" to f))
        assertNotNull(raster(icons, f, SIDE, SIDE))
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        val lent = icons.icon(f, 30, 30) as PackIcons.Raster
        assertFalse(lent.exact)
        assertEquals(30 to 30, lent.width to lent.height)
        assertEquals(SIDE, lent.bitmap.width)
        queued.forEach { it.run() }
        val own = icons.icon(f, 30, 30) as PackIcons.Raster
        assertTrue(own.exact)
        assertEquals(30, own.bitmap.width)
    }

    // a restored backup's file whose header reads and whose pixels do not
    @Test
    fun aFileWhosePixelsDoNotReadIsBroken() {
        val bytes = RasterPacks.png(48, 48, Color.RED, noise = true)
        val data = String(bytes, Charsets.ISO_8859_1).indexOf("IDAT") + 4 + 2
        bytes.fill(0, data, bytes.size - 12)
        val f = file("bad.png", bytes)
        val icons = PackIcons(mapOf("x" to f))
        assertNull(icons.icon(f, SIDE, SIDE))
        assertTrue(PackIcons.RASTERS.broken(f))
        assertFalse(icons.draws(f))
    }

    private companion object {
        const val SIDE = 48
        const val OPAQUE = 0xFF000000.toInt()
    }
}
