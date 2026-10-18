/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.junit.Assert.assertEquals
import org.junit.Test

class ChaiziTableTest {

    // chaizi-jt.txt as the dictionary writes it: ways after tabs, a side radical as its character or not
    private val lines = sequenceOf(
        "犇\t牛 牛 牛",
        "湘\t水 相\t氵 相",
        "长\t长",
        "张\t弓 长",
        "□\t口 口",
        "森\t木 木 木\t木 林",
        "林\t木 木",
        "龘\t龍 龍 龍 龍 龍",
        "从\t人 人\t从 人",
    )

    private val readings = mapOf(
        "牛" to "niu", "水" to "shui", "相" to "xiang/xiang", "弓" to "gong", "长" to "chang/zhang",
        "木" to "mu", "林" to "lin", "龍" to "long", "人" to "ren", "口" to "kou",
    )

    @Test
    fun eachWayOfPartsTheDictionaryReadsSpellsTheCharacterByItsPartsLikeliestReadings() {
        val spellings = ChaiziTable.spellings(lines, { readings[it].orEmpty() })
        assertEquals(setOf("niuniuniu"), spellings["犇"])
        // 氵 read by no one: 水 is how it is typed
        assertEquals(setOf("shuixiang"), spellings["湘"])
        // 弓长张: 长 as it is likeliest read, not every way it may be
        assertEquals(setOf("gongchang"), spellings["张"])
        assertEquals(setOf("mumumu", "mulin"), spellings["森"])
        // no way that is itself or has itself, none of more parts than MAX_PARTS, no □
        assertEquals(setOf("张", "犇", "湘", "森", "林", "从"), spellings.keys)
        assertEquals(setOf("renren"), spellings["从"])
    }

    @Test
    fun aRadicalIsReadAsTheCharacterItStandsFor() {
        // the pinyin data reads 艹 likeliest as ao: 芭 is caoba, not aoba too
        val spellings = ChaiziTable.spellings(sequenceOf("芭\t草 巴\t艹 巴"), { mapOf("艹" to "ao/cao", "草" to "cao", "巴" to "ba")[it].orEmpty() })
        assertEquals(setOf("caoba"), spellings["芭"])
    }

    @Test
    fun theSideRadicalsWhoseLikeliestReadingIsAnotherAreReadAsTheirCharacters() {
        // dict_sc.txt's likeliest: 氵 san, 亻 dan, 扌 ti, 犭 fan, 刂 li, 灬 biao
        val read = mapOf(
            "氵" to "san/shui", "亻" to "dan/ren", "扌" to "ti/shou", "犭" to "fan/quan", "刂" to "li/dao", "灬" to "biao/huo",
            "水" to "shui", "人" to "ren", "手" to "shou", "犬" to "quan", "刀" to "dao", "火" to "huo", "可" to "ke",
        )
        val lines = sequenceOf("河\t氵 可", "何\t亻 可", "抲\t扌 可", "犄\t犭 可", "剞\t可 刂", "炣\t可 灬")
        val spellings = ChaiziTable.spellings(lines, { read[it].orEmpty() })
        assertEquals(
            mapOf("河" to setOf("shuike"), "何" to setOf("renke"), "抲" to setOf("shouke"), "犄" to setOf("quanke"), "剞" to setOf("kedao"), "炣" to setOf("kehuo")),
            spellings,
        )
    }

    @Test
    fun bySpellingTheCommonestFirstAtMostMax() {
        val found = ChaiziTable.bySpelling(mapOf("吅" to setOf("koukou"), "吕" to setOf("koukou"), "犇" to setOf("niuniuniu")), listOf("吕", "犇"))
        assertEquals(listOf("吕", "吅"), found["koukou"])
        val many = (1..Chaizi.MAX + 1).associate { "字$it" to setOf("mumu") }
        assertEquals(Chaizi.MAX, ChaiziTable.bySpelling(many, emptyList()).getValue("mumu").size)
    }
}
