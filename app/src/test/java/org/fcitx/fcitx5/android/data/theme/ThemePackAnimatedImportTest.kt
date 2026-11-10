/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.theme.AnimatedWebp
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A pack whose animated picture is past ThemeAssetLimits.MAX_ANIMATED_SIDE (720: decoding goes with the
 * pixels, 060-render-perf 动效) imports with the warning that it is drawn still; one at the limit plays, untold.
 * Apart from ThemePackImportTest for its size (detekt LargeClass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePackAnimatedImportTest {

    private val dir get() = ThemeFilesManager.themeDir
    private val real = ThemeFilesManager.probe

    // the animated WebP's size from its header, the still cut any, off Android
    private object WebpProbe : ThemeFilesManager.AssetProbe {
        override fun imageSize(file: File) = AnimatedWebp.header(file)?.let { it.width to it.height } ?: (1080 to 720)
        override fun fontReads(file: File) = false
    }

    @Before
    fun empty() {
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeFilesManager.probe = WebpProbe
    }

    @After
    fun realProbe() {
        ThemeFilesManager.probe = real
        dir.deleteRecursively()
    }

    private fun warnings(w: Int, h: Int): List<String> {
        val nord = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord).replace("name=\"nord\"", "name=\"mine\"")
        val first = nord.indexOf("<style ")
        val xml = nord.substring(0, first) + """<picture src="anim.webp" animated="true"><cut orientation="portrait" file="still.png"/></picture>""" + nord.substring(first)
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                for ((name, data) in listOf("theme.xml" to xml.encodeToByteArray(), "anim.webp" to animatedWebp(w, h), "still.png" to ByteArray(8))) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                }
            }
        }.toByteArray()
        val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes()).getOrThrow()
        return (opened as ThemeFilesManager.Opened.Pack).staged.warnings
    }

    @Test
    fun anAnimatedPictureWiderThanItsLimitIsToldItIsDrawnStill() {
        assertEquals(emptyList<String>(), warnings(ThemeAssetLimits.MAX_ANIMATED_SIDE, 480))
        // 1080 wide (a phone's keyboard): past the 720 a side it plays at, shown still and told
        val still = appContext.getString(R.string.theme_pack_animated_still, "4", "720", "300", "20")
        assertEquals(listOf(still), warnings(1080, 720))
    }

    // an animated WebP's header (VP8X, ANIM, [frames] ANMF of 50 ms), w×h
    private fun animatedWebp(w: Int, h: Int, frames: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(o: ByteArrayOutputStream, v: Int, n: Int) = repeat(n) { o.write((v shr (8 * it)) and 0xff) }
        fun chunk(tag: String, data: ByteArray) {
            out.write(tag.toByteArray())
            le(out, data.size, 4)
            out.write(data)
        }
        fun bytes(f: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(f).toByteArray()
        out.write("RIFF".toByteArray())
        le(out, 0, 4)
        out.write("WEBP".toByteArray())
        chunk("VP8X", bytes { o -> o.write(0x02); le(o, 0, 3); le(o, w - 1, 3); le(o, h - 1, 3) })
        chunk("ANIM", ByteArray(6))
        repeat(frames) { chunk("ANMF", bytes { o -> le(o, 0, 6); le(o, w - 1, 3); le(o, h - 1, 3); le(o, 50, 3); o.write(0); o.write(ByteArray(4)) }) }
        return out.toByteArray().also { b -> repeat(4) { b[4 + it] = ((b.size - 8) shr (8 * it)).toByte() } }
    }
}
