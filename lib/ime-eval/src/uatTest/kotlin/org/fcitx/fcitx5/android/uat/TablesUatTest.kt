/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/10-tables.md: what the tables type, each case from its B0 (B0-五笔, B0-二笔…). A setting the
 * table's page sets is set as the page writes it (EngineSettings.tables, by the table's group); a
 * long press's menu is done as the menu does it (FcitxAPI.learning).
 */
class TablesUatTest {

    // the hint the bar shows beside [text] on the page, null when it is not there (named apart from
    // EngineReplay.hintOf, the engine's list's, which as a member would win over an extension)
    private fun EngineReplay.barHintOf(text: String): String? {
        val i = page.indexOf(text)
        return if (i >= 0) panel.hints.getOrNull(i) else null
    }

    // as the table's page sets one of its options: the others as a new install has them
    private fun EngineReplay.wubi(set: TableSettings) {
        val now = engines.settings.tables.getValue("Wubi")
        val made = TableSettings(set.autoSelect ?: now.autoSelect, set.hint ?: now.hint, set.orderByUse ?: now.orderByUse)
        engines.settings = engines.settings.copy(tables = engines.settings.tables + ("Wubi" to made))
    }

    @Test
    @Uat("TBL-03")
    fun fullCodeThenAnotherKeyCommitsTheFirst() = with(EngineReplay(im = Engines.WUBI)) {
        type("wqiy")
        expectFirst("你")
        type("w")
        expectText("你")
        expectPreedit("w")
        type("q")
        space()
        expectText("你你")
    }

    @Test
    @Uat("TBL-04")
    fun autoCommitOff() {
        with(EngineReplay(im = Engines.WUBI)) {
            wubi(TableSettings(autoSelect = false))
            type("ftjb")
            expectText("")
            expectAmong("都")
            space()
            expectText("都")
        }
    }

    @Test
    @Uat("TBL-05")
    fun anEmptyCodeIsDroppedByTheNextKey() = with(EngineReplay(im = "engine-erbi")) {
        type("eaaa")
        expectPreedit("eaaa")
        assertEquals(emptyList<String>(), candidates)
        expectText("")
        type("q")
        expectPreedit("q")
        expectFirst("起")
        expectText("")
    }

    @Test
    @Uat("TBL-06")
    fun codeHintsByDefault() = with(EngineReplay(im = Engines.WUBI)) {
        type("w")
        // 代's short code is wa: a is the key still to press
        assertEquals("代 ${page.zip(panel.hints)}", "a", barHintOf("代"))
        assertTrue("no hints ${panel.hints}", panel.hints.any { it.isNotEmpty() })
    }

    @Test
    @Uat("TBL-07")
    fun codeHintsOff() {
        with(EngineReplay(im = Engines.WUBI)) {
            wubi(TableSettings(hint = false))
            type("w")
            assertTrue("candidates ${page}", page.isNotEmpty())
            assertTrue("hints ${panel.hints}", panel.hints.all { it.isEmpty() })
        }
    }

    @Test
    @Uat("TBL-08")
    fun wubiZLooksUpByPinyin() = with(EngineReplay(im = Engines.WUBI)) {
        type("zni")
        expectOnFirstPage("你")
        assertTrue("candidates $page", page.size > 3)
        assertEquals("你 ${page.zip(panel.hints)}", "wqiy", barHintOf("你"))
    }

    @Test
    @Uat("TBL-09")
    fun wubiZIsAWildcard() = with(EngineReplay(im = Engines.WUBI)) {
        type("wziy")
        expectAmong("你")
    }

    private fun EngineReplay.typeJingYuanThreeTimes() {
        repeat(3) {
            type("qgyi")
            pick("鲸")
            type("lfqv")
        }
        expectText("鲸园鲸园鲸园")
        focus("")
    }

