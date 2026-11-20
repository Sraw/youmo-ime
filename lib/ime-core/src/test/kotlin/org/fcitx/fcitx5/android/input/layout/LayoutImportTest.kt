/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.layout.LayoutImport.Installed
import org.fcitx.fcitx5.android.input.layout.LayoutImport.Verdict
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayoutImportTest {

    private val dir = File(CustomLayoutFilesTest.LAYOUTS)
    private val boards = dir.listFiles()!!.filter { it.name.endsWith(".xml") }.associate { it.name.removeSuffix(".xml") to it.readText() }
    private val sources = LayoutCheck.Sources(boards - "grouped-bottom", boards.filterKeys { it == "grouped-bottom" })

    private fun own(id: String, named: String = "my-$id") = boards.getValue(id).replaceFirst("id=\"$id\"", "id=\"$named\" based-on=\"$id\"")

    private fun judge(text: String, installed: List<Installed> = emptyList(), app: String = "0.6.1") =
        LayoutImport.judge(text, LayoutCheck.check(text, sources, app), installed)

    @Test
    fun aLayoutWithNoErrorIsConfirmedNamedAsItIs() {
        val v = judge(own("qwerty")) as Verdict.Ok
        assertEquals("my-qwerty", v.layout.id)
        assertNull(v.replaces)
        assertEquals(listOf("pinyin", "shuangpin", "english", "table"), v.report.ims)
    }

    @Test
    fun oneOfAnIdOnThePhoneSaysWhatItReplaces() {
        val old = Installed("my-qwerty", "旧", "1.1")
        val v = judge(own("qwerty"), listOf(Installed("x", "x", null), old)) as Verdict.Ok
        assertEquals(old, v.replaces)
    }

    @Test
    fun errorsRefuseItTheFirstFiveShown() {
        // seven keys whose label is too long
        var text = own("qwerty")
        for (k in "qwertyu") text = text.replaceFirst("<key id=\"$k\" label=\"{case:$k}\"", "<key id=\"$k\" label=\"${"长".repeat(LayoutCheck.MAX_LABEL + 1)}\"")
        val v = judge(text) as Verdict.Refused
        assertEquals(LayoutImport.SHOWN, v.errors.size)
        assertTrue(v.errors.all { it.key == "label-long" })
        assertEquals(2, v.more)
        val newer = judge(own("qwerty").replaceFirst(Regex("(<keyboard[^>]*>)"), "$1<meta min-app=\"0.6.2\"/>")) as Verdict.Refused
        assertEquals(listOf("newer"), newer.errors.map { it.key })
        // a dev build's version takes any min-app
        assertTrue(judge(own("qwerty").replaceFirst(Regex("(<keyboard[^>]*>)"), "$1<meta min-app=\"0.6.2\"/>"), app = LayoutImport.appVersion(null)) is Verdict.Ok)
        assertEquals("0.6.1", LayoutImport.appVersion("0.6.1"))
    }

    @Test
    fun aBuiltInsIdIsRefused() {
        val v = judge(boards.getValue("qwerty")) as Verdict.Refused
        assertTrue(v.errors.map { it.key }.toString(), "id-built-in" in v.errors.map { it.key })
    }

    @Test
    fun theHundredthLayoutIsTheLastButOneReplacedStillIs() {
        val full = (1..CustomLayoutFiles.MAX_LAYOUTS).map { Installed("l$it", "l$it", null) }
        assertEquals(Verdict.TooMany(CustomLayoutFiles.MAX_LAYOUTS), judge(own("qwerty"), full))
        assertTrue(judge(own("qwerty", "l7"), full) is Verdict.Ok)
        assertTrue(judge(own("qwerty"), full.drop(1)) is Verdict.Ok)
    }

    @Test
    fun theTextsTypedAsWrittenAreListedFiveAndCounted() {
        var text = own("qwerty")
        for ((i, k) in "asdfghj".withIndex()) text = text.replaceFirst("<key id=\"$k\" label=\"{case:$k}\" send=\"$k\"", "<key id=\"$k\" label=\"{case:$k}\" send=\"commit:w$i\"")
        val v = judge(text) as Verdict.Ok
        assertEquals((0..4).map { "w$it" }, v.commits)
        assertEquals(2, v.moreCommits)
        assertEquals(LayoutImport.installed(v.layout), Installed("my-qwerty", v.layout.name, null))
    }

    @Test
    fun aLayoutsKeysInLittleAreWhereTheEngineBuildsThem() {
        for (id in listOf("qwerty", "k18", "nine", "number", "symbols")) {
            val layout = LayoutImport.layout(own(id))!!
            val fragments = layout.parts.filterIsInstance<org.fcitx.fcitx5.android.scene.layout.Include>()
                .associate { it.src to (org.fcitx.fcitx5.android.scene.layout.LayoutReader.fragment(boards.getValue(it.src)) as org.fcitx.fcitx5.android.scene.Parsed.Ok).value }
            val cells = LayoutThumbnail.cells(layout, fragments)
            assertTrue(id, cells.isNotEmpty())
            assertTrue(id, cells.all { it.left >= 0f && it.top >= 0f && it.right <= 1.001f && it.bottom <= 1.001f && it.left < it.right && it.top < it.bottom })
        }
        val qwerty = LayoutThumbnail.cells(LayoutImport.layout(own("qwerty"))!!, emptyMap())
        assertEquals(26, qwerty.count { it.kind == KeyKind.Letter && it.top < 0.75f })
        assertEquals(1, qwerty.count { it.kind == KeyKind.Space })
        assertEquals(1, qwerty.count { it.kind == KeyKind.Enter })
    }

    // the files the settings UAT imports (uat/fixtures/layouts): taken, the newer replacing it, one refused
    @Test
    @Uat("ST-148", "ST-149")
    fun theUatsLayoutsAreJudgedAsItSays() {
        fun fixture(name: String) = File("../../uat/fixtures/layouts/$name.layout").readText()
        val first = judge(fixture("uat-qwerty-1.0")) as Verdict.Ok
        assertEquals(listOf("哈哈"), first.commits)
        assertEquals("1.0", first.layout.meta!!.version)
        val again = judge(fixture("uat-qwerty-1.1"), listOf(LayoutImport.installed(first.layout))) as Verdict.Ok
        assertEquals(Installed("uat-qwerty", "UAT 26 键", "1.0"), again.replaces)
        val bad = judge(fixture("uat-qwerty-bad")) as Verdict.Refused
        assertEquals("label-long", bad.errors.single().key)
    }

    // the number keyboard's and the symbol panel's: taken with no warning
    @Test
    @Uat("ST-170", "ST-172")
    fun theUatsPanelLayoutsAreTakenWithNoWarning() {
        fun fixture(name: String) = File("../../uat/fixtures/layouts/$name.layout").readText()
        val numbers = judge(fixture("uat-number-1.0")) as Verdict.Ok
        assertEquals(listOf("full", "phone"), numbers.layout.pads)
        assertEquals(emptyList<LayoutCheck.Finding>(), numbers.report.warnings)
        val symbols = judge(fixture("uat-symbols-1.0")) as Verdict.Ok
        assertEquals("symbols", symbols.layout.family)
        assertEquals(emptyList<LayoutCheck.Finding>(), symbols.report.warnings)
    }

    // boards lacking keys: taken, each told once what it lacks
    @Test
    @Uat("ST-197")
    fun theUatsLayoutsLackingKeysAreTakenAndTold() {
        fun lacks(name: String) = (judge(File("../../uat/fixtures/layouts/$name.layout").readText(), app = "0.6.3") as Verdict.Ok).report.warnings
            .filter { it.key == "lacks" }.map { it.args }
        assertEquals(listOf(listOf("digits", "0")), lacks("uat-nine-big-enter"))
        assertEquals(listOf(listOf("letters", "m")), lacks("uat-k17-no-m"))
        assertEquals(listOf(listOf("symbol-grid")), lacks("uat-symbols-no-grid"))
    }

    // the UAT's uat-names.layout: taken, its WE's 下滑 told (the 18 keys' is used) and not among what it types
    @Test
    @Uat("ST-188")
    fun theUatsLayoutOfNamesIsTakenItsHeldKeyTold() {
        val ok = judge(File("../../uat/fixtures/layouts/uat-names.layout").readText()) as Verdict.Ok
        assertEquals(listOf(LayoutCheck.Finding(14, "held", listOf("g18/we", "k18"))), ok.report.warnings.filter { it.key == "held" })
        assertEquals(listOf("哈", "哈哈", "嘿"), ok.commits)
    }

    // a split row with 8 keys to halve, taken with that one warning
    @Test
    @Uat("ST-180")
    fun theUatsSplitRowThatCannotBeHalvedIsTakenWithAWarning() {
        val split = judge(File("../../uat/fixtures/layouts/uat-split-1.0.layout").readText()) as Verdict.Ok
        assertEquals(listOf("split-scaled"), split.report.warnings.map { it.key })
        assertEquals("8", split.report.warnings.single().args[0])
        assertEquals(emptyList<String>(), split.commits)
    }
}
