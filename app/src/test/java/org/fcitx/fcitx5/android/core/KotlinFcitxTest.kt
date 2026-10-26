/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [KotlinFcitx] behind [FcitxAPI], as the daemon starts it: its calls on the host thread, the settings pages' round trip through
 * androidengine.conf, the module files read again, and keys through the real host to a scripted
 * engine (the app's data is not on the JVM's classpath).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KotlinFcitxTest {

    private lateinit var home: File
    private lateinit var engine: ScriptedEngine
    private lateinit var fcitx: KotlinFcitx
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events: MutableList<FcitxEvent<*>> = Collections.synchronizedList(ArrayList())

    private fun config(path: String) = File(home, "config/$path")

    @Before
    fun setUp() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        // a fresh install each case: AppPrefs outlives a case's sandbox, with the last one's first run done
        AppPrefs.getInstance().internal.firstRun.setValue(true)
        start()
    }

    private fun start() {
        engine = ScriptedEngine(home)
        fcitx = KotlinFcitx(appContext, engine)
        scope.launch { fcitx.eventFlow.collect { events += it } }
        fcitx.start()
        val until = System.currentTimeMillis() + WAIT_MILLIS
        while (!fcitx.isReady) {
            check(System.currentTimeMillis() < until) { "the host did not start" }
            Thread.sleep(5)
        }
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
    }

    @Test
    fun readyIsTheFirstEventAndPinyinLeadsTheGroupOnTheFirstRun() = runBlocking {
        assertTrue(events.first() is FcitxEvent.ReadyEvent)
        assertEquals(Engines.PINYIN, fcitx.enabledIme().first().uniqueName)
    }

    // the settings page reads "cfg" and "desc" both (FcitxPreferenceFragment.kt:105) and saves what it shows
    @Test
    fun anInputMethodsPageSavesReadsBackAndSurvivesARestart() = runBlocking {
        val page = fcitx.getImConfig(Engines.PINYIN)
        assertNotNull(page.findByName("desc"))
        val cfg = page["cfg"]
        assertEquals("7", cfg["PageSize"].value)
        val changed = cfg.copy(subItems = cfg.subItems!!.map { if (it.name == "PageSize") it.copy(value = "9") else it }.toTypedArray())
        // the page's "cfg" node itself, as FcitxPreferenceFragment.save passes it
        fcitx.setImConfig(Engines.PINYIN, changed)
        assertEquals("9", fcitx.getImConfig(Engines.PINYIN)["cfg"]["PageSize"].value)
        assertTrue("the engines told", engine.settings.last().contains("PageSize=9"))
        fcitx.save()
        assertTrue(config(EngineOptions.PATH).readText().lines().contains("PageSize=9"))
        restart()
        assertEquals("9", fcitx.getImConfig(Engines.PINYIN)["cfg"]["PageSize"].value)
    }

    @Test
    fun configsWithNoPageAreEmpty() = assertRows(
        listOf<Pair<String, suspend () -> RawConfig>>(
            "an input method with no page" to { fcitx.getImConfig("keyboard-xx") },
            // fcitx's global options are not read on this host, and no page of the app asks for them
            "the global config" to { fcitx.getGlobalConfig() },
        ),
        { it.first },
    ) { (_, read) -> assertEquals(null, runBlocking { read() }.findByName("cfg")) }

    // the keyboard's punctuation popups and the punctuation page read it so (PunctuationManager.load);
    // the built-in table is an app asset, not on the JVM's classpath: the page's save gives one
    @Test
    fun thePunctuationMapSavedReadsBackAsThePagesParseIt() = runBlocking {
        val table = listOf(PunctuationMapEntry("(", "（", ""), PunctuationMapEntry(",", "，", ""))
        fcitx.savePunctuationConfig(config = RawConfig(arrayOf(RawConfig(PunctuationManager.ENTRIES,
            table.mapIndexed { i, it -> it.toRawConfig(i) }.toTypedArray()))))
        val read = PunctuationManager.parseRawConfig(fcitx.getPunctuationConfig(ZH_CN))
        assertEquals("（", read.first { it.key == "(" }.mapping)
    }

    @Test
    fun reloadConfigReadsChttransEnabledStateAgain() = runBlocking {
        suspend fun trad() = fcitx.statusArea().first { it.name == "chttrans" }
        fcitx.activate(1, "org.example")
        fcitx.focus(true)
        assertEquals("fcitx-chttrans-inactive", trad().icon)
        config(ModuleConfigs.CHTTRANS).apply { parentFile!!.mkdirs() }
            .writeText(ModuleConfigs.chttransText(null, setOf(Engines.PINYIN)))
        fcitx.reloadConfig()
        assertEquals("fcitx-chttrans-active", trad().icon)
    }

    @Test
    fun keysGoThroughTheHostAndATapCommitsTheCandidate() = runBlocking {
        fcitx.activate(1, "org.example")
        fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
        fcitx.focus(true)
        // told as the frontend tells a focused context's input method (androidfrontend.cpp:376-379)
        assertEquals(Engines.PINYIN, fcitx.inputMethodEntryCached.uniqueName)
        events.clear()
        "nihao".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        val list = events.filterIsInstance<FcitxEvent.CandidateListEvent>().last()
        assertEquals(ScriptedEngine.WORDS, list.data.candidates.map { it.text })
        assertEquals("nihao", fcitx.inputPanelCached.preedit.toString())
        fcitx.select(0, null)
        assertEquals("你好", events.filterIsInstance<FcitxEvent.CommitStringEvent>().single().data.text)
        // the panel cleared after the commit, as the addon's show() of an empty snapshot clears it
        assertEquals("", fcitx.inputPanelCached.preedit.toString())
        assertEquals(0, events.filterIsInstance<FcitxEvent.CandidateListEvent>().last().data.candidates.size)
    }

    // PR-10: a restore stops the host (its pending writes flushed), replaces the settings files and
    // starts the app again (BackupFragment.kt:71-82); the host started then reads the backup's, a file
    // the backup has not (pruned, UserDataManager.pruneAbsent) at its default
    @Test
    fun aRestoredBackupsSettingsAreWhatTheNextStartReads() = runBlocking {
        suspend fun pageSize() = fcitx.getImConfig(Engines.PINYIN)["cfg"]["PageSize"].value
        suspend fun setPageSize(size: String) {
            val cfg = fcitx.getImConfig(Engines.PINYIN)["cfg"]
            fcitx.setImConfig(Engines.PINYIN, cfg.copy(subItems = cfg.subItems!!.map {
                if (it.name == "PageSize") it.copy(value = size) else it
            }.toTypedArray()))
        }
        fun restore(vararg entries: Pair<String, String>) {
            fcitx.stop()
            val other = File(home.parentFile, "kotlin-host-restore").apply { deleteRecursively() }
            val dirs = UserDataManager.Dirs(File(other, "shared_prefs"), File(other, "databases"), home,
                File(other, "recently_used"), File(other, "engine"))
            UserDataManager.import(backup(*entries), dirs).getOrThrow()
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

    private fun backup(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write("""{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":1,"versionName":"1","exportTime":0}""".encodeToByteArray())
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray().inputStream()

    // a startup that throws reaches the thread's uncaught handler (the process ends), not the executor's silence (F3803)
    @Test
    fun aStartupThatThrowsReachesTheUncaughtHandler() {
        fcitx.stop()
        val caught = CompletableFuture<Throwable>()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        // fcitx-main has no handler of its own: its group passes the throw to the default one
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught.complete(e) }
        try {
            val failing = object : KotlinFcitx.EngineSide by ScriptedEngine(home) {
                override fun start() = error("no engine")
            }
            val dead = KotlinFcitx(appContext, failing)
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
        val closing = object : KotlinFcitx.EngineSide by ScriptedEngine(home) {
            override fun close() = error("close failed")
        }
        fcitx = KotlinFcitx(appContext, closing)
        repeat(2) {
            fcitx.start()
            val until = System.currentTimeMillis() + WAIT_MILLIS
            while (!fcitx.isReady) {
                check(System.currentTimeMillis() < until) { "the host did not start" }
                Thread.sleep(5)
            }
            assertThrows(ExecutionException::class.java) { fcitx.stop() }
            assertEquals(FcitxLifecycle.State.STOPPED, fcitx.lifecycle.currentState)
        }
        start() // tearDown stops this one
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val ZH_CN = "zh_CN"
    }
}
