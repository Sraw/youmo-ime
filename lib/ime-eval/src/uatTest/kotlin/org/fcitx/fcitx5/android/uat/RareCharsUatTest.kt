/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** uat/13-words.md WD-50–WD-54: 生僻字屏蔽 over the app's data, [UatEngines.rareChars] standing for the page's file. */
class RareCharsUatTest {

    private val levels: CharLevels by lazy { CharLevels.load(UatEngines.data(InputMethodCatalog.CHAR_LEVELS_DATA), verify = true) }

    // the page's file: on, [groups] held, [allow] let through
    private fun EngineReplay.choose(on: Boolean = true, groups: String = "EXT", allow: String = "") {
        UatEngines.rareChars = "# youmo-rare-chars 1\non\t${if (on) 1 else 0}\ngroups\t$groups\nallow\t$allow\n"
        engines.reload()
    }

    // the candidates for [keys] typed afresh
    private fun EngineReplay.typedAfresh(keys: String): List<String> {
        focus("")
        type(keys)
        return candidates
    }

    // the characters of [texts] in a group 生僻字屏蔽 holds: what an all-groups choice must never show
    private fun rareIn(texts: List<String>): List<String> =
        texts.filter { t -> t.codePoints().anyMatch { levels.of(it) in Level.GRID } }

    @Test
    @Uat("WD-50", "WD-55")
    fun turnedOnExtensionBIsGoneTheRestStaysTurnedOffItIsBack() = with(EngineReplay()) {
        // 𤝰 (U+24770) reads da, as 龘 does: the one 扩展 B, the other 其它表外字
        assertEquals(Level.EXT, levels.of(0x24770))
        expectNothingMissing("before", typedAfresh("da"), "𤝰", "龘", "大", "打")
        choose(on = true)
        val on = typedAfresh("da")
        assertTrue("𤝰 still offered: ${on.indexOf("𤝰")}", "𤝰" !in on)
        expectNothingMissing("on", on, "龘", "大", "打")
        // 借手机 (不用我的输入习惯) holds back as before: a setting, not a habit
        engines.useHabits = false
        val borrowed = typedAfresh("da")
        engines.useHabits = true
        assertTrue("𤝰 offered with the habits off", "𤝰" !in borrowed)
        expectNothingMissing("habits off", borrowed, "龘", "大")
        choose(on = false)
        expectNothingMissing("off again", typedAfresh("da"), "𤝰")
    }

    private fun expectNothingMissing(what: String, texts: List<String>, vararg wanted: String) =
        assertEquals(what, emptyList<String>(), wanted.filter { it !in texts })

    @Test
    @Uat("WD-51")
    fun byDefaultOnlyExtensionBIsHeld() = with(EngineReplay()) {
        choose(on = true)
        assertRows(
            "诶 (其它表外字)" to { expectAmongFresh("ei", "诶") },
            "囧 (其它表外字)" to { expectAmongFresh("jiong", "囧") },
            "龘 (其它表外字)" to { expectAmongFresh("da", "龘") },
            "闫 (三级字)" to { expectAmongFresh("yan", "闫") },
            "裡 (繁体字)" to { expectAmongFresh("li", "裡") },
        )
    }

    private fun EngineReplay.expectAmongFresh(keys: String, text: String) {
        val texts = typedAfresh(keys)
        assertTrue("$text not among $keys's ${texts.size}", text in texts)
    }

    @Test
    @Uat("WD-52")
    fun theOtherOutOfTableHeldThenOneLetThrough() = with(EngineReplay()) {
        choose(groups = "OUT EXT")
        val held = typedAfresh("da")
        assertTrue("龘 offered when its group is held", "龘" !in held)
        assertTrue("大 not offered", "大" in held)
        choose(groups = "OUT EXT", allow = "龘")
        assertTrue("龘 let through, not offered", "龘" in typedAfresh("da"))
    }

