/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Looper
import androidx.core.content.edit
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.UserDataOrigin
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.SchemeMigration
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** The 双拼 scheme [KotlinHost] reads from androidengine.conf at each start (SchemeMigration has the rules). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KotlinHostSchemeTest {

    private lateinit var home: File
    private lateinit var engine: ScriptedEngine
    private lateinit var fcitx: KotlinHost
    private val said: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun config(path: String) = File(home, "config/$path")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val internal get() = AppPrefs.getInstance().internal

    @Before
    fun setUp() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        internal.firstRun.setValue(true)
        start()
    }

    private fun start(side: (ScriptedEngine) -> KotlinHost.EngineSide = { it }) {
        engine = ScriptedEngine(home)
        fcitx = startedHost(side(engine), firstRun = false)
    }

    private fun restart() {
        fcitx.stop()
        start()
    }

    @After
    fun tearDown() {
        fcitx.stop()
        home.deleteRecursively()
        EngineNotices.sayer = null
        internal.schemeSettled.setValue(true)
        internal.schemeRetiredTold.setValue(false)
        internal.schemeRetiredUnseen.setValue("")
        directBoot(false)
    }

    // before the first unlock: the config none of the user's, nothing written
    private val locked = { s: ScriptedEngine -> object : KotlinHost.EngineSide by s { override fun config() = HostConfig(null, writable = false) } }

    private fun conf() = config(EngineOptions.PATH).takeIf { it.exists() }?.readText()

    /** [settled] false: an install from before the migration. */
    private fun schemeRead(
        conf: String?,
        firstRun: Boolean,
        settled: Boolean = false,
        side: (ScriptedEngine) -> KotlinHost.EngineSide = { it },
        shown: Boolean = true,
    ): Read {
        fcitx.stop()
        home.deleteRecursively()
        internal.firstRun.setValue(firstRun)
        internal.schemeSettled.setValue(settled)
        conf?.let { config(EngineOptions.PATH).apply { parentFile!!.mkdirs() }.writeText(it) }
        val told = Collections.synchronizedList(ArrayList<Told>())
        EngineNotices.sayer = EngineNotices.Sayer { message, steady ->
            if (shown) told += Told(message, steady)
            shown
        }
        start(side)
        idle()
        return Read(engine.settings.last().shuangpin, told.toList())
    }

    private data class Read(val scheme: String, val told: List<Told> = emptyList())

    private data class Told(val message: String, val steady: Boolean)

    private fun dropped(name: String) = appContext.getString(R.string.shuangpin_scheme_retired, ShuangpinNames.of(appContext, name))

    // settled, so a later start is no upgrade
    @Test
    @Uat("SP-27")
    fun aNewInstallsShuangpinIsXiaohe() {
        assertEquals(Read(EngineSettings.XIAOHE), schemeRead(null, firstRun = true))
        assertNull(conf())
        assertTrue(internal.schemeSettled.getValue())
        restart()
        assertEquals(EngineSettings.XIAOHE, engine.settings.last().shuangpin)
        assertNull(conf())
        // a config with some other option saved but no scheme
        assertEquals(Read(EngineSettings.XIAOHE), schemeRead("PageSize=9\n", firstRun = false, settled = true))
        assertEquals("PageSize=9\n", conf())
    }

    // none written was the old default 自然码: pinned so, explicitly; one stored is left as it is
    @Test
    @Uat("SET-14")
    fun anUpgradeKeepsItsScheme() {
        assertRows(listOf(null, "PageSize=9\n", "# ShuangpinProfile=Ziranma\n"), { "$it" }) { conf ->
            assertEquals(Read(EngineSettings.ZIRANMA), schemeRead(conf, firstRun = false))
            // on the writer's thread
            until("settled") { internal.schemeSettled.getValue() }
            assertTrue(conf()!!.lines().contains("ShuangpinProfile=Ziranma"))
            restart()
            assertEquals(EngineSettings.ZIRANMA, engine.settings.last().shuangpin)
        }
        assertRows(
            listOf(
                "ShuangpinProfile=Ziranma\n" to EngineSettings.ZIRANMA,
                "ShuangpinProfile=MS\n" to "MS",
                "ShuangpinProfile=Xiaohe\n" to EngineSettings.XIAOHE,
            ),
            { it.first },
        ) { (conf, scheme) ->
            assertEquals(Read(scheme), schemeRead(conf, firstRun = false))
            assertEquals(conf, conf())
        }
    }

    // the start after the unlock settles it: 自然码 for an upgrade, 小鹤 for a new install
    @Test
    fun directBootSettlesNothing() {
        assertRows(listOf(false to EngineSettings.ZIRANMA, true to EngineSettings.XIAOHE), { "first run ${it.first}" }) { (firstRun, after) ->
            directBoot(true)
            assertEquals(Read(EngineSettings.XIAOHE), schemeRead(null, firstRun, side = locked))
            assertFalse(internal.schemeSettled.getValue())
            directBoot(false)
            // restarted at the unlock, with the user's prefs
            fcitx.stop()
            internal.firstRun.setValue(firstRun)
            start()
            idle()
            assertEquals(after, engine.settings.last().shuangpin)
            // on the writer's thread: settled once on disk, not by the idle above
            until("settled") { internal.schemeSettled.getValue() }
        }
    }

    // not writable, held unread, a failed write: 自然码 for this start, not settled, so the next start pins again
    @Test
    fun aPinNotOnDiskIsMadeAgainAtTheNextStart() {
        val file = config(EngineOptions.PATH)
        val unwritable = { s: ScriptedEngine ->
            object : KotlinHost.EngineSide by s {
                override fun config() = HostConfig(File(home, "config"), writable = false, ConfigHome.Problem.EXTERNAL_AWAY)
            }
        }
        class Row(val why: String, val side: (ScriptedEngine) -> KotlinHost.EngineSide = { it }, val block: () -> Boolean = { true }, val unblock: () -> Unit = {})
        val unreadable = File.createTempFile("probe", null).run { (setReadable(false) && !canRead()).also { delete() } }
        val rows = listOf(Row("not writable", side = unwritable)) + if (!unreadable) emptyList<Row>() else listOf(
            Row("not read", block = { file.setReadable(false) }, unblock = { file.setReadable(true) }),
            Row("not written", block = { file.parentFile!!.setWritable(false) }, unblock = { file.parentFile!!.setWritable(true) }),
        )
        assertRows(rows, { it.why }) { row ->
            fcitx.stop()
            home.deleteRecursively()
            internal.firstRun.setValue(false)
            internal.schemeSettled.setValue(false)
            file.apply { parentFile!!.mkdirs() }.writeText("PageSize=9\n")
            check(row.block())
            try {
                start(row.side)
                idle()
                assertEquals(EngineSettings.ZIRANMA, engine.settings.last().shuangpin)
                // the stop writes what is pending
                fcitx.stop()
                assertFalse(internal.schemeSettled.getValue())
            } finally {
                row.unblock()
            }
            assertEquals("PageSize=9\n", file.readText())
            start()
            idle()
            assertEquals(EngineSettings.ZIRANMA, engine.settings.last().shuangpin)
            until("settled") { internal.schemeSettled.getValue() }
            assertTrue(file.readText().lines().contains("ShuangpinProfile=Ziranma"))
        }
    }

    // a dropped scheme: 小鹤, written so, told once by its old name as news; the next start says nothing
    @Test
    @Uat("SET-15")
    fun aDroppedSchemeIsXiaoheAndTheUserIsToldOnce() {
        val told = internal.schemeRetiredTold
        try {
            assertRows(SchemeMigration.RETIRED, { it }) { name ->
                told.setValue(false)
                val quoted = if (' ' in name) "\"$name\"" else name
                assertEquals(Read(EngineSettings.XIAOHE, listOf(Told(dropped(name), steady = false))), schemeRead("ShuangpinProfile=$quoted\n", firstRun = false))
                assertTrue(told.getValue())
                // the default is left out of the file; written on the writer's thread, so waited for
                until("「$name」 written away", { conf() }) { conf()?.contains(name) == false }
                EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; true }
                said.clear()
                restart()
                idle()
                assertEquals(emptyList<String>(), said)
                assertEquals(EngineSettings.XIAOHE, engine.settings.last().shuangpin)
            }
            // told before: 小鹤, not said again
            told.setValue(true)
            assertEquals(Read(EngineSettings.XIAOHE), schemeRead("ShuangpinProfile=ABC\n", firstRun = false))
        } finally {
            told.setValue(told.defaultValue)
        }
    }

    // the process ended before any keyboard showed: told at the next start, then not again
    @Test
    fun aDroppedSchemesNoticeNotSeenIsToldAtTheNextStart() {
        assertEquals(Read(EngineSettings.XIAOHE), schemeRead("ShuangpinProfile=Ziguang\n", firstRun = false, shown = false))
        assertFalse(internal.schemeRetiredTold.getValue())
        assertEquals("Ziguang", internal.schemeRetiredUnseen.getValue())
        fcitx.stop()
        EngineNotices.forgetToldOnce()
        said.clear()
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; true }
        start()
        idle()
        assertEquals(listOf(dropped("Ziguang")), said)
        assertTrue(internal.schemeRetiredTold.getValue())
        assertEquals("", internal.schemeRetiredUnseen.getValue())
        said.clear()
        restart()
        idle()
        assertEquals(emptyList<String>(), said)
        assertEquals(EngineSettings.XIAOHE, engine.settings.last().shuangpin)
    }

    // a restore over a new install: an old backup with a dropped scheme is 小鹤 and told, one with none 自然码; a newer one with none 小鹤
    @Test
    @Uat("SET-15")
    fun aZeroFourBackupRestoredHasItsSchemeRead() {
        val prefs = AppPrefs.getInstance()
        val told = prefs.internal.schemeRetiredTold
        val raw = rawPrefs()
        try {
            assertRows(
                listOf(
                    Triple("ShuangpinProfile=Ziguang\n", false, EngineSettings.XIAOHE),
                    Triple("PageSize=9\n", false, EngineSettings.ZIRANMA),
                    Triple("PageSize=9\n", true, EngineSettings.XIAOHE),
                ),
                { "${it.first.trim()} settled ${it.second}" },
            ) { (conf, settled, scheme) ->
                // each row from its own install: the restore below writes over the prefs
                assertEquals(EngineSettings.XIAOHE, schemeRead(null, firstRun = true).scheme)
                told.setValue(true)
                fcitx.stop()
                val other = File(home.parentFile, "kotlin-host-restore").apply { deleteRecursively() }
                val settledPref = if (settled) """<boolean name="${prefs.internal.schemeSettled.key}" value="true" />""" else ""
                val zeroFourPrefs = """<map><boolean name="first_run" value="false" />$settledPref</map>"""
                val dirs = dirsUnder(other, external = home, config = File(home, "config"))
                val prefsFile = UserDataOrigin.preferencesFile(BuildConfig.APPLICATION_ID)
                UserDataManager.import(backupZip("external/config/${EngineOptions.PATH}" to conf, "shared_prefs/$prefsFile" to zeroFourPrefs), dirs).getOrThrow()
                other.deleteRecursively()
                // the restore restarts the app, which reads the backup's settings (JVM prefs are in memory)
                raw.edit(commit = true) {
                    remove(told.key)
                    putBoolean(prefs.internal.firstRun.key, false)
                    if (settled) putBoolean(prefs.internal.schemeSettled.key, true) else remove(prefs.internal.schemeSettled.key)
                }
                val notices = Collections.synchronizedList(ArrayList<String>())
                EngineNotices.sayer = EngineNotices.Sayer { message, _ -> notices += message; true }
                start()
                idle()
                assertEquals("$conf $settled", scheme, engine.settings.last().shuangpin)
                assertEquals("$conf $settled", if ("Ziguang" in conf) listOf(dropped("Ziguang")) else emptyList(), notices.toList())
            }
        } finally {
            told.setValue(told.defaultValue)
        }
    }
}
