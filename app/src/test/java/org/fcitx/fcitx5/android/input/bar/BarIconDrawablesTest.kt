/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.RasterPacks
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIconDrawable
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePainter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import splitties.dimensions.dp
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A theme's PNG on the toolbar: its own colours unless a mask, told apart from a broken one, read ahead. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class BarIconDrawablesTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun atOnce() = RasterPacks.atOnce()

    @After
    fun putBack() = RasterPacks.putBack()

    private fun icons(png: ByteArray, tint: Boolean = false): PackIcons {
        val mask = if (tint) """ tint="true"""" else ""
        // drawn by a key too, so no undrawn-icon warning
        return PackIcons.of(RasterPacks.pack("""<icon name="undo" src="undo.png"$mask/>""", mapOf("undo.png" to png))[1])
    }

    private fun middle(d: Drawable): Int {
        val b = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, PX, PX)
        d.draw(Canvas(b))
        return b.getPixel(PX / 2, PX / 2)
    }

    private fun of(icons: PackIcons) = BarIconDrawables.of(ctx, icons, BarIcons.UNDO, Color.BLUE, PX) {}

    @Test
    fun aPictureKeepsItsColoursAMaskTakesThePlacesColour() {
        val own = of(icons(RasterPacks.png(24, 24, Color.RED)))
        assertTrue(own is PackIconDrawable)
        assertEquals(Color.RED, middle(own!!))
        assertEquals(Color.BLUE, middle(of(icons(RasterPacks.png(24, 24, Color.RED), tint = true))!!))
    }

    @Test
    fun aPictureOverItsLimitIsTheApps() {
        val icons = icons(RasterPacks.png(48, 48, Color.RED))
        // a restored backup's file, never checked
        java.io.File(icons.file("undo")!!).writeBytes(RasterPacks.png(300, 300, Color.RED))
        ScenePainter.forgetIcons()
        assertTrue(BarIconDrawables.drawsOwn(icons, BarIcons.UNDO))
        assertFalse(of(icons) is PackIconDrawable)
        assertFalse(BarIconDrawables.drawsOwn(icons, BarIcons.UNDO))
    }

    @Test
    fun aPictureIsReadAheadOnceForEveryBarSize() {
        val icons = icons(RasterPacks.png(48, 48, Color.RED))
        ScenePainter.forgetIcons()
        BarIconDrawables.readAhead(icons)
        assertEquals(1, PackIcons.RASTERS.keptOf(icons.file("undo")!!))
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        // a narrow cell's and a button's before its size is set
        assertTrue(of(icons) is PackIconDrawable)
        assertTrue(BarIconDrawables.of(ctx, icons, BarIcons.UNDO, Color.BLUE, ctx.dp(24)) {} is PackIconDrawable)
        assertTrue(queued.isEmpty())
        assertEquals(1, PackIcons.RASTERS.keptOf(icons.file("undo")!!))
    }

    private companion object {
        const val PX = 48
    }
}