    @Test
    @Uat("WD-53")
    fun wubiAutoCommitsTheOneLeftAndAgainChoosesOnceLetThrough() = with(EngineReplay(im = InputMethodCatalog.WUBI)) {
        // 期 and 朞 share adwe: 朞 held, the code has one character left, put in at once
        engines.settings = engines.settings.copy(tables = engines.settings.tables + ("Wubi" to TableSettings(autoSelect = true)))
        assertEquals(Level.OUT, levels.of('朞'.code))
        type("adwe")
        expectStart("期", "朞")
        focus("")
        choose(groups = "OUT EXT")
        type("adwe")
        expectText("期")
        expectPreedit("")
        focus("")
        choose(groups = "OUT EXT", allow = "朞")
        type("adwe")
        expectStart("期", "朞")
    }

    @Test
    @Uat("WD-53")
    fun aWordWithAHeldCharacterIsNotOfferedNorPredicted() = with(EngineReplay()) {
        // dada: words of 哒 (二级) offered, none with a character of the groups held
        choose(groups = "OUT EXT")
        val dada = typedAfresh("dada")
        assertTrue("no word of 哒: $dada", dada.any { "哒" in it })
        assertEquals(emptyList<String>(), dada.filter(::outOrExt))
        focus("")
        type("da")
        pick("打")
        assertEquals("predictions after 打", emptyList<String>(), candidates.filter(::outOrExt))
    }

    @Test
    @Uat("WD-54")
    fun handwritingStrokesPartsAndGlideOfferNoneHeld() = with(EngineReplay()) {
        choose(groups = "OUT EXT")
        assertRows(
            "笔画查字 (uhhh offered 弐 亖 and others of the group)" to { assertEquals(emptyList<String>(), rareIn(typedAfresh("uhhh")).filter(::outOrExt)) },
            "拆字 (ukoukou offered 吕 吅)" to {
                val parts = typedAfresh("ukoukou")
                assertTrue("吕 not offered: $parts", "吕" in parts)
                assertTrue("吅 offered", "吅" !in parts)
            },
            "滑行 (da glided offered 龘)" to {
                focus("")
                glide("da")
                assertTrue("龘 offered", "龘" !in candidates)
                assertTrue("大 not offered", "大" in candidates)
            },
        )
        ime(InputMethodCatalog.PINYIN)
        write("龘", "大")
        assertEquals("the pad's 龘 read as 大", listOf("大"), candidates)
    }

    // every group held, what is not filtered by design kept out: the phrases, emoji
    private fun allHeld(im: String = InputMethodCatalog.PINYIN, s: EngineSettings = HELD_ALL) =
        EngineReplay(settings(s), im = im).also { it.choose(groups = "L3 TRAD OUT EXT") }

    // what [this] shows of the groups, into [found] under [what]
    private fun EngineReplay.collect(found: MutableMap<String, List<String>>, what: String) {
        rareIn(candidates).takeIf { it.isNotEmpty() }?.let { found[what] = it }
        // the code shown is the keys' (u一丨: strokes), not text
        rareIn(listOf(text)).takeIf { it.isNotEmpty() }?.let { found["$what (text)"] = it }
    }

    private fun outOrExt(text: String) = text.codePoints().anyMatch { levels.of(it) == Level.OUT || levels.of(it) == Level.EXT }

    /** Every Chinese input method and keyboard with every group held: no candidate, prediction or text has their characters. */
    @Test
    @Uat("WD-50", "WD-53", "WD-54")
    fun withEveryGroupHeldNoListedChineseInputMethodOffersOne() {
        val pinyin = listOf("da", "dada", "long", "ei", "jiong", "yan", "li", "zhongguo", "biang", "shi", "fa", "xiexie")
        val found = LinkedHashMap<String, List<String>>()
        fun EngineReplay.check(what: String) = collect(found, what)
        with(allHeld()) {
            for (keys in pinyin) {
                focus("")
                type(keys)
                check("全拼 $keys")
                focus("")
                glide(keys)
                check("全拼 滑行 $keys")
            }
            for (keys in listOf("uhhh", "uhs", "upn", "ukoukou", "uniuniuniu", "ushuishuishui")) {
                focus("")
                type(keys)
                check("u $keys")
            }
            focus("")
            type("da")
            pick("打")
            check("联想 after 打")
            focus("")
            type("woaizhongguo")
            space()
            check("整句 上屏")
        }
        with(allHeld()) {
            for (digits in listOf("32", "3232", "5664", "34")) {
                focus("")
                digits.forEach { key(EngineReplay.symOf(Keyboard.pinyinNineKey(it))) }
                pause()
                check("九键 $digits")
            }
        }
        assertEquals("held characters offered", emptyMap<String, List<String>>(), found)
    }

