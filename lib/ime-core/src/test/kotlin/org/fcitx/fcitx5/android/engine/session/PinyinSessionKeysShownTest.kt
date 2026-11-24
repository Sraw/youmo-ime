/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.MICROSOFT
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.XIAOHE
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZIRANMA
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双拼's code as the keys pressed (`keysShown`): on 26 keys the keys themselves, on the grouped keyboards the scheme's letters each key was read as. */
class PinyinSessionKeysShownTest {

    private val data = pinyinDataOf(
        Triple("你", "ni", -2f),
        Triple("好", "hao", -2.5f),
        Triple("你好", "ni hao", -2.5f),
        Triple("中", "zhong", -2.5f),
        Triple("国", "guo", -2.5f),
        Triple("中国", "zhong guo", -2.5f),
        Triple("星", "xing", -3f),
        Triple("知", "zhi", -3f),
        Triple("他", "ta", -2.5f),
        Triple("们", "men", -3f),
        Triple("词", "ci", -3f),
        Triple("西", "xi", -2f),
        Triple("句", "ju", -3f),
        Triple("杀", "sha", -2.5f),
    )

    private fun session(segmenter: Segmenter, keysShown: Boolean = true) =
        PinyinSession(data, segmenter, spell = true, keysShown = keysShown, prediction = false)

    private fun full(scheme: ShuangpinScheme = ZIRANMA, keysShown: Boolean = true) = session(ShuangpinSegmenter(scheme), keysShown)

    private fun grouped(letters: NineKeyLetters, scheme: ShuangpinScheme = ZIRANMA, keysShown: Boolean = true, fuzzy: Set<Fuzzy> = emptySet()) =
        session(GroupedShuangpinSegmenter(scheme, letters, fuzzy), keysShown)

    // the scheme's letters as [letters]' codes, `'` as itself
    private fun on(letters: NineKeyLetters?, keys: String) = if (letters == null) keys else keys.map { if (it == '\'') it else letters.key(it) }.joinToString("")

    private fun Session.type(keys: String, letters: NineKeyLetters? = null): Snapshot = on(letters, keys).map { apply(Key(it)) }.last()

    private fun Session.pick(text: String) = apply(Action.Pick(candidates(0, 50).indexOfFirst { it.text == text }))

    private val groupedBoards = listOf("18 键" to NineKeyLetters.G18, "14 键" to NineKeyLetters.G14, "九键" to NineKeyLetters.NINE_SP)

    private val boards = listOf<Pair<String, NineKeyLetters?>>("26 键" to null) + groupedBoards

    private fun board(letters: NineKeyLetters?, scheme: ShuangpinScheme = ZIRANMA, keysShown: Boolean = true) =
        if (letters == null) full(scheme, keysShown) else grouped(letters, scheme, keysShown)

    @Test
    @Uat("SP-60")
    fun eachKeyboardShowsTheSchemesKeysAndPinyinWithTheSwitchOff() = assertRows(
        *boards.flatMap { (name, letters) ->
            listOf(
                "$name 自然码 nihk: the keys" to { assertEquals("ni hk", board(letters).type("nihk", letters).preedit) },
                "$name 小鹤 nihc: the keys, its own" to { assertEquals("ni hc", board(letters, XIAOHE).type("nihc", letters).preedit) },
                "$name 自然码 vsgo: zh's key v, not zhong" to { assertEquals("vs go", board(letters).type("vsgo", letters).preedit) },
                "$name off: the pinyin, as before" to { assertEquals("ni hao", board(letters, keysShown = false).type("nihk", letters).preedit) },
            )
        }.toTypedArray(),
        // 微软 has no 14 keys
        "26 键 微软 x;: ing's key" to { assertEquals("x;", full(MICROSOFT).type("x;").preedit) },
        "18 键 微软 x;: ing's key on L;" to { assertEquals("x;", grouped(NineKeyLetters.G18, MICROSOFT).type("x;", NineKeyLetters.G18).preedit) },
        "九键 微软 x then 1: ing's key" to {
            val s = grouped(NineKeyLetters.NINE_SP, MICROSOFT)
            s.type("x", NineKeyLetters.NINE_SP)
            assertEquals("x;", s.apply(Key(NineKeyLetters.NINE_SP_SEMICOLON)).preedit)
        },
    )

