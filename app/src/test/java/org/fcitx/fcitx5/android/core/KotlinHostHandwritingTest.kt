/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows as assertNamedRows
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HandwritingRetire
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** The retired 手写 input method as [KotlinHost] reads a profile that lists it (HandwritingRetire has the rules). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KotlinHostHandwritingTest {

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private val said: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun config(path: String) = File(home, "config/$path")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun listen() {
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; true }
    }

    @Before
    fun setUp() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        AppPrefs.getInstance().internal.firstRun.setValue(true)
        start()
    }

    private fun start() {
        fcitx = startedHost(ScriptedEngine(home), firstRun = false)
    }

    private fun restart() {
        fcitx.stop()
        start()
    }

    @After
    fun tearDown() {
        fcitx.stop()
        home.deleteRecursively()
        // the singleton's prefs outlive the test
        AppPrefs.getInstance().internal.run {
            for (p in listOf(handwritingOpen, handwritingRetired, keyHandwritingUnseen)) p.setValue(false)
            handwritingRetiredUnseen.setValue("")
        }
        EngineState.traditionalIms = null
        EngineNotices.sayer = null
        EngineNotices.forgetToldOnce()
    }

    private fun zeroSixProfile(first: Boolean): String {
        val items = if (first) listOf(HandwritingRetire.OLD, InputMethodCatalog.ENGLISH)
        else listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH, HandwritingRetire.OLD)
        return buildString {
            append("[Groups/0]\nName=Default\nDefault Layout=us\nDefaultIM=${items.first()}\n\n")
            items.forEachIndexed { i, im -> append("[Groups/0/Items/$i]\nName=$im\n\n") }
            append("[GroupOrder]\n0=Default\n")
        }
    }

    private fun zeroSixInstalled(first: Boolean) {
        fcitx.stop()
        home.deleteRecursively()
        AppPrefs.getInstance().internal.firstRun.setValue(false)
        config(PROFILE).apply { parentFile!!.mkdirs() }.writeText(zeroSixProfile(first))
        EngineNotices.forgetToldOnce()
        said.clear()
        listen()
    }

    // pinyin takes its place where it was first, with the toolbar's 手写 on; written without it, told once
    @Test
    @Uat("PR-55", "PR-56")
    fun zeroSixsHandwritingInputMethodMovesOntoTheToolbarAndTheUserIsToldOnce() = runBlocking {
        val internal = AppPrefs.getInstance().internal
        fun row(first: Boolean, ims: List<String>, notice: String) = "handwriting first: $first" to fun() = runBlocking {
            zeroSixInstalled(first)
            internal.handwritingRetired.setValue(false)
            internal.handwritingOpen.setValue(false)
            start()
            idle()
            assertEquals(ims, fcitx.enabledIme().map { it.uniqueName })
            assertEquals("turned on where it was first", first, internal.handwritingOpen.getValue())
            assertTrue(internal.handwritingRetired.getValue())
            assertEquals(listOf(notice), said)
            fcitx.save()
            assertFalse(config(PROFILE).readText(), HandwritingRetire.OLD in config(PROFILE).readText())
            said.clear()
            restart()
            idle()
            assertEquals(emptyList<String>(), said)
        }
        assertNamedRows(
            row(true, listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH),
                appContext.getString(R.string.handwriting_retired, appContext.getString(R.string.handwriting_retired_moved))),
            row(false, listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), appContext.getString(R.string.handwriting_retired, "")),
        )
    }

    // where pinyin took its first place it is pinyin's 繁体 now; elsewhere that 繁体 is gone and the notice says so
    @Test
    @Uat("PR-58")
    fun zeroSixsTraditionalHandwritingIsPinyinsWhereItTookItsPlace() = runBlocking {
        val internal = AppPrefs.getInstance().internal
        fun row(first: Boolean, enabled: Set<String>, more: Int?) = "handwriting first: $first" to fun() = runBlocking {
            zeroSixInstalled(first)
            config(ModuleConfigs.CHTTRANS).apply { parentFile!!.mkdirs() }.writeText(ModuleConfigs.chttransText(null, setOf(HandwritingRetire.OLD)))
            internal.handwritingRetired.setValue(false)
            start()
            idle()
            assertEquals(enabled, ModuleConfigs.chttrans(config(ModuleConfigs.CHTTRANS).readText()))
            assertEquals(enabled, EngineState.traditionalIms)
            val moved = if (first) appContext.getString(R.string.handwriting_retired_moved) else ""
            val told = moved + (more?.let(appContext::getString) ?: "")
            assertEquals(listOf(appContext.getString(R.string.handwriting_retired, told)), said)
        }
        assertNamedRows(
            row(true, setOf(InputMethodCatalog.PINYIN), null),
            row(false, emptySet(), R.string.handwriting_retired_traditional),
        )
    }

    // retired again (an old backup restored): told again, but the user's button choice since is kept
    @Test
    fun handwritingRetiredAgainIsToldButNotPinnedOrTurnedOnAgain() = runBlocking {
        val internal = AppPrefs.getInstance().internal
        zeroSixInstalled(first = true)
        internal.handwritingRetired.setValue(true)
        internal.handwritingOpen.setValue(false)
        // the user took 手写 off the bar since
        val buttons = ToolbarConfig(ToolbarConfig.DEFAULT.items.filterNot { it is ToolbarConfig.Item.Button && it.button == ToolbarButton.Handwriting }).serialize()
        internal.toolbarButtons.setValue(buttons)
        try {
            start()
            idle()
            assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
            assertFalse(internal.handwritingOpen.getValue())
            assertEquals(buttons, internal.toolbarButtons.getValue())
            assertEquals(listOf(appContext.getString(R.string.handwriting_retired, appContext.getString(R.string.handwriting_retired_moved))), said)
        } finally {
            internal.toolbarButtons.setValue("")
        }
    }

    // an old backup's prefs lack the retirement marks, so its profile retires as an upgrade's: pinned, turned on where first, told
    @Test
    @Uat("PR-57")
    fun aRestoredBackupsHandwritingIsRetiredAsAnUpgradesIs() = runBlocking {
        val internal = AppPrefs.getInstance().internal
        zeroSixInstalled(first = true)
        internal.handwritingRetired.setValue(true)
        internal.handwritingOpen.setValue(false)
        internal.toolbarButtons.setValue(ToolbarConfig(ToolbarConfig.DEFAULT.items.filterNot { it is ToolbarConfig.Item.Button && it.button == ToolbarButton.Handwriting }).serialize())
        try {
            // an old prefs file has neither key
            rawPrefs().edit().remove(internal.handwritingRetired.key).remove(internal.toolbarButtons.key).commit()
            assertFalse(internal.handwritingRetired.getValue())
            start()
            idle()
            assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
            assertTrue(internal.handwritingOpen.getValue())
            assertTrue(internal.handwritingRetired.getValue())
            val bar = internal.toolbarButtons.getValue().ifEmpty { null }?.let(ToolbarConfig::parse) ?: ToolbarConfig.DEFAULT
            assertTrue(bar.toString(), bar.items.any { it is ToolbarConfig.Item.Button && it.button == ToolbarButton.Handwriting && it.shown })
            assertEquals(listOf(appContext.getString(R.string.handwriting_retired, appContext.getString(R.string.handwriting_retired_moved))), said)
        } finally {
            internal.toolbarButtons.setValue("")
        }
    }

    // in direct boot the prefs and config are not the user's: read without handwriting, nothing written or noted
    @Test
    fun directBootRetiresHandwritingWritingNothing() = runBlocking {
        val internal = AppPrefs.getInstance().internal
        zeroSixInstalled(first = true)
        internal.handwritingRetired.setValue(false)
        internal.handwritingOpen.setValue(false)
        val profile = config(PROFILE).readText()
        directBoot(true)
        try {
            start()
            idle()
            assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH), fcitx.enabledIme().map { it.uniqueName })
            fcitx.save()
            idle()
            assertEquals(profile, config(PROFILE).readText())
            assertFalse(internal.handwritingOpen.getValue())
            assertFalse(internal.handwritingRetired.getValue())
            assertEquals("", internal.handwritingRetiredUnseen.getValue())
            assertEquals(emptyList<String>(), said)
        } finally {
            directBoot(false)
        }
    }

    // read by nothing now: dropped at start (AppPrefs.dropRetired)
    @Test
    fun theHandwritingPadsReadingStyleIsDropped() {
        val raw = rawPrefs()
        raw.edit().putString("last_candidate_reading", "pinyin").commit()
        AppPrefs.getInstance().dropRetired()
        assertFalse(raw.contains("last_candidate_reading"))
    }

    // each its own notice
    @Test
    @Uat("MN-25")
    fun bothHandwritingNoticesAreToldEachOnce() = runBlocking {
        val internal = AppPrefs.getInstance().internal
        zeroSixInstalled(first = false)
        internal.handwritingRetired.setValue(false)
        internal.keyHandwritingUnseen.setValue(true)
        try {
            start()
            idle()
            assertEquals(
                listOf(appContext.getString(R.string.handwriting_retired, ""), appContext.getString(R.string.key_handwriting_retired)),
                said,
            )
            said.clear()
            restart()
            idle()
            assertEquals(emptyList<String>(), said)
        } finally {
            internal.keyHandwritingUnseen.setValue(false)
        }
    }

    @Test
    @Uat("ST-122")
    fun handwritingIsNoInputMethodToAdd() = runBlocking {
        val names = fcitx.availableIme().map { it.uniqueName }
        assertTrue(names.toString(), InputMethodCatalog.PINYIN in names)
        assertFalse(names.toString(), HandwritingRetire.OLD in names)
        assertFalse(HandwritingRetire.OLD in fcitx.enabledIme().map { it.uniqueName })
    }

    // the process gone before a keyboard showed: told at the next start
    @Test
    @Uat("MN-24")
    fun theHandwritingNoticeNotSeenIsToldAtTheNextStart() = runBlocking {
        val notice = appContext.getString(R.string.handwriting_retired, appContext.getString(R.string.handwriting_retired_moved))
        zeroSixInstalled(first = true)
        EngineNotices.sayer = EngineNotices.Sayer { _, _ -> false }
        start()
        idle()
        assertTrue(AppPrefs.getInstance().internal.handwritingRetiredUnseen.getValue().isNotEmpty())
        fcitx.stop()
        EngineNotices.forgetToldOnce()
        listen()
        start()
        idle()
        assertEquals(listOf(notice), said)
        assertEquals("", AppPrefs.getInstance().internal.handwritingRetiredUnseen.getValue())
        said.clear()
        restart()
        idle()
        assertEquals(emptyList<String>(), said)
    }

    private companion object {
        const val PROFILE = "profile"
    }
}
