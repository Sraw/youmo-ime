/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.layout

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.backupZip
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.core.rawPrefs
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFixture
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeListFragment
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A theme pack's boards the user picks among their own layouts put in 外观 → 布局 (061-custom-layout §11, L7): the packs
 * installed before 0.6.1 at its first start, the boards chosen switched to them; a backup of 0.6.0's packs at the start
 * after its restore, the same; one imported with 「一起导入」, listed alone. Once a pack; one imported again an update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeLayoutsToListTest : ThemePackFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    private val layouts get() = File(folder.root, "layouts")
    private val prefs get() = AppPrefs.getInstance()
    private val keyboard get() = prefs.keyboard
    private val builtIn by lazy { BuiltInLayouts(appContext) }

    // the 0.6.0 example pack's 17 keys (lib/keyboard-scene/src/commonTest/data/mint-k17): groups of its own, 拼音's alone
    private val k17 = File("../lib/keyboard-scene/src/commonTest/data/mint-k17/layouts/k18.xml").readText()
    private fun qwerty(name: String = "主题 26 键") = builtIn.text("qwerty")!!.replace("name=\"26 键\"", "name=\"$name\"")
    private fun k18(name: String) = builtIn.text("k18")!!.replace("name=\"18 键\"", "name=\"$name\"")

    private val dirWas = CustomKeyboards.dir
    private val schemeWas = EngineState.shuangpinScheme
    private val realListed = ThemeLayoutsToList.listedFile
    private val realRestored = ThemeLayoutsToList.restoredFile
    private val realTrying = ThemeLayoutsToList.tryingFile
    private val realBackground = ThemeLayoutsToList.background
    private val realMain = ThemeLayoutsToList.mainThread
    private val listing = ArrayDeque<Runnable>()
    private val switching = ArrayDeque<Runnable>()
    private val marker get() = File(folder.root, "no_backup/theme-layouts-listed")
    private val restored get() = File(folder.root, "no_backup/theme-layouts-restored")
    private val trying get() = File(folder.root, "no_backup/theme-layouts-trying")

    @Before
    fun ours() {
        CustomKeyboards.dir = { layouts }
        EngineState.shuangpinScheme = ShuangpinScheme.XIAOHE
        ThemeLayoutsToList.listedFile = { marker }
        ThemeLayoutsToList.restoredFile = { restored }
        ThemeLayoutsToList.tryingFile = { trying }
        // each stage at once, as a start's, unless a case holds them ([held])
        ThemeLayoutsToList.background = Executor { it.run() }
        ThemeLayoutsToList.mainThread = Executor { it.run() }
        keyboard.setImKeyboard(PINYIN, ImKeyboard.KEYS18)
        keyboard.setImKeyboard(SHUANGPIN, ImKeyboard.KEYS18)
        keyboard.setImKeyboard(ENGLISH, ImKeyboard.FULL)
    }

    @After
    fun asItWas() {
        forgetTheTestsLayouts(dirWas)
        EngineState.shuangpinScheme = schemeWas
        ThemeLayoutsToList.listedFile = realListed
        ThemeLayoutsToList.restoredFile = realRestored
        ThemeLayoutsToList.tryingFile = realTrying
        ThemeLayoutsToList.background = realBackground
        ThemeLayoutsToList.mainThread = realMain
        CustomLayouts.forgetAll()
        keyboard.pinyinKeyboard.setValue(keyboard.pinyinKeyboard.defaultValue)
        keyboard.shuangpinKeyboard.setValue(keyboard.shuangpinKeyboard.defaultValue)
        keyboard.englishKeyboard.setValue(keyboard.englishKeyboard.defaultValue)
        listOf(keyboard.pinyinKeyboardLayout, keyboard.shuangpinKeyboardLayout, keyboard.englishKeyboardLayout, keyboard.tableKeyboardLayout,
            keyboard.numberKeyboardLayout, keyboard.symbolsPanelLayout).forEach { it.setValue("") }
        prefs.internal.themeLayoutsUnseen.setValue("")
        prefs.setStoredKeyLists(null)
        // a switch's notice (tellUnseen) left in EngineNotices' static sets would be dropped or said with another class's
        EngineNotices.forgetToldOnce()
    }

    // a pack [name] imported with [files] in its layouts/
    private fun pack(name: String, vararg files: Pair<String, String>): Theme.Pack {
        placed(sample(sampleXml.replace("name=\"mine\"", "name=\"$name\""), *files.map { "layouts/${it.first}" to it.second }.toTypedArray()))
        return ThemeFilesManager.listPacks().first { it.file == name }
    }

    // a start with [shown] drawn and [other] the other slot's, a light phone
    private fun start(packs: List<Theme.Pack>, shown: Theme?, other: Theme?) =
        ThemeLayoutsToList.atStart(packs) { ThemeLayoutsToList.InUse(shown, other, dark = false) }

    // the listing and the switch held, each run when the case says
    private fun held() {
        ThemeLayoutsToList.background = Executor { listing += it }
        ThemeLayoutsToList.mainThread = Executor { switching += it }
    }

    private fun listed() = CustomLayouts.list(layouts).associate { it.name to it.id }

    private fun chosen(im: String) = keyboard.imKeyboards().let { it.of(im) to it.layouts[im] }

    @Test
    @Uat("ST-175")
    fun atTheFirstStartThePacksBoardsAreListedAndThoseChosenSwitchedOnce() {
        val mint = pack("mint", "k18.xml" to k17, "qwerty.xml" to qwerty())
        val other = pack("other", "k18.xml" to k18("另一个"))
        start(listOf(mint, other), mint, null)
        val ids = listed()
        assertEquals(setOf("mint · 17 键", "mint · 主题 26 键", "other · 另一个"), ids.keys)
        // 拼音 on the 18 keys the pack drew as its 17: that layout now, the same keys; English on its 26 keys
        val k17id = ids.getValue("mint · 17 键")
        assertEquals(ImKeyboard.CUSTOM to k17id, chosen(PINYIN))
        assertEquals(ImKeyboard.CUSTOM to ids.getValue("mint · 主题 26 键"), chosen(ENGLISH))
        val choice = CustomKeyboards.choice(k17id)!!
        assertTrue(choice.drawn)
        assertEquals(17, choice.groups.size)
        // 双拼 cannot be typed on 17 keys of other groups (S8): the app's 18 keys, as it was drawn in 0.6.0
        assertEquals(ImKeyboard.KEYS18 to null, chosen(SHUANGPIN))
        // the tables drew the 26 keys too, and these have their extra keys: theirs as well
        assertEquals(ids.getValue("mint · 主题 26 键"), keyboard.tableKeyboardLayout.getValue())
        // told once, the pack in use's, in the boards' order
        val told = prefs.internal.themeLayoutsUnseen.getValue()
        assertEquals(appContext.getString(R.string.theme_layouts_in_use, "mint", "主题 26 键, 17 键", "English, Wubi and other tables, Pinyin"), told)
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
        // once an install: the user's choice since stays
        keyboard.setImKeyboard(PINYIN, ImKeyboard.KEYS18)
        start(listOf(mint, other), mint, null)
        assertEquals(ImKeyboard.KEYS18, chosen(PINYIN).first)
    }

    @Test
    @Uat("ST-72")
    fun anImportListsAloneOnceAndOneImportedAgainUpdatesTheSameLayout() {
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        val mint = pack("mint", "k18.xml" to k17)
        val made = ThemeLayoutsToList.run(mint)!!
        assertEquals(listOf("k18"), made.map { it.board })
        assertEquals(mapOf("mint · 17 键" to made.single().done.id), listed())
        // listed, nothing chosen
        assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
        assertEquals("", prefs.internal.themeLayoutsUnseen.getValue())
        assertNull(ThemeLayoutsToList.run(mint))
        // imported again, another file (its theme.xml's time another): the same id, its new name
        val again = pack("mint", "k18.xml" to k17.replace("name=\"17 键\"", "name=\"新 17 键\""))
            .let { p -> if (p.stamp != mint.stamp) p else File(p.dir, "theme.xml").let { it.setLastModified(it.lastModified() + 2000) }.let { ThemeFilesManager.listPacks().first { it.file == "mint" } } }
        assertEquals(1, ThemeLayoutsToList.run(again)!!.size)
        assertEquals(mapOf("mint · 新 17 键" to made.single().done.id), listed())
        // the pack's last line alone (NIT2), the layout's digest as written now
        val record = CustomLayouts.record(layouts)
        assertEquals(listOf("mint@${again.stamp}"), record.packs)
        assertTrue(record.current)
        val text = File(layouts, CustomLayoutFiles.fileName(made.single().done.id)).readBytes()
        assertEquals(CustomLayoutFiles.digest(text), record.madeOf("mint", "k18")!!.digest)
        // a start after: nothing (none restored, the install's done)
        start(listOf(again), again, null)
        assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
    }

    // Q15: 跟随系统深色模式 with a pack in each slot of the same board: the one shown taken, the other's listed and told
    @Test
    @Uat("ST-176")
    fun bothSlotsPacksOfOneBoardTheShownOnesChosenTheOthersToldOf() {
        keyboard.setImKeyboard(SHUANGPIN, ImKeyboard.FULL)
        keyboard.setImKeyboard(ENGLISH, ImKeyboard.NINE_KEYS)
        val day = pack("day", "k18.xml" to k17.replace("name=\"17 键\"", "name=\"日 17\""))
        val night = pack("night", "k18.xml" to k18("夜 18"))
        start(listOf(day, night), day, night)
        val ids = listed()
        assertEquals(setOf("day · 日 17", "night · 夜 18"), ids.keys)
        assertEquals(ImKeyboard.CUSTOM to ids.getValue("day · 日 17"), chosen(PINYIN))
        val told = appContext.getString(R.string.theme_layouts_in_use, "day", "日 17", "Pinyin") + "\n" +
            appContext.getString(R.string.theme_layouts_aside_dark, "night", "夜 18")
        assertEquals(told, prefs.internal.themeLayoutsUnseen.getValue())
    }

    // a backup of 0.6.0 restored into 0.6.1: its packs recorded, then at the next start listed and what it chose switched
    // (the keyboard as the backup's app drew it); one of 0.6.1 its record has not, listed alone; one its record has, none
    @Test
    @Uat("PR-68")
    fun aBackupOf060sPacksIsListedAtTheStartAfterItsRestoreItsChoicesSwitched() {
        fun install(name: String) = File(folder.root, name).let { dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme")) }
        val theme = "external/theme/v4/mint/theme.xml" to "<theme/>"
        UserDataManager.import(backupZip(theme, versionCode = V060), install("v060")).getOrThrow()
        assertEquals(listOf("switch", "mint"), restored.readLines())
        restored.delete()
        // 0.6.1's, the pack in its record: done there already
        UserDataManager.import(backupZip(theme, "layouts/.converted" to "#l7\nmint@5\n", versionCode = V061), install("v061")).getOrThrow()
        assertFalse(restored.exists())
        UserDataManager.import(backupZip(theme, "layouts/.converted" to "#l7\nother@5\n", versionCode = V061), install("v061b")).getOrThrow()
        assertEquals(listOf("list", "mint"), restored.readLines())
        restored.delete()
        // by its record, not its version (NIT1): a 0.6.1 beta's (layouts, no #l7) switched; 0.6.1's of none listed alone
        UserDataManager.import(backupZip(theme, "layouts/mine.xml" to "<keyboard/>", versionCode = V061), install("beta")).getOrThrow()
        assertEquals(listOf("switch", "mint"), restored.readLines())
        restored.delete()
        UserDataManager.import(backupZip(theme, "layouts/.converted" to "#l7\n", versionCode = V061), install("none")).getOrThrow()
        assertEquals(listOf("list", "mint"), restored.readLines())
        // two restores before a start: switched where either is
        UserDataManager.import(backupZip(theme, versionCode = V060), install("v060b")).getOrThrow()
        assertEquals(listOf("switch", "mint"), restored.readLines())
        // the next start: this install's done, the restored pack's boards listed and switched
        marker.writeText("")
        val mint = pack("mint", "k18.xml" to k17)
        val untouched = pack("kept", "k18.xml" to k18("留着"))
        start(listOf(mint, untouched), mint, null)
        assertEquals(setOf("mint · 17 键"), listed().keys)
        assertEquals(ImKeyboard.CUSTOM to listed().getValue("mint · 17 键"), chosen(PINYIN))
        assertFalse(restored.exists())
    }

    @Test
    @Uat("PR-68")
    fun aRestoreOf061sPacksListsThemAndChoosesNone() {
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        ThemeLayoutsToList.restored(listOf("mint"), switch = false)
        val mint = pack("mint", "k18.xml" to k17)
        start(listOf(mint), mint, null)
        assertEquals(setOf("mint · 17 键"), listed().keys)
        assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
    }

    // the names a pack's keys had that the rule refuses (§2.3): what the user set under one copied under its new one
    @Test
    @Uat("ST-193")
    fun theSettingsOfARenamedKeyAreCopiedUnderItsNewName() {
        val mint = pack("mint", "k18.xml" to k17.replace("shortcut=\"g18/q\"", "shortcut=\"mine\""))
        prefs.setStoredKeyLists("@2\nfn/mine\tset\t:Y\tY\tZ")
        val done = ThemeLayoutsToList.run(mint)!!.single().done
        val id = done.id
        // copied by the caller, on the main thread
        assertEquals("@2\nfn/mine\tset\t:Y\tY\tZ", prefs.storedKeyLists())
        ThemeLayoutsToList.copySettings(done.renames)
        val lists = prefs.storedKeyLists()!!
        assertTrue(lists, "fn/mine\t" in lists)
        assertTrue(lists, "$id/r1c1\t" in lists)
    }

    @Test
    @Uat("ST-72")
    fun aListFullTakesNoNewOneThePackRecordedAllTheSame() {
        layouts.mkdirs()
        repeat(CustomLayoutFiles.MAX_LAYOUTS) { i ->
            CustomLayouts.save("own$i", builtIn.text("qwerty")!!.replaceFirst("id=\"qwerty\"", "id=\"own$i\" based-on=\"qwerty\""), layouts)
        }
        val mint = pack("mint", "k18.xml" to k17)
        val made = ThemeLayoutsToList.run(mint)
        assertEquals(emptyList<ThemeLayoutsToList.Listed>(), made)
        // and the import says so (R26 F8518): its board not listed
        assertEquals(appContext.getString(R.string.theme_pack_layouts_not_listed), ThemeListFragment.layoutsNotListedNotice(appContext, made, 1))
        assertNull("none expected: nothing said", ThemeListFragment.layoutsNotListedNotice(appContext, made, 0))
        assertEquals(CustomLayoutFiles.MAX_LAYOUTS, CustomLayouts.list(layouts).size)
        assertEquals(listOf("mint@${mint.stamp}"), CustomLayouts.record(layouts).packs)
        // its confirmation says so first (NIT4)
        assertEquals(1, ThemeLayoutsToList.staged(File(mint.dir, "layouts"), "mint", null, ThemeLayoutsToList.appVersion(), layouts).notFitting)
    }

    // L7 review S2: a layout of the id is written over only where this pack made it as it is; else the pack's under a
    // free id, the other kept; the confirmation says which it replaces
    @Test
    @Uat("ST-72")
    fun aPackImportedAgainReplacesOnlyTheLayoutItMadeAsItIs() {
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        val mint = pack("mint", "k18.xml" to k17)
        val id = ThemeLayoutsToList.run(mint)!!.single().done.id
        val file = File(layouts, CustomLayoutFiles.fileName(id))
        // as it made it: the next version's import replaces it, and says so
        val staged = ThemeLayoutsToList.staged(File(mint.dir, "layouts"), "mint", null, ThemeLayoutsToList.appVersion(), layouts)
        assertEquals(listOf("mint · 17 键"), staged.replaced)
        // changed by the user since (exported, edited, imported over it)
        file.writeText(file.readText().replace("name=\"mint · 17 键\"", "name=\"我改的\""))
        CustomLayouts.forgetAll()
        assertEquals(emptyList<String>(), ThemeLayoutsToList.staged(File(mint.dir, "layouts"), "mint", null, ThemeLayoutsToList.appVersion(), layouts).replaced)
        val again = reimported(mint)
        val made = ThemeLayoutsToList.run(again)!!.single()
        assertEquals("$id-2", made.done.id)
        assertEquals(CustomLayoutFiles.Put.NEW, made.put)
        assertEquals(mapOf("我改的" to id, "mint · 17 键" to "$id-2"), listed())
        // another pack whose name hashes alike, or the user's own of that id: the same
        val own = PackLayouts.idOf("other", "k18")
        CustomLayouts.save(own, k17.replaceFirst("id=\"k18\"", "id=\"$own\" based-on=\"k18\"").replace("name=\"17 键\"", "name=\"我的\""), layouts)
        val other = pack("other", "k18.xml" to k18("另一个"))
        assertEquals("$own-2", ThemeLayoutsToList.run(other)!!.single().done.id)
        assertTrue(listed().containsKey("我的"))
    }

    // L7 re-check SHOULD-1: a layout saved, the run killed before its record was written (or the write failed), then
    // tried again: the file as the board makes it is written over, not listed twice (a second 「mint · 17 键」 at -2)
    @Test
    fun aRunKilledBetweenItsSaveAndItsRecordListsItOnceWhenTriedAgain() {
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        val mint = pack("mint", "k18.xml" to k17)
        val id = ThemeLayoutsToList.run(mint)!!.single().done.id
        File(layouts, CustomLayoutFiles.CONVERTED).writeText("${CustomLayoutFiles.CURRENT}\n")
        CustomLayouts.forgetAll()
        val again = ThemeLayoutsToList.run(mint, again = true)!!.single()
        assertEquals(id, again.done.id)
        assertEquals(CustomLayoutFiles.Put.REPLACE, again.put)
        assertEquals(mapOf("mint · 17 键" to id), listed())
        assertEquals(id, CustomLayouts.record(layouts).madeOf(mint.file, "k18")?.id)
    }

    // the pack [p] imported again: another stamp
    private fun reimported(p: Theme.Pack): Theme.Pack {
        File(p.dir, "theme.xml").let { it.setLastModified(it.lastModified() + 2000) }
        return ThemeFilesManager.listPacks().first { it.file == p.file }.also { assertTrue(it.stamp != p.stamp) }
    }

    // NIT1: one imported unticked is recorded, so a restore of a backup holding it does not list it
    @Test
    @Uat("PR-68", "ST-72")
    fun onePackImportedUntickedIsRecordedNotListed() {
        val mint = pack("mint", "k18.xml" to k17)
        ThemeLayoutsToList.notListed(mint, layouts)
        assertTrue(listed().isEmpty())
        assertEquals(setOf("mint"), CustomLayouts.record(layouts).packNames)
        assertNull(ThemeLayoutsToList.run(mint))
    }

    // a start that ended as it listed them (killed, as is common: L7 review B2): the next tries again, the third that
    // finds it ended so gives up (logged), its markers written
    @Test
    @Uat("ST-175")
    fun aStartThatEndedInTheMiddleIsTriedAgainThreeTimesAtMost() {
        val mint = pack("mint", "k18.xml" to k17)
        trying.parentFile!!.mkdirs()
        trying.writeText("2")
        start(listOf(mint), mint, null)
        assertEquals(ImKeyboard.CUSTOM to listed().getValue("mint · 17 键"), chosen(PINYIN))
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
        // one more that ended so: given up
        marker.delete()
        keyboard.setImKeyboard(PINYIN, ImKeyboard.KEYS18)
        trying.writeText(ThemeLayoutsToList.MAX_TRIES.toString())
        ThemeLayoutsToList.restored(listOf("mint"), switch = true)
        start(listOf(mint), mint, null)
        assertEquals(ImKeyboard.KEYS18, chosen(PINYIN).first)
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
        assertFalse(restored.exists())
    }

    // the count of tries as written: one held, its count there till the switch
    @Test
    fun aStartCountsItsTry() {
        held()
        val mint = pack("mint", "k18.xml" to k17)
        start(listOf(mint), mint, null)
        listing.removeFirst().run()
        assertEquals("1", trying.readText())
    }

    // the start's listing off the main thread, its switch on it after: till then nothing chosen, the settings of a key
    // renamed not copied, the themes in use not read; the marker of trying there between the two, gone after
    @Test
    @Uat("ST-175")
    fun theStartListsOffTheMainThreadAndSwitchesOnItAfter() {
        held()
        val mint = pack("mint", "k18.xml" to k17.replace("shortcut=\"g18/q\"", "shortcut=\"mine\""))
        prefs.setStoredKeyLists("@2\nfn/mine\tset\t:Y\tY\tZ")
        var read = 0
        ThemeLayoutsToList.atStart(listOf(mint)) { read++; ThemeLayoutsToList.InUse(mint, null, dark = false) }
        assertTrue(listed().isEmpty())
        assertFalse(trying.exists())
        listing.removeFirst().run()
        val id = listed().getValue("mint · 17 键")
        assertTrue(trying.isFile)
        assertFalse(marker.exists())
        assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
        assertEquals(0, read)
        assertFalse(prefs.storedKeyLists()!!.contains("$id/"))
        assertEquals("", prefs.internal.themeLayoutsUnseen.getValue())
        ThemeLayoutsToList.restored(listOf("late"), switch = false)
        // a pack imported or 外观 → 布局 opened meanwhile waits for the switch too (the choices it reads made)
        val waiting = thread(isDaemon = true) { ThemeLayoutsToList.awaitStart() }
        waiting.join(WAIT_MS)
        assertTrue(waiting.isAlive)
        switching.removeFirst().run()
        waiting.join(WAIT_MS * 20)
        assertFalse(waiting.isAlive)
        assertEquals(ImKeyboard.CUSTOM to id, chosen(PINYIN))
        assertEquals(1, read)
        // a restore recorded between, not taken by this start: kept for the next (B4)
        assertEquals(listOf("list", "late"), restored.readLines())
        assertTrue(prefs.storedKeyLists()!!.contains("$id/r1c1\t"))
        assertTrue(prefs.internal.themeLayoutsUnseen.getValue().isNotEmpty())
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
    }

    // killed after the listing, before the switch (L7 review B1, S5): its markers not yet written, the next start lists
    // again (the packs recorded: by their record's ids) and switches
    @Test
    @Uat("ST-175")
    fun aStartKilledBetweenItsListingAndItsSwitchSwitchesAtTheNext() {
        held()
        val mint = pack("mint", "k18.xml" to k17)
        ThemeLayoutsToList.restored(listOf("mint"), switch = true)
        start(listOf(mint), mint, null)
        listing.removeFirst().run()
        switching.clear()
        assertFalse(marker.exists())
        assertTrue(restored.exists())
        ThemeLayoutsToList.background = Executor { it.run() }
        ThemeLayoutsToList.mainThread = Executor { it.run() }
        start(listOf(mint), mint, null)
        assertEquals(setOf("mint · 17 键"), listed().keys)
        assertEquals(ImKeyboard.CUSTOM to listed().getValue("mint · 17 键"), chosen(PINYIN))
        assertTrue(prefs.internal.themeLayoutsUnseen.getValue().isNotEmpty())
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
        assertFalse(restored.exists())
    }

    // the switch planned on the settings as they are as it is made (B3): one changed while it listed is not overridden
    @Test
    fun theSwitchIsPlannedOnTheSettingsAsItIsMade() {
        held()
        val mint = pack("mint", "k18.xml" to k17)
        start(listOf(mint), mint, null)
        listing.removeFirst().run()
        keyboard.setImKeyboard(PINYIN, ImKeyboard.FULL)
        switching.removeFirst().run()
        assertEquals(ImKeyboard.FULL to null, chosen(PINYIN))
    }

    // L5 (PanelLayouts.missing): a number keyboard set to a layout gone, or of another family now, is none, the pack's
    // switched in; one whose file does not read is kept (读不出，暂用内置的), as the app's card says
    @Test
    fun aPanelSetToALayoutGoneCountsAsNone() {
        val number = builtIn.text("number")!!.replace("name=\"数字\"", "name=\"大数字\"")
        val mint = pack("mint", "number.xml" to number)
        CustomLayouts.save("broken", "<layout", layouts)
        keyboard.numberKeyboardLayout.setValue("broken")
        start(listOf(mint), mint, null)
        assertEquals("broken", keyboard.numberKeyboardLayout.getValue())
        CustomLayouts.save("mine", k18("我的").replaceFirst("id=\"k18\"", "id=\"mine\" based-on=\"k18\""), layouts)
        for (id in listOf("mine", "gone")) {
            keyboard.numberKeyboardLayout.setValue(id)
            marker.delete()
            start(listOf(mint), mint, null)
            assertEquals(id, listed().getValue("mint · 大数字"), keyboard.numberKeyboardLayout.getValue())
        }
    }

    // a restore (UserDataManager.import) as the first start lists, the listing and the switch held: it waits for the
    // listing (the packs and the layouts not written under it). Past its checks it drops the switch not yet made (the
    // settings it copies in not written over by it), and the packs it records are the next start's; refused at them
    // (R26 F8134) it copied no settings in, and the upgrade's switch is made all the same, not lost with its end written.
    // Each row a pack and a phone of its own (R26 F8431: the drop was the test's own call, not the import's)
    @Test
    @Uat("PR-68")
    fun aRestoreAsTheStartListsDropsItsSwitchOnlyPastItsChecks() {
        // [name]'s pack listed by a first start, a backup of [versionCode] restored meanwhile: the restore's result
        fun restoredAsItStarts(name: String, versionCode: Long): Result<*> {
            // a row's own queues: not a start an earlier row left held (R26 F8505)
            listing.clear()
            switching.clear()
            held()
            listOf(marker, restored, trying).forEach { it.delete() }
            keyboard.setImKeyboard(PINYIN, ImKeyboard.KEYS18)
            prefs.internal.themeLayoutsUnseen.setValue("")
            val p = pack(name, "k18.xml" to k17)
            start(listOf(p), p, null)
            val phone = File(folder.root, "phone-$name").let { dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme")) }
            var result: Result<*>? = null
            val restoring = thread(isDaemon = true) {
                // its record of none (a 0.6.1 phone's: #l7 alone), so its packs listed, not switched
                val record = "layouts/${CustomLayoutFiles.CONVERTED}" to "${CustomLayoutFiles.CURRENT}\n"
                result = UserDataManager.import(backupZip("external/theme/v4/$name/theme.xml" to "<theme/>", record, versionCode = versionCode), phone)
            }
            restoring.join(WAIT_MS)
            assertTrue("waits for the listing", restoring.isAlive)
            listing.removeFirst().run()
            restoring.join(WAIT_MS * 20)
            assertFalse(restoring.isAlive)
            switching.removeFirst().run()
            return result!!
        }
        assertRows(
            "past its checks: the switch dropped, the install's listing done, its own packs the next start's" to {
                assertTrue(restoredAsItStarts("mint", V061).isSuccess)
                assertTrue(listed().containsKey("mint · 17 键"))
                assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
                assertEquals("", prefs.internal.themeLayoutsUnseen.getValue())
                assertFalse(trying.exists())
                // not listed and switched again over its settings
                assertTrue(marker.isFile)
                // recorded after the listing: 0.6.1's of no record listed alone, by the next start
                assertEquals(listOf("list", "mint"), restored.readLines())
            },
            "refused at its checks (too old a backup): the switch made" to {
                assertTrue(restoredAsItStarts("fern", TOO_OLD).isFailure)
                assertEquals(ImKeyboard.CUSTOM to listed().getValue("fern · 17 键"), chosen(PINYIN))
                assertTrue(prefs.internal.themeLayoutsUnseen.getValue().isNotEmpty())
                assertTrue(marker.isFile)
            },
        )
    }

    // a restore past its checks as the start's switch is being made (begun before it came, here held in it): no more to
    // drop, waited for, the backup's settings copied over the switch's, not the switch's over the backup's (R26 F8423).
    // Through UserDataManager.import onto AppPrefs' own file (R26 F8504): pins the order, the switch ended before the
    // backup's settings are copied in. On a phone the switch's apply() may still be writing then (dropStart's
    // awaitWritten); Robolectric's apply() writes at once, so that wait is not pinned here
    @Test
    @Uat("PR-68")
    fun aRestoreWaitsForASwitchBegunBeforeIt() {
        held()
        val mint = pack("mint", "k18.xml" to k17)
        val inSwitch = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        ThemeLayoutsToList.atStart(listOf(mint)) {
            inSwitch.countDown()
            goOn.await(WAIT_MS * 50, TimeUnit.MILLISECONDS)
            ThemeLayoutsToList.InUse(mint, null, dark = false)
        }
        listing.removeFirst().run()
        val switch = thread(isDaemon = true) { switching.removeFirst().run() }
        assertTrue(inSwitch.await(WAIT_MS * 20, TimeUnit.MILLISECONDS))
        // the backup's settings: pinyin on the nine keys; copied in where AppPrefs keeps its own
        val file = rawPrefs().javaClass.getDeclaredField("mFile").apply { isAccessible = true }.get(rawPrefs()) as File
        val backupPrefs = """<map><string name="${keyboard.pinyinKeyboard.key}">${PinyinKeyboard.NineKeys.name}</string></map>"""
        val phone = File(folder.root, "phone").let {
            dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme"), sharedPrefs = file.parentFile!!)
        }
        // a 0.6.1 phone's backup, its record of none: its packs listed, not switched
        val record = "layouts/${CustomLayoutFiles.CONVERTED}" to "${CustomLayoutFiles.CURRENT}\n"
        val zip = backupZip("external/theme/v4/mint/theme.xml" to "<theme/>", record, "shared_prefs/${file.name}" to backupPrefs, versionCode = V061)
        var result: Result<*>? = null
        val restoring = thread(isDaemon = true) { result = UserDataManager.import(zip, phone) }
        restoring.join(WAIT_MS)
        assertTrue("waits for the switch begun", restoring.isAlive)
        goOn.countDown()
        restoring.join(WAIT_MS * 20)
        assertFalse(restoring.isAlive)
        switch.join(WAIT_MS * 20)
        assertTrue(result.toString(), result!!.isSuccess)
        // made: begun before the restore came
        assertEquals(ImKeyboard.CUSTOM to listed().getValue("mint · 17 键"), chosen(PINYIN))
        assertTrue(marker.isFile)
        // every write of the phone's settings on disk by now (the app started again after a restore): the backup's remain
        AppPrefs.getInstance().awaitWritten()
        assertEquals(backupPrefs, file.readText())
    }

    // nothing to list (the install's done, none restored; or no pack has a board): no switch, nothing to wait for
    @Test
    fun aStartWithNothingToListIsNotWaitedFor() {
        held()
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        start(emptyList(), null, null)
        listing.removeFirst().run()
        assertTrue(switching.isEmpty())
        // nor where no pack has a board to list: its markers written off the main thread
        marker.delete()
        start(listOf(pack("plain")), null, null)
        listing.removeFirst().run()
        assertTrue(switching.isEmpty())
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
        val waiting = thread(isDaemon = true) { ThemeLayoutsToList.awaitStart() }
        waiting.join(WAIT_MS * 20)
        assertFalse(waiting.isAlive)
    }

    private companion object {
        const val WAIT_MS = 200L

        // 0.6.0's version code and 0.6.1's lowest
        const val V060 = 5122L
        const val V061 = UserDataManager.FIRST_WITH_SEPARATOR

        // a backup of before 0.3.1 (UserDataManager.FIRST_WITH_THE_RECORD): refused at its checks
        const val TOO_OLD = 2000L
    }
}
