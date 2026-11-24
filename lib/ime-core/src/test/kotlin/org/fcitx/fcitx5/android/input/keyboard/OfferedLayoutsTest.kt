/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.CUSTOM
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.FULL
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.KEYS18
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards.Chosen
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards.TABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OfferedLayoutsTest {

    private val ziranma = ShuangpinScheme.ZIRANMA
    private val g18 = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")
    private val g14 = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")
    private val letters = CustomChoice("my-qwerty", "我的", "letters", "qwerty", tableExtras = true)
    private val k18 = CustomChoice("t1a2-k18", "薄荷 · 18 键", "grouped", "k18", g18)
    private val k14 = CustomChoice("my-k14", "14", "grouped", "k14", g14)
    private val all = listOf(letters, k18, k14)

    private fun offered(text: String) = OfferedLayouts.parse(text)!!

    @Test
    fun storedAsAHeadThenALineASlotAndReadBack() {
        val o = OfferedLayouts.NONE.with(PINYIN, "t1a2-k18").with(PINYIN, "my-qwerty").with(TABLE, "my-qwerty").with(ENGLISH, "my-qwerty")
        assertEquals("@1\npinyin t1a2-k18 my-qwerty\nenglish my-qwerty\ntable my-qwerty", o.serialize())
        assertEquals(o, offered(o.serialize()))
        assertEquals(listOf("my-qwerty"), o.of(TABLE))
        assertEquals("@1", OfferedLayouts.NONE.serialize())
        assertSame(o, o.with(PINYIN, "my-qwerty"))
        assertSame(o, o.without(SHUANGPIN, "my-qwerty"))
    }

    @Test
    fun nothingStoredIsNotNoneOn() {
        assertNull(OfferedLayouts.parse(null))
        assertNull("the copy's default", OfferedLayouts.parse(""))
        assertEquals(OfferedLayouts.NONE, offered("@1"))
        assertEquals(OfferedLayouts.NONE, offered("@1\n"))
    }

    @Test
    fun anIdNoFileCanHaveIsDroppedAndASlotOfALaterVersionKeptAsItWas() {
        val o = offered("@1\npinyin ../x my-k14  my-k14\nzhuyin z1\n\nwubi")
        assertEquals(listOf("my-k14"), o.of(PINYIN))
        assertEquals("@1\npinyin my-k14\nzhuyin z1\nwubi", o.serialize())
        assertEquals("@1\npinyin my-k14 my-qwerty\nzhuyin z1\nwubi", o.with(PINYIN, "my-qwerty").serialize())
    }

    @Test
    fun aLaterHeadIsReadAsNoneStoredAndWrittenOverAtTheNextChange() {
        assertNull(OfferedLayouts.parse("@2\npinyin my-k14"))
        val chosen = Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to "t1a2-k18"))
        val read = OfferedLayouts.read("@2\npinyin my-k14", chosen)
        assertEquals(listOf("t1a2-k18"), read.of(PINYIN))
        assertEquals("@1\npinyin t1a2-k18\nenglish my-qwerty", read.with(ENGLISH, "my-qwerty").serialize())
    }

    @Test
    @Uat("ST-226")
    fun anUpgradeKeepsEachInputMethodsCurrentOneAlone() {
        val chosen = Chosen(
            pinyin = CUSTOM,
            shuangpin = KEYS18,
            english = CUSTOM,
            table = CUSTOM,
            layouts = mapOf(PINYIN to "t1a2-k18", SHUANGPIN to "my-k14", ENGLISH to "my-qwerty", TABLE to "my-qwerty"),
        )
        val o = OfferedLayouts.migrate(chosen)
        assertEquals(listOf("t1a2-k18"), o.of(PINYIN))
        // 双拼 back on the app's keyboard: the layout it once chose is not kept
        assertEquals(emptyList<String>(), o.of(SHUANGPIN))
        assertEquals(listOf("my-qwerty"), o.of(ENGLISH))
        assertEquals(listOf("my-qwerty"), o.of(TABLE))
        assertEquals(OfferedLayouts.NONE, OfferedLayouts.migrate(Chosen()))
        assertEquals("one no file can have", OfferedLayouts.NONE, OfferedLayouts.migrate(Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to "../x"))))
        // a stored one is read as it is, the current one listed all the same
        assertEquals(emptyList<String>(), OfferedLayouts.read("@1", chosen).of(PINYIN))
    }

    @Test
    fun theListIsThoseOnAndTheCurrentOneInTheFilesOrder() {
        val o = OfferedLayouts.NONE.with(PINYIN, "my-k14").with(PINYIN, "my-qwerty")
        assertEquals(listOf(letters, k14), o.listed(PINYIN, Chosen(), all))
        // a stale backup's: the current one never hidden
        val current = Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to "t1a2-k18"))
        assertEquals(all, o.listed(PINYIN, current, all))
        assertEquals(emptyList<CustomChoice>(), o.listed(ENGLISH, current, all))
        // the tables share one
        assertEquals(listOf(letters), OfferedLayouts.NONE.with(TABLE, "my-qwerty").listed(WUBI, Chosen(), all))
        // one whose file is gone is no more listed, yet kept on
        assertEquals(listOf(letters), o.listed(PINYIN, Chosen(), listOf(letters, k18)))
        assertEquals(listOf("my-k14", "my-qwerty"), o.of(PINYIN))
    }

    @Test
    fun onUnderMicrosoftsSchemeTheFourteenKeysAreKeptOnAndListedOnceAnotherIsChosen() {
        val o = OfferedLayouts.NONE.with(SHUANGPIN, "my-k14")
        val listed = o.listed(SHUANGPIN, Chosen(shuangpin = KEYS18), all)
        assertEquals(listOf(k14), ImKeyboards.customs(SHUANGPIN, ziranma, listed))
        assertEquals(emptyList<CustomChoice>(), ImKeyboards.customs(SHUANGPIN, ShuangpinScheme.MICROSOFT, listed))
        assertEquals(listOf(SHUANGPIN), o.usedBy(Chosen(), "my-k14", k14, ziranma))
        assertEquals(emptyList<String>(), o.usedBy(Chosen(), "my-k14", k14, ShuangpinScheme.MICROSOFT))
    }

    @Test
    fun usedByIsWhereItIsOnAndUsableOrInUse() {
        val o = OfferedLayouts.NONE.with(PINYIN, k18.id).with(ENGLISH, k18.id)
        // English cannot type on grouped keys
        assertEquals(listOf(PINYIN), o.usedBy(Chosen(), k18.id, k18, ziranma))
        val inUse = Chosen(shuangpin = CUSTOM, layouts = mapOf(SHUANGPIN to k18.id))
        assertEquals(listOf(PINYIN, SHUANGPIN), o.usedBy(inUse, k18.id, k18, ziranma))
        // one that does not read: only where it is in use
        assertEquals(listOf(SHUANGPIN), o.usedBy(inUse, k18.id, null, ziranma))
    }

    @Test
    @Uat("ST-227", "ST-228")
    fun useForTicksAndAnImportsQuestion() = assertRows(
        "ticked anew is on, and one left ticked is not put back on it" to {
            val slots = ImKeyboards.usableBy(k18, ziranma)
            assertEquals(listOf(PINYIN, SHUANGPIN), slots)
            val pinyinOnOnly = OfferedLayouts.NONE.with(PINYIN, k18.id)
            // pinyin left ticked, typed on the 26 keys meanwhile: not put back on it
            val t = pinyinOnOnly.afterTicks(Chosen(), k18, ziranma, slots, listOf(PINYIN, SHUANGPIN), ask = false)
            assertEquals(mapOf(SHUANGPIN to CUSTOM), t.keyboards)
            assertEquals(listOf(k18.id), t.offered.of(SHUANGPIN))
            assertEquals(listOf(k18.id), t.offered.of(PINYIN))
        },
        "unticked is off, and back on its base where in use" to {
            val on = OfferedLayouts.NONE.with(PINYIN, k18.id).with(SHUANGPIN, k18.id)
            val inUse = Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to k18.id))
            val t = on.afterTicks(inUse, k18, ziranma, listOf(PINYIN, SHUANGPIN), emptyList(), ask = false)
            assertEquals(OfferedLayouts.NONE, t.offered)
            // pinyin was on it: its base; 双拼 was not: left as it is
            assertEquals(mapOf(PINYIN to KEYS18), t.keyboards)
        },
        "ticked on the current one a stale backup left off keeps it on" to {
            val inUse = Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to k18.id))
            val t = OfferedLayouts.NONE.afterTicks(inUse, k18, ziranma, listOf(PINYIN, SHUANGPIN), listOf(PINYIN), ask = false)
            assertEquals(emptyMap<String, ImKeyboard>(), t.keyboards)
            assertEquals(listOf(k18.id), t.offered.of(PINYIN))
        },
        "an import's question only adds: 先不用 on the same id imported again sends nothing back, turns nothing off" to {
            val inUse = Chosen(pinyin = CUSTOM, shuangpin = CUSTOM, layouts = mapOf(PINYIN to k18.id, SHUANGPIN to k18.id))
            val on = OfferedLayouts.NONE.with(PINYIN, k18.id)
            val none = on.afterTicks(inUse, k18, ziranma, listOf(SHUANGPIN), emptyList(), ask = true)
            assertEquals(on, none.offered)
            assertEquals(emptyMap<String, ImKeyboard>(), none.keyboards)
        },
        "an import's question ticked turns it on and uses it" to {
            val yes = OfferedLayouts.NONE.afterTicks(Chosen(), k18, ziranma, listOf(PINYIN), listOf(PINYIN), ask = true)
            assertEquals(mapOf(PINYIN to CUSTOM), yes.keyboards)
            assertEquals(listOf(k18.id), yes.offered.of(PINYIN))
        },
    )

    @Test
    fun aDeleteOrAFreshFileTurnsItOffEverywhere() {
        val o = OfferedLayouts.NONE.with(PINYIN, letters.id).with(ENGLISH, letters.id).with(TABLE, letters.id).with(PINYIN, k18.id)
        val gone = o.withoutAnywhere(letters.id)
        assertEquals(listOf(k18.id), gone.of(PINYIN))
        assertEquals("@1\npinyin ${k18.id}", gone.serialize())
        // the file new again under its id: in no list till 用在…
        assertEquals(emptyList<CustomChoice>(), gone.listed(ENGLISH, Chosen(english = FULL), all))
    }
}
