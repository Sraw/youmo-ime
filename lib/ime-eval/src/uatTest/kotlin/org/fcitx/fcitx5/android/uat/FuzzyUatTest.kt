/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/** 模糊音 in 双拼 and on keys that read a taken syllable, initial or symbol: uat/07-shuangpin.md SP-62 to SP-64, uat/06-pinyin.md PY-164 and PY-165. */
class FuzzyUatTest {

    // 模糊音 costs a syllable alike in 双拼 and full pinyin: each scheme's jing keys list 金 where full pinyin's jing does
    @Test
    @Uat("SP-62")
    fun fuzzyPinyinReadsEverySchemeAsFullPinyinDoes() {
        val inIng = EngineSettings(fuzzy = setOf(Fuzzy.IN_ING))
        fun shown(s: EngineSettings, im: String, keys: String) = with(EngineReplay(settings(s), im = im)) {
            type(keys)
            candidates.filter { it.length == 1 }.take(FIRST)
        }
        val jing = shown(inIng, InputMethodCatalog.PINYIN, "jing")
        val jin = shown(inIng, InputMethodCatalog.PINYIN, "jin")
        assertTrue("jing: $jing", "金" in jing)
        assertTrue("jin: $jin", "京" in jin)
        for ((scheme, keys) in listOf(EngineSettings.ZIRANMA to listOf("jy", "jn"), EngineSettings.XIAOHE to listOf("jk", "jb"), EngineSettings.MS to listOf("j;", "jn"))) {
            val on = inIng.copy(shuangpin = scheme)
            assertEquals("$scheme ${keys[0]}", jing, shown(on, InputMethodCatalog.SHUANGPIN, keys[0]))
            assertEquals("$scheme ${keys[1]}", jin, shown(on, InputMethodCatalog.SHUANGPIN, keys[1]))
            assertFalse("$scheme ${keys[0]}, off", "金" in shown(EngineSettings(shuangpin = scheme), InputMethodCatalog.SHUANGPIN, keys[0]))
            // jin ji read as jing ji
            with(EngineReplay(settings(on), im = InputMethodCatalog.SHUANGPIN)) {
                type(keys[1] + "ji")
                expectOnFirstPage("经济")
            }
            // 笔画筛选 keeps the fuzzy readings: jing yu then 丿 lists 金鱼 and 金; off, neither
            for (s in listOf(on, EngineSettings(shuangpin = scheme))) with(EngineReplay(settings(s), im = InputMethodCatalog.SHUANGPIN)) {
                type(keys[0] + "yu")
                stroke("p")
                assertEquals("$scheme ${s.fuzzy}: $candidates", s.fuzzy.isNotEmpty(), "金鱼" in page && "金" in candidates.take(FIRST))
                assertEquals("$scheme ${s.fuzzy}: $candidates", s.fuzzy.isNotEmpty(), "金鱼" in candidates || "金" in candidates)
            }
        }
    }

    // on the 18, 14 and nine keys, jing taken off the bar still reads jin with in ↔ ing on: as the full keyboard's jing; learned as jin yu
    @Test
    @Uat("SP-63")
    fun aSyllableTakenOnTheGroupedKeysStillReadsTheFuzzyPairsOn() {
        for (name in listOf(EngineSettings.ZIRANMA, EngineSettings.XIAOHE)) {
            val scheme = EngineSettings.SCHEMES.getValue(name)
            val inIng = EngineSettings(shuangpin = name, fuzzy = setOf(Fuzzy.IN_ING))
            val full = with(EngineReplay(settings(inIng), im = InputMethodCatalog.SHUANGPIN)) {
                type(codeOf(scheme, "jing"))
                candidates.filter { it.length == 1 }.take(FIRST)
            }
            for (letters in GROUPED.filter { it != NineKeyLetters.G14 || ImKeyboards.has14(scheme) }) {
                for (s in listOf(inIng, EngineSettings(shuangpin = name))) takeJingThenYu(s, scheme, letters, full)
                learnJinyuTaken(inIng, scheme, letters)
            }
        }
    }

    private fun codeOf(scheme: ShuangpinScheme, syllable: String) = scheme.encode(Syllables.id(syllable))!!

    // jing taken: the singles as the full keyboard's jing; then yu and 丿 list 金鱼 and 金; off, neither
    private fun takeJingThenYu(s: EngineSettings, scheme: ShuangpinScheme, letters: NineKeyLetters, full: List<String>) =
        with(EngineReplay(settings(s), im = InputMethodCatalog.SHUANGPIN)) {
            val fuzzy = s.fuzzy.isNotEmpty()
            typeOn(letters, codeOf(scheme, "jing"))
            syllable("jing")
            val singles = candidates.filter { it.length == 1 }.take(FIRST)
            if (fuzzy) assertEquals("${s.shuangpin} $letters", full, singles) else assertFalse("${s.shuangpin} $letters off: $singles", "金" in singles)
            typeOn(letters, codeOf(scheme, "yu"))
            stroke("p")
            assertEquals("${s.shuangpin} $letters ${s.fuzzy}: $candidates", fuzzy, "金鱼" in candidates.take(FIRST) && "金" in candidates.take(FIRST))
        }