    // the bar as the code: each offered one as it shows once taken, its pinyin for TalkBack; taking one reads as with the switch off
    @Test
    @Uat("SP-65")
    fun theBarShowsTheKeysTheCodeShowsAndTakesAsBefore() = assertRows(
        *groupedBoards.flatMap { (name, letters) ->
            fun bar(scheme: ShuangpinScheme, keys: String, keysShown: Boolean = true) = grouped(letters, scheme, keysShown).type(keys, letters)
            listOf(
                "$name 自然码 uani: sha ua, sh u" to {
                    val s = bar(ZIRANMA, "uani")
                    assertEquals(bar(ZIRANMA, "uani", keysShown = false).syllables, s.syllablesSaid)
                    assertEquals(listOf("ua", "u"), listOf("sha", "sh").map { s.syllables[s.syllablesSaid.indexOf(it)] })
                },
                "$name 小鹤 xkni: xing xk, x x" to {
                    val s = bar(XIAOHE, "xkni")
                    assertEquals(listOf("xk", "x"), listOf("xing", "x").map { s.syllables[s.syllablesSaid.indexOf(it)] })
                },
                "$name off: pinyin, nothing said apart" to {
                    val s = bar(ZIRANMA, "uani", keysShown = false)
                    assertTrue(s.syllables.toString(), "sha" in s.syllables && "sh" in s.syllables)
                    assertEquals(emptyList<String>(), s.syllablesSaid)
                },
                "$name ua taken: as sha taken with the switch off, the code its keys" to {
                    val on = grouped(letters)
                    val took = on.type("uani", letters).let { on.apply(Action.Syllable(it.syllables.indexOf("ua"))) }
                    val off = grouped(letters, keysShown = false)
                    val offTook = off.type("uani", letters).let { off.apply(Action.Syllable(it.syllables.indexOf("sha"))) }
                    assertEquals(offTook.candidates, took.candidates)
                    assertEquals("杀你", took.candidates[0])
                    assertTrue(took.preedit, took.preedit.startsWith("ua"))
                    assertEquals(emptyList<String>(), offTook.syllablesSaid)
                    assertEquals(offTook.syllables, took.syllablesSaid)
                },
            )
        }.toTypedArray(),
    )

    @Test
    @Uat("SP-60")
    fun initialsAPickAndSeparatorsShowAsKeysToo() = assertRows(
        *boards.flatMap { (name, letters) ->
            listOf(
                "$name 简拼 vsg: g alone is its key" to { assertEquals("vs g", board(letters).type("vsg", letters).preedit) },
                "$name 你 picked of nihk: the rest still keys" to {
                    val s = board(letters)
                    s.type("nihk", letters)
                    assertEquals("你hk", s.pick("你").preedit)
                },
                "$name ni'hk: the separator kept" to { assertEquals("ni'hk", board(letters).type("ni'hk", letters).preedit) },
            )
        }.toTypedArray(),
    )

    // the cursor goes by the keys: one key back is between h and k, a tap after n is after n
    @Test
    @Uat("SP-60")
    fun theCursorIsAmongTheKeysShown() = assertRows(
        *boards.flatMap { (name, letters) ->
            listOf(
                "$name one key back" to {
                    val s = board(letters)
                    s.type("nihk", letters)
                    val shown = s.apply(Action.Step(-1))
                    assertEquals("ni h", shown.preedit.take(shown.cursor))
                },
                "$name a tap after n" to {
                    val s = board(letters)
                    s.type("nihk", letters)
                    assertEquals(1, s.apply(Action.Cursor(1)).cursor)
                },
            )
        }.toTypedArray(),
    )

