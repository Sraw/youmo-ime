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
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.UserDataOrigin
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
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
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * [KotlinHost] behind [HostApi], as the daemon starts it: its calls on the host thread, the settings pages' round trip through
 * androidengine.conf, the module files read again, and keys through the real host to a scripted
 * engine (the app's data is not on the JVM's classpath).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KotlinHostTest {

    private lateinit var home: File
    private lateinit var engine: ScriptedEngine
    private lateinit var fcitx: KotlinHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events: MutableList<HostEvent> = Collections.synchronizedList(ArrayList())

    private fun config(path: String) = File(home, "config/$path")

    @Before
    fun setUp() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        // a fresh install each case: AppPrefs outlives a case's sandbox, with the last one's first run done
        AppPrefs.getInstance().internal.firstRun.setValue(true)
        start()
    }

    private fun start(side: (ScriptedEngine) -> KotlinHost.EngineSide = { it }) {
        engine = ScriptedEngine(home)
        // the first run is the case's setUp's (or the case's own) to set: a restart is none
        fcitx = startedHost(side(engine), firstRun = false) { host -> scope.launch { host.eventFlow.collect { events += it } } }
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
        // the singleton's prefs outlive the test: a notice one left unseen is not the next one's
        AppPrefs.getInstance().internal.nineKeysUnseen.setValue(false)
        AppPrefs.getInstance().keyboard.pinyinKeyboard.run { setValue(defaultValue) }
        AppPrefs.getInstance().internal.nineKeysMerged.run { setValue(defaultValue) }
        // published by the host, read by the keyboard window: not the next test's
        EngineState.traditionalIms = null
        EngineNotices.sayer = null
        directBoot(false)
    }

    @Test
    fun readyIsTheFirstEventAndPinyinLeadsTheGroupOnTheFirstRun() = runBlocking {
        assertTrue(events.first() is HostEvent.Ready)
        assertEquals(InputMethodCatalog.PINYIN, fcitx.enabledIme().first().uniqueName)
        // UAT decision 6: no options file yet, so the engine is told the host options' defaults, which the
        // engine replay's UatEngines.BASELINE reads too (but for 双拼's scheme)
        assertEquals(EngineOptions().toSettings(), engine.settings.last())
    }

    // a settings page reads every option of its input method and saves what it shows
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
        fcitx.setPunctuationMap("ja_JP", listOf(PunctuationMapEntry(",", "、", "")))
        assertEquals(emptyList<PunctuationMapEntry>(), fcitx.punctuationMap("ja_JP"))
    }

    // the keyboard's punctuation popups and the punctuation page read it so (PunctuationManager.load);
    // the built-in table is an app asset, not on the JVM's classpath: the page's save gives one
    @Test
    fun thePunctuationMapSavedReadsBack() = runBlocking {
        val table = listOf(PunctuationMapEntry("(", "（", ""), PunctuationMapEntry(",", "，", ""))
        fcitx.setPunctuationMap(ZH_CN, table)
        assertEquals("（", fcitx.punctuationMap(ZH_CN).first { it.key == "(" }.mapping)
    }

    // contract: the files of installs upgraded from 0.3.2 and 0.4.0 as those builds wrote them (fcitx
    // up to 0.3.2, the Kotlin host's same INI in 0.4.0, the last build with config/ and data/ on external
    // storage; uat/fixtures/upgrade/v0.3.2/README.md steps 2, 3, 6, 9, v0.4.0's 2, 3, 6, 9): read in place,
    // as the host reads them once ConfigHome has moved them (that move is ConfigHomeTest's). Each row is
    // its own install; first run stays false as an upgrade's prefs have it, else the first run's move of
    // pinyin to the group's head would hide a group read in the wrong order
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

    private val said: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun listen() {
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; true }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * A file the host keeps that it cannot read this run: [file] holds [text],
     * the user is told [notice], [shown] is what the run has instead, and what [edit] saves from it
     * leaves the file as it was; [after] checks anything more once the file reads again.
     */
    private class Unreadable(
        val why: String,
        val file: () -> File,
        val text: String,
        val notice: Int,
        val shown: suspend KotlinHost.() -> Unit,
        val edit: suspend KotlinHost.() -> Unit,
        val after: suspend () -> Unit = {},
    )

    // A4: a file there that cannot be read is not its defaults: it is never written over this run, and the user is told
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
                    assertEquals(emptyList<String>(), said)
                },
            ),
            Unreadable(
                "an edited punctuation table: the built-in one typed with (an app asset not on the JVM's classpath: none here), the edit page's save not written over it",
                { File(home, "data/punctuation/punc.mb.zh_CN") }, ", 、\n", R.string.user_data_unreadable,
                shown = { assertEquals(emptyList<PunctuationMapEntry>(), punctuationMap("zh_CN")) },
                edit = { assertTrue(runCatching { setPunctuationMap("zh_CN", listOf(PunctuationMapEntry("(", "（", ""))) }.exceptionOrNull() is IOException) },
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
                assertEquals(listOf(appContext.getString(row.notice)), said)
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

    // A4: the config or fcitx's data not moved yet and the external storage away
    // (ConfigHome.Problem.EXTERNAL_AWAY): read, nothing written, the user told
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
                "fcitx's data: the punctuation table saved is not written",
                notice = R.string.user_data_unreadable,
                prepare = {},
                side = { scripted ->
                    object : KotlinHost.EngineSide by scripted {
                        override fun data() = HostConfig(File(home, "data"), writable = false, ConfigHome.Problem.EXTERNAL_AWAY)
                    }
                },
                check = {
                    assertTrue(runCatching { setPunctuationMap("zh_CN", listOf(PunctuationMapEntry(",", "、", ""))) }.exceptionOrNull() is IOException)
                    // refused, not used either
                    assertEquals(emptyList<PunctuationMapEntry>(), punctuationMap("zh_CN"))
                    assertFalse(File(home, "data/punctuation").exists())
                },
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
                assertEquals(listOf(appContext.getString(row.notice)), said)
                row.check(fcitx)
            } finally {
                EngineNotices.sayer = null
            }
        }
    }

    // a stat that fails (the external storage's FUSE) reads as no file: the defaults loaded and written
    // over it at the next change. Held instead, the user told; a file truly not there is the defaults, written
    // [file]: where the row's host writes the config when not the home's, checked there
    private class StatCase(
        val why: String,
        val prepare: () -> Unit,
        val held: Boolean,
        val side: (ScriptedEngine) -> KotlinHost.EngineSide = { it },
        val undo: () -> Unit = {},
        val file: (() -> File)? = null,
    )

    // F4897: a config whose stat failed was taken for no file and the defaults written over it
    @Test
    fun aConfigFileNotStattedIsHeld() {
        // these rows take a directory's search permission away, which root keeps: checked once
        // here, as a skip (F4987), not a row that passes having checked nothing
        val probe = File(home, "probe").apply { mkdirs() }
        assumeTrue("a directory not searchable (not as root)", probe.setExecutable(false) && !probe.canExecute())
        probe.setExecutable(true)
        probe.delete()
        statRows(permissionRows)
    }

    // the rows that change no permission bits run as root too (F5004)
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
            // F4976: checked where this host writes, not the moved home's file a deleted home never has
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
                assertEquals(if (row.held) listOf(appContext.getString(R.string.config_unreadable)) else emptyList<String>(), said)
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

    // held through a file that did not read, then found cleanly gone at a reload: the defaults, safe to write again
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

    @Test
    fun reloadConfigReadsChttransEnabledStateAgain() = runBlocking {
        suspend fun trad() = fcitx.statusArea().first { it.name == "chttrans" }
        fcitx.activate(1, "org.example")
        fcitx.focus(true)
        assertEquals("fcitx-chttrans-inactive", trad().icon)
        // what the keyboard's 注音 hint reads: the list as started, read again, toggled
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
        // told as the frontend tells a focused context's input method (androidfrontend.cpp:376-379)
        assertEquals(InputMethodCatalog.PINYIN, fcitx.inputMethodEntryCached.uniqueName)
        events.clear()
        "nihao".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        val list = events.filterIsInstance<HostEvent.Candidates>().last()
        assertEquals(ScriptedEngine.WORDS, list.candidates.map { it.text })
        assertEquals("nihao", fcitx.inputPanelCached.preedit.toString())
        // kept for a view made anew (A2): the list as the bar last had it
        assertEquals(list, fcitx.candidatesCached)
        fcitx.select(0, null)
        assertEquals("你好", events.filterIsInstance<HostEvent.Commit>().single().text)
        // the panel cleared after the commit, as the addon's show() of an empty snapshot clears it
        assertEquals("", fcitx.inputPanelCached.preedit.toString())
        assertEquals(0, events.filterIsInstance<HostEvent.Candidates>().last().candidates.size)
        assertEquals(0, fcitx.candidatesCached.candidates.size)
    }

    // the hardware keyboard's page in place of the bulk list: the list the bar had is no longer the engine's
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

    // A2 review: a view made anew after a restart (the app's language changed) before the new host
    // speaks replays the caches; the stopped one's code row and candidates are not shown again
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

    // PR-10: a restore stops the host (its pending writes flushed), replaces the settings files and
    // starts the app again (BackupFragment.kt:71-82); the host started then reads the backup's, a file
    // the backup has not (pruned, UserDataManager.pruneAbsent) at its default
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
        // set on the phone after the backup was made and not yet on disk: stop() writes it, the restore replaces it
        setPageSize("9")
        restore("external/config/${EngineOptions.PATH}" to "PageSize=5\n")
        assertEquals("5", pageSize())
        setPageSize("8")
        fcitx.save()
        restore("external/config/config" to "")
        assertEquals("7", pageSize())
    }

    // 0.4's profile with 拼音 and 九键拼音 both on, 九键拼音 the default (NineKeysMergeTest has every case)
    private val zeroFourProfile = """
        [Groups/0]
        Name=Default
        Default Layout=us
        DefaultIM=${InputMethodCatalog.PINYIN}

        [Groups/0/Items/0]
        Name=${InputMethodCatalog.T9}

        [Groups/0/Items/1]
        Name=${InputMethodCatalog.PINYIN}

        [Groups/0/Items/2]
        Name=${InputMethodCatalog.ENGLISH}

        [GroupOrder]
        0=Default

    """.trimIndent()

    // SET-13: an upgrade from 0.4 with both pinyins on: one pinyin on the nine keys, the profile written
    // without the old name, the user told once; the next start has nothing to merge, says nothing
    @Test
    @Uat("SET-13")
    fun zeroFoursNineKeysPinyinBecomesPinyinsKeyboardAndTheUserIsToldOnce() = runBlocking {
        val keyboard = AppPrefs.getInstance().keyboard.pinyinKeyboard
        val merged = AppPrefs.getInstance().internal.nineKeysMerged
        zeroFourInstalled()
        val steady = Collections.synchronizedList(ArrayList<Boolean>())
        EngineNotices.sayer = EngineNotices.Sayer { message, isSteady -> said += message; steady += isSteady; true }
        start()
        idle()
        assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
        assertEquals(PinyinKeyboard.NineKeys, keyboard.getValue())
        assertTrue(merged.getValue())
        assertEquals(listOf(appContext.getString(R.string.nine_keys_merged)), said)
        // news, not a failure: the plain notice
        assertEquals(listOf(false), steady)
        fcitx.save()
        assertFalse(config(PROFILE).readText(), InputMethodCatalog.T9 in config(PROFILE).readText())
        said.clear()
        restart()
        idle()
        assertEquals(emptyList<String>(), said)
        assertEquals(PinyinKeyboard.NineKeys, keyboard.getValue())
        assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
    }

    // review 2: the merge's notice queued as the host starts, the process ended before any keyboard showed
    // (the user opened settings first, then swiped the app away): told at the next start, once it is seen
    @Test
    fun theMergesNoticeNotSeenIsToldAtTheNextStart() = runBlocking {
        val keyboard = AppPrefs.getInstance().keyboard.pinyinKeyboard
        val merged = AppPrefs.getInstance().internal.nineKeysMerged
        val unseen = AppPrefs.getInstance().internal.nineKeysUnseen
        val notice = appContext.getString(R.string.nine_keys_merged)
        zeroFourInstalled()
        // no keyboard: said nowhere
        EngineNotices.sayer = EngineNotices.Sayer { _, _ -> false }
        start()
        idle()
        assertTrue(merged.getValue())
        assertTrue(unseen.getValue())
        // the process gone: what waited in memory with it
        fcitx.stop()
        EngineNotices.forgetToldOnce()
        listen()
        start()
        idle()
        assertEquals(listOf(notice), said)
        assertFalse(unseen.getValue())
        said.clear()
        restart()
        idle()
        assertEquals(emptyList<String>(), said)
    }

    // a 0.4 install's files with both pinyins on, no merge done yet; the host stopped, listened to
    private fun zeroFourInstalled() {
        fcitx.stop()
        home.deleteRecursively()
        AppPrefs.getInstance().internal.firstRun.setValue(false)
        AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Full)
        AppPrefs.getInstance().internal.nineKeysMerged.setValue(false)
        config(PROFILE).apply { parentFile!!.mkdirs() }.writeText(zeroFourProfile)
        EngineNotices.forgetToldOnce()
        said.clear()
        listen()
    }

    // the profile not written back (the config not writable): merged again as each start reads it, yet
    // pinyin's keyboard left as the user set it since and nothing told again (Internal.nineKeysMerged)
    @Test
    fun aMergeNotWrittenBackIsNotDoneOverTheUsersKeyboard() = runBlocking {
        val keyboard = AppPrefs.getInstance().keyboard.pinyinKeyboard
        val notice = appContext.getString(R.string.nine_keys_merged)
        val unwritable = { s: ScriptedEngine ->
            object : KotlinHost.EngineSide by s {
                override fun config() = HostConfig(File(home, "config"), writable = false, ConfigHome.Problem.EXTERNAL_AWAY)
            }
        }
        zeroFourInstalled()
        start(unwritable)
        idle()
        assertEquals(PinyinKeyboard.NineKeys, keyboard.getValue())
        assertEquals(1, said.count { it == notice })
        fcitx.save()
        assertTrue(InputMethodCatalog.T9 in config(PROFILE).readText())
        keyboard.setValue(PinyinKeyboard.Full)
        said.clear()
        fcitx.stop()
        start(unwritable)
        idle()
        assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
        assertEquals(PinyinKeyboard.Full, keyboard.getValue())
        assertEquals(0, said.count { it == notice })
    }

    // before the first unlock the config is none of the user's (HostConfigDir: no folder): nothing merged,
    // set, written or told; the start after the unlock merges
    @Test
    fun inDirectBootNothingIsMergedTillTheUnlock() = runBlocking {
        val keyboard = AppPrefs.getInstance().keyboard.pinyinKeyboard
        val merged = AppPrefs.getInstance().internal.nineKeysMerged
        zeroFourInstalled()
        directBoot(true)
        start { s -> object : KotlinHost.EngineSide by s { override fun config() = HostConfig(null, writable = false) } }
        idle()
        assertEquals(PinyinKeyboard.Full, keyboard.getValue())
        assertFalse(merged.getValue())
        assertFalse(appContext.getString(R.string.nine_keys_merged) in said)
        assertEquals(zeroFourProfile, config(PROFILE).readText())
        directBoot(false)
        restart()
        idle()
        assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
        assertEquals(PinyinKeyboard.NineKeys, keyboard.getValue())
        assertTrue(merged.getValue())
    }

    // PR-47: a 0.4 backup restored on 0.5 (the restore starts the app again, BackupFragment): its profile
    // read as the upgrade reads it, pinyin on the nine keys again though the 26 keys were chosen since
    @Test
    @Uat("PR-47")
    fun aZeroFourBackupRestoredMergesItsNineKeysPinyinAgain() = runBlocking {
        val prefs = AppPrefs.getInstance()
        val keyboard = prefs.keyboard.pinyinKeyboard
        val raw = rawPrefs()
        // merged on this phone before, the 26 keys chosen since
        keyboard.setValue(PinyinKeyboard.Full)
        prefs.internal.nineKeysMerged.setValue(true)
        fcitx.stop()
        val other = File(home.parentFile, "kotlin-host-restore").apply { deleteRecursively() }
        val zeroFourPrefs = """<map><string name="pinyin_keyboard">Full</string><boolean name="english_on_nine_keys" value="true" /></map>"""
        val dirs = dirsUnder(other, external = home, config = File(home, "config"))
        val prefsFile = UserDataOrigin.preferencesFile(BuildConfig.APPLICATION_ID)
        UserDataManager.import(backupZip("external/config/$PROFILE" to zeroFourProfile, "shared_prefs/$prefsFile" to zeroFourPrefs), dirs).getOrThrow()
        assertEquals(zeroFourPrefs, File(dirs.sharedPrefs, prefsFile).readText())
        other.deleteRecursively()
        // the restore starts the app again, which reads those settings (a JVM test's prefs are in memory: as
        // the file has them) and drops the retired ones (FcitxApplication), then the host merges
        raw.edit(commit = true) {
            remove(prefs.internal.nineKeysMerged.key)
            putString(keyboard.key, "Full")
            putBoolean("english_on_nine_keys", true)
        }
        prefs.dropRetired()
        assertFalse(raw.contains("english_on_nine_keys"))
        start()
        idle()
        assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
        assertEquals(PinyinKeyboard.NineKeys, keyboard.getValue())
        assertTrue(prefs.internal.nineKeysMerged.getValue())
    }

    // a startup that throws reaches the thread's uncaught handler (the process ends), not the executor's silence (F3803)
    @Test
    fun aStartupThatThrowsReachesTheUncaughtHandler() {
        fcitx.stop()
        val caught = CompletableFuture<Throwable>()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        // fcitx-main has no handler of its own: its group passes the throw to the default one
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
                // left STARTING, which stop() skips: its thread and clipboard listener would outlive
                // the test and be seen by the next ones (process-wide counts, F4296)
                dead.tearDownFailedStart()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
        start() // tearDown stops this one
    }

    // a stop whose last step throws still ends STOPPED: the host starts again (F3799)
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

        // KotlinHost's profile, under the config home
        const val PROFILE = "profile"
    }

    @Test
    fun wordBoundariesGoATextAtATimeAndStopWhenTheCallerGivesUp() {
        val texts = listOf("今天", "天气", "很好")
        val split = { t: List<String> -> t.associateWith { intArrayOf(0, it.length) } }
        // the same as all at once
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
