/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import org.fcitx.fcitx5.android.engine.host.Engines
import java.util.Random

/**
 * Random parity sequences from a seed (fcitx-replacement §7.2): typing in pinyin, the nine keys,
 * English and a table, marks, candidates picked, deletes, hardware keys, input method, field and app
 * switches. The same seed gives the same sequences on every machine ([java.util.Random] is specified).
 *
 * Every step is one the device driver can do on any screen it leads to, so a sequence never stops
 * for want of a key: the nine keys type digits and only in a text field (a password, URL or email
 * field shows letters), marks only where the bottom row has `,` and `.` (not the nine keys, not a
 * URL or email field), no input method switch in a password field (it types English there).
 */
class SequenceGenerator(private val ims: List<String>, seed: Long) {
    private val random = Random(seed)
    private val prefix = "r$seed-"

    init {
        require(ims.isNotEmpty()) { "no input methods" }
    }

    fun generate(count: Int): List<Sequence> = (1..count).map { one("$prefix$it") }

    private class At(var im: String, var field: String, var composing: Boolean = false, var hardware: Boolean = false)

    private fun one(id: String): Sequence {
        val at = At(ims[random.nextInt(ims.size)], "text")
        val start = at.im
        val steps = ArrayList<Step>()
        repeat(MIN_STEPS + random.nextInt(MAX_STEPS - MIN_STEPS + 1)) { steps += next(at) }
        return Sequence(id, start, "text", steps)
    }

    private fun next(at: At): Step {
        val nine = at.im == T9 && at.field == "text"
        val marks = !nine && at.field in MARK_FIELDS
        val choices = buildList {
            add(Weighted(TYPE) { typing(at, nine).also { at.composing = true } })
            if (marks) add(Weighted(MARK) { Step("type", if (random.nextBoolean()) "," else ".").also { at.composing = false } })
            add(Weighted(if (at.composing) PICK_COMPOSING else PICK_IDLE) { Step("pick", "${1 + random.nextInt(PICKS)}") })
            add(Weighted(SPACE) { Step("space").also { at.composing = false } })
            add(Weighted(BKSP) { Step("bksp", "${1 + random.nextInt(BKSPS)}") })
            add(Weighted(ENTER) { Step("enter").also { at.composing = false } })
            add(Weighted(HW) { Step("hw", Step.HW_KEYS[random.nextInt(Step.HW_KEYS.size)]).also { at.hardware = true } })
            if (at.field != "password" && ims.size > 1) add(Weighted(IME) { switchIm(at) })
            add(Weighted(FIELD) { switchField(at) })
            add(Weighted(APP) { Step("app").also { at.field = "text"; at.composing = false } })
            add(Weighted(PAUSE) { Step("pause") })
            addAll(toolbar(at))
        }
        var roll = random.nextInt(choices.sumOf { it.weight })
        for (c in choices) {
            roll -= c.weight
            if (roll < 0) return c.make()
        }
        error("unreachable")
    }

    // the toolbar's and the held keys' steps, where the screen has them
    private fun toolbar(at: At): List<Weighted> = buildList {
        // a hardware key puts the touch keyboard away for the floating bar, which has no toolbar
        // nor 符 key: none for the rest of the sequence, whatever brings the keyboard back
        if (at.hardware) return@buildList
        // the toolbar's status area is over the candidates only when nothing is composing
        if (at.im in CHINESE && at.field == "text" && !at.composing) {
            add(Weighted(TOGGLE) { Step("toggle", Step.TOGGLES[random.nextInt(Step.TOGGLES.size)]) })
        }
        // the 符 and 123 keys held: the full keyboard's, not the nine keys'
        if (at.im != T9 && at.field == "text" && !at.composing) {
            add(Weighted(TEMP) { Step(if (random.nextBoolean()) "quick" else "unicode").also { at.composing = true } })
        }
    }

    private class Weighted(val weight: Int, val make: () -> Step)

    private fun switchIm(at: At): Step {
        // the nine keys only where they show: another input method's field kinds are kept for it
        val to = ims.filter { it != at.im && (it != T9 || at.field == "text") }
        if (to.isEmpty()) return Step("pause")
        at.im = to[random.nextInt(to.size)]
        at.composing = false
        return Step("ime", at.im)
    }

    private fun switchField(at: At): Step {
        val kinds = if (at.im == T9) listOf("text") else Step.FIELDS
        at.field = kinds[random.nextInt(kinds.size)]
        at.composing = false
        return Step("field", at.field)
    }

    private fun typing(at: At, nine: Boolean): Step {
        val keys = when {
            at.field != "text" || at.im == ENGLISH -> WORDS[random.nextInt(WORDS.size)]
            nine -> syllables(1 + random.nextInt(MAX_SYLLABLES)).joinToString("") { NINE.getValue(it) }
            at.im in TABLES -> (1..1 + random.nextInt(MAX_CODE)).map { 'a' + random.nextInt(TABLE_LETTERS) }.joinToString("")
            else -> syllables(1 + random.nextInt(MAX_SYLLABLES)).joinToString("")
        }
        return Step("type", keys)
    }

    private fun syllables(n: Int) = (1..n).map { SYLLABLES[random.nextInt(SYLLABLES.size)] }

    companion object {
        const val PINYIN = Engines.PINYIN
        const val T9 = Engines.T9
        const val WUBI = Engines.WUBI
        const val ENGLISH = Engines.ENGLISH

        /** The input methods a device's base state has, in its order: pinyin and English on a new install, the nine keys and 五笔 added. */
        val DEFAULT_IMS = listOf(PINYIN, ENGLISH, T9, WUBI)

        private val TABLES = Engines.TABLES.keys
        private val MARK_FIELDS = setOf("text", "password")
        private val CHINESE = setOf(PINYIN, T9, Engines.SHUANGPIN) + TABLES

        // the weights of the steps
        private const val TYPE = 34
        private const val MARK = 6
        private const val PICK_COMPOSING = 14
        private const val PICK_IDLE = 3
        private const val SPACE = 10
        private const val BKSP = 9
        private const val ENTER = 3
        private const val HW = 4
        private const val IME = 5
        private const val FIELD = 4
        private const val APP = 2
        private const val PAUSE = 3
        private const val TOGGLE = 3
        private const val TEMP = 2

        private const val MIN_STEPS = 4
        private const val MAX_STEPS = 20
        private const val PICKS = 3
        private const val BKSPS = 3
        private const val MAX_SYLLABLES = 4
        private const val MAX_CODE = 4

        // a table's codes are a to y (z is its wildcard key)
        private const val TABLE_LETTERS = 25

        private val SYLLABLES = listOf(
            "ni", "hao", "wo", "men", "shi", "de", "zai", "you", "le", "bu", "ta", "zhe", "ge", "ren", "da",
            "shang", "lai", "dao", "guo", "jiu", "yao", "hui", "ke", "yi", "xia", "dui", "sheng", "zhong",
            "xue", "jia", "tian", "qi", "qu", "gong", "yuan", "kan", "shuo", "xiang", "zuo", "ming", "nian",
            "zhi", "chu", "fa", "dian", "hua", "wen", "ti", "mei", "xian", "lv", "nv", "e", "ai", "an", "ou",
        )

        private val WORDS = listOf(
            "the", "hello", "world", "test", "input", "method", "phone", "keyboard", "good", "morning",
            "thanks", "today", "where", "what", "time", "home", "work", "please", "send", "message",
        )

        private val NINE: Map<String, String> = SYLLABLES.associateWith { s ->
            s.map { c -> "22233344455566677778889999"[c - 'a'] }.joinToString("")
        }
    }
}