    // a taken syllable shows fewer letters than its keys: the cursor stops only where the code can show it
    @Test
    @Uat("SP-60")
    fun theCursorNeverStopsInsideATakenSyllable() = assertRows(
        *groupedBoards.flatMap { (name, letters) ->
            // vsgo with zhong taken off the bar (ni first when typed before it)
            fun taken(ni: Boolean = false): Session = grouped(letters).also { s ->
                var shown = s.type(if (ni) "nivsgo" else "vsgo", letters)
                if (ni) shown = s.apply(Action.Syllable(shown.syllablesSaid.indexOf("ni")))
                s.apply(Action.Syllable(shown.syllablesSaid.indexOf("zhong")))
            }
            fun Snapshot.before() = preedit.take(cursor.takeIf { it >= 0 } ?: preedit.length)
            listOf(
                "$name zhong taken: each step back moves" to {
                    val s = taken()
                    val seen = (1..6).map { s.apply(Action.Step(-1)).before() }
                    // after zhong, before its `'`, then straight to the start
                    assertEquals(listOf("vs'g", "vs'", "vs", "", "", ""), seen)
                },
                "$name zhong taken: a step forward from the start passes it" to {
                    val s = taken()
                    s.apply(Action.Step.START)
                    assertEquals("vs", s.apply(Action.Step(1)).before())
                },
                "$name zh taken: a step back goes before it" to {
                    val s = grouped(letters)
                    val typed = s.type("vs", letters)
                    s.apply(Action.Syllable(typed.syllablesSaid.indexOf("zh")))
                    assertEquals("", s.apply(Action.Step(-1)).before())
                },
                "$name zhong taken, a tap between v and s, then a key: zhong kept" to {
                    val s = taken()
                    s.apply(Action.Cursor(1))
                    assertTrue(s.apply(Key(letters.key('n'))).preedit.endsWith("vs'go"))
                },
                "$name zhong taken, a tap between v and s, then Backspace: zhong kept" to {
                    val s = taken()
                    s.apply(Action.Cursor(1))
                    assertEquals("vs'go", s.apply(Action.Backspace).preedit)
                },
                // what is left of zhong reads no more: its letters, each a place the cursor shows
                "$name ni and zhong taken, Backspace right of zhong" to {
                    val s = taken(ni = true)
                    repeat(3) { s.apply(Action.Step(-1)) }
                    assertEquals("ni'zhon", s.apply(Action.Backspace).before().replace(" ", ""))
                    assertEquals("ni'zho", s.apply(Action.Step(-1)).before().replace(" ", ""))
                },
            )
        }.toTypedArray(),
    )

    @Test
    @Uat("SP-60")
    fun backspaceLeavesTheRestAsKeys() = assertRows(
        *boards.flatMap { (name, letters) ->
            listOf(
                "$name nihk, Backspace" to {
                    val s = board(letters)
                    s.type("nihk", letters)
                    assertEquals("ni h", s.apply(Action.Backspace).preedit)
                },
                "$name nihk, h taken out from the middle: ni still keys" to {
                    val s = board(letters)
                    s.type("nihk", letters)
                    s.apply(Action.Step(-1))
                    assertTrue(s.apply(Action.Backspace).preedit.startsWith("ni "))
                },
            )
        }.toTypedArray(),
        // the last taken given back first: the keys again
        "18 键 zhong taken, Backspace at the end" to {
            val s = grouped(NineKeyLetters.G18)
            val typed = s.type("vsgo", NineKeyLetters.G18)
            s.apply(Action.Syllable(typed.syllablesSaid.indexOf("zhong")))
            assertEquals("vs go", s.apply(Action.Backspace).preedit)
        },
    )

    // ü typed with its own key (自然码 v) is a typo read as ju, not a slip: the key pressed shows
    @Test
    @Uat("SP-60")
    fun aUTypedAsUmlautShowsTheKeyPressed() {
        val shown = full().type("jv")
        assertEquals("句", shown.candidates[0])
        assertEquals("jv", shown.preedit)
    }

