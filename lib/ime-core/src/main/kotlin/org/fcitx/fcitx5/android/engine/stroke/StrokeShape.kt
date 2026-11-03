/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

/**
 * A stroke's shape as 《GB13000.1字符集汉字折笔规范》 and 《现代汉语通用字笔顺规范》 name them, for a
 * character's card: [code] is the stroke's abbreviation in Unicode's names of the CJK Strokes block
 * (`CJK STROKE HZG` 横折钩), how `lexicon/stroke-order.tsv` writes it; [kind] the standard's number of it,
 * 1 横 2 竖 3 撇 4 点 5 折 (提 is 横, 竖钩 竖, 捺 点), which stroke typing and the checks go by; [shown]
 * the character the card draws it with. Unicode 16's ㇤ (HXG) and ㇥ (SZP) are not in the phone's
 * fonts yet (Noto Sans CJK 2.004): ⺄ and ㄣ, their closest shapes there, stand in.
 *
 * The order of the entries is charinfo.data's (each stroke its index): add new ones at the end.
 */
enum class StrokeShape(val code: String, val kind: Int, val shown: Char) {
    H("H", 1, '一'), // 横
    T("T", 1, '㇀'), // 提
    S("S", 2, '丨'), // 竖
    SG("SG", 2, '亅'), // 竖钩
    P("P", 3, '丿'), // 撇
    D("D", 4, '丶'), // 点
    N("N", 4, '㇏'), // 捺
    HZ("HZ", 5, '㇕'), // 横折 口
    HP("HP", 5, '㇇'), // 横撇 又
    HG("HG", 5, '㇖'), // 横钩 冖
    HZG("HZG", 5, '㇆'), // 横折钩 月
    HZT("HZT", 5, '㇊'), // 横折提 讠
    HZW("HZW", 5, '㇍'), // 横折弯 朵
    HZZ("HZZ", 5, '㇅'), // 横折折 凹
    HXG("HXG", 5, '⺄'), // 横斜钩 风
    HZWG("HZWG", 5, '㇈'), // 横折弯钩 九
    HPWG("HPWG", 5, '㇌'), // 横撇弯钩 阝
    HZZP("HZZP", 5, '㇋'), // 横折折撇 及
    HZZZG("HZZZG", 5, '㇡'), // 横折折折钩 乃
    HZZZ("HZZZ", 5, '㇎'), // 横折折折 凸
    SZ("SZ", 5, '㇗'), // 竖折 山
    SW("SW", 5, '㇄'), // 竖弯 四
    ST("ST", 5, '㇙'), // 竖提 以
    SZP("SZP", 5, 'ㄣ'), // 竖折撇 专
    SZZ("SZZ", 5, '㇞'), // 竖折折 鼎
    SWG("SWG", 5, '乚'), // 竖弯钩 七
    SZWG("SZWG", 5, '㇉'), // 竖折折钩 马 (Unicode's name: 竖折弯钩)
    PZ("PZ", 5, '㇜'), // 撇折 云
    PD("PD", 5, '㇛'), // 撇点 女
    WG("WG", 5, '㇁'), // 弯钩 犭
    XG("XG", 5, '㇂'), // 斜钩 戈
    BXG("BXG", 5, '㇃'), // 卧钩 心

    /**
     * A stroke of one of the five kinds only, rime-stroke's order of a character the mainland's
     * table lacks (a traditional one): shown as the kind, 一丨丿丶乛.
     */
    ZHE("", 5, '乛'),
    ;

    companion object {
        private val byCode = entries.filter { it.code.isNotEmpty() }.associateBy { it.code }
        private val kinds = arrayOf(H, S, P, D, ZHE)

        /** The shape written [code] (`HZG`), null for none: a kind alone is no shape. */
        fun of(code: String): StrokeShape? = byCode[code]

        /** The shapes written [codes], a space between (`P P S H H SG`); a code that is none fails. */
        fun parse(codes: String): List<StrokeShape> = codes.split(' ').filter { it.isNotEmpty() }.map { requireNotNull(of(it)) { "not a stroke's shape: $it" } }

        /** A stroke known by its kind only, a [Strokes.KEYS] letter (rime-stroke's `z`: [ZHE]). */
        fun ofKey(key: Char): StrokeShape = kinds[Strokes.KEYS.indexOf(key).also { require(it >= 0) { "not a stroke: $key" } }]

        /** [shapes] as [Strokes.KEYS] letters, a kind a stroke: rime-stroke's way of writing them. */
        fun keys(shapes: List<StrokeShape>): String = shapes.joinToString("") { Strokes.KEYS[it.kind - 1].toString() }

        /** [shapes] as the card draws them: 行 丿丿丨一一亅. */
        fun shown(shapes: List<StrokeShape>): String = shapes.joinToString("") { it.shown.toString() }
    }
}