    @Test
    @Uat("TBL-11")
    fun aCharacterAtATimeMakesNoWord() = with(EngineReplay(im = Engines.WUBI)) {
        typeJingYuanThreeTimes()
        type("qglf")
        expectText("昏天黑地")
        expectNotAmong("鲸园")
    }

    @Test
    @Uat("TBL-12")
    fun aPinyinWordTakesNoTablesOnlyCode() = with(EngineReplay(im = Engines.WUBI)) {
        assertTrue(engines.addWord("鲸园", "jing yuan"))
        type("qglf")
        expectText("昏天黑地")
        expectNotAmong("鲸园")
        // the user lexicon's row: 五笔：与 昏天黑地 同码 (the row's words are the page's)
        assertEquals(listOf(Engines.WUBI to "昏天黑地"), engines.sameCodes(listOf("鲸园"), listOf(Engines.WUBI))["鲸园"])
        ime(Engines.PINYIN)
        focus("")
        type("jingyuan")
        expectAmong("鲸园")
    }

    @Test
    @Uat("TBL-12b")
    fun importedWordsAfterTheTablesOwnNotOnItsOnlyCode() = with(EngineReplay(im = Engines.WUBI)) {
        val imported = engines.importWords(UatEngines.fixture("dict-words.txt").readLines().asSequence(), "dict-words.txt")
        assertEquals(5, imported.imported + imported.added)
        type("jbys")
        val c = candidates
        val at = listOf("临阵磨枪", "电子计算机", "量子计算机", "量子文化村").map { c.indexOf(it) }
        assertTrue("jbys $c", at.all { it >= 0 } && maxOf(at[0], at[1]) < minOf(at[2], at[3]))
        focus("")
        type("rkoq")
        expectText("打嘴炮")
        expectNotAmong("氪喵糖铺")
    }

    @Test
    @Uat("TBL-14")
    fun noOrderByUseOnANewInstall() = with(EngineReplay(im = Engines.WUBI)) {
        type("fghy")
        expectStart("雨", "寸", "圷")
        repeat(2) {
            pick("寸")
            commit("。")
            type("fghy")
        }
        expectStart("雨", "寸", "圷")
    }

    @Test
    @Uat("TBL-15")
    fun orderByUseOnPinyinUntouchedThenTheTablesOrderBack() {
        with(EngineReplay()) {
            wubi(TableSettings(orderByUse = true))
            type("cun")
            val before = candidates
            focus("")
            ime(Engines.WUBI)
            type("fghy")
            pick("寸")
            focus("")
            type("fghy")
            expectFirst("雨")
            pick("寸")
            focus("")
            type("fghy")
            expectFirst("寸")
            focus("")
            ime(Engines.PINYIN)
            type("cun")
            expectFirst("村")
            assertEquals("cun", before.indexOf("寸"), candidates.indexOf("寸"))
            focus("")
            ime(Engines.WUBI)
            assertEquals(LearningResult.Done(), engines.learning(im, LearningOp.ResetTableOrder(im)))
            type("fghy")
            expectStart("雨", "寸", "圷")
        }
    }

    @Test
    @Uat("TBL-16")
    fun aTablesCommitPredicts() = with(EngineReplay(im = Engines.WUBI)) {
        // not committed at the fourth code (TBL-10): the space takes it
        type("khlg")
        space()
        expectText("中国")
        assertTrue("prediction ${page}", panel.predicting && page.isNotEmpty())
        val two = page.first { it.codePointCount(0, it.length) == 2 }
        pick(two)
        expectText("中国$two")
    }

    @Test
    @Uat("ST-34")
    fun predictionOffHoldsForTablesToo() = with(EngineReplay(im = Engines.WUBI)) {
        engines.settings = engines.settings.copy(prediction = false)
        type("khlg")
        space()
        expectText("中国")
        assertTrue("prediction ${page}", !panel.predicting && page.isEmpty())
    }

