/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MisreadingsTest {

    @Test
    fun aLineIsAWordItsReadingAndItsMisreadings() {
        val list = Misreadings.parse(sequenceOf("# a comment", "", "恫吓\tdòng hè\ttong'xia,dong'xia"), "list")
        val entry = list.single()
        assertEquals("恫吓", entry.word)
        assertEquals("dòng hè", entry.reading)
        assertEquals("dong'he", entry.typed)
        assertEquals(listOf("tong'xia", "dong'xia"), entry.misread)
    }

    @Test
    fun anEntryGoesIntoPinyinDataAndBack() {
        val entry = Misreadings("恫吓", "dòng hè", listOf("tong'xia", "dong'xia"))
        val back = Misreadings.fromMeta("恫吓", Misreadings.meta(entry))!!
        assertEquals(entry.reading, back.reading)
        assertEquals(entry.misread, back.misread)
        assertEquals(null, Misreadings.fromMeta("恫吓", "dòng hè"))
    }

    @Test
    fun tonesGoAndUmlautIsV() {
        assertEquals("bo're", Misreadings.toneless("bō rě"))
        assertEquals("nv'lv", Misreadings.toneless("nǚ lǜ"))
        assertEquals("han'chen", Misreadings.toneless(" hán  chen "))
    }

    @Test
    fun aLineThatIsNoneSaysWhichAndWhy() {
        fun error(line: String) = assertThrows(SourceException::class.java) { Misreadings.parse(sequenceOf("#", line), "list") }
        assertEquals(2, error("般若\tbō rě").line)
        assertTrue(error("般若\tbō\tban'ruo").message!!.contains("a syllable a character"))
        assertTrue(error("般若\tbō rě\tban'ruo,bo're").message!!.contains("is the reading"))
    }

    @Test
    fun theListShippedReadsAndEveryReadingIsSyllables() {
        val file = File("../../lexicon/misreadings.tsv")
        val list = file.useLines { Misreadings.parse(it, file.path) }
        assertTrue(list.size > 100)
        assertEquals(list.size, list.map { it.word }.toSet().size)
        for (entry in list) {
            for (reading in listOf(entry.typed) + entry.misread) {
                assertTrue("${entry.word} $reading", reading.split('\'').all { Syllables.id(it) >= 0 })
            }
        }
    }
}
