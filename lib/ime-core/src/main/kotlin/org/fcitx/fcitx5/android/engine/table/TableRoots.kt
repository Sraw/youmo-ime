/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/**
 * A table's roots (字根) on its letter keys: each key's [name] (仓颉's one root, 五笔's 键名) and
 * [all] of them. [inPreedit]: the code is shown as its roots too (日月 for ab), where a key has one
 * root and a code is spelt by them (仓颉); 五笔's keys hold many, its code stays letters.
 *
 * What a key shows is [TableOptions.keyLabels]'s, the keyboard asks the engine (TableKeyboard).
 */
class TableRoots(val names: Map<Char, String>, val all: Map<Char, List<String>>, val inPreedit: Boolean) {

    /** [code] as [inPreedit] shows it: each letter its root, one character for one (the cursor counts the same), the rest as typed. */
    fun spell(code: String): String {
        if (!inPreedit) return code
        val out = StringBuilder(code.length)
        for (c in code) out.append(names[c]?.singleOrNull() ?: c)
        return out.toString()
    }

    companion object {
        /** 仓颉's 24 roots and 難 (X) and 重 (Z), one a key: its name. */
        val CANGJIE: TableRoots = roots(
            "a日 b月 c金 d木 e水 f火 g土 h竹 i戈 j十 k大 l中 m一 n弓 o人 p心 q手 r口 s尸 t廿 u山 v女 w田 x難 y卜 z重",
        ).let { names -> TableRoots(names, names.mapValues { listOf(it.value) }, inPreedit = true) }

        /**
         * 王码五笔 86's: each key's 键名 and its roots, as zh.wikipedia's 五笔字型输入法 (§86版五笔字根)
         * gives the 口诀 and izywang.com/126.html explains them root by root. Only roots a character
         * of their own writes: 具字上部, 皮字上部, 羊底, 革底, 衣底, 业头, 高头, 母无点 and the like have none, and
         * a 口诀's 礻 and 衤 stand for them without their dots, as it says. Z has none: 五笔's 学习键.
         */
        val WUBI86: TableRoots = TableRoots(
            roots("g王 f土 d大 s木 a工 h目 j日 k口 l田 m山 t禾 r白 e月 w人 q金 y言 u立 i水 o火 p之 n已 b子 v女 c又 x纟"),
            mapOf(
                'g' to "王 龶 戋 五 一",
                'f' to "土 士 二 干 十 寸 雨",
                'd' to "大 犬 三 古 石 厂 丆 ナ 镸",
                's' to "木 丁 西 覀",
                'a' to "工 戈 艹 廾 廿 匚 七 弋",
                'h' to "目 上 止 卜 ⺊ 虍 丨",
                'j' to "日 曰 早 刂 虫",
                'k' to "口 川",
                'l' to "田 甲 囗 四 罒 皿 车 力",
                'm' to "山 由 贝 冂 几",
                't' to "禾 竹 ⺮ 丿 彳 攵 夂",
                'r' to "白 手 扌 龵 斤",
                'e' to "月 彡 乃 用 豕 爫",
                'w' to "人 亻 八 癶",
                'q' to "金 钅 勹 ⺈ 乂 儿 夕",
                'y' to "言 讠 文 方 广 亠 丶",
                'u' to "立 辛 六 门 疒 丬 冫 丷",
                'i' to "水 氵 氺 小 ⺌",
                'o' to "火 灬 米",
                'p' to "之 辶 廴 冖 宀 礻 衤",
                'n' to "已 巳 己 尸 心 忄 羽 乙",
                'b' to "子 孑 耳 了 也 卩 阝 凵",
                'v' to "女 刀 九 臼 彐 巛",
                'c' to "又 巴 马 厶",
                'x' to "纟 弓 匕 幺",
            ).mapValues { it.value.split(' ') },
            inPreedit = false,
        )

        // "a日 b月": a key's letter and its name
        private fun roots(pairs: String): Map<Char, String> = pairs.split(' ').associate { it[0] to it.substring(1) }
    }
}

/** What a table's letter keys show of its [TableRoots]: the letters alone, each key's name, all its roots. */
enum class KeyLabels(val key: String) {
    NONE("None"),
    NAMES("Names"),
    ROOTS("Roots"),
    ;

    companion object {
        /** By its [key], as the config has it; null for none of them. */
        fun of(key: String): KeyLabels? = entries.firstOrNull { it.key == key }
    }
}