    @Test
    @Uat("TBL-18")
    fun erbiBracketLooksUpByPinyin() = with(EngineReplay(im = "engine-erbi")) {
        type("[ni")
        expectOnFirstPage("你")
        assertTrue("hints ${page.zip(panel.hints)}", !barHintOf("你").isNullOrEmpty())
    }

    @Test
    @Uat("TBL-21")
    fun ziranmaTable() = with(EngineReplay(im = "engine-ziranma")) {
        type("nihk")
        expectAmong("你好")
        focus("")
        type("nihkwomf")
        expectNotAmong("你好我们")
    }

    @Test
    @Uat("TBL-27")
    fun wubiEmoji() = with(EngineReplay(im = Engines.WUBI)) {
        type("kwkw")
        expectFirst("哈哈")
        assertTrue("kwkw $page", page.indexOf("😃") >= 2)
        backspace(4)
        type("hktf")
        expectFirst("点赞")
        assertTrue("hktf $page", page.indexOf("👍") >= 2)
        pick("👍")
        expectText("👍")
    }

    @Test
    @Uat("TBL-28")
    fun everyTableHasEmojiAfterASwitchAndARestart() = with(EngineReplay()) {
        type("haha")
        expectAmong("😃")
        focus("")
        for (row in listOf(
            listOf(Engines.WUBI, "kwkw", "哈哈", "😃"),
            listOf("engine-wubipinyin", "jqpv", "晚安", "😴"),
        )) {
            val (table, code, word) = row
            val emoji = row[3]
            ime(table)
            type(code)
            expectFirst(word)
            assertEquals("$table $code $page", 2, page.indexOf(emoji))
            focus("")
        }
        for (row in listOf(
            listOf("engine-erbi", "dazq", "点赞", "👍"),
            listOf("engine-ziranma", "haha", "哈哈", "😃"),
        )) {
            val (table, code, word) = row
            val emoji = row[3]
            ime(table)
            type(code)
            expectText(word)
            assertEquals("$table $code prediction $page", emoji, page.firstOrNull())
            pick(emoji)
            expectText(emoji)
            focus("")
        }
        ime("engine-erbi")
        type("dazq")
        val after = page[1]
        pick(after)
        expectText("点赞$after")
        expectNotAmong("👍")
        focus("")
        // started afresh: what a force stop leaves, the engines read again
        restart()
        type("dazq")
        expectText("点赞")
        assertEquals("prediction $page", "👍", page.firstOrNull())
        pick("👍")
        expectText("👍")
        focus("")
        ime("engine-cangjie")
        type("onf")
        assertTrue("cangjie $candidates", candidates.none { it.codePointAt(0) >= 0x1f000 })
    }

    @Test
    @Uat("TBL-36")
    fun aBlockInATableIsForThatCodeListedAndRestored() = with(EngineReplay(im = Engines.WUBI)) {
        type("fghy")
        val menu = engines.learning(im, LearningOp.Menu(candidates.indexOf("寸"), "寸"))
        // the confirmation's 打这串码不再出现「寸」；别的编码、别的输入法照旧。 is UNDER_CODE's
        assertTrue("menu $menu", menu is LearningResult.Menu && menu.scope == BlockScope.UNDER_CODE)
        engines.learning(im, LearningOp.Block(candidates.indexOf("寸"), "寸"))
        focus("")
        type("fghy")
        expectNotAmong("寸")
        focus("")
        ime(Engines.PINYIN)
        type("cun")
        expectAmong("寸")
        focus("")
        val listed = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS))
        assertTrue("listed $listed", listed is LearningResult.Listed && listed.items.single().text == "寸")
        engines.learning(null, LearningOp.RemoveItems((listed as LearningResult.Listed).items))
        val left = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS))
        assertTrue("left $left", left is LearningResult.Listed && left.items.isEmpty())
        ime(Engines.WUBI)
        type("fghy")
        expectStart("雨", "寸", "圷")
    }
}
