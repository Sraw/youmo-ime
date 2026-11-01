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

/**
 * 双拼 down to 小鹤, 微软 and 自然码 (the user's 2026-11-01): the scheme [KotlinHost] reads from
 * androidengine.conf at each start, a new install's, an upgrade's, a dropped one's, a restored one's
 * (SchemeMigration, its rules; KotlinHostTest the rest of the host).
 */
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

    // before the first unlock: the config none of the user's, nothing written (KotlinHostTest's direct boot case)
    private val locked = { s: ScriptedEngine -> object : KotlinHost.EngineSide by s { override fun config() = HostConfig(null, writable = false) } }

    private fun conf() = config(EngineOptions.PATH).takeIf { it.exists() }?.readText()

    /**
     * What the host reads at a start over [conf] as stored (none: no file): an install's first run, or
     * one from before 0.5 ([settled] false) or since; the scheme and what it said.
     */
    private fun schemeRead(
        conf: String?,
        firstRun: Boolean,
        settled: Boolean = false,
        side: (ScriptedEngine) -> KotlinHost.EngineSide = { it },
        // whether a keyboard is shown to say it on
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

    /** A start's 双拼 [scheme] and the notices it [told]. */
    private data class Read(val scheme: String, val told: List<Told> = emptyList())

    /** A notice, [steady] if a failure's (EngineNotices.Sayer). */
    private data class Told(val message: String, val steady: Boolean)

    private fun dropped(name: String) = appContext.getString(R.string.shuangpin_scheme_retired, ShuangpinNames.of(appContext, name))

    // SP-27: a new install's 双拼 is 小鹤, the option's default: nothing written, nothing said, settled so a
    // later start (no first run) is no upgrade
    @Test
    @Uat("SP-27")
    fun aNewInstallsShuangpinIsXiaohe() {
        assertEquals(Read(EngineSettings.XIAOHE), schemeRead(null, firstRun = true))
        assertNull(conf())
        assertTrue(internal.schemeSettled.getValue())
        restart()
        assertEquals(EngineSettings.XIAOHE, engine.settings.last().shuangpin)
        assertNull(conf())
        // a 0.5 install's config with none (some other option saved): 小鹤, as it had
        assertEquals(Read(EngineSettings.XIAOHE), schemeRead("PageSize=9\n", firstRun = false, settled = true))
        assertEquals("PageSize=9\n", conf())
    }

    // SET-14: an upgrade keeps the scheme it had: none written was 自然码 (0.4's default), pinned so,
    // written explicitly, nothing said; one stored left as it is, the file too
    @Test
    @Uat("SET-14")
    fun anUpgradeKeepsItsScheme() {
        assertRows(listOf(null, "PageSize=9\n", "# ShuangpinProfile=Ziranma\n"), { "$it" }) { conf ->
            assertEquals(Read(EngineSettings.ZIRANMA), schemeRead(conf, firstRun = false))
            // settled once the pin is on disk, on the writer's thread
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

    // direct boot: the defaults (小鹤), nothing written nor settled; the start after the unlock settles it, an
    // upgrade's to 自然码, a new install's 小鹤
    @Test
    fun directBootSettlesNothing() {
        assertRows(listOf(false to EngineSettings.ZIRANMA, true to EngineSettings.XIAOHE), { "first run ${it.first}" }) { (firstRun, after) ->
            directBoot(true)
            assertEquals(Read(EngineSettings.XIAOHE), schemeRead(null, firstRun, side = locked))
            assertFalse(internal.schemeSettled.getValue())
            directBoot(false)
            // the process started again at the unlock, with the user's prefs (a first run's still a first run)
            fcitx.stop()
            internal.firstRun.setValue(firstRun)
            start()
            idle()
            assertEquals(after, engine.settings.last().shuangpin)
            // the pin lands on the writer's thread: settled once it is on disk, not by the idle above
            until("settled") { internal.schemeSettled.getValue() }
        }
    }

    // the pin not on disk: a config that cannot be written (writable false), one there but not read (held:
    // the pin dropped, not written) and a write that fails (a full disk): 自然码 pinned for the start, not
    // settled, so the next start, the file written, still pins 自然码 and settles then
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
                // the stop writes what is pending: whatever was to land has
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

    // SET-15: one of the five dropped: 小鹤, written so, the user told once by the old name as news (not a
    // failure); the next start says nothing
    @Test
    @Uat("SET-15")
    fun aDroppedSchemeIsXiaoheAndTheUserIsToldOnce() {
        val told = internal.schemeRetiredTold
        try {
            assertRows(SchemeMigration.RETIRED, { it }) { name ->
                // each one as the first the user is told of
                told.setValue(false)
                val quoted = if (' ' in name) "\"$name\"" else name
                assertEquals(Read(EngineSettings.XIAOHE, listOf(Told(dropped(name), steady = false))), schemeRead("ShuangpinProfile=$quoted\n", firstRun = false))
                assertTrue(told.getValue())
                // written back as the default, which is left out: the dropped name gone (written on the
                // writer's thread, so waited for: a slow runner reads it before the write lands)
                until("「$name」 written away", { conf() }) { conf()?.contains(name) == false }
                EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; true }
                said.clear()
                restart()
                idle()
                assertEquals(emptyList<String>(), said)
                assertEquals(EngineSettings.XIAOHE, engine.settings.last().shuangpin)
            }
            // told before, a config with one again (written back by hand, or not written): 小鹤, not said again
            told.setValue(true)
            assertEquals(Read(EngineSettings.XIAOHE), schemeRead("ShuangpinProfile=ABC\n", firstRun = false))
        } finally {
            told.setValue(told.defaultValue)
        }
    }

    // review 2: the notice queued as the host starts, the process ended before any keyboard showed: told at
    // the next start (小鹤 already settled and written), told once it is seen and not again
    @Test
    fun aDroppedSchemesNoticeNotSeenIsToldAtTheNextStart() {
        assertEquals(Read(EngineSettings.XIAOHE), schemeRead("ShuangpinProfile=Ziguang\n", firstRun = false, shown = false))
        assertFalse(internal.schemeRetiredTold.getValue())
        assertEquals("Ziguang", internal.schemeRetiredUnseen.getValue())
        fcitx.stop()
        // the process gone: what waited in memory with it
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

    // SET-15: a backup restored over a new install's 小鹤: a 0.4 one with a dropped scheme is 小鹤 and told
    // again, one with none 自然码, as 0.4 had (its prefs have neither flag); a 0.5 one with none 小鹤 (settled)
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
                // this phone a new install, told of a dropped scheme before (each row from its own install: the
                // restore below writes over the prefs)
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
                // the restore starts the app again, which reads the backup's settings (a JVM test's prefs are in memory: as the file has them)
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
