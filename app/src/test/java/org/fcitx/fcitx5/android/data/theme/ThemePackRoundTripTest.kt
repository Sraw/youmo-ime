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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** A pack's PNG icons, stickers and dark picture are taken, checked and exported like its other files. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class ThemePackRoundTripTest {

    @Before
    fun atOnce() = RasterPacks.atOnce()

    @After
    fun putBack() = RasterPacks.putBack()

    private val pictures = """<picture src="l.png"><cut orientation="portrait" file="l.png"/></picture>""" +
        """<picture variant="dark" src="d.png"><cut orientation="portrait" file="d-p.png"/></picture>"""

    private fun rest(icon: String = "bs.png", sticker: String = "q.png") =
        """<style select="qwerty/q" sticker="$sticker"/><icon name="backspace" src="$icon"/>"""

    private fun files(vararg over: Pair<String, ByteArray>): Map<String, ByteArray> = mapOf(
        "l.png" to RasterPacks.png(80, 160, Color.RED),
        "d.png" to RasterPacks.png(80, 160, Color.BLUE),
        "d-p.png" to RasterPacks.png(80, 160, Color.BLUE),
        "bs.png" to RasterPacks.png(48, 48, Color.RED),
        "q.png" to RasterPacks.png(60, 90, Color.GREEN),
    ) + over

    @Test
    fun theyAreTakenAndExported() {
        val packs = RasterPacks.pack(rest(), files(), pictures)
        val out = ByteArrayOutputStream()
        ThemePackFiles.export(packs[0], out)
        val names = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toSet() }
        assertTrue(names.containsAll(listOf("l.png", "d.png", "d-p.png", "bs.png", "q.png")))
    }

    private fun refused(rest: String, files: Map<String, ByteArray>): String =
        RasterPacks.open(RasterPacks.zip(rest, files, pictures)).exceptionOrNull()?.message ?: "taken"

    @Test
    fun eachIsHeldToItsLimits() {
        assertTrue(refused(rest(icon = "big.png"), files("big.png" to RasterPacks.png(300, 300, Color.RED))).contains("256"))
        assertTrue(refused(rest(icon = "heavy.png"), files("heavy.png" to RasterPacks.png(256, 256, Color.RED, noise = true))).contains("128"))
        assertTrue(refused(rest(icon = "moving.webp"), files("moving.webp" to RasterPacks.animatedWebp())).contains(STILL))
        assertTrue(refused(rest(sticker = "big.png"), files("big.png" to RasterPacks.png(600, 600, Color.RED))).contains("512"))
        assertTrue(refused(rest(sticker = "heavy.png"), files("heavy.png" to RasterPacks.png(300, 300, Color.RED, noise = true))).contains("256"))
        assertTrue(refused(rest(sticker = "moving.webp"), files("moving.webp" to RasterPacks.animatedWebp())).contains(STILL))
        assertTrue(refused(rest(sticker = "fake.png"), files("fake.png" to "not a png".encodeToByteArray())).contains(STILL))
        val dark = refused(rest(), files("d-p.png" to RasterPacks.png(80, 5000, Color.RED)))
        assertEquals(false, dark == "taken")
    }

    // a 0.6.2 pack's reserved sticker was never copied: taken without it, drawn as what it hides
    @Test
    fun aStickerThePackLacksIsLeftOut() {
        val packs = RasterPacks.pack(rest(sticker = "gone.png"), files(), pictures)
        val out = ByteArrayOutputStream()
        ThemePackFiles.export(packs[0], out)
        val names = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toSet() }
        assertTrue(names.contains("bs.png") && "gone.png" !in names)
        // listed anew from its folder, where the sticker is not
        ThemeManager.refreshThemes()
        assertTrue(ThemeManager.getAllThemes().any { it.name == packs[0].name })
    }

    private companion object {
        const val STILL = "not a still PNG or WebP"
    }
}
