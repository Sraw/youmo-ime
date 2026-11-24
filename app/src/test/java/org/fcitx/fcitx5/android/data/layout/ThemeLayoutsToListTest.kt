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
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
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

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeLayoutsToListTest : ThemePackFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    private val layouts get() = File(folder.root, "layouts")
    private val prefs get() = AppPrefs.getInstance()
    private val keyboard get() = prefs.keyboard
    private val builtIn by lazy { BuiltInLayouts(appContext) }

    // the 0.6.0 example pack's 17 keys: groups of its own, pinyin's alone
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
            keyboard.numberKeyboardLayout, keyboard.symbolsPanelLayout, keyboard.keyboardLayoutsOffered).forEach { it.setValue("") }
        prefs.internal.themeLayoutsUnseen.setValue("")
        prefs.setStoredKeyLists(null)
        // a switch's notice left in EngineNotices' static sets would be dropped or said with another class's
        EngineNotices.forgetToldOnce()
    }

    private fun pack(name: String, vararg files: Pair<String, String>): Theme.Pack {
        placed(sample(sampleXml.replace("name=\"mine\"", "name=\"$name\""), *files.map { "layouts/${it.first}" to it.second }.toTypedArray()))
        return ThemeFilesManager.listPacks().first { it.file == name }
    }

    private fun start(packs: List<Theme.Pack>, shown: Theme?, other: Theme?) =
        ThemeLayoutsToList.atStart(packs) { ThemeLayoutsToList.InUse(shown, other, dark = false) }

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
        // pinyin on the 18 keys the pack drew as its 17: that layout now; English on its 26 keys
        val k17id = ids.getValue("mint · 17 键")
        assertEquals(ImKeyboard.CUSTOM to k17id, chosen(PINYIN))
        assertEquals(ImKeyboard.CUSTOM to ids.getValue("mint · 主题 26 键"), chosen(ENGLISH))
        val choice = CustomKeyboards.choice(k17id)!!
        assertTrue(choice.drawn)
        assertEquals(17, choice.groups.size)
        // 双拼 cannot be typed on 17 keys of other groups: the app's 18 keys, as 0.6.0 drew it
        assertEquals(ImKeyboard.KEYS18 to null, chosen(SHUANGPIN))
        // the tables drew the 26 keys too
        assertEquals(ids.getValue("mint · 主题 26 键"), keyboard.tableKeyboardLayout.getValue())
        // those switched in are listed in the keyboard choice; one only listed is not
        val offered = keyboard.offeredLayouts()
        assertEquals(listOf(k17id), offered.of(PINYIN))
        assertEquals(emptyList<String>(), offered.of(SHUANGPIN))
        assertTrue(ImKeyboards.SLOTS.none { ids.getValue("other · 另一个") in offered.of(it) })
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

    // 0.6.0 refused a board lacking ⌫ and drew the app's: at the start from 0.6.0 (or its backup's) listed, not switched in;
    // one lacking ⇧ it drew, so that one is
    @Test
    @Uat("ST-175")
    fun aPacksBoardLackingKeysIsListedAtTheFirstStartNotChosen() {
        val noBackspace = k17.replace(Regex("<key id=\"backspace\"[^>]*/>"), "")
        check(noBackspace != k17)
        val noShift = qwerty().replace(Regex("<key id=\"shift\"[^>]*/>"), "")
        check(noShift != qwerty())
        val mint = pack("mint", "k18.xml" to noBackspace, "qwerty.xml" to noShift)
        start(listOf(mint), mint, null)
        val ids = listed()
        assertEquals(setOf("mint · 17 键", "mint · 主题 26 键"), ids.keys)
        assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
        assertEquals(ImKeyboard.CUSTOM to ids.getValue("mint · 主题 26 键"), chosen(ENGLISH))
        assertFalse(prefs.internal.themeLayoutsUnseen.getValue().contains("17"))
    }

    // one lacking a key 0.6.0 refused it for: listed, but its user drew the app's and is not switched to it
    @Test
    fun aPacksBoardLackingWhat060RefusedIsListedNotChosen() {
        val noQ = qwerty().replace(Regex("<key id=\"q\"[^>]*>[\\s\\S]*?</key>"), "")
        check(noQ != qwerty())
        val mint = pack("mint", "qwerty.xml" to noQ)
        val before = chosen(ENGLISH)
        start(listOf(mint), mint, null)
        val ids = listed()
        assertTrue(ids.toString(), "mint · 主题 26 键" in ids)
        assertEquals(before, chosen(ENGLISH))
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
        assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
        assertEquals("", prefs.internal.themeLayoutsUnseen.getValue())
        assertNull(ThemeLayoutsToList.run(mint))
        // reimported, another file: the same id, its new name
        val again = pack("mint", "k18.xml" to k17.replace("name=\"17 键\"", "name=\"新 17 键\""))
            .let { p -> if (p.stamp != mint.stamp) p else File(p.dir, "theme.xml").let { it.setLastModified(it.lastModified() + 2000) }.let { ThemeFilesManager.listPacks().first { it.file == "mint" } } }
        assertEquals(1, ThemeLayoutsToList.run(again)!!.size)
        assertEquals(mapOf("mint · 新 17 键" to made.single().done.id), listed())
        val record = CustomLayouts.record(layouts)
        assertEquals(listOf("mint@${again.stamp}"), record.packs)
        assertTrue(record.current)
        val text = File(layouts, CustomLayoutFiles.fileName(made.single().done.id)).readBytes()
        assertEquals(CustomLayoutFiles.digest(text), record.madeOf("mint", "k18")!!.digest)
        start(listOf(again), again, null)
        assertEquals(ImKeyboard.KEYS18 to null, chosen(PINYIN))
    }

    // 跟随系统深色模式 with a pack in each slot: the shown one taken, the other's listed and told
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

    // a 0.6.0 backup restored: listed at the next start and switched; a 0.6.1 backup's pack its record lacks is listed alone
    @Test
    @Uat("PR-68")
    fun aBackupOf060sPacksIsListedAtTheStartAfterItsRestoreItsChoicesSwitched() {
        fun install(name: String) = File(folder.root, name).let { dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme")) }
        val theme = "external/theme/v4/mint/theme.xml" to "<theme/>"
        UserDataManager.import(backupZip(theme, versionCode = V060), install("v060")).getOrThrow()
        assertEquals(listOf("switch", "mint"), restored.readLines())
        restored.delete()
        UserDataManager.import(backupZip(theme, "layouts/.converted" to "#l7\nmint@5\n", versionCode = V061), install("v061")).getOrThrow()
        assertFalse(restored.exists())
        UserDataManager.import(backupZip(theme, "layouts/.converted" to "#l7\nother@5\n", versionCode = V061), install("v061b")).getOrThrow()
        assertEquals(listOf("list", "mint"), restored.readLines())
        restored.delete()
        // by its record, not its version: a 0.6.1 beta's switched; 0.6.1's with none listed alone
        UserDataManager.import(backupZip(theme, "layouts/mine.xml" to "<keyboard/>", versionCode = V061), install("beta")).getOrThrow()
        assertEquals(listOf("switch", "mint"), restored.readLines())
        restored.delete()
        UserDataManager.import(backupZip(theme, "layouts/.converted" to "#l7\n", versionCode = V061), install("none")).getOrThrow()
        assertEquals(listOf("list", "mint"), restored.readLines())
        // two restores before a start: switched where either is
        UserDataManager.import(backupZip(theme, versionCode = V060), install("v060b")).getOrThrow()
        assertEquals(listOf("switch", "mint"), restored.readLines())
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

    // the user's settings under a key name the rule refuses are copied under its new one
    @Test
    @Uat("ST-193")
    fun theSettingsOfARenamedKeyAreCopiedUnderItsNewName() {
        val mint = pack("mint", "k18.xml" to k17.replace("shortcut=\"g18/q\"", "shortcut=\"mine\""))
        prefs.setStoredKeyLists("@2\nfn/mine\tset\t:Y\tY\tZ")
        val done = ThemeLayoutsToList.run(mint)!!.single().done
        val id = done.id
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
        assertEquals(appContext.getString(R.string.theme_pack_layouts_not_listed), ThemeListFragment.layoutsNotListedNotice(appContext, made, 1))
        assertNull("none expected: nothing said", ThemeListFragment.layoutsNotListedNotice(appContext, made, 0))
        assertEquals(CustomLayoutFiles.MAX_LAYOUTS, CustomLayouts.list(layouts).size)
        assertEquals(listOf("mint@${mint.stamp}"), CustomLayouts.record(layouts).packs)
        assertEquals(1, ThemeLayoutsToList.staged(File(mint.dir, "layouts"), "mint", null, ThemeLayoutsToList.appVersion(), layouts).notFitting)
    }

    // a layout is written over only where this pack made it as it is; else the pack's goes under a free id
    @Test
    @Uat("ST-72")
    fun aPackImportedAgainReplacesOnlyTheLayoutItMadeAsItIs() {
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        val mint = pack("mint", "k18.xml" to k17)
        val id = ThemeLayoutsToList.run(mint)!!.single().done.id
        val file = File(layouts, CustomLayoutFiles.fileName(id))
        val staged = ThemeLayoutsToList.staged(File(mint.dir, "layouts"), "mint", null, ThemeLayoutsToList.appVersion(), layouts)
        assertEquals(listOf("mint · 17 键"), staged.replaced)
        // changed by the user since
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

    // killed before its record was written: a retry writes over the file, not listing it twice
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

    private fun reimported(p: Theme.Pack): Theme.Pack {
        File(p.dir, "theme.xml").let { it.setLastModified(it.lastModified() + 2000) }
        return ThemeFilesManager.listPacks().first { it.file == p.file }.also { assertTrue(it.stamp != p.stamp) }
    }

    @Test
    @Uat("PR-68", "ST-72")
    fun onePackImportedUntickedIsRecordedNotListed() {
        val mint = pack("mint", "k18.xml" to k17)
        ThemeLayoutsToList.notListed(mint, layouts)
        assertTrue(listed().isEmpty())
        assertEquals(setOf("mint"), CustomLayouts.record(layouts).packNames)
        assertNull(ThemeLayoutsToList.run(mint))
    }

    // killed while listing: the next start retries; the third that finds it so gives up
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

    @Test
    fun aStartCountsItsTry() {
        held()
        val mint = pack("mint", "k18.xml" to k17)
        start(listOf(mint), mint, null)
        listing.removeFirst().run()
        assertEquals("1", trying.readText())
    }

    // listed off the main thread, switched on it after; the trying marker exists between the two
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
        // a pack import or 外观 → 布局 opened meanwhile waits for the switch too
        val waiting = thread(isDaemon = true) { ThemeLayoutsToList.awaitStart() }
        waiting.join(WAIT_MS)
        assertTrue(waiting.isAlive)
        switching.removeFirst().run()
        waiting.join(WAIT_MS * 20)
        assertFalse(waiting.isAlive)
        assertEquals(ImKeyboard.CUSTOM to id, chosen(PINYIN))
        assertEquals(1, read)
        // a restore recorded between is kept for the next start
        assertEquals(listOf("list", "late"), restored.readLines())
        assertTrue(prefs.storedKeyLists()!!.contains("$id/r1c1\t"))
        assertTrue(prefs.internal.themeLayoutsUnseen.getValue().isNotEmpty())
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
    }

    // killed after listing, before the switch: the next start relists (by the record's ids) and switches
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

    // a setting changed while listing is not overridden
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

    // a number keyboard on a gone or other-family layout is none, the pack's switched in; an unreadable file's is kept
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

    // a restore during the first start's held listing waits for it, then drops the unmade switch; refused, the switch
    // still happens. Each row its own pack and phone
    @Test
    @Uat("PR-68")
    fun aRestoreAsTheStartListsDropsItsSwitchOnlyPastItsChecks() {
        fun restoredAsItStarts(name: String, versionCode: Long): Result<*> {
            // a row's own queues: not a start an earlier row left held
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
                // a 0.6.1 phone's empty record: its packs listed, not switched
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
                // recorded after the listing: listed alone by the next start
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

    // a restore past its checks while the switch is being made waits for it, so the backup's settings win. Robolectric's
    // apply() writes at once, so dropStart's awaitWritten is not pinned here
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
        // copied in where AppPrefs keeps its own file
        val file = rawPrefs().javaClass.getDeclaredField("mFile").apply { isAccessible = true }.get(rawPrefs()) as File
        val backupPrefs = """<map><string name="${keyboard.pinyinKeyboard.key}">${PinyinKeyboard.NineKeys.name}</string></map>"""
        val phone = File(folder.root, "phone").let {
            dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme"), sharedPrefs = file.parentFile!!)
        }
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
        // the phone's writes are on disk by now: the backup's remain
        AppPrefs.getInstance().awaitWritten()
        assertEquals(backupPrefs, file.readText())
    }

    // nothing to list: no switch, nothing to wait for
    @Test
    fun aStartWithNothingToListIsNotWaitedFor() {
        held()
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        start(emptyList(), null, null)
        listing.removeFirst().run()
        assertTrue(switching.isEmpty())
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

        const val TOO_OLD = 2000L
    }
}
