/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.host.KeyRouter
import org.fcitx.fcitx5.android.host.KeyStates as HostKeyStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/10-tables.md: what the tables type, each case from its B0 (B0-五笔, B0-二笔…). A setting the
 * table's page sets is set as the page writes it (EngineSettings.tables, by the table's group); a
 * long press's menu is done as the menu does it (HostApi.learning).
 */
class TablesUatTest {

    // the hint the bar shows beside [text] on the page, null when it is not there
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
    fun fullCodeThenAnotherKeyCommitsTheFirst() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
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
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
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
    fun codeHintsByDefault() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
        type("w")
        // 代's short code is wa: a is the key still to press
        assertEquals("代 ${page.zip(panel.hints)}", "a", barHintOf("代"))
        assertTrue("no hints ${panel.hints}", panel.hints.any { it.isNotEmpty() })
    }

    @Test
    @Uat("TBL-07")
    fun codeHintsOff() {
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
            wubi(TableSettings(hint = false))
            type("w")
            assertTrue("candidates ${page}", page.isNotEmpty())
            assertTrue("hints ${panel.hints}", panel.hints.all { it.isEmpty() })
        }
    }

    @Test
    @Uat("TBL-08")
    fun wubiZLooksUpByPinyin() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
        type("zni")
        expectOnFirstPage("你")
        assertTrue("candidates $page", page.size > 3)
        assertEquals("你 ${page.zip(panel.hints)}", "wqiy", barHintOf("你"))
    }

    @Test
    @Uat("TBL-09")
    fun wubiZIsAWildcard() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
        type("wziy")
        expectAmong("你")
    }

    // A3: 仓颉's and 二笔's matching key is * (TableOptions), typed as the keyboard sends it (S held, or a
    // hardware keyboard's shifted 8) through the whole host, its punctuation module after the engine: a
    // code, not the punctuation's ×; 五笔's is z, its * the punctuation's
    @Test
    @Uat("TBL-45")
    fun cangjieAndErbiStarIsAWildcard() = assertRows(
        // each its own replay, so one failing does not hide the others
        "仓颉 ab*: the three-key codes after 日月, 冒 (abu) the table's only one" to { with(HostReplay(im = InputMethodCatalog.CANGJIE)) {
            type("ab*")
            // its roots, as the keys show them (TBL-46)
            expectPreedit("日月*")
            assertEquals(listOf("冒"), candidates)
            space()
            expectText("冒")
        } },
        "仓颉 *nf, o*f: 你 is onf, * for its first key or its middle one" to { with(HostReplay(im = InputMethodCatalog.CANGJIE)) {
            type("*nf")
            assertTrue(candidates.toString(), "你" in candidates)
            key(EngineReplay.BACKSPACE)
            key(EngineReplay.BACKSPACE)
            key(EngineReplay.BACKSPACE)
            type("o*f")
            assertTrue(candidates.toString(), "你" in candidates)
        } },
        "仓颉 * alone: every one-key code, the table's own order, the first committed by the space" to { with(HostReplay(im = InputMethodCatalog.CANGJIE)) {
            type("*")
            expectPreedit("*")
            assertEquals(listOf("日", "月", "金", "木", "水", "火", "土"), candidates.take(7))
            space()
            expectText("日")
        } },
        "仓颉 on a hardware keyboard: its *, shift held, is the wildcard too" to { with(HostReplay(im = InputMethodCatalog.CANGJIE)) {
            key('o'.code)
            key('*'.code, HostKeyStates.SHIFT)
            key('f'.code)
            pause()
            expectPreedit("人*火")
            assertTrue(candidates.toString(), "你" in candidates)
        } },
        "二笔 da*q: 点赞 is dazq" to { with(HostReplay(im = InputMethodCatalog.ERBI)) {
            type("da*q")
            assertTrue(candidates.toString(), "点赞" in candidates)
        } },
        "五笔: its wildcard is z (TBL-09), its * the punctuation's" to { with(HostReplay(im = InputMethodCatalog.WUBI)) {
            type("*")
            expectPreedit("")
            expectText("*")
        } },
    )

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
    fun aCharacterAtATimeMakesNoWord() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
        typeJingYuanThreeTimes()
        type("qglf")
        expectText("昏天黑地")
        expectNotAmong("鲸园")
    }

    @Test
    @Uat("TBL-12")
    fun aPinyinWordTakesNoTablesOnlyCode() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
        assertTrue(engines.addWord("鲸园", "jing yuan"))
        type("qglf")
        expectText("昏天黑地")
        expectNotAmong("鲸园")
        // the user lexicon's row: 五笔：与 昏天黑地 同码 (the row's words are the page's)
        assertEquals(listOf(InputMethodCatalog.WUBI to "昏天黑地"), engines.sameCodes(listOf("鲸园"), listOf(InputMethodCatalog.WUBI))["鲸园"])
        ime(InputMethodCatalog.PINYIN)
        focus("")
        type("jingyuan")
        expectAmong("鲸园")
    }

    @Test
    @Uat("TBL-12b")
    fun importedWordsAfterTheTablesOwnNotOnItsOnlyCode() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
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
    fun noOrderByUseOnANewInstall() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
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
            ime(InputMethodCatalog.WUBI)
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
            ime(InputMethodCatalog.PINYIN)
            type("cun")
            expectFirst("村")
            assertEquals("cun", before.indexOf("寸"), candidates.indexOf("寸"))
            focus("")
            ime(InputMethodCatalog.WUBI)
            assertEquals(LearningResult.Done(), engines.learning(im, LearningOp.ResetTableOrder(im)))
            type("fghy")
            expectStart("雨", "寸", "圷")
        }
    }

    @Test
    @Uat("TBL-16")
    fun aTablesCommitPredicts() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
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
    fun predictionOffHoldsForTablesToo() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
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
    fun wubiEmoji() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
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
            listOf(InputMethodCatalog.WUBI, "kwkw", "哈哈", "😃"),
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
    fun aBlockInATableIsForThatCodeListedAndRestored() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
        type("fghy")
        val menu = engines.learning(im, LearningOp.Menu(candidates.indexOf("寸"), "寸"))
        // the confirmation's 打这串码不再出现「寸」；别的编码、别的输入法照旧。 is UNDER_CODE's
        assertTrue("menu $menu", menu is LearningResult.Menu && menu.scope == BlockScope.UNDER_CODE)
        engines.learning(im, LearningOp.Block(candidates.indexOf("寸"), "寸"))
        focus("")
        type("fghy")
        expectNotAmong("寸")
        focus("")
        ime(InputMethodCatalog.PINYIN)
        type("cun")
        expectAmong("寸")
        focus("")
        val listed = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS))
        assertTrue("listed $listed", listed is LearningResult.Listed && listed.items.single().text == "寸")
        engines.learning(null, LearningOp.RemoveItems((listed as LearningResult.Listed).items))
        val left = engines.learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS))
        assertTrue("left $left", left is LearningResult.Listed && left.items.isEmpty())
        ime(InputMethodCatalog.WUBI)
        type("fghy")
        expectStart("雨", "寸", "圷")
    }

    // B5: 仓颉's code shown in the roots its keys show, the letters again with the keys' roots off
    @Test
    @Uat("TBL-46")
    fun cangjiesCodeIsShownInItsRoots() = with(HostReplay(im = InputMethodCatalog.CANGJIE)) {
        type("onf")
        expectPreedit("人弓火")
        assertTrue(candidates.toString(), "你" in candidates)
        key(EngineReplay.BACKSPACE)
        expectPreedit("人弓")
        key(EngineReplay.BACKSPACE)
        key(EngineReplay.BACKSPACE)
        engines.settings = engines.settings.copy(tables = engines.settings.tables + ("Cangjie" to engines.settings.tables.getValue("Cangjie").copy(keyLabels = KeyLabels.NONE)))
        type("onf")
        expectPreedit("onf")
    }

    // B5: the 拼 key (Keyboard.LOOKUP) of a table's keyboard: pinyin till it is pressed again, 拼 before
    // the code row; 二笔 and 仓颉 as 五笔, whose z still looks up a word alone
    @Test
    @Uat("TBL-47")
    fun theLookUpKeyLooksUpByPinyinTillPressedAgain() = assertRows(
        "五笔: 拼 on, hao is 好 and 拼 stays on; off, wqiy is 你" to { with(HostReplay(im = InputMethodCatalog.WUBI)) {
            lookUpKey()
            type("hao")
            assertTrue(row, row.startsWith(KeyRouter.LOOKING_UP))
            assertTrue(candidates.toString(), "好" in candidates)
            pick("好")
            type("ni")
            assertTrue(row, row.startsWith(KeyRouter.LOOKING_UP))
            pick("你")
            expectText("好你")
            lookUpKey()
            type("wqiy")
            assertEquals("你", candidates.first())
            assertTrue(row, !row.startsWith(KeyRouter.LOOKING_UP))
        } },
        "五笔: 拼 on, switched away and back, still on; 二笔's its own, off" to { with(HostReplay(im = InputMethodCatalog.WUBI)) {
            lookUpKey()
            ime(InputMethodCatalog.ERBI)
            type("hao")
            assertTrue(row, !row.startsWith(KeyRouter.LOOKING_UP))
            ime(InputMethodCatalog.WUBI)
            type("hao")
            assertTrue(row, row.startsWith(KeyRouter.LOOKING_UP))
            assertTrue(candidates.toString(), "好" in candidates)
        } },
        "五笔: a code typed goes into the lookup as the key is pressed" to { with(HostReplay(im = InputMethodCatalog.WUBI)) {
            type("hao")
            lookUpKey()
            assertTrue(row, row.startsWith(KeyRouter.LOOKING_UP))
            assertTrue(candidates.toString(), "好" in candidates)
        } },
        "五笔: off mid-lookup, a word picked of it is put in, its letters after it a code again" to { with(HostReplay(im = InputMethodCatalog.WUBI)) {
            lookUpKey()
            type("nihao")
            pick("你")
            expectText("")
            lookUpKey()
            expectText("你")
            assertTrue(row, !row.startsWith(KeyRouter.LOOKING_UP))
            assertTrue(row, row.startsWith("hao"))
        } },
        "五笔: off with nothing picked, nothing put in, the ' dropped" to { with(HostReplay(im = InputMethodCatalog.WUBI)) {
            lookUpKey()
            type("xi'an")
            lookUpKey()
            expectText("")
            assertTrue(row, row.startsWith("xian"))
        } },
        "五笔: z still looks up one word, marked" to { with(HostReplay(im = InputMethodCatalog.WUBI)) {
            type("zhao")
            assertTrue(row, row.startsWith(KeyRouter.LOOKING_UP))
            pick("好")
            expectText("好")
            type("wqiy")
            assertEquals("你", candidates.first())
        } },
        "二笔: its lookup by the key, marked" to { with(HostReplay(im = InputMethodCatalog.ERBI)) {
            lookUpKey()
            type("hao")
            assertTrue(row, row.startsWith(KeyRouter.LOOKING_UP))
            assertTrue(candidates.toString(), "好" in candidates)
        } },
        "仓颉: a lookup it had no key for, marked" to { with(HostReplay(im = InputMethodCatalog.CANGJIE)) {
            lookUpKey()
            type("hao")
            assertTrue(row, row.startsWith(KeyRouter.LOOKING_UP))
            assertTrue(candidates.toString(), "好" in candidates)
            pick("好")
            expectText("好")
        } },
    )

    private fun HostReplay.lookUpKey() = key(EngineReplay.UNICODE_KEYSYM + Keyboard.LOOKUP)

    // B5: 二笔's ; typed as its key sends it (TablesKeysUatTest has the key): a code, 号's whole
    @Test
    @Uat("TBL-50")
    fun erbisSemicolonTypesItsCode() = with(HostReplay(im = InputMethodCatalog.ERBI)) {
        type("hl;")
        expectText("号")
    }
}
