/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Color
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** `<picture variant="dark">`: each version draws its own, one decoded at a time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class ThemePictureDarkTest {

    @Before
    fun atOnce() = RasterPacks.atOnce()

    @After
    fun putBack() = RasterPacks.putBack()

    @Test
    fun eachVersionDrawsItsOwnPictureOneDecodedAtATime() {
        val (light, dark) = RasterPacks.pack(
            "",
            mapOf("l.png" to RasterPacks.png(80, 160, Color.RED), "d.png" to RasterPacks.png(80, 160, Color.BLUE)),
            """<picture src="l.png"><cut orientation="portrait" file="l.png"/></picture>""" +
                """<picture variant="dark" src="d.png"><cut orientation="portrait" file="d.png"/></picture>""",
        )
        assertTrue(light.look.picture!!.portrait.file.endsWith("/l.png"))
        assertTrue(dark.look.picture!!.portrait.file.endsWith("/d.png"))
        fun middle(p: Theme.Pack): Int = ThemeAssets.picture(p.look, false, 0, 80, 1f)!!.getPixel(40, 80)
        assertEquals(Color.RED, middle(light))
        assertEquals(Color.BLUE, middle(dark))
        assertEquals(1, ThemeAssets.pictureCount())
        assertEquals(Color.RED, middle(light))
        assertEquals(1, ThemeAssets.pictureCount())
    }
}