    @Test
    @Uat("SP-60")
    fun aGroupedKeyShowsTheLetterReadNotTheGroup() = assertRows(
        // XC IO, c taken off the bar: the bound final's letter, 自然码 ci
        "18 键 an initial taken" to {
            val s = grouped(NineKeyLetters.G18)
            val typed = s.type("ci", NineKeyLetters.G18)
            assertEquals("ci", s.apply(Action.Syllable(typed.syllablesSaid.indexOf("c"))).preedit)
        },
        // zh taken off V: its key, 自然码 v
        "18 键 zh taken" to {
            val s = grouped(NineKeyLetters.G18)
            val typed = s.type("vs", NineKeyLetters.G18)
            val zh = s.apply(Action.Syllable(typed.syllablesSaid.indexOf("zh")))
            assertEquals("vs", zh.preedit)
        },
        // a whole syllable taken: its code, then the rest's keys
        "18 键 zhong taken" to {
            val s = grouped(NineKeyLetters.G18)
            val typed = s.type("vsgo", NineKeyLetters.G18)
            assertEquals("vs'go", s.apply(Action.Syllable(typed.syllablesSaid.indexOf("zhong"))).preedit)
        },
        // fuzzy z ↔ zh: ZX UI read as zhi shows z i, the letters pressed: neither zhi's v nor the keys' first letters (zu)
        "14 键 fuzzy zhi off z" to {
            val s = PinyinSession(pinyinDataOf(Triple("知", "zhi", -3f)), GroupedShuangpinSegmenter(ZIRANMA, NineKeyLetters.G14, setOf(Fuzzy.Z_ZH)), spell = true, keysShown = true, prediction = false)
            val shown = s.type("zi", NineKeyLetters.G14)
            assertEquals("知", shown.candidates[0])
            assertEquals("zi", shown.preedit)
        },
        "九键 nihk on 6 4 4 5: the letters read, not the keys' first letters (mg gj)" to { assertEquals("ni hk", grouped(NineKeyLetters.NINE_SP).type("nihk", NineKeyLetters.NINE_SP).preedit) },
    )

    // a slipped key shows as the key meant, as the pinyin does
    @Test
    @Uat("SP-60")
    fun aSlippedKeyShowsAsTheKeyMeant() = assertRows(
        // 小鹤 tq (tiu spells nothing) with q tapped by a: ta
        "26 键 小鹤 tq toward a" to {
            val s = session(ShuangpinSegmenter(XIAOHE, neighbours = true))
            val shown = s.tap("tq", listOf(TouchBoxes.tap(TouchBoxes.QWERTY, 't'), TouchBoxes.tap(TouchBoxes.QWERTY, 'q', dx = 0.3f, dy = 0.45f)))
            assertEquals("他", shown.candidates[0])
            assertEquals("ta", shown.preedit)
        },
        // 小鹤 [BN][F] with BN tapped toward M: 们 men, its keys mf
        "18 键 小鹤 BN F toward M" to {
            val letters = NineKeyLetters.G18
            val s = session(GroupedShuangpinSegmenter(XIAOHE, letters, neighbours = true))
            val shown = s.tap("bf".map(letters::key).joinToString(""), listOf(TouchBoxes.tap(TouchBoxes.G18, letters.key('b'), dx = 0.45f), TouchBoxes.tap(TouchBoxes.G18, letters.key('f'))))
            assertEquals("们", shown.candidates[0])
            assertEquals("mf", shown.preedit)
        },
    )

    // Enter puts in what the code shows: the keys, on the grouped keyboards the letters read
    @Test
    @Uat("SP-60")
    fun enterPutsInTheKeysShown() = assertRows(
        *boards.map { (name, letters) ->
            "$name nihk" to {
                val s = board(letters)
                s.type("nihk", letters)
                assertEquals("nihk", s.apply(Action.CommitRaw).commit)
            }
        }.toTypedArray(),
    )

    private fun Session.tap(keys: String, touches: List<Touch?>): Snapshot = keys.indices.map { apply(Key(keys[it], touch = touches[it])) }.last()
}
