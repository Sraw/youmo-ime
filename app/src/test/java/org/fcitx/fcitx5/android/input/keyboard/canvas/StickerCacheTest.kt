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
import org.fcitx.fcitx5.android.scene.engine.Part
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** A sticker over a key: decoded at the size drawn, its own cache, the label it hides drawn till it is in, read with the board. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class StickerCacheTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = createTempDirectory("stickers").toFile()
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

    @Test
    fun aStickerIsDecodedToFitItsBox() {
        val f = file("tall.png", RasterPacks.png(100, 200, Color.RED))
        PackIcons.EMPTY.sticker(f, 50, 50)!!.let { assertEquals(25 to 50, it.width to it.height) }
    }

    @Test
    fun aLowMemoryPhoneKeepsHalf() {
        val files = (0 until 4).map { file("s$it.png", RasterPacks.png(512, 512, Color.RED)) }
        // 1 MB each: 3 of 6 MB a low-memory phone
        fun kept() = files.count { PackIcons.EMPTY.sticker(it, 512, 512) != null }
        PackIcons.lowMemory = true
        assertEquals(3, kept())
        ScenePainter.forgetIcons()
        PackIcons.lowMemory = false
        assertEquals(4, kept())
        assertTrue(PackIcons.STICKERS.bytes <= 6L * 1024 * 1024)
    }

    private fun letters(): Theme = RasterPacks.pack(
        """<style select="qwerty/q" sticker="q.png"/><style select="qwerty/q:pressed" sticker="q-down.png"/>""" +
            """<style select="qwerty/q label" hidden="true"/>""",
        mapOf("q.png" to RasterPacks.png(60, 90, Color.RED), "q-down.png" to RasterPacks.png(60, 90, Color.GREEN)),
    )[1]

    private fun q(theme: Theme): KeyNode {
        ThemeManager.setNormalModeTheme(theme)
        val h = KeyboardHarness(theme = theme).start().also { harness = it }
        return h.keys().filterIsInstance<SceneShownKey>().first { it.node.id == "q" }.node
    }

    private class Drawn(val bitmap: Bitmap, val missed: Boolean)

    private fun draw(theme: Theme, ops: List<Op>, b: Bitmap): Drawn {
        val look = ThemeManager.shownLook(theme)
        val painter = ScenePainter(View(context), look, BoardImages.NONE, SceneFonts(look), PackIcons.of(theme))
        painter.draw(Canvas(b), ops)
        return Drawn(b, painter.missed)
    }

    private fun blank(node: KeyNode) = Bitmap.createBitmap(node.bounds.right.toInt() + 1, node.bounds.bottom.toInt() + 1, Bitmap.Config.ARGB_8888)

    private fun middle(node: KeyNode, b: Bitmap) = b.getPixel(((node.bounds.left + node.bounds.right) / 2).toInt(), ((node.bounds.top + node.bounds.bottom) / 2).toInt())

    // never a blank key: the letter till the sticker is in, then the sticker
    @Test
    fun theLabelItHidesIsDrawnTillItIsDecoded() {
        val theme = letters()
        val node = q(theme)
        val ops = node.ops(false)
        val sticker = ops.filterIsInstance<Op.Sticker>().single()
        assertTrue(sticker.standIn.isNotEmpty())
        ScenePainter.forgetIcons()
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        val waiting = draw(theme, ops, blank(node))
        assertTrue(waiting.missed)
        val letter = draw(theme, ops.flatMap { if (it is Op.Sticker) it.standIn else listOf(it) }, blank(node))
        assertTrue(waiting.bitmap.sameAs(letter.bitmap))
        queued.forEach { it.run() }
        val done = draw(theme, ops, blank(node))
        assertFalse(done.missed)
        assertEquals(Color.RED, middle(node, done.bitmap))
    }

    // read with the board: the first press draws the pressed sticker, nothing decoded then
    @Test
    fun thePressedStickerIsReadWithTheBoard() {
        val theme = letters()
        val node = q(theme)
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        val pressed = draw(theme, node.ops(true), blank(node))
        assertTrue(queued.isEmpty())
        assertFalse(pressed.missed)
        assertEquals(Color.GREEN, middle(node, pressed.bitmap))
    }

    // the settings' cap of a key whose long press is off fades its words, and so what stands for them
    @Test
    fun aFadedLabelFadesItsStickerAndWhatStandsInForIt() {
        val theme = RasterPacks.pack(
            """<style select="key" sticker="s.png"/><style select="key label" hidden="true"/>""",
            mapOf("s.png" to RasterPacks.png(60, 90, Color.RED)),
        )[1]
        val cell = SceneKeyCell(context, theme, ownSize = true).apply {
            attrs = KeyAttrs(label = Text.Literal("a"))
            layout(0, 0, 160, 160)
        }
        val full = cell.drawn().filterIsInstance<Op.Sticker>().single()
        cell.labelAlpha = 0.4
        val faded = cell.drawn().filterIsInstance<Op.Sticker>().single()
        assertEquals(full.alpha * 0.4, faded.alpha, 1e-9)
        fun label(s: Op.Sticker) = s.standIn.filterIsInstance<Op.Text>().single { it.part == Part.Label.xml }
        assertEquals((label(full).color.alpha * 0.4).toInt().toDouble(), label(faded).color.alpha.toDouble(), 1.0)
    }

    // what shows now is read first: past the budget it is a press not shown yet that goes unkept
    @Test
    fun overItsBudgetABoardKeepsWhatItShowsNow() {
        PackIcons.lowMemory = true
        val box = org.fcitx.fcitx5.android.scene.Box(0.0, 0.0, 600.0, 600.0)
        fun node(id: String) = KeyNode(
            id, 0, "letter", null, org.fcitx.fcitx5.android.scene.Shape.Rect(box), box, org.fcitx.fcitx5.android.scene.Shape.Rect(box),
            org.fcitx.fcitx5.android.scene.Point(0.0, 0.0), org.fcitx.fcitx5.android.scene.Point(0.0, 0.0), emptySet(),
            mapOf(
                emptySet<org.fcitx.fcitx5.android.scene.KeyState>() to listOf(Op.Sticker(file("$id.png", RasterPacks.png(256, 256, Color.RED)), box)),
                setOf(org.fcitx.fcitx5.android.scene.KeyState.Pressed) to listOf(Op.Sticker(file("$id-p.png", RasterPacks.png(256, 256, Color.GREEN)), box)),
            ),
            id, emptyMap(),
        )
        // 600 px a side: 1.44 MB each, two of 3 MB kept
        PackIcons.EMPTY.readAhead(listOf(node("a"), node("b")))
        assertEquals(1, PackIcons.STICKERS.keptOf(File(dir, "a.png").path))
        assertEquals(1, PackIcons.STICKERS.keptOf(File(dir, "b.png").path))
        assertEquals(0, PackIcons.STICKERS.keptOf(File(dir, "a-p.png").path))
    }

    // a sticker of Shift's once state, decoded at the content it is drawn in (a circle's face is not it)
    @Test
    fun aStateStickerIsReadAtTheBoxItIsDrawnIn() {
        val theme = RasterPacks.pack(
            """<style select="key[role=shift]" shape="circle"/><style select="key[role=shift]:shifted" sticker="s.png"/>""",
            mapOf("s.png" to RasterPacks.png(60, 90, Color.RED)),
        )[1]
        ThemeManager.setNormalModeTheme(theme)
        val h = KeyboardHarness(theme = theme).start().also { harness = it }
        fun shift() = h.keys().filterIsInstance<SceneShownKey>().first { it.node.role == "shift" }
        val before = shift().node
        assertTrue(before.content != before.shape.bounds)
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        h.tap(shift())
        val node = shift().node
        assertTrue(org.fcitx.fcitx5.android.scene.KeyState.Shifted in node.states)
        val drawn = draw(theme, node.ops(false), blank(node))
        assertTrue(queued.isEmpty())
        assertFalse(drawn.missed)
        assertEquals(1, PackIcons.STICKERS.keptOf(node.ops(false).filterIsInstance<Op.Sticker>().single().file))
    }

    // a state's own sticker, which the board shows only once Shift locks
    @Test
    fun aStickerOnlyAStateSetsIsReadAhead() {
        val theme = RasterPacks.pack("""<style select="key[role=shift]:locked" sticker="s.png"/>""", mapOf("s.png" to RasterPacks.png(60, 90, Color.RED)))[1]
        ThemeManager.setNormalModeTheme(theme)
        val h = KeyboardHarness(theme = theme).start().also { harness = it }
        assertTrue(h.keys().filterIsInstance<SceneShownKey>().any { it.node.role == "shift" })
        assertEquals(1, PackIcons.STICKERS.keptOf(PackIcons.of(theme).ahead.single { it.sticker }.file))
    }
}
