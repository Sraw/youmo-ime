/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIconDrawable
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePainter
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A command in a scroll column draws the theme's icon of its name, as the keys and the bar do; the app's where the theme has none. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ScrollColumnIconTest {

    @get:Rule
    val prefs = PrefsRule()

    private val dir get() = ThemeFilesManager.themeDir
    private val runner = ThemeAssets.runner
    private val poster = ThemeAssets.poster
    private var harness: KeyboardHarness? = null

    private val pref get() = AppPrefs.getInstance().internal.scrollLists

    @Before
    fun atOnce() {
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.refreshThemes()
        ScenePainter.forgetIcons()
    }

    @After
    fun putBack() {
        harness?.close()
        pref.setValue(ScrollListStore.HEADER)
        ThemeAssets.runner = runner
        ThemeAssets.poster = poster
        ScenePainter.forgetIcons()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
        dir.deleteRecursively()
        ThemeManager.refreshThemes()
    }

    // Nord renamed, an icon for each of [names]; its dark version
    private fun pack(vararg names: String): Theme.Pack {
        val icons = names.joinToString("") { """<icon name="$it" src="$it.svg"/>""" }
        val xml = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord).replace("name=\"nord\"", "name=\"mine\"")
            .replace("</theme>", "$icons</theme>")
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry(ThemePackFiles.THEME_XML))
                it.write(xml.encodeToByteArray())
                for (n in names) {
                    it.putNextEntry(ZipEntry("$n.svg"))
                    it.write(SQUARE.encodeToByteArray())
                }
            }
        }.toByteArray()
        val opened = ThemeFilesManager.openTheme(zip.inputStream()).getOrThrow() as ThemeFilesManager.Opened.Pack
        return opened.commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }[1]
    }

    private fun copyShown(theme: Theme): TextView {
        pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NUMBER, listOf(ScrollItem.Command(KeyCommand.Copy.key))).serialize())
        ThemeManager.setNormalModeTheme(theme)
        val h = KeyboardHarness(theme = theme).start().also { harness = it }
        h.tap(h.key("123"))
        return KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().first { h.shown(it) }
            .let { it.getChildAt(0) as ViewGroup }.children.filterIsInstance<TextView>().single { it.isVisible }
    }

    /** The column's copy under a theme with icons of [names], each row on its own theme and keyboard. */
    private fun drawn(vararg names: String, queued: MutableList<Runnable>? = null, check: (TextView) -> Unit) {
        atOnce()
        try {
            val theme = pack(*names)
            ScenePainter.forgetIcons()
            if (queued != null) ThemeAssets.runner = { queued += it }
            check(copyShown(theme))
        } finally {
            harness?.close()
            harness = null
            ThemeAssets.runner = { it.run() }
        }
    }

    @Test
    @Uat("ST-129")
    fun aCommandDrawsTheThemesIconElseTheApps() {
        val copy = SceneIcons.nameOf(KeyShortcutLooks.of(KeyCommand.Copy).icon)
        assertRows(
            "the theme has the copy icon: the column draws it" to { drawn(copy) { assertTrue(it.foreground is PackIconDrawable) } },
            "the theme has none: the app's own" to {
                drawn("undo") {
                    assertNotNull(it.foreground)
                    assertFalse(it.foreground is PackIconDrawable)
                }
            },
            "the theme's not read yet: nothing till it is, never the app's first" to {
                val queued = ArrayList<Runnable>()
                drawn(copy, queued = queued) {
                    assertNull(it.foreground)
                    while (queued.isNotEmpty()) queued.removeAt(0).run()
                    assertTrue(it.foreground is PackIconDrawable)
                }
            },
        )
    }

    private companion object {
        const val SQUARE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0h24v24h-24z"/></svg>"""
    }
}
