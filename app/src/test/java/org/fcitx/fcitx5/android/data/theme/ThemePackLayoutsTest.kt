/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemePackDialogs
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A theme pack's layouts (060-render-p3 T8b): while the pack is in use each keyboard it has is drawn from it, one
 * that does not read or is not the app's keyboard of its id the app's alone, the rest the pack's; another theme, the app's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePackLayoutsTest {

    private val dir get() = ThemeFilesManager.themeDir
    private val builtIn by lazy { BuiltInLayouts(appContext) }

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeFilesManager.listThemes()
        ThemeManager.refreshThemes()
        SceneLayouts.invalidate()
    }

    @After
    fun builtInAgain() {
        ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
        dir.deleteRecursively()
        ThemeManager.refreshThemes()
        SceneLayouts.invalidate()
    }

    private val nord by lazy { BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord) }

    // the app's 26 keys named [name], q and w swiped up typing text of their own
    private fun qwerty(name: String = "主题 26 键") = builtIn.text("qwerty")!!.replace("name=\"26 键\"", "name=\"$name\"")
        .replace("send=\"q\" swipe-up=\"1\"", "send=\"q\" swipe-up=\"commit:qq\"")
        .replace("send=\"w\" swipe-up=\"2\"", "send=\"w\" swipe-up=\"commit:ww\"")

    private fun zip(name: String, layouts: Map<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("theme.xml"))
            zip.write(nord.replace("name=\"nord\"", "name=\"$name\"").encodeToByteArray())
            for ((file, text) in layouts) {
                zip.putNextEntry(ZipEntry("layouts/$file"))
                zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray()

    private fun opened(name: String, layouts: Map<String, String>) =
        ThemeFilesManager.openTheme(zip(name, layouts).inputStream(), ThemeManager.getAllThemes()).getOrThrow() as ThemeFilesManager.Opened.Pack

    // imported and put in use
    private fun inUse(name: String, layouts: Map<String, String>) {
        val packs = opened(name, layouts).commit().getOrThrow()
        ThemeManager.packPlaced(packs[0].file)
        ThemeManager.setNormalModeTheme(ThemeManager.getAllThemes().first { it is Theme.Pack && it.file == name })
    }

    private fun name(id: String) = SceneLayouts.of(appContext, id)!!.layout.name

    private fun keyQ(id: String) = SceneLayouts.of(appContext, id)!!.layout.parts.filterIsInstance<Row>()
        .flatMap { it.cells }.filterIsInstance<Key>().first { it.id == "q" }

    @Test
    fun aPacksQwertyReplacesThe26Keys() {
        inUse("mine", mapOf("qwerty.xml" to qwerty()))
        assertEquals("主题 26 键", name("qwerty"))
        assertEquals("commit:qq", keyQ("qwerty").attrs.actions["swipe-up"])
        // the others the app's
        assertSame(SceneLayouts.builtIn(appContext, "k14"), SceneLayouts.of(appContext, "k14"))
        assertEquals(
            appContext.getString(R.string.keyboard_themed, "26", "主题 26 键"),
            ThemePackLayouts.label(appContext, InputMethodCatalog.PINYIN, ImKeyboard.FULL, "26"),
        )
        assertEquals("18", ThemePackLayouts.label(appContext, InputMethodCatalog.PINYIN, ImKeyboard.KEYS18, "18"))
    }

    @Test
    fun aBadLayoutFallsBackAloneTheRestThePacks() {
        // k14 does not read; k18 is the 26 keys under k18's id (not the app's 18 keys' family)
        inUse("mine", mapOf("qwerty.xml" to qwerty(), "k14.xml" to "<keyboard/>", "k18.xml" to qwerty().replace("id=\"qwerty\"", "id=\"k18\"")))
        assertSame(SceneLayouts.builtIn(appContext, "k14"), SceneLayouts.of(appContext, "k14"))
        assertSame(SceneLayouts.builtIn(appContext, "k18"), SceneLayouts.of(appContext, "k18"))
        assertEquals("主题 26 键", name("qwerty"))
        assertNull(ThemePackLayouts.themedName(appContext, InputMethodCatalog.PINYIN, ImKeyboard.KEYS14))
    }

    @Test
    fun aLayoutIncludingAFragmentThePackHasNotTakesTheApps() {
        val k18 = builtIn.text("k18")!!.replace("name=\"18 键\"", "name=\"主题 18 键\"")
        inUse("mine", mapOf("k18.xml" to k18))
        assertEquals("主题 18 键", name("k18"))
        assertEquals(setOf("grouped-bottom"), SceneLayouts.of(appContext, "k18")!!.fragments.keys)
        // the app's groups: 双拼 types them too
        assertEquals("主题 18 键", ThemePackLayouts.themedName(appContext, InputMethodCatalog.SHUANGPIN, ImKeyboard.KEYS18))
    }

    @Test
    fun anotherThemeDrawsTheAppsLayoutsAndThePacksAgainOnceBack() {
        inUse("mine", mapOf("qwerty.xml" to qwerty()))
        val pack = ThemeManager.activeTheme
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
        assertEquals("26 键", name("qwerty"))
        ThemeManager.setNormalModeTheme(pack)
        assertEquals("主题 26 键", name("qwerty"))
        // imported again (another file), read again
        inUse("mine", mapOf("qwerty.xml" to qwerty("又一个")))
        assertEquals("又一个", name("qwerty"))
    }

    @Test
    fun theImportTellsTheLayoutsAndTheirCommits() {
        val pack = opened("mine", mapOf("qwerty.xml" to qwerty(), "mine.xml" to "<keyboard/>"))
        // the app's own 26 keys type a few (.com and the like), two more of the pack's
        val own = ThemePackLayouts.commits(builtIn.text("qwerty")!!)
        assertEquals(own + 2, pack.staged.commits)
        val text = ThemePackDialogs.confirmText(appContext, pack)
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_layouts, "qwerty", own + 2)))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_layouts_unused, "mine")))
    }

    @Test
    fun commitsAreCountedOutsideComments() {
        assertEquals(1, ThemePackLayouts.commits("<!-- send=\"commit:x\" --><key send = 'commit:hi' long-press=\"a\"/>"))
    }
}
