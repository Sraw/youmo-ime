/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.fixture
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.core.untar
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.ui.main.settings.Incoming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * uat/13-words.md's base dictionary cases DictionaryUatTest leaves: a .scel converted into the base
 * (WD-14z2) and 0.3.1's text dictionaries upgraded at the start (WD-14h's migration; the device
 * keeps the real upgrade's not crashing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class BaseDictionaryUpgradeUatTest {

    private val dir get() = PinyinDictManager.pinyinDicDir

    private fun typed(text: String) = File(dir, MergedDictionary.FILE).takeIf { it.isFile }
        ?.let { DictionaryFile.open(JvmFile(it)).hasText(text) } == true

    private fun listed() = PinyinDictManager.listDictionaries().associate { it.name to it.isEnabled }

    @Before
    fun empty() {
        phoneStorage()
        MergedDictionary.reset()
        MergedDictionary.made = {}
        MergedDictionary.base = { null }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
    }

    @After
    fun done() {
        MergedDictionary.told = {}
        dir.listFiles()?.forEach { it.deleteRecursively() }
        MergedDictionary.reset()
    }

    @Test
    @Uat("WD-14z2")
    fun aScelFileIntoTheBaseIsConvertedAndMerged() {
        val scel = fixture("scel/extra.scel")
        PinyinDictManager.prepare(scel.inputStream(), scel.name, scel.length()).use {
            PinyinDictManager.importPrepared(it, Incoming(scel.name).name).getOrThrow()
        }
        assertEquals(mapOf("extra" to true), listed())
        assertTrue(typed("琮璟"))
        assertTrue(typed("骁骎"))
    }

    // 0.3.1's files: its external storage as the fixture keeps it (no dictionary of its own), then
    // what the case puts there by hand: frost as text, and a dictionary 0.3.1 turned off
    private fun v031() {
        for (home in listOf(ConfigHome.CONFIG, ConfigHome.DATA, ConfigHome.THEME)) {
            untar(fixture("upgrade/v0.3.1/external.tar"), HostConfigDir.inUse(home).dir, under = "${home.old}/")
        }
        File(dir, "frost.txt").writeText("阿坝州 a'ba'zhou 0.0\n跟你说 gen'ni'shuo 0.0\n")
        File(dir, "old.txt.disable").writeText("氪喵星港 ke'miao'xing'gang 0.0\n")
    }

    @Test
    @Uat("WD-14h")
    fun v031sTextDictionariesAreUpgradedAtTheStart() {
        v031()
        val kinds = Collections.synchronizedSet(HashSet<MergedDictionary.Kind>())
        MergedDictionary.told = { kinds += it.kind }
        MergedDictionary.refresh(dir)?.get(1, TimeUnit.MINUTES)
        assertEquals(setOf(MergedDictionary.Kind.UPGRADE), kinds.toSet())
        val left = dir.list()!!.filter { !it.startsWith(".") }.sorted()
        assertEquals(listOf(MergedDictionary.FILE, "frost.txt.gz", "old.txt.gz.disable").sorted(), left)
        assertEquals(mapOf("frost" to true, "old" to false), listed())
        assertTrue(typed("阿坝州"))
        assertTrue(typed("跟你说"))
        assertFalse(typed("氪喵星港"))
    }
}
