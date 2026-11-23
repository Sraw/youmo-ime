/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import android.graphics.Color
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.RasterPacks
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.scene.pet.PetLimits
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

/** A pack's pet sheets are held to PetLimits as it is imported, and copied and exported as its other files. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class PetFilesTest {

    @Before
    fun atOnce() = RasterPacks.atOnce()

    @After
    fun putBack() = RasterPacks.putBack()

    private fun pet(sheet: String = "pet-idle.png", frames: Int = 4, columns: Int = 4) =
        """<pet><state name="idle" sheet="$sheet" frames="$frames" columns="$columns"/><state name="typing" sheet="pet-typing.png" frames="2"/></pet>"""

    private fun files(vararg over: Pair<String, ByteArray>) = mapOf(
        "pet-idle.png" to RasterPacks.png(160, 40, Color.RED),
        "pet-typing.png" to RasterPacks.png(80, 40, Color.BLUE),
    ) + over

    private fun refused(rest: String, files: Map<String, ByteArray>): String =
        RasterPacks.open(RasterPacks.zip(rest, files)).exceptionOrNull()?.message ?: "taken"

    @Test
    fun itsSheetsAreTakenAndExported() {
        val packs = RasterPacks.pack(pet(), files())
        assertEquals(setOf("pet-idle.png", "pet-typing.png"), packs[0].scene.pet!!.states.values.map { it.sheet }.toSet())
        val out = ByteArrayOutputStream()
        ThemePackFiles.export(packs[0], out)
        val names = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toSet() }
        assertTrue(names.containsAll(listOf("pet-idle.png", "pet-typing.png")))
    }

    @Test
    fun aSheetOverItsLimitsRefusesThePack() {
        assertTrue(refused(pet(), files("pet-idle.png" to RasterPacks.png(150, 40, Color.RED))).contains("idle · pet-idle.png: 150 px wide, not 4 columns"))
        assertTrue(refused(pet(frames = 4, columns = 2), files("pet-idle.png" to RasterPacks.png(160, 41, Color.RED))).contains("41 px high, not 2 rows"))
        assertTrue(refused(pet(frames = 2, columns = 2), files("pet-idle.png" to RasterPacks.png(600, 40, Color.RED))).contains("over 256 px a side"))
        assertTrue(refused(pet(frames = 1, columns = 1), files("pet-idle.png" to RasterPacks.png(2100, 8, Color.RED))).contains("over 2048 px a side"))
        assertTrue(refused(pet(frames = 16), files("pet-idle.png" to RasterPacks.png(1024, 256, Color.RED, noise = true))).contains("over 512 KB"))
        assertTrue(refused(pet(sheet = "pet.webp"), files("pet.webp" to RasterPacks.animatedWebp())).contains("an animated WebP"))
        assertTrue(refused(pet(), files("pet-idle.png" to "not a png".encodeToByteArray())).contains("not a PNG or WebP"))
    }

    // the string takes the limits as numbers: given only what was wrong, it would throw instead of saying it
    @Test
    fun aRefusedSheetSaysTheLimits() {
        val said = refused(pet(frames = 2, columns = 2), files("pet-idle.png" to RasterPacks.png(600, 40, Color.RED)))
        val limits = "at most ${PetLimits.MAX_SHEET_BYTES / 1024} KB and ${PetLimits.MAX_SHEET_SIDE} px a side, frames at most ${PetLimits.MAX_FRAME_SIDE} px"
        assertTrue(said, said.contains(limits) && said.contains("idle · pet-idle.png: frames of 300×40 px, over 256 px a side"))
    }

    @Test
    fun aSheetThePackLacksIsNamedAsAnyFile() {
        val missing = refused(pet(sheet = "gone.png"), files())
        assertTrue(missing, missing.contains("gone.png"))
    }

    @Test
    fun aBrokenPetIsLeftOutAndThePackTaken() {
        val packs = RasterPacks.pack("""<pet><state name="typing" sheet="pet-typing.png"/></pet>""", files())
        assertEquals(null, packs[0].scene.pet)
    }
}
