/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.Context
import android.content.res.Configuration
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Every finding the check makes has its string. */
@RunWith(RobolectricTestRunner::class)
@Config(application = org.fcitx.fcitx5.android.FcitxApplication::class)
class LayoutTextsTest {

    private fun zh(): Context = appContext.createConfigurationContext(Configuration(appContext.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })

    private fun sample(key: String) = LayoutCheck.Finding(12, key, listOf("a", "65536"))

    @Test
    fun everyFindingOfTheCheckIsSaidInWordsOfItsOwn() {
        for (key in LayoutCheck.KEYS) {
            assertNotNull(key, LayoutTexts.string(key))
            for (ctx in listOf(appContext, zh())) {
                val text = LayoutTexts.finding(ctx, sample(key))
                assertFalse("$key: $text", text.contains("%"))
            }
        }
        assertEquals("第 12 行：键「q」很窄，可能不好按", LayoutTexts.finding(zh(), LayoutCheck.Finding(12, "narrow", listOf("q"))))
        assertEquals("需要幽默输入法 0.6.2 或更新（min-app）", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "newer", listOf("0.6.2"))))
        assertEquals("缺 based-on：从哪块内置键盘改来", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "based-on", listOf(""))))
        assertEquals("没有键能切到数字键盘、符号面板", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "no-way", listOf("Number", "Symbol"))))
        assertEquals("文件 70000 字节，超过 64 KB", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "too-big", listOf("70000", "65536"))))
        assertEquals("左下角通常是 符、返回（和 App 自带的一样）", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "bottom-left", listOf("symbols back"))))
        assertEquals("第 30 行：g18/h 是 18 键的键，这里写的长按、角标、滑动不起作用", LayoutTexts.finding(zh(), LayoutCheck.Finding(30, "held", listOf("g18/h", "k18"))))
        assertEquals("第 4 行：+ 是数字键盘的键，这里写的长按、角标、滑动不起作用", LayoutTexts.finding(zh(), LayoutCheck.Finding(4, "held", listOf("+", "number"))))
        // every board of the app's by its name, never its id; a space between 18 键 and the Chinese around it
        assertEquals("第 2 行：fn/x 是数字行的键，这里写的长按、角标、滑动不起作用", LayoutTexts.finding(zh(), LayoutCheck.Finding(2, "held", listOf("fn/x", "number-row"))))
        assertEquals("第 30 行：g18/h 是 18 键的键，长按跟它走，不按这里写的字母；要用这些字母，给它起个自己的键名", LayoutTexts.finding(zh(), LayoutCheck.Finding(30, "held-letters", listOf("g18/h", "k18"))))
        assertEquals("这里写的 popups= 不起作用，跟 18 键一样", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "held-popups", listOf("k18"))))
        assertEquals("数字键盘「电话」缺 + *：这种输入框里打不出来", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "pad-lacks", listOf("phone", "+ *"))))
        // the full pad is no kind of field: every number field has it
        assertEquals("数字键盘缺 - .：数字框里打不出来（比如金额的负号、小数点）", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "pad-lacks", listOf("full", "- ."))))
        assertEquals("第 9 行：键「k1」打出的是字母：数字输入框多半不收字母", LayoutTexts.finding(zh(), LayoutCheck.Finding(9, "pad-letters", listOf("k1"))))
        assertEquals("App 没有这块底稿：九键", LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "base", listOf("nine"))))
    }

    // what a board lacks, each with what it costs; a pad named only where the board's other pads have it
    @Test
    fun whatABoardLacksIsSaidWithWhatItCosts() {
        fun lacks(vararg args: String) = LayoutTexts.finding(zh(), LayoutCheck.Finding(0, "lacks", args.toList()))
        assertEquals("没有 ⌫：删不了字，打拼音时打错的字母去不掉（不在打字时可以用工具栏的文本编辑面板删）", lacks("backspace"))
        assertEquals("没有回车：换不了行，也按不了发送、搜索", lacks("enter"))
        assertEquals("没有返回键：回字母键盘要收起键盘再点输入框；数字输入框里一直是这块", lacks("back"))
        assertEquals("没有返回键：回字母键盘要收起键盘再点输入框", lacks("panel-back"))
        assertEquals("没有地球键：换输入法要把空格键长按行为设成切换输入法", lacks("lang"))
        assertEquals("没有地球键：换输入法要把空格键长按行为设成切换输入法；也没有空格，空格长按也用不了", lacks("lang-no-space"))
        assertEquals("没有数字键 0", lacks("digits", "0"))
        assertEquals("数字输入框里键盘直接是这块，打不出 0", lacks("pad-digits", "0"))
        assertEquals("数字键盘「电话」打不出 5：这种输入框里键盘直接是这块", lacks("pad-digits", "5", "phone"))
        assertEquals("没有返回键：回字母键盘要收起键盘再点输入框；数字输入框里一直是这块（数字键盘「电话、日期时间」）", lacks("back", "", "phone datetime"))
        assertEquals("没有字母 q x（密码框里也用这块键盘）", lacks("letters", "q x"))
        assertEquals("没有符号格子的位置：没有符号可选", lacks("symbol-grid"))
        // one of a newer check's: its words, never a blank line
        assertEquals("少了键：hyper", lacks("hyper"))
        for (what in listOf("space", "shift", "nine-side", "symbol-categories")) {
            for (ctx in listOf(appContext, zh())) {
                val text = LayoutTexts.finding(ctx, LayoutCheck.Finding(0, "lacks", listOf(what)))
                assertFalse("$what: $text", text.contains("%") || text == ctx.getString(R.string.layout_finding_lacks, what))
            }
        }
    }

    // replacing one an input method cannot type on now: said under the line of what it replaces
    @Test
    fun aReplacementSaysWhatGoesBack() {
        val layout = LayoutImport.layout(BuiltInLayouts(appContext).text("k18")!!.replaceFirst("id=\"k18\"", "id=\"mine\" based-on=\"k18\""))!!
        val report = LayoutCheck.Report(emptyList(), emptyList(), "grouped", listOf("pinyin"), emptyList(), emptyList(), "0.6.1")
        val ok = LayoutImport.Verdict.Ok(layout, report, LayoutImport.Installed("mine", "我的", null), emptyList(), 0)
        val back = zh().getString(R.string.layout_import_goes_back, "双拼", zh().getString(R.string.layout_goes_back, "双拼", "18 键"))
        val lines = LayoutTexts.confirm(zh(), ok, back).split("\n")
        assertEquals(listOf("替换已有的『我的』", "这一版双拼用不了，导入后双拼换回 18 键。"), lines.take(2))
        assertFalse(LayoutTexts.confirm(zh(), ok).contains("用不了"))
    }

    @Test
    fun aRefusalListsTheFirstErrorsAndCountsTheRest() {
        val refused = LayoutImport.Verdict.Refused(listOf(LayoutCheck.Finding(3, "label-long", listOf("q", "16"))), 4)
        assertEquals("· 第 3 行：键「q」显示的字太长：最多 16 个字符\n……还有 4 条", LayoutTexts.refused(zh(), refused))
    }

    @Test
    fun whatALayoutCanBeUsedFor() {
        fun report(family: String, ims: List<String> = emptyList(), pads: List<String> = emptyList()) =
            LayoutCheck.Report(emptyList(), emptyList(), family, ims, pads, emptyList(), "0.6.1")
        assertEquals("能用在：拼音、双拼的键盘", LayoutTexts.usedFor(zh(), report("grouped", listOf("pinyin", "shuangpin"))))
        assertEquals("能用在：数字键盘：123、电话", LayoutTexts.usedFor(zh(), report("number", pads = listOf("full", "phone"))))
        assertEquals("能用在：符号面板", LayoutTexts.usedFor(zh(), report("symbols")))
        assertTrue(LayoutTexts.usedFor(appContext, report("letters", listOf("english"))).contains("English"))
    }
}
