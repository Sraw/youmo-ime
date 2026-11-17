/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyboardVariants
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLabels
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keys.KeyOutput
import org.fcitx.fcitx5.android.input.keys.KeyState
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.ui.main.settings.KeyCaps
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
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A theme pack's layouts (060-render-p3 T8b): while the pack is in use each keyboard it has is drawn from it, one
 * that does not read or is not the app's keyboard of its id the app's alone, the rest the pack's; another theme, the app's.
 * Since 0.6.1 only the boards the user does not pick among their own layouts (注音's…: PackLayouts.replaces); its
 * `qwerty`, `k18`… are layouts of the user's to be (ThemeLayoutsToList, 061-custom-layout §11), the app's drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePackLayoutsTest {

    private val dir get() = ThemeFilesManager.themeDir
    private val builtIn by lazy { BuiltInLayouts(appContext) }
    private val realRecheck = ThemePackFiles.recheck

    // the settings as they were before the test, put back after it
    private var followBefore = false
    private var normalBefore: Theme? = null

    @Before
    fun empty() {
        followBefore = ThemeManager.prefs.followSystemDayNightTheme.getValue()
        normalBefore = ThemeManager.prefs.normalModeTheme.getValue()
        // a listing's check again at once: what it refuses is left out of that listing
        ThemePackFiles.recheck = { it.run() }
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
        ThemePackFiles.recheck = realRecheck
        ThemeManager.prefs.followSystemDayNightTheme.setValue(followBefore)
        ThemeManager.setNormalModeTheme(normalBefore ?: ThemeManager.prefs.normalModeTheme.defaultValue)
        dir.deleteRecursively()
        ThemeManager.refreshThemes()
        SceneLayouts.invalidate()
    }

    private val nord by lazy { BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord) }

    // the app's 26 keys named [name], q and w swiped up typing text of their own (a theme's own swipes: the app's write
    // none; since 0.6.1 not taken, 主题只管外观)
    private fun qwerty(name: String = "主题 26 键") = builtIn.text("qwerty")!!.replace("name=\"26 键\"", "name=\"$name\"")
        .replace("send=\"q\"", "send=\"q\" swipe-up=\"commit:qq\"")
        .replace("send=\"w\"", "send=\"w\" swipe-up=\"commit:ww\"")

    // the app's 注音大千 named [name]: a board a pack still stands for
    private fun zhuyin(name: String = "主题注音") = builtIn.text("zhuyin")!!.replace("name=\"注音大千\"", "name=\"$name\"")

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
        // the app's boards changed (an update): checked again, once
        val was = ThemePackFiles.builtInHash
        try {
            ThemePackFiles.builtInHash = { "another build's" }
            repeat(2) { ThemeFilesManager.listPacks() }
            assertEquals(2, ThemePackFiles.blankChecks)
        } finally {
            ThemePackFiles.builtInHash = was
        }
    }

    // a pack bringing a stamp of its own, made as the app makes one, its keys blank: checked all the same, refused
    @Test
    fun aPacksOwnStampIsNotTrusted() {
        val blank = nord.replace("name=\"nord\"", "name=\"forged\"").replace("</theme>", """<style select="key" hidden="true"/></theme>""")
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${BlankKeys.RULE}\n$blank".toByteArray())
        val stamp = md.digest().joinToString("") { "%02x".format(it) }
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("theme.xml"))
                zip.write(blank.encodeToByteArray())
                zip.putNextEntry(ZipEntry(".checked"))
                zip.write(stamp.encodeToByteArray())
            }
        }.toByteArray()
        val refused = ThemeFilesManager.openTheme(bytes.inputStream(), ThemeManager.getAllThemes()).exceptionOrNull()
        assertEquals(appContext.getString(R.string.exception_theme_hidden_required, "qwerty/shift"), refused?.message)
    }

    @Test
    fun aPacksZhuyinReplacesTheAppsItsQwertyNoLonger() {
        inUse("mine", mapOf("zhuyin.xml" to zhuyin(), "qwerty.xml" to qwerty()))
        assertEquals("主题注音", name("zhuyin"))
        assertEquals(
            appContext.getString(R.string.keyboard_themed, "注音", "主题注音"),
            KeyboardLabels.label(appContext, InputMethodCatalog.PINYIN, ImKeyboard.ZHUYIN_FULL, "注音"),
        )
        // its 26 keys a layout of the user's to be (061-custom-layout §11): the app's drawn, named the app's
        assertSame(SceneLayouts.builtIn(appContext, "qwerty"), SceneLayouts.of(appContext, "qwerty"))
        assertEquals("26", KeyboardLabels.label(appContext, InputMethodCatalog.PINYIN, ImKeyboard.FULL, "26"))
        assertSame(SceneLayouts.builtIn(appContext, "k14"), SceneLayouts.of(appContext, "k14"))
    }

    @Test
    fun aPacksK14WritingAnotherBoardItIsBasedOnIsMadeOneBasedOnThe14Keys() {
        // a pack's board is of its own id (judge holds it to k14's): based-on="k18" makes it no 18 keys, the layout
        // of the user's made of it based on the 14 keys
        val k14 = builtIn.text("k14")!!.replaceFirst("<keyboard ", "<keyboard based-on=\"k18\" ")
        val made = ThemeLayoutsToList.convert(packLayouts(mapOf("k14.xml" to k14)), "mine", null).single().second as PackLayouts.Converted.Done
        val layout = (LayoutReader.layout(made.text) as Parsed.Ok).value
        assertEquals("k14", layout.basedOn)
        assertEquals(Form.Grouped14, KeyboardVariants.of(layout, InputMethodCatalog.PINYIN, EngineState.shuangpinScheme, base = "k14").form)
    }

    // a pack's layouts written in a folder of their own, as an import would leave them
    private fun packLayouts(layouts: Map<String, String>): File = File(appContext.cacheDir, "pack-layouts").apply {
        deleteRecursively()
        mkdirs()
        layouts.forEach { (name, text) -> File(this, name).writeText(text) }
    }

    @Test
    fun aBadLayoutFallsBackAloneTheRestThePacks() {
        // 注音九键 does not read; 注音大千 the pack's
        inUse("mine", mapOf("zhuyin.xml" to zhuyin(), "zhuyin-nine.xml" to "<keyboard/>"))
        assertSame(SceneLayouts.builtIn(appContext, "zhuyin-nine"), SceneLayouts.of(appContext, "zhuyin-nine"))
        assertEquals("主题注音", name("zhuyin"))
        assertNull(KeyboardLabels.themedName(appContext, InputMethodCatalog.PINYIN, ImKeyboard.ZHUYIN_NINE))
    }

    @Test
    fun aLayoutIncludingAFragmentThePackHasNotTakesTheApps() {
        inUse("mine", mapOf("zhuyin.xml" to zhuyin()))
        assertEquals("主题注音", name("zhuyin"))
        assertEquals(setOf("grouped-bottom"), SceneLayouts.of(appContext, "zhuyin")!!.fragments.keys)
        assertEquals("主题注音", KeyboardLabels.themedName(appContext, InputMethodCatalog.PINYIN, ImKeyboard.ZHUYIN_FULL))
    }

    @Test
    fun anotherThemeDrawsTheAppsLayoutsAndThePacksAgainOnceBack() {
        inUse("mine", mapOf("zhuyin.xml" to zhuyin()))
        val pack = ThemeManager.activeTheme
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
        assertEquals("注音大千", name("zhuyin"))
        ThemeManager.setNormalModeTheme(pack)
        assertEquals("主题注音", name("zhuyin"))
        // imported again (another file), read again
        inUse("mine", mapOf("zhuyin.xml" to zhuyin("又一个")))
        assertEquals("又一个", name("zhuyin"))
    }

    @Test
    fun theImportTellsTheKeyboardsUsedAndTheirCommitsEvenOnesWrittenAsReferences() {
        // the bottom row's .com tap written c as a character reference: the reader decodes it, so it counts; the swipes
        // of 注音's 1 and 2 (one a reference too) are not taken, so not counted, and told as not taken instead
        val zy = zhuyin().replace("shortcut=\"zyfull/1\"", "shortcut=\"zyfull/1\" swipe-up=\"&#99;ommit:ee\"")
            .replace("shortcut=\"zyfull/2\"", "shortcut=\"zyfull/2\" swipe-up=\"commit:ff\" swipe-down=\"commit:gg\"")
        val bottom = builtIn.text("grouped-bottom")!!.replace("send=\"commit:.com\"", "send=\"&#99;ommit:.com\"")
        val pack = opened("mine", mapOf("zhuyin.xml" to zy, "mine.xml" to "<keyboard/>", "zhuyin-nine.xml" to "<keyboard/>", "grouped-bottom.xml" to bottom))
        // the bottom row's four (/ @ . .com), the pack's own swipes none
        val report = pack.staged.layoutReport
        assertEquals(4, report.commits)
        assertEquals(3, report.ignored)
        assertEquals(listOf("zhuyin"), report.used)
        // not the app's; does not read
        assertEquals(setOf("mine", "zhuyin-nine"), report.unused.map { it.first }.toSet())
        val text = ThemePackDialogs.confirmText(appContext, pack)
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_layouts, "zhuyin", 4)))
        assertTrue(text, text.contains(appContext.resources.getQuantityString(R.plurals.theme_pack_layouts_ignored, 3, 3)))
        // one: said as one (the English plural)
        val one = opened("one", mapOf("zhuyin.xml" to builtIn.text("zhuyin")!!.replace("shortcut=\"zyfull/1\"", "shortcut=\"zyfull/1\" swipe-up=\"commit:ee\"")))
        assertEquals(1, one.staged.layoutReport.ignored)
        val said = ThemePackDialogs.confirmText(appContext, one)
        assertTrue(said, said.contains("1 long-press, corner or swipe setting written in its layouts does nothing"))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_layouts_unused, report.unused.joinToString(", ") { it.first })))
    }

    // its boards the user picks among their own: told as the layouts of theirs 「一起导入」 makes, never as unused; one
    // that is not one with why; the pack's bottom row they take in no fragment unused
    @Test
    @Uat("ST-72")
    fun theImportTellsTheLayoutsItWouldList() {
        val k18 = builtIn.text("k18")!!.replace("name=\"18 键\"", "name=\"17 键\"")
        val pack = opened("薄荷", mapOf("qwerty.xml" to qwerty(), "k18.xml" to k18, "nine.xml" to "<keyboard/>", "grouped-bottom.xml" to builtIn.text("grouped-bottom")!!))
        val report = pack.staged.layoutReport
        assertEquals(listOf("薄荷 · 主题 26 键", "薄荷 · 17 键"), report.listed)
        assertEquals(emptyList<String>(), report.used)
        assertEquals(listOf("nine"), report.unused.map { it.first })
        val text = ThemePackDialogs.confirmText(appContext, pack)
        assertTrue(text, text.contains(appContext.resources.getQuantityString(R.plurals.theme_pack_layouts_listed, 2, 2, report.listed.joinToString(appContext.getString(R.string.list_separator)))))
        // what their keys type written in them (§13; L7 review S1): the 26 keys' q and w swiped up
        assertEquals(2, report.listedCommits)
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_layouts_listed_commits, 2)))
        // none: nothing said of them
        assertTrue(opened("none", mapOf("zhuyin.xml" to zhuyin())).staged.layoutReport.listed.isEmpty())
    }

    // 主题只管外观 (061-custom-layout §19): a pack's 注音 (no board 0.6.1 moves) writing keys' corners and swipes, as
    // installed packs of 0.6.0 may: the import tells what is not taken, the keyboard holds and swipes them as the app's,
    // and a first the user set stays theirs
    @Test
    fun aPacksZhuyinKeysAreHeldAndSwipedAsTheAppsTheUsersFirstKept() {
        val written = builtIn.text("zhuyin")!!
            .replace("shortcut=\"zyfull/1\"", "shortcut=\"zyfull/1\" alt=\"!\" swipe-up=\"commit:ㄅㄅ\"")
            .replace("shortcut=\"zyfull/2\"", "shortcut=\"zyfull/2\" alt=\"?\"")
        val asApp = builtIn.text("zhuyin")!!
        val report = opened("mine", mapOf("zhuyin.xml" to written)).staged.layoutReport
        assertEquals(listOf("zhuyin"), report.used)
        assertEquals(3, report.ignored)
        // the commits counted as taken: the app's own, not the pack's swipe
        val same = opened("same", mapOf("zhuyin.xml" to asApp)).staged.layoutReport
        assertEquals(same.commits, report.commits)
        assertEquals(0, same.ignored)

        fun shown(id: String) = KeyCaps.resolver(appContext, "zhuyin")!!.resolve(KeyId(id), KeyState()).let { it.corner to it.first?.let(KeyOutput::of) }
        val app = listOf("zyfull/1", "zyfull/2").associateWith(::shown)
        val prefs = AppPrefs.getInstance()
        try {
            prefs.setStoredKeyLists("@2\nzyfull/2\tset\t:Y\tY\tZ")
            inUse("held", mapOf("zhuyin.xml" to written))
            assertNull(SceneLayouts.of(appContext, "zhuyin")!!.layout.parts.filterIsInstance<Row>().flatMap { it.cells }
                .filterIsInstance<Key>().first { it.attrs.shortcut == "zyfull/1" }.attrs.actions["swipe-up"])
            assertEquals(app.getValue("zyfull/1"), shown("zyfull/1"))
            // the user's first: its corner
            assertEquals("Y", shown("zyfull/2").first)
        } finally {
            prefs.setStoredKeyLists(null)
        }
    }

    @Test
    fun aPacksFragmentIncludedIsCountedOnceAndABrokenOneRefusesItsKeyboard() {
        val bottom = builtIn.text("grouped-bottom")!!
        val zy = builtIn.text("zhuyin")!!
        val nine = builtIn.text("zhuyin-nine")!!
        val both = opened("mine", mapOf("zhuyin.xml" to zy, "zhuyin-nine.xml" to nine, "grouped-bottom.xml" to bottom)).staged.layoutReport
        assertEquals(listOf("zhuyin", "zhuyin-nine"), both.used.sorted())
        assertTrue(both.unused.isEmpty())
        // the bottom row's four (注音's have none of their own), once though both include it
        assertEquals(4, both.commits)
        val broken = opened("other", mapOf("zhuyin.xml" to zy, "grouped-bottom.xml" to "<fragment/>")).staged.layoutReport
        assertTrue(broken.used.isEmpty())
        inUse("third", mapOf("zhuyin.xml" to zhuyin("x"), "grouped-bottom.xml" to "<fragment/>"))
        assertSame(SceneLayouts.builtIn(appContext, "zhuyin"), SceneLayouts.of(appContext, "zhuyin"))
        // a board the user picks including it: made one with no layout (its fragment does not read), told so
        val made = opened("fourth", mapOf("k18.xml" to builtIn.text("k18")!!, "grouped-bottom.xml" to "<fragment/>")).staged.layoutReport
        assertEquals(setOf("grouped-bottom" to "does not read", "k18" to "its fragment grouped-bottom does not read"), made.unused.toSet())
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
        inUse("mine", mapOf("zhuyin.xml" to zhuyin().replace("role=\"backspace\" ", "")))
        assertSame(SceneLayouts.builtIn(appContext, "zhuyin"), SceneLayouts.of(appContext, "zhuyin"))
    }

    @Test
    fun theImportsCheckLeavesTheCacheAlone() {
        // the check runs off the main thread; the cache is the main thread's
        SceneLayouts.invalidate()
        opened("mine", mapOf("zhuyin.xml" to zhuyin(), "k18.xml" to builtIn.text("k18")!!))
        assertEquals(0, SceneLayouts.cached)
    }

    @Test
    fun aLayoutIsReadPastItsByteOrderMarkAndNotPastTheLimit() {
        val dir = File(appContext.cacheDir, "layouts-read").apply { deleteRecursively(); mkdirs() }
        File(dir, "zhuyin.xml").writeText("\uFEFF<keyboard/>")
        assertEquals("<keyboard/>", ThemePackLayouts.read(dir, "zhuyin"))
        File(dir, "zhuyin.xml").writeText("x".repeat(ThemeAssetLimits.MAX_LAYOUT_BYTES.toInt() + 1))
        assertNull(ThemePackLayouts.read(dir, "zhuyin"))
        File(dir, "mine.xml").writeText("<keyboard/>")
        assertNull(ThemePackLayouts.read(dir, "mine"))
        // one the user picks among their own: not read so since 0.6.1
        File(dir, "qwerty.xml").writeText("<keyboard/>")
        assertNull(ThemePackLayouts.read(dir, "qwerty"))
        dir.deleteRecursively()
        inUse("mine", mapOf("zhuyin.xml" to "\uFEFF" + zhuyin()))
        assertEquals("主题注音", name("zhuyin"))
    }
}
