/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibimeMigrationTest {

    @Test
    fun libimesInputMethodsBecomeTheEnginesEachOnce() {
        val profile = """
            [Groups/0]
            # Group Name
            Name=默认
            Default Layout=us
            DefaultIM=wbpy

            [Groups/0/Items/0]
            Name=keyboard-us
            Layout=

            [Groups/0/Items/1]
            Name=wbx
            Layout=

            [Groups/0/Items/2]
            Name=wbpy
            Layout=

            [Groups/0/Items/3]
            Name=pinyin
            Layout=

            [GroupOrder]
            0=默认
        """.trimIndent()
        val expected = """
            [Groups/0]
            # Group Name
            Name=默认
            Default Layout=us
            DefaultIM=engine-wubi

            [Groups/0/Items/0]
            Name=keyboard-us
            Layout=

            [Groups/0/Items/1]
            Name=engine-wubi
            Layout=

            [Groups/0/Items/2]
            Name=engine-pinyin
            Layout=

            [GroupOrder]
            0=默认

        """.trimIndent()
        assertEquals(expected, LibimeMigration.profile(profile))
    }

    @Test
    fun aProfileWithoutLibimesInputMethodsIsLeftAlone() {
        assertNull(LibimeMigration.profile("[Groups/0]\nDefaultIM=engine-pinyin\n\n[Groups/0/Items/0]\nName=engine-pinyin\n"))
        assertNull(LibimeMigration.profile(""))
    }

    @Test
    fun eachGroupListsItsOwn() {
        val profile = "[Groups/0/Items/0]\nName=wbx\n[Groups/0/Items/1]\nName=wbpy\n" +
            "[Groups/1/Items/0]\nName=wbpy\n[Groups/1/Items/1]\nName=zrm\n"
        assertEquals(
            "[Groups/0/Items/0]\nName=engine-wubi\n\n" +
                "[Groups/1/Items/0]\nName=engine-wubi\n\n[Groups/1/Items/1]\nName=engine-ziranma\n",
            LibimeMigration.profile(profile),
        )
    }

    @Test
    fun pinyinsSettingsCarryOverAndReadBack() {
        val pinyin = """
            ShuangpinProfile=Xiaohe
            PageSize=9
            Prediction=False
            CloudPinyinEnabled=True

            [Fuzzy]
            VE_UE=True
            NG_GN=False
            L_N=True
            Z_ZH=True
            InnerShort=True

            [ForgetWord]
            0=Control+7
        """.trimIndent()
        val config = LibimeMigration.settings(pinyin)
        assertEquals(
            "ShuangpinProfile=Xiaohe\nPageSize=9\nPrediction=False\n\n[Fuzzy]\nZ_ZH=True\nL_N=True\nNG_GN=False\n",
            config,
        )
        // the addon hands its config over flattened, a group's keys after its name
        val flat = config.lines().fold("" to "") { (group, out), line ->
            when {
                line.startsWith("[") -> line.trim('[', ']') to out
                line.isEmpty() -> group to out
                group.isEmpty() -> group to "$out$line\n"
                else -> group to "$out$group/$line\n"
            }
        }.second
        assertEquals(
            EngineSettings(shuangpin = "Xiaohe", fuzzy = setOf(Fuzzy.Z_ZH, Fuzzy.L_N), typos = false, prediction = false, pageSize = 9),
            EngineSettings.parse(flat),
        )
        assertEquals("", LibimeMigration.settings(""))
    }

    @Test
    fun settingsMissingOrUnreadableAreTheDefaults() {
        assertEquals(EngineSettings(), EngineSettings.parse(""))
        assertEquals(EngineSettings(), EngineSettings.parse("ShuangpinProfile=Custom\nPageSize=99\nPrediction=1\n=x\nFuzzy/L_N=yes"))
        assertEquals(ShuangpinScheme.ZIRANMA, EngineSettings(shuangpin = "nothing").scheme)
        assertEquals(ShuangpinScheme.MICROSOFT, EngineSettings.parse("ShuangpinProfile=MS").scheme)
        assertEquals(EngineSettings(sentenceModel = false), EngineSettings.parse("SentenceModel=False"))
    }

    @Test
    fun aListOfInputMethodsNamesTheEnginesEachOnce() {
        assertEquals(
            "[EnabledIM]\n# a comment\n0=engine-pinyin\n1=engine-wubi\n2=rime\n\n[Other]\nx=pinyin\n",
            LibimeMigration.inputMethodList("[EnabledIM]\n# a comment\n0=pinyin\n1=wbx\n2=\"wbpy\"\n3=rime\n\n[Other]\nx=pinyin\n"),
        )
        assertNull(LibimeMigration.inputMethodList("[EnabledIM]\n0=rime\n"))
        assertNull(LibimeMigration.inputMethodList("[Other]\n0=pinyin\n"))
    }

    @Test
    fun aQuotedSchemeCarriesOver() {
        val settings = LibimeMigration.settings("ShuangpinProfile=\"GB Standard\"\n")
        assertEquals("ShuangpinProfile=\"GB Standard\"\n", settings)
    }
}