    /** As [withEveryGroupHeldNoListedChineseInputMethodOffersOne], the other keyboards and the tables. */
    @Test
    @Uat("WD-53", "WD-54")
    fun withEveryGroupHeldNoOtherKeyboardNorTableOffersOne() {
        val found = LinkedHashMap<String, List<String>>()
        fun EngineReplay.check(what: String) = collect(found, what)
        with(allHeld()) {
            for (zhuyin in listOf("ㄉㄚ", "ㄌㄨㄥ", "ㄌㄧ")) {
                focus("")
                zhuyin.forEach { key(EngineReplay.symOf(Keyboard.zhuyinKey(it))) }
                pause()
                check("大千 $zhuyin")
                focus("")
                zhuyin.forEach { c -> key(EngineReplay.symOf(Keyboard.zhuyinNineKey(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { c in it }))) }
                pause()
                check("注音九键 $zhuyin")
            }
        }
        with(allHeld(InputMethodCatalog.SHUANGPIN)) {
            for (keys in listOf("dada", "ls", "ez", "jsgo", "yj", "li", "vsgo")) {
                focus("")
                type(keys)
                check("双拼 自然码 $keys")
            }
        }
        with(allHeld(InputMethodCatalog.SHUANGPIN, HELD_ALL.copy(shuangpin = "Xiaohe"))) {
            for ((name, letters) in listOf("18 键" to NineKeyLetters.G18, "14 键" to NineKeyLetters.G14)) {
                for (keys in listOf("da", "ls", "ui")) {
                    focus("")
                    type(keys.map(letters::key).joinToString(""))
                    check("双拼 $name $keys")
                }
            }
        }
        val codes = mapOf(
            InputMethodCatalog.WUBI to listOf("adwe", "a", "dd", "ggll", "wqiy", "kkkk"),
            InputMethodCatalog.CANGJIE to listOf("a", "kkk", "ypu", "hhh"),
            InputMethodCatalog.ZIRANMA to listOf("da", "ls", "ui", "dd"),
            InputMethodCatalog.ERBI to listOf("d", "dd", "zh", "ao"),
            InputMethodCatalog.WUBI_PINYIN to listOf("da", "adwe", "long"),
        )
        for ((im, list) in codes) with(allHeld(im)) {
            for (code in list) {
                focus("")
                type(code)
                check("$im $code")
            }
        }
        with(allHeld(InputMethodCatalog.PINYIN)) {
            write("龘", "大")
            write("裡", "里")
            write("闫", "门")
            check("手写")
        }
        assertEquals("held characters offered", emptyMap<String, List<String>>(), found)
    }

    @Test
    fun theRealLevelsTableHasTheEdgesTheDesignNames() = assertRows(
        "the table's groups' sizes" to {
            val counts = Level.entries.associateWith { l -> (0 until MAX).count { levels.of(it) == l } }
            assertEquals(mapOf(Level.L1 to 3500, Level.L2 to 3000, Level.L3 to 1605, Level.TRAD to 2947), counts.filterKeys { it !in setOf(Level.OUT, Level.EXT) })
        },
        "U+3447 (扩展 A) is 三级, not 其它表外字" to { assertEquals(Level.L3, levels.of(0x3447)) },
        "a U+2xxxx 三级字 is 三级, not 扩展区" to { assertEquals(Level.L3, levels.of(0x20164)) },
        "扩展 A unlisted is 其它表外字" to { assertEquals(Level.OUT, levels.of(0x3400)) },
        "扩展 B unlisted is 扩展区" to { assertEquals(Level.EXT, levels.of(0x24770)) },
        "〇 is no group's" to { assertEquals(null, levels.of('〇'.code)) },
    )

    private companion object {
        const val MAX = 0x40000
        val HELD_ALL = UatEngines.BASELINE.copy(builtInPhrases = false, emoji = false)
    }
}
