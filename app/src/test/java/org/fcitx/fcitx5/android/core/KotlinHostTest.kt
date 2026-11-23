/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Looper
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows as assertNamedRows
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** [KotlinHost] as the daemon starts it, keys going to a scripted engine (the app's data is not on the JVM classpath). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KotlinHostTest {

    private lateinit var home: File
    private lateinit var engine: ScriptedEngine
    private lateinit var fcitx: KotlinHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events = HostLog<HostEvent>()

    private fun config(path: String) = File(home, "config/$path")

    @Before
    fun setUp() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        // AppPrefs outlives a case's sandbox, with the last one's first run done
        AppPrefs.getInstance().internal.firstRun.setValue(true)
        start()
    }

    private fun start(side: (ScriptedEngine) -> KotlinHost.EngineSide = { it }) {
        engine = ScriptedEngine(home)
        val readies = events.list().count { it is HostEvent.Ready }
        // the first run is the case's to set: a restart is none
        fcitx = startedHost(side(engine), firstRun = false) { host -> scope.launch { host.eventFlow.collect(events::add) } }
        // isReady turns a few calls before Ready is emitted
        until("the Ready event") { events.list().count { it is HostEvent.Ready } > readies }
    }

    private fun restart() {
        fcitx.stop()
        start()
    }

    @After
    fun tearDown() {
        fcitx.stop()
        scope.cancel()
        home.deleteRecursively()
        // the singleton's prefs outlive the test
        AppPrefs.getInstance().internal.themeLayoutsUnseen.setValue("")
        AppPrefs.getInstance().keyboard.pinyinKeyboard.run { setValue(defaultValue) }
        // published by the host, read by the keyboard window: not the next test's
        EngineState.traditionalIms = null
        EngineState.ownKeys = null
        NineKeyLetters.clearInUse()
        EngineNotices.sayer = null
        // they wait in the object: not the next class's to say
        EngineNotices.forgetToldOnce()
        directBoot(false)
    }

    @Test
    fun readyIsTheFirstEventAndPinyinLeadsTheGroupOnTheFirstRun() = runBlocking {
        assertTrue(events.list().first() is HostEvent.Ready)
        assertEquals(InputMethodCatalog.PINYIN, fcitx.enabledIme().first().uniqueName)
        // no options file yet: the host options' defaults, 自动 read as the device's level
        // medium: Robolectric's fixed profile, never HostCore's default high nor the build machine's CPU
        assertEquals(EngineOptions().toSettings(ModelLevel.MEDIUM), engine.settings.last())
    }

    @Test
    fun anInputMethodsPageSavesReadsBackAndSurvivesARestart() = runBlocking {
        val page = fcitx.imOptions(InputMethodCatalog.PINYIN)!!
        assertEquals("7", page["PageSize"])
        fcitx.setImOptions(InputMethodCatalog.PINYIN, page + ("PageSize" to "9"))
        assertEquals("9", fcitx.imOptions(InputMethodCatalog.PINYIN)!!["PageSize"])
        assertTrue("the engines told", engine.settings.last().pageSize == 9)
        fcitx.save()
        assertTrue(config(EngineOptions.PATH).readText().lines().contains("PageSize=9"))
        restart()
        assertEquals("9", fcitx.imOptions(InputMethodCatalog.PINYIN)!!["PageSize"])
    }

    @Test
    fun anInputMethodWithNoPageHasNoOptionsAndALanguageWithNoMapNoPunctuation() = runBlocking {
        assertNull(fcitx.imOptions("keyboard-xx"))
        assertEquals(emptyList<PunctuationMapEntry>(), fcitx.punctuationMap("ja_JP"))
    }

    // the built-in table is an app asset, not on the JVM classpath: a user's file is what is read
    @Test
    fun theUsersTableIsTheMapRead() = runBlocking {
        val table = listOf(PunctuationMapEntry("(", "（", ""), PunctuationMapEntry(",", "，", ""))
        fcitx.putPunctuationTable(home, table)
        assertEquals("（", fcitx.punctuationMap(ZH_CN).first { it.key == "(" }.mapping)
    }

    // contract: the host's files as 0.3.2 and 0.4.0 wrote them (uat/fixtures/upgrade/*/README.md), read in place: the format
    // later builds keep, not the upgrade from those builds (that goes through 0.6.2). Each row its own install; first run
    // false as an upgrade's, else moving pinyin to the head would hide a group read in the wrong order
    @Test
    fun anUpgradedInstallsSettingsAreWhatItSet() = assertRows(listOf("v0.3.2", "v0.4.0"), { it }) { version ->
        runBlocking {
            fcitx.stop()
            home.deleteRecursively()
            AppPrefs.getInstance().internal.firstRun.setValue(false)
            untar(fixture("upgrade/$version/external.tar"), home)
            start()
            assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodNames.KEYBOARD, InputMethodCatalog.SHUANGPIN), fcitx.enabledIme().map { it.uniqueName })
            assertEquals("Medium", fcitx.imOptions(InputMethodCatalog.PINYIN)!![EngineSettings.MODEL_LEVEL])
            assertEquals("Medium", engine.settings.last().modelLevel.key)
            assertEquals("、", fcitx.punctuationMap(ZH_CN).first { it.key == "," }.mapping)
            fcitx.activateIme(InputMethodCatalog.PINYIN)
            fcitx.activate(1, "org.example")
            fcitx.focus(true)
            assertEquals("fcitx-chttrans-active", fcitx.statusArea().first { it.name == "chttrans" }.icon)
            assertEquals(setOf(InputMethodCatalog.PINYIN), EngineState.traditionalIms)
        }
    }

    private val said = HostLog<String>()

    private fun listen() {
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said.add(message); true }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A file the host cannot read this run: [notice] told, [shown] used instead, [edit]'s save leaves the file as it was; [after] checks more once it reads again. */
    private class Unreadable(
        val why: String,
        val file: () -> File,
        val text: String,
        val notice: Int,
        val shown: suspend KotlinHost.() -> Unit,
        val edit: suspend KotlinHost.() -> Unit = {},
        val after: suspend () -> Unit = {},
    )

    // a file there that cannot be read is not its defaults: never written over this run, the user told
    @Test
    @Uat("PR-42")
    fun anUnreadableFileIsNeverWrittenOverAndTheUserIsTold() {
        val probe = File(home, "probe").apply { parentFile!!.mkdirs(); writeText("") }
        assumeTrue("an unreadable file (not as root)", probe.setReadable(false) && !probe.canRead())
        probe.delete()
        unreadableRows()
    }

    private fun unreadableRows() = assertRows(
        listOf(
            Unreadable(
                "the config: its defaults shown, as nothing else can be; the next start that reads it has the user's settings back, said nothing",
                { config(EngineOptions.PATH) }, "PageSize=9\n", R.string.config_unreadable,
                shown = { assertEquals("7", imOptions(InputMethodCatalog.PINYIN)!!["PageSize"]) },
                edit = {
                    setImOptions(InputMethodCatalog.PINYIN, imOptions(InputMethodCatalog.PINYIN)!! + ("PageSize" to "8"))
                    reloadConfig()
                    save()
                },
                after = {
                    said.clear()
                    start()
                    idle()
                    assertEquals("9", fcitx.imOptions(InputMethodCatalog.PINYIN)!!["PageSize"])
                    assertEquals(emptyList<String>(), said.list())
                },
            ),
            Unreadable(
                "an edited punctuation table: the built-in one typed with (an app asset not on the JVM's classpath: none here), the file kept as it is",
                { File(home, "data/punctuation/punc.mb.zh_CN") }, ", 、\n", R.string.user_data_unreadable,
                shown = { assertEquals(emptyList<PunctuationMapEntry>(), punctuationMap("zh_CN")) },
            ),
        ),
        { it.why },
    ) { row ->
        runBlocking {
            fcitx.stop()
            home.deleteRecursively()
            val file = row.file().apply { parentFile!!.mkdirs() }
            file.writeText(row.text)
            try {
                check(file.setReadable(false))
                EngineNotices.forgetToldOnce()
                said.clear()
                listen()
                start()
                idle()
                assertEquals(listOf(appContext.getString(row.notice)), said.list())
                row.shown(fcitx)
                row.edit(fcitx)
                fcitx.stop()
                file.setReadable(true)
                assertEquals(row.text, file.readText())
                row.after()
            } finally {
                file.setReadable(true)
                EngineNotices.sayer = null
                // the next row and tearDown stop a started host
                if (!fcitx.isReady) start()
            }
        }
    }

    // not moved yet and the external storage away: read, nothing written, the user told
    @Test
    @Uat("PR-42")
    fun filesNotSettledAreReadButNotWrittenAndTheUserIsTold() = assertRows(
        listOf(
            NotSettled(
                "the config: the file there read, the page's save not written, nothing else (the first run's profile) made",
                notice = R.string.config_not_settled,
                prepare = { config(EngineOptions.PATH).apply { parentFile!!.mkdirs() }.writeText("PageSize=9\n") },
                side = { scripted ->
                    object : KotlinHost.EngineSide by scripted {
                        override fun config() = HostConfig(File(home, "config"), writable = false, ConfigHome.Problem.EXTERNAL_AWAY)
                    }
                },
                check = {
                    assertEquals("9", imOptions(InputMethodCatalog.PINYIN)!!["PageSize"])
                    setImOptions(InputMethodCatalog.PINYIN, imOptions(InputMethodCatalog.PINYIN)!! + ("PageSize" to "8"))
                    save()
                    assertEquals("PageSize=9\n", config(EngineOptions.PATH).readText())
                    assertEquals(listOf(EngineOptions.PATH), File(home, "config").walk().filter { it.isFile }.map { it.relativeTo(File(home, "config")).path }.toList())
                },
            ),
            NotSettled(
                "fcitx's data: the user's punctuation table there read",
                notice = R.string.user_data_unreadable,
                prepare = { File(home, "data/punctuation/punc.mb.zh_CN").apply { parentFile!!.mkdirs() }.writeText(", 、\n") },
                side = { scripted ->
                    object : KotlinHost.EngineSide by scripted {
                        override fun data() = HostConfig(File(home, "data"), writable = false, ConfigHome.Problem.EXTERNAL_AWAY)
                    }
                },
                check = { assertEquals(listOf(PunctuationMapEntry(",", "、", "")), punctuationMap("zh_CN")) },
            ),
        ),
        { it.why },
    ) { row ->
        runBlocking {
            fcitx.stop()
            home.deleteRecursively()
            row.prepare()
            EngineNotices.forgetToldOnce()
            said.clear()
            listen()
            try {
                start(row.side)
                idle()
                assertEquals(listOf(appContext.getString(row.notice)), said.list())
                row.check(fcitx)
            } finally {
                EngineNotices.sayer = null
            }
        }
    }

    // a failed stat (external storage's FUSE) reads as no file, so the defaults would be written over it: held, told.
    // [file]: where the row's host writes the config when not the home's
    private class StatCase(
        val why: String,
        val prepare: () -> Unit,
        val held: Boolean,
        val side: (ScriptedEngine) -> KotlinHost.EngineSide = { it },
        val undo: () -> Unit = {},
        val file: (() -> File)? = null,
    )

    @Test
    fun aConfigFileNotStattedIsHeld() {
        // these rows remove a directory's search permission, which root keeps: a skip there, not a pass that checked nothing
        val probe = File(home, "probe").apply { mkdirs() }
        assumeTrue("a directory not searchable (not as root)", probe.setExecutable(false) && !probe.canExecute())
        probe.setExecutable(true)
        probe.delete()
        statRows(permissionRows)
    }

    // the rows that change no permission bits run as root too
    @Test
    fun aConfigNotThereIsTheDefaultsAndAHomeNotMovedIsHeld() = statRows(otherRows)

    private val permissionRows = listOf(
        StatCase("listed by its directory, its stat failing (no search permission): held",
            prepare = { config(EngineOptions.PATH).apply { parentFile!!.mkdirs(); writeText("PageSize=9\n") }.parentFile!!.setExecutable(false) },
            held = true, undo = { config(EngineOptions.PATH).parentFile!!.setExecutable(true) }),
        StatCase("a directory on the way there but not listable: held",
            prepare = { config(EngineOptions.PATH).apply { parentFile!!.mkdirs(); writeText("PageSize=9\n") }.parentFile!!.let { it.setReadable(false); it.setExecutable(false) } },
            held = true, undo = { config(EngineOptions.PATH).parentFile!!.let { it.setReadable(true); it.setExecutable(true) } }),
    )

    private val otherRows = listOf(
        StatCase("not there: the defaults, the page's save written", prepare = {}, held = false),
        StatCase("the home not moved and not there (its storage away): held, never the defaults written",
            prepare = {}, held = true,
            side = { s -> object : KotlinHost.EngineSide by s { override fun config() = HostConfig(File(home, "away"), writable = true, ConfigHome.Problem.NOT_MOVED) } },
            // checked where this host writes, not the moved home's file a deleted home never has
            file = { File(home, "away/${EngineOptions.PATH}") }),
    )

    private fun statRows(rows: List<StatCase>) = assertRows(
        rows,
        { it.why },
    ) { row ->
        runBlocking {
            fcitx.stop()
            home.deleteRecursively()
            row.prepare()
            EngineNotices.forgetToldOnce()
            said.clear()
            listen()
            try {
                start(row.side)
                idle()
                assertEquals(if (row.held) listOf(appContext.getString(R.string.config_unreadable)) else emptyList<String>(), said.list())
                fcitx.setImOptions(InputMethodCatalog.PINYIN, fcitx.imOptions(InputMethodCatalog.PINYIN)!! + ("PageSize" to "8"))
                fcitx.save()
                fcitx.stop()
                row.undo()
                val f = row.file?.invoke() ?: config(EngineOptions.PATH)
                if (row.held) assertFalse(f.exists() && f.readText().contains("PageSize=8"))
                else assertTrue(f.readText().contains("PageSize=8"))
            } finally {
                row.undo()
                EngineNotices.sayer = null
                if (!fcitx.isReady) start()
            }
        }
    }

    // found cleanly gone at a reload: the defaults, safe to write again
    @Test
    fun aHeldConfigFoundGoneAtAReloadIsWrittenAgain() = runBlocking {
        fcitx.stop()
        home.deleteRecursively()
        val file = config(EngineOptions.PATH).apply { parentFile!!.mkdirs(); writeText("PageSize=9\n") }
        assumeTrue("an unreadable file (not as root)", file.setReadable(false) && !file.canRead())
        try {
            start()
            idle()
            file.delete()
            fcitx.reloadConfig()
            fcitx.setImOptions(InputMethodCatalog.PINYIN, fcitx.imOptions(InputMethodCatalog.PINYIN)!! + ("PageSize" to "8"))
            fcitx.save()
            assertTrue(file.readText().contains("PageSize=8"))
        } finally {
            file.setReadable(true)
        }
    }

    private class NotSettled(
        val why: String,
        val notice: Int,
        val prepare: () -> Unit,
        val side: (ScriptedEngine) -> KotlinHost.EngineSide,
        val check: suspend KotlinHost.() -> Unit,
    )

    // one event: the status sent once, with the new marks
    @Test
    fun reloadConfigSendsTheStatusOnce() = runBlocking {
        fcitx.activate(1, "org.example")
        fcitx.focus(true)
        fcitx.putPunctuationTable(home, listOf(PunctuationMapEntry(",", "、", "")))
        fcitx.isEmpty()
        idle()
        val before = events.list().count { it is HostEvent.StatusArea }
        fcitx.reloadConfig()
        fcitx.isEmpty()
        idle()
        val sent = events.list().filterIsInstance<HostEvent.StatusArea>().drop(before)
        assertEquals(1, sent.size)
        assertEquals(mapOf("," to "、"), sent.single().punctuation)
    }

    @Test
    fun reloadConfigReadsChttransEnabledStateAgain() = runBlocking {
        suspend fun trad() = fcitx.statusArea().first { it.name == "chttrans" }
        fcitx.activate(1, "org.example")
        fcitx.focus(true)
        assertEquals("fcitx-chttrans-inactive", trad().icon)
        // what the keyboard's 注音 hint reads
        assertEquals(emptySet<String>(), EngineState.traditionalIms)
        config(ModuleConfigs.CHTTRANS).apply { parentFile!!.mkdirs() }
            .writeText(ModuleConfigs.chttransText(null, setOf(InputMethodCatalog.PINYIN)))
        fcitx.reloadConfig()
        assertEquals("fcitx-chttrans-active", trad().icon)
        assertEquals(setOf(InputMethodCatalog.PINYIN), EngineState.traditionalIms)
        fcitx.activateAction(trad().id)
        assertEquals("fcitx-chttrans-inactive", trad().icon)
        assertEquals(emptySet<String>(), EngineState.traditionalIms)
    }

    @Test
    fun keysGoThroughTheHostAndATapCommitsTheCandidate() = runBlocking {
        fcitx.activate(1, "org.example")
        fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
        fcitx.focus(true)
        // as androidfrontend tells a focused context's input method
        assertEquals(InputMethodCatalog.PINYIN, fcitx.inputMethodEntryCached.uniqueName)
        events.clear()
        "nihao".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        val list = events.list().filterIsInstance<HostEvent.Candidates>().last()
        assertEquals(ScriptedEngine.WORDS, list.candidates.map { it.text })
        assertEquals("nihao", fcitx.inputPanelCached.preedit.toString())
        // kept for a remade view
        assertEquals(list, fcitx.candidatesCached)
        fcitx.select(0, null)
        assertEquals("你好", events.list().filterIsInstance<HostEvent.Commit>().single().text)
        // as the addon's show() of an empty snapshot clears it
        assertEquals("", fcitx.inputPanelCached.preedit.toString())
        assertEquals(0, events.list().filterIsInstance<HostEvent.Candidates>().last().candidates.size)
        assertEquals(0, fcitx.candidatesCached.candidates.size)
    }

    // a remembered word's 撤销 goes to the table it was remembered in, set aside if another input method is shown since
    private fun undoneAfter(switched: Boolean, then: (Action.Unremember) -> Unit) = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.WUBI))
        fcitx.activate(1, "org.example")
        fcitx.focus(true)
        fcitx.activateIme(InputMethodCatalog.WUBI)
        assertEquals(InputMethodCatalog.WUBI, fcitx.inputMethodEntryCached.uniqueName)
        fcitx.rememberWord(1, 0, "汪小明")
        if (switched) fcitx.activateIme(InputMethodCatalog.PINYIN)
        engine.payloads.clear()
        fcitx.unrememberWord("汪小明", "iije")
        then(Action.Unremember("汪小明", "iije"))
    }

    @Test
    fun theUndoOfAWordRememberedInTheTableShownGoesToIt() = undoneAfter(switched = false) { undo ->
        assertEquals(emptyList<Pair<String, Action?>>(), engine.asides.toList())
        assertEquals(undo, engine.payloads.last())
        assertEquals(InputMethodCatalog.WUBI, engine.ims.last())
    }

    @Test
    fun theUndoOfAWordRememberedBeforeASwitchGoesAsideToItsTable() = undoneAfter(switched = true) { undo ->
        assertEquals(listOf(InputMethodCatalog.WUBI to undo), engine.asides.toList())
        assertFalse("pinyin, shown, is not sent it", undo in engine.payloads)
    }

    @Test
    fun aPageSentDropsTheCachedListAndAListThePage() = runBlocking {
        fcitx.activate(1, "org.example")
        fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
        fcitx.focus(true)
        "ni".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        assertEquals(ScriptedEngine.WORDS, fcitx.candidatesCached.candidates.map { it.text })
        fcitx.setCandidatePagingMode(1)
        fcitx.sendKey('h', 0u, 0, false, 0)
        assertEquals(ScriptedEngine.WORDS, fcitx.pagedCached.candidates.map { it.text })
        assertEquals(HostEvent.Candidates.EMPTY, fcitx.candidatesCached)
        fcitx.setCandidatePagingMode(0)
        fcitx.sendKey('a', 0u, 0, false, 0)
        assertEquals(HostEvent.Paged.EMPTY, fcitx.pagedCached)
        assertEquals(ScriptedEngine.WORDS, fcitx.candidatesCached.candidates.map { it.text })
    }

    // a view remade after a restart before the new host speaks replays the caches: not the stopped one's
    @Test
    fun aStoppedHostLeavesNothingCachedToReplay() = runBlocking {
        fcitx.activate(1, "org.example")
        fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
        fcitx.focus(true)
        "ni".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        assertEquals(ScriptedEngine.WORDS, fcitx.candidatesCached.candidates.map { it.text })
        fcitx.setCandidatePagingMode(1)
        fcitx.sendKey('h', 0u, 0, false, 0)
        assertTrue(fcitx.inputPanelCached != HostEvent.InputPanel.EMPTY && fcitx.pagedCached != HostEvent.Paged.EMPTY)
        fcitx.stop()
        assertEquals(HostEvent.InputPanel.EMPTY, fcitx.inputPanelCached)
        assertEquals(HostEvent.Candidates.EMPTY, fcitx.candidatesCached)
        assertEquals(HostEvent.Paged.EMPTY, fcitx.pagedCached)
        assertTrue(fcitx.statusAreaCached.isEmpty())
        start()
    }

    // a restore flushes and stops the host, replaces the files and restarts (BackupFragment); a file the backup lacks reads as default
    @Test
    fun aRestoredBackupsSettingsAreWhatTheNextStartReads() = runBlocking {
        suspend fun pageSize() = fcitx.imOptions(InputMethodCatalog.PINYIN)!!["PageSize"]
        suspend fun setPageSize(size: String) = fcitx.setImOptions(InputMethodCatalog.PINYIN, fcitx.imOptions(InputMethodCatalog.PINYIN)!! + ("PageSize" to size))
        fun restore(vararg entries: Pair<String, String>) {
            fcitx.stop()
            val other = File(home.parentFile, "kotlin-host-restore").apply { deleteRecursively() }
            val dirs = dirsUnder(other, external = home, config = File(home, "config"))
            UserDataManager.import(backupZip(*entries), dirs).getOrThrow()
            other.deleteRecursively()
            start()
        }
        // set after the backup and not yet on disk: stop() writes it, the restore replaces it
        setPageSize("9")
        restore("external/config/${EngineOptions.PATH}" to "PageSize=5\n")
        assertEquals("5", pageSize())
        setPageSize("8")
        fcitx.save()
        restore("external/config/config" to "")
        assertEquals("7", pageSize())
    }

    // a 0.4 backup's 九键拼音 is dropped as an upgrade from 0.4 drops it; pinyin keeps the board chosen here
    @Test
    @Uat("PR-73")
    fun aZeroFourBackupsNineKeysPinyinIsDroppedFromTheList() = runBlocking {
        val keyboard = AppPrefs.getInstance().keyboard.pinyinKeyboard
        keyboard.setValue(PinyinKeyboard.Full)
        // 0.4's profile with 九键拼音 first, then 拼音 and English
        val zeroFourProfile = """
            [Groups/0]
            Name=Default
            Default Layout=us
            DefaultIM=${InputMethodCatalog.T9}

            [Groups/0/Items/0]
            Name=${InputMethodCatalog.T9}

            [Groups/0/Items/1]
            Name=${InputMethodCatalog.PINYIN}

            [Groups/0/Items/2]
            Name=${InputMethodCatalog.ENGLISH}

            [GroupOrder]
            0=Default

        """.trimIndent()
        fcitx.stop()
        val other = File(home.parentFile, "kotlin-host-restore").apply { deleteRecursively() }
        val dirs = dirsUnder(other, external = home, config = File(home, "config"))
        UserDataManager.import(backupZip("external/config/profile" to zeroFourProfile), dirs).getOrThrow()
        other.deleteRecursively()
        start()
        idle()
        assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
        assertEquals(PinyinKeyboard.Full, keyboard.getValue())
    }

    // the theme packs' boards listed and chosen at the start (ThemeLayoutsToList): said once seen
    @Test
    @Uat("ST-175")
    fun theThemeLayoutsChosenAreToldOnce() = runBlocking {
        val unseen = AppPrefs.getInstance().internal.themeLayoutsUnseen
        unseen.setValue("主题「薄荷」带的17 键已放进 外观 → 布局，拼音正在用")
        fcitx.stop()
        EngineNotices.forgetToldOnce()
        said.clear()
        listen()
        start()
        idle()
        assertEquals(listOf("主题「薄荷」带的17 键已放进 外观 → 布局，拼音正在用"), said.list())
        assertEquals("", unseen.getValue())
    }

    // reaches the uncaught handler (the process ends), not the executor's silence
    @Test
    fun aStartupThatThrowsReachesTheUncaughtHandler() {
        fcitx.stop()
        val caught = CompletableFuture<Throwable>()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        // fcitx-main has no handler of its own: its group passes the throw to the default
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught.complete(e) }
        try {
            val failing = object : KotlinHost.EngineSide by ScriptedEngine(home) {
                override fun start() = error("no engine")
            }
            val dead = KotlinHost(appContext, failing)
            try {
                dead.start()
                assertEquals("no engine", caught.get(WAIT_MILLIS, TimeUnit.MILLISECONDS).message)
            } finally {
                // left STARTING, which stop() skips: its thread and clipboard listener would outlive the test
                dead.tearDownFailedStart()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
        start() // tearDown stops this one
    }

    @Test
    fun aStopWhoseCloseThrowsStillLetsTheHostStartAgain() {
        fcitx.stop()
        val closing = object : KotlinHost.EngineSide by ScriptedEngine(home) {
            override fun close() = error("close failed")
        }
        fcitx = KotlinHost(appContext, closing)
        repeat(2) {
            fcitx.start()
            until("host start", timeoutMs = WAIT_MILLIS) { fcitx.isReady }
            assertThrows(ExecutionException::class.java) { fcitx.stop() }
            assertEquals(HostLifecycle.State.STOPPED, fcitx.lifecycle.currentState)
        }
        start() // tearDown stops this one
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val ZH_CN = "zh_CN"
    }

    @Test
    fun wordBoundariesGoATextAtATimeAndStopWhenTheCallerGivesUp() {
        val texts = listOf("今天", "天气", "很好")
        val split = { t: List<String> -> t.associateWith { intArrayOf(0, it.length) } }
        val all = runBlocking { aTextAtATime(texts, split) }
        assertEquals(split(texts).mapValues { it.value.toList() }, all.mapValues { it.value.toList() })
        // the caller gone after the first: the rest are not split
        val asked = mutableListOf<String>()
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            job = launch {
                aTextAtATime(texts) {
                    asked += it
                    job.cancel()
                    split(it)
                }
            }
            job.join()
        }
        assertEquals(listOf("今天"), asked)
    }
}
