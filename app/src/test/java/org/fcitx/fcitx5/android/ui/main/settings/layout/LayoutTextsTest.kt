/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.Context
import android.content.res.Configuration
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
        assertEquals("能用在：数字键盘：123、小数、电话", LayoutTexts.usedFor(zh(), report("number", pads = listOf("full", "decimal", "phone"))))
        assertEquals("能用在：符号面板", LayoutTexts.usedFor(zh(), report("symbols")))
        assertTrue(LayoutTexts.usedFor(appContext, report("letters", listOf("english"))).contains("English"))
    }
}