    // 金鱼 picked twice with jing taken comes first untaken, learned as jin yu
    private fun learnJinyuTaken(inIng: EngineSettings, scheme: ShuangpinScheme, letters: NineKeyLetters) =
        with(EngineReplay(settings(inIng.copy(candidateReading = ReadingStyle.PINYIN)), im = InputMethodCatalog.SHUANGPIN)) {
            repeat(2) {
                typeOn(letters, codeOf(scheme, "jing"))
                syllable("jing")
                typeOn(letters, codeOf(scheme, "yu"))
                stroke("p")
                pick("金鱼")
            }
            typeOn(letters, codeOf(scheme, "jing") + codeOf(scheme, "yu"))
            assertTrue("${inIng.shuangpin} $letters learned: $page", "金鱼" in page)
            assertEquals("jin yu", Normalizer.normalize(readingOf("金鱼"), Normalizer.Form.NFD).replace(TONE, ""))
        }

    // on 注音九键, ㄓ taken off the bar reads ㄗ too with z ↔ zh on: ㄓ, then ㄐㄧ gives 自己 (zi ji)
    @Test
    @Uat("PY-164")
    fun aZhuyinInitialTakenReadsItsFuzzyPartner() {
        for (s in listOf(EngineSettings(fuzzy = setOf(Fuzzy.Z_ZH)), EngineSettings())) with(EngineReplay(settings(s))) {
            key(EngineReplay.symOf(nineCode('ㄓ')))
            pause()
            syllable("ㄓ")
            "ㄐㄧ".forEach { key(EngineReplay.symOf(nineCode(it))) }
            pause()
            assertEquals("ㄓ taken ${s.fuzzy}: ${candidates.take(FIRST)}", s.fuzzy.isNotEmpty(), "自己" in candidates.take(FIRST))
        }
    }

    // on 注音九键, ㄓ long-pressed (a symbol typed as itself) reads ㄗ too with z ↔ zh on: ㄓ, then ㄐㄧ gives 自己 (zi ji)
    @Test
    @Uat("PY-165")
    fun aZhuyinSymbolLongPressedReadsItsFuzzyPartner() {
        for (s in listOf(EngineSettings(fuzzy = setOf(Fuzzy.Z_ZH)), EngineSettings())) with(EngineReplay(settings(s))) {
            key(EngineReplay.symOf(Keyboard.zhuyinNineSymbol('ㄓ')))
            "ㄐㄧ".forEach { key(EngineReplay.symOf(nineCode(it))) }
            pause()
            assertEquals("ㄓ long-pressed ${s.fuzzy}: ${candidates.take(FIRST)}", s.fuzzy.isNotEmpty(), "自己" in candidates.take(FIRST))
        }
    }

    // 自然码 c taken off the bar reads ch too with c ↔ ch on: at the end 吃, then the IO key 戳 (chuo) beside 错; off, neither
    @Test
    @Uat("SP-64")
    fun anInitialTakenOnTheGroupedKeysReadsItsFuzzyPartner() {
        val on = EngineSettings(shuangpin = EngineSettings.ZIRANMA, fuzzy = setOf(Fuzzy.C_CH))
        for (letters in listOf(NineKeyLetters.G18, NineKeyLetters.NINE_SP)) {
            for (s in listOf(on, EngineSettings(shuangpin = EngineSettings.ZIRANMA))) with(EngineReplay(settings(s), im = InputMethodCatalog.SHUANGPIN)) {
                val fuzzy = s.fuzzy.isNotEmpty()
                typeOn(letters, "c")
                syllable("c")
                assertEquals("$letters c ${s.fuzzy}: $candidates", fuzzy, "吃" in candidates)
                typeOn(letters, "o")
                assertTrue("$letters co: $candidates", "错" in candidates.take(FIRST))
                assertEquals("$letters co ${s.fuzzy}: $candidates", fuzzy, "戳" in candidates)
            }
        }
    }

    private fun nineCode(c: Char) = Keyboard.zhuyinNineKey(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { c in it })

    private companion object {
        // the single characters compared: full pinyin's jing puts 金 15th
        const val FIRST = 30

        // a reading's tone marks, once decomposed
        val TONE = Regex("\\p{M}")

        val GROUPED = listOf(NineKeyLetters.G18, NineKeyLetters.G14, NineKeyLetters.NINE_SP)
    }
}
