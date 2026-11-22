/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePainter
import org.fcitx.fcitx5.android.utils.appContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Packs of PNGs made in the test (no one's skin): Nord's file renamed, imported, light and dark versions. */
internal object RasterPacks {
    private var made = 0

    private val runner = ThemeAssets.runner
    private val poster = ThemeAssets.poster

    fun atOnce() {
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
        phoneStorage()
        ThemeFilesManager.themeDir.deleteRecursively()
        ThemeFilesManager.themeDir.mkdirs()
        ThemeManager.refreshThemes()
        ScenePainter.forgetIcons()
        PackIcons.lowMemory = false
    }

    fun putBack() {
        ThemeAssets.runner = runner
        ThemeAssets.poster = poster
        ScenePainter.forgetIcons()
        PackIcons.lowMemory = null
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
        ThemeFilesManager.themeDir.deleteRecursively()
        ThemeManager.refreshThemes()
    }

    fun png(width: Int, height: Int, color: Int, noise: Boolean = false): ByteArray {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        // noise does not compress: a file over its size limit within its side
        if (noise) Random(1).let { r -> for (y in 0 until height) for (x in 0 until width) b.setPixel(x, y, r.nextInt() or OPAQUE) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A still-looking VP8X header that says it moves. */
    fun animatedWebp(): ByteArray {
        val b = ByteArray(VP8X_FILE)
        "RIFF".encodeToByteArray().copyInto(b, 0)
        "WEBP".encodeToByteArray().copyInto(b, WEBP_TAG)
        "VP8X".encodeToByteArray().copyInto(b, CHUNK)
        b[VP8X_FLAGS] = ANIMATION
        return b
    }

    /** [pictures] go before the styles, [rest] at the end; light first. */
    fun zip(rest: String, files: Map<String, ByteArray>, pictures: String = ""): ByteArray {
        val name = "raster${made++}"
        var xml = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord).replace("name=\"nord\"", "name=\"$name\"")
            .replace("</theme>", "$rest</theme>")
        if (pictures.isNotEmpty()) {
            val first = xml.indexOf("<style ")
            xml = xml.substring(0, first) + pictures + xml.substring(first)
        }
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry(ThemePackFiles.THEME_XML))
                it.write(xml.encodeToByteArray())
                for ((n, b) in files) {
                    it.putNextEntry(ZipEntry(n))
                    it.write(b)
                }
            }
        }.toByteArray()
    }

    fun open(zip: ByteArray): Result<ThemeFilesManager.Opened> = ThemeFilesManager.openTheme(zip.inputStream())

    fun pack(rest: String, files: Map<String, ByteArray>, pictures: String = ""): List<Theme.Pack> {
        val opened = open(zip(rest, files, pictures)).getOrThrow() as ThemeFilesManager.Opened.Pack
        return opened.commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }
    }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val VP8X_FILE = 30
    private const val WEBP_TAG = 8
    private const val CHUNK = 12
    private const val VP8X_FLAGS = 20
    private const val ANIMATION: Byte = 0x02
}
