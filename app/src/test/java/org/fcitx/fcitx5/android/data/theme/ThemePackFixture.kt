/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// an image is a file of "<w>x<h>", a font one of "font": what an import reads of them, off Android
private object PackProbe : ThemeFilesManager.AssetProbe {
    override fun imageSize(file: File): Pair<Int, Int>? = file.readText().split('x').takeIf { it.size == 2 }
        ?.let { (w, h) -> w.toIntOrNull()?.let { a -> h.toIntOrNull()?.let { b -> a to b } } }

    override fun fontReads(file: File) = file.readText() == "font"
}

/** What the theme pack tests share: an empty theme folder, the probe off Android, a sample pack and its zips. */
abstract class ThemePackFixture {

    protected val dir get() = ThemeFilesManager.themeDir
    protected val packs get() = File(dir, ThemePackFiles.FOLDER)
    protected val unpacking get() = File(appContext.cacheDir, "theme-import")
    internal val real = ThemeFilesManager.probe
    private val realRecheck = ThemePackFiles.recheck
    private val realOnRefused = ThemePackFiles.onRefused

    // the settings as they were before the test, put back after it
    private var followBefore = false
    private var normalBefore: Theme? = null

    @Before
    fun empty() {
        followBefore = ThemeManager.prefs.followSystemDayNightTheme.getValue()
        normalBefore = ThemeManager.prefs.normalModeTheme.getValue()
        // a listing's check again at once: what it refuses is left out of that listing
        ThemePackFiles.recheck = { it.run() }
        // the checks an earlier test of this process asked or refused, and its listing's themes, not this one's
        ThemePackFiles.forgetListings()
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeFilesManager.probe = PackProbe
        ThemeFilesManager.replacingFile().delete()
        // the theme in use the normal mode's, not the day's or night's
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeFilesManager.listThemes()
        ThemeManager.refreshThemes()
    }

    @After
    fun realProbe() {
        ThemeFilesManager.probe = real
        ThemePackFiles.beforeInPlace = null
        ThemePackFiles.recheck = realRecheck
        ThemePackFiles.onRefused = realOnRefused
        ThemeManager.prefs.followSystemDayNightTheme.setValue(followBefore)
        ThemeManager.setNormalModeTheme(normalBefore ?: ThemeManager.prefs.normalModeTheme.defaultValue)
        dir.deleteRecursively()
        ThemeManager.refreshThemes()
    }

    // a built-in theme's file of two versions, as a user's of [name]: [meta] before its colours, [before] ahead of
    // its first style (a picture, fonts), [styles] and [icons] at its end
    protected val nord by lazy { BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord) }

    protected fun xml(name: String = "mine", meta: String = "", before: String = "", styles: String = "", icons: String = ""): String {
        var x = nord.replace("name=\"nord\"", "name=\"$name\"").replace("<colors>", "$meta<colors>")
        val first = x.indexOf("<style ")
        x = x.substring(0, first) + before + x.substring(first)
        return x.replace("</theme>", "$styles$icons</theme>")
    }

    protected val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0L24 24Z" fill="#fff"/></svg>"""

    protected val sampleXml = xml(
        meta = """<meta author="Ann" version="1.2" license="CC-BY-4.0" min-app="0.5.0" preview="preview.png"/>""",
        before = """<picture src="photo.jpg" brightness="80%"><cut orientation="portrait" file="p.webp"/>""" +
            """<cut orientation="landscape" file="l.webp"/></picture><font name="round" src="round.ttf"/>""",
        styles = """<style select="key.enter" fill="image(enter.png, 4 4 4 4)"/><style select="key" font="round"/>""",
        icons = """<icon name="star" src="star.svg"/>""",
    )

    // [more] over the sample's own of their names
    protected fun sample(xml: String = sampleXml, vararg more: Pair<String, String>) = zip(*(sampleEntries(xml).toMap() + more).toList().toTypedArray())

    protected fun sampleEntries(xml: String) = arrayOf(
        "theme.xml" to xml, "preview.png" to "100x50", "photo.jpg" to "3000x2000", "p.webp" to "1080x720", "l.webp" to "2000x700",
        "enter.png" to "64x64", "round.ttf" to "font", "star.svg" to svg, "layouts/" to "", "layouts/qwerty.xml" to "<layout/>",
        // what it does not name, and a Mac's attributes: not taken
        "readme.txt" to "hello", "__MACOSX/._enter.png" to "attr",
    )

    protected fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray()

    protected fun open(bytes: ByteArray, themes: List<Theme> = ThemeManager.getAllThemes()) = ThemeFilesManager.openTheme(bytes.inputStream(), themes)

    protected fun opened(bytes: ByteArray) = open(bytes).getOrThrow() as ThemeFilesManager.Opened.Pack

    // imported and listed, as the settings' list does after ([ThemeManager.packPlaced])
    protected fun placed(bytes: ByteArray) = opened(bytes).commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }

    protected fun refusal(bytes: ByteArray, themes: List<Theme> = ThemeManager.getAllThemes()) = open(bytes, themes).exceptionOrNull()!!.message

    protected fun tree(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }

    protected fun nothingLeft() {
        assertEquals(emptyMap<String, String>(), tree(packs))
        assertEquals(emptyList<String>(), unpacking.list().orEmpty().toList())
    }

}
