/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ToneMark

/**
 * The tones of a dictionary word no curated source (`lexicon/tones.tsv`, the misreadings) reads, by rule
 * rather than a word at a time: the neutral tones the downloaded lists leave out.
 *
 * 1. A word no source lists takes the tones of the longest word it ends with that one does
 *    (有时候 as 时候 shí hou, 不好意思 as 意思 yì si, 小伙子 as 伙子 huǒ zi).
 * 2. Else, a word no source lists ending in 们, 么 or 子 has it neutral (朋友们, 怎么, 小本子), unless 子 is
 *    a person, a son, a seed or a particle there ([FULL_ZI]: 男子, 原子, 瓜子).
 *    A word no source lists ending in 为 has it wéi (沦为, 视为), but 因为.
 * 3. A kinship term said twice has the second neutral, whatever a source wrote (爷爷 yé ye, 舅舅 jiù jiu).
 * 4. So does a direction after a verb's (回来 huí lai, 下去 xià qu, 停下来 tíng xià lai; not 过去, the past),
 *    and a side after a place's (里边 lǐ bian, 外面 wài mian, 前头 qián tou): 《现代汉语词典》 marks them so.
 *
 * Rule 2 sets a neutral tone only where the character has one ([neutral]).
 */
object ToneRules {

    private val ENDINGS = mapOf('们' to Syllables.id("men"), '么' to Syllables.id("me"), '子' to Syllables.id("zi"))

    // 子 a person, a son, a seed or a particle: zǐ
    private val FULL_ZI = setOf(
        "男子", "女子", "君子", "孔子", "孟子", "老子", "庄子", "荀子", "墨子", "韩非子", "弟子", "才子", "学子", "游子", "赤子",
        "王子", "公子", "天子", "太子", "世子", "皇子", "父子", "母子", "孝子", "臣子", "诸子", "遗腹子", "独生子",
        "瓜子", "棋子", "莲子", "松子", "原子", "电子", "分子", "粒子", "量子", "质子", "中子", "离子", "光子",
        "因子", "卵子", "精子", "孢子", "介子", "芥子", "舍利子",
    )

    // kinship said twice: 爸爸, 妈妈, 爷爷, 奶奶, 姥姥, 哥哥, 姐姐, 弟弟, 妹妹, 叔叔, 婶婶, 舅舅, 姑姑, 姨姨, 公公, 婆婆, 伯伯
    private const val KIN = "爸妈爷奶姥哥姐弟妹叔婶舅姑姨公婆伯"

    /**
     * [word] (its code points [cps]) read as [reading]: [own] the tones a source gave it, null if none did;
     * [usual] its characters' usual tones; [listed] another word's listed tones for a reading, if any.
     */
    fun tones(
        word: String,
        cps: IntArray,
        reading: List<Int>,
        own: ByteArray?,
        usual: ByteArray,
        neutral: (codePoint: Int, syllable: Int) -> Boolean,
        listed: (String, List<Int>) -> ByteArray?,
    ): ByteArray {
        val out = (own ?: usual).copyOf()
        val n = cps.size
        if (n < 2) return out
        if (own == null) {
            // the longest listed word this one ends with
            val from = (1..n - 2).firstOrNull { listed(String(cps, it, n - it), reading.subList(it, n)) != null }
            val last = cps[n - 1]
            if (from != null) {
                listed(String(cps, from, n - from), reading.subList(from, n))!!.copyInto(out, from)
            } else if (ENDINGS[last.toChar()] == reading[n - 1] && neutralEnding(word, last, reading[n - 1], neutral)) {
                out[n - 1] = NEUTRAL
            }
        }
        if (own == null && wei(word, cps, reading)) out[n - 1] = 2
        if (grammarNeutral(cps)) out[n - 1] = NEUTRAL
        return out
    }

    private fun wei(word: String, cps: IntArray, reading: List<Int>) =
        cps.last() == '为'.code && reading.last() == WEI && word != "因为"

    private fun neutralEnding(word: String, last: Int, syllable: Int, neutral: (Int, Int) -> Boolean) =
        word !in FULL_ZI && neutral(last, syllable)

    // the dictionaries list no neutral tone for 公, 去, 边: the grammar's, not the character's
    private fun grammarNeutral(cps: IntArray): Boolean {
        val n = cps.size
        val pair = String(cps, n - 2, 2)
        val kin = n == 2 && cps[0] == cps[1] && cps[0].toChar() in KIN
        val direction = pair[0] in MOVED && pair[1] in DIRECTION && pair != "过去"
        return kin || direction || n == 2 && pair[0] in PLACE && pair[1] in SIDE
    }

    private const val MOVED = "上下进出回过起"
    private const val DIRECTION = "来去"
    private const val PLACE = "上下前后里外"
    private const val SIDE = "边面头"
    private val WEI = Syllables.id("wei")

    private const val NEUTRAL = ToneMark.NEUTRAL.toByte()
}
