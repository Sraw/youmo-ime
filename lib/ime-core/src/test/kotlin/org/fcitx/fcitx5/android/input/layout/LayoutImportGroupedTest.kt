/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.layout.LayoutImport.Verdict
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The grouped layouts the pinyin and 双拼 UAT imports (uat/fixtures/layouts, 061-custom-layout L4), judged as the import
 * judges them (LayoutImportTest's way): the 17 keys of their own groups, the 18 keys with Q and WE swapped, which are the
 * 18 keys.
 */
class LayoutImportGroupedTest {

    private val boards = File(CustomLayoutFilesTest.LAYOUTS).listFiles()!!.filter { it.name.endsWith(".xml") }
        .associate { it.name.removeSuffix(".xml") to it.readText() }
    private val sources = LayoutCheck.Sources(boards - "grouped-bottom", boards.filterKeys { it == "grouped-bottom" })

    private fun judged(name: String): Verdict.Ok {
        val text = File("../../uat/fixtures/layouts/$name.layout").readText()
        return LayoutImport.judge(text, LayoutCheck.check(text, sources, "0.6.1"), emptyList()) as Verdict.Ok
    }

    @Test
    @Uat("PY-153", "SP-51")
    fun theUatsGroupedLayoutsAreTheSeventeenKeysAndTheEighteenRearranged() {
        val k17 = judged("uat-k17-1.0")
        assertEquals(listOf("pinyin"), k17.report.ims)
        val groups = SceneGroups.written(k17.layout)
        assertEquals(17, groups.size)
        assertTrue("hjk" in groups)
        // HJK under a name of its own: nothing held (under g18/h it would be the app's H, told as held-letters)
        assertTrue(k17.report.warnings.toString(), k17.report.warnings.none { it.key.startsWith("held") })
        val underH = File("../../uat/fixtures/layouts/uat-k17-1.0.layout").readText().replace("uat-k17/hjk", "g18/h")
        assertEquals(listOf("held-letters"), LayoutCheck.check(underH, sources, "0.6.1").warnings.map { it.key }.filter { it.startsWith("held") })
        assertTrue(SceneGroups.letters(groups)!!.codes.all { it.code in NineKeyLetters.CUSTOM_BLOCK })
        // its taps lean by where they fall (按落点加权): no key writes lean="off"
        assertEquals(groups.filter { it.length > 1 }.toSet(), SceneGroups.leaning(k17.layout))
        val swapped = judged("uat-k18-swapped-1.0")
        assertEquals(listOf("pinyin", "shuangpin"), swapped.report.ims)
        assertEquals(listOf("we", "q"), SceneGroups.of(swapped.layout).take(2))
        assertEquals(NineKeyLetters.G18, SceneGroups.letters(SceneGroups.of(swapped.layout)))
    }
}
