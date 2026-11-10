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
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLabels
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
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
import java.io.File
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

    // the blank-key check runs at import, its pass stamped: a listing does not run it again, unless the pack (its
    // theme.xml, its layouts) changed
    @Test
    fun aListingTrustsThePacksStamp() {
        val pack = opened("mine", mapOf("qwerty.xml" to qwerty())).commit().getOrThrow()[0]
        ThemePackFiles.blankChecks = 0
        repeat(2) { ThemeFilesManager.listPacks() }
        assertEquals(0, ThemePackFiles.blankChecks)
        File(pack.dir, "layouts/qwerty.xml").appendText("\n")
        repeat(2) { assertEquals(listOf("mine"), ThemeFilesManager.listPacks().map { it.file }.distinct()) }
        assertEquals(1, ThemePackFiles.blankChecks)
    }

    @Test
    fun aPacksQwertyReplacesThe26Keys() {
        inUse("mine", mapOf("qwerty.xml" to qwerty()))
        assertEquals("主题 26 键", name("qwerty"))
        assertEquals("commit:qq", keyQ("qwerty").attrs.actions["swipe-up"])
        // the others the app's
        assertSame(SceneLayouts.builtIn(appContext, "k14"), SceneLayouts.of(appContext, "k14"))
        assertEquals(
            appContext.getString(R.string.keyboard_themed, "26", "主题 26 键"),
            KeyboardLabels.label(appContext, InputMethodCatalog.PINYIN, ImKeyboard.FULL, "26"),
        )
        assertEquals("18", KeyboardLabels.label(appContext, InputMethodCatalog.PINYIN, ImKeyboard.KEYS18, "18"))
    }

    @Test
    fun aBadLayoutFallsBackAloneTheRestThePacks() {
        // k14 does not read; k18 is the 26 keys under k18's id (not the app's 18 keys' family)
        inUse("mine", mapOf("qwerty.xml" to qwerty(), "k14.xml" to "<keyboard/>", "k18.xml" to qwerty().replace("id=\"qwerty\"", "id=\"k18\"")))
        assertSame(SceneLayouts.builtIn(appContext, "k14"), SceneLayouts.of(appContext, "k14"))
        assertSame(SceneLayouts.builtIn(appContext, "k18"), SceneLayouts.of(appContext, "k18"))
        assertEquals("主题 26 键", name("qwerty"))
        assertNull(KeyboardLabels.themedName(appContext, InputMethodCatalog.PINYIN, ImKeyboard.KEYS14))
    }

    @Test
    fun aLayoutIncludingAFragmentThePackHasNotTakesTheApps() {
        val k18 = builtIn.text("k18")!!.replace("name=\"18 键\"", "name=\"主题 18 键\"")
        inUse("mine", mapOf("k18.xml" to k18))
        assertEquals("主题 18 键", name("k18"))
        assertEquals(setOf("grouped-bottom"), SceneLayouts.of(appContext, "k18")!!.fragments.keys)
        // the app's groups: 双拼 types them too
        assertEquals("主题 18 键", KeyboardLabels.themedName(appContext, InputMethodCatalog.SHUANGPIN, ImKeyboard.KEYS18))
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
    fun theImportTellsTheKeyboardsUsedAndTheirCommitsEvenOnesWrittenAsReferences() {
        // e swiped up written c as a character reference: the reader decodes it, so it counts
        val q = qwerty().replace("send=\"e\" swipe-up=\"3\"", "send=\"e\" swipe-up=\"&#99;ommit:ee\"")
        val pack = opened("mine", mapOf("qwerty.xml" to q, "mine.xml" to "<keyboard/>", "k14.xml" to "<keyboard/>", "grouped-bottom.xml" to builtIn.text("grouped-bottom")!!))
        // the app's 26 keys have four (/ @ . .com), the pack two of its own and the referenced one
        val report = pack.staged.layoutReport
        assertEquals(7, report.commits)
        assertEquals(listOf("qwerty"), report.used)
        // not the app's; does not read; a fragment no keyboard used includes
        assertEquals(setOf("mine", "k14", "grouped-bottom"), report.unused.map { it.first }.toSet())
        val text = ThemePackDialogs.confirmText(appContext, pack)
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_layouts, "qwerty", 7)))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_layouts_unused, report.unused.joinToString(", ") { it.first })))
    }

    @Test
    fun aPacksFragmentIncludedIsCountedOnceAndABrokenOneRefusesItsKeyboard() {
        val bottom = builtIn.text("grouped-bottom")!!
        val k14 = builtIn.text("k14")!!
        val k18 = builtIn.text("k18")!!
        val both = opened("mine", mapOf("k14.xml" to k14, "k18.xml" to k18, "grouped-bottom.xml" to bottom)).staged.layoutReport
        assertEquals(listOf("k14", "k18"), both.used.sorted())
        assertTrue(both.unused.isEmpty())
        // the bottom row's four (k14 and k18 have none of their own), once though both include it
        assertEquals(4, both.commits)
        val broken = opened("other", mapOf("k18.xml" to k18, "grouped-bottom.xml" to "<fragment/>")).staged.layoutReport
        assertTrue(broken.used.isEmpty())
        inUse("third", mapOf("k18.xml" to k18.replace("name=\"18 键\"", "name=\"x\""), "grouped-bottom.xml" to "<fragment/>"))
        assertSame(SceneLayouts.builtIn(appContext, "k18"), SceneLayouts.of(appContext, "k18"))
    }

    @Test
    fun aLayoutMissingWhatItsKeyboardNeedsOrOfAnotherIdIsTheApps() {
        val app = SceneLayouts.builtIn(appContext, "qwerty")!!
        fun judged(text: String) = ThemePackLayouts.judge("qwerty", (LayoutReader.layout(text) as Parsed.Ok).value, app.fragments, app)
        assertNull(judged(qwerty()))
        assertEquals("its id is k14", judged(qwerty().replace("id=\"qwerty\"", "id=\"k14\"")))
        assertEquals("it has no key [backspace]", judged(qwerty().replace("role=\"backspace\" ", "")))
        val nine = SceneLayouts.builtIn(appContext, "nine")!!
        val noFive = builtIn.text("nine")!!.replace("id=\"5\"", "id=\"five\"")
        assertEquals("it has no key [5]", ThemePackLayouts.judge("nine", (LayoutReader.layout(noFive) as Parsed.Ok).value, nine.fragments, nine))
        val noSide = builtIn.text("nine")!!.replace("kind=\"nine-side\"", "kind=\"symbol-grid\"")
        assertTrue(ThemePackLayouts.judge("nine", (LayoutReader.layout(noSide) as Parsed.Ok).value, nine.fragments, nine)!!.startsWith("it has no slot"))
        // in use: the app's drawn
        inUse("mine", mapOf("qwerty.xml" to qwerty().replace("role=\"backspace\" ", "")))
        assertSame(app, SceneLayouts.of(appContext, "qwerty"))
    }

    @Test
    fun theImportsCheckLeavesTheCacheAlone() {
        // the check runs off the main thread; the cache is the main thread's
        SceneLayouts.invalidate()
        opened("mine", mapOf("qwerty.xml" to qwerty(), "k18.xml" to builtIn.text("k18")!!))
        assertEquals(0, SceneLayouts.cached)
    }

    @Test
    fun aLayoutIsReadPastItsByteOrderMarkAndNotPastTheLimit() {
        val dir = File(appContext.cacheDir, "layouts-read").apply { deleteRecursively(); mkdirs() }
        File(dir, "qwerty.xml").writeText("\uFEFF<keyboard/>")
        assertEquals("<keyboard/>", ThemePackLayouts.read(dir, "qwerty"))
        File(dir, "qwerty.xml").writeText("x".repeat(ThemeAssetLimits.MAX_LAYOUT_BYTES.toInt() + 1))
        assertNull(ThemePackLayouts.read(dir, "qwerty"))
        File(dir, "mine.xml").writeText("<keyboard/>")
        assertNull(ThemePackLayouts.read(dir, "mine"))
        dir.deleteRecursively()
        inUse("mine", mapOf("qwerty.xml" to "\uFEFF" + qwerty()))
        assertEquals("主题 26 键", name("qwerty"))
    }
}
