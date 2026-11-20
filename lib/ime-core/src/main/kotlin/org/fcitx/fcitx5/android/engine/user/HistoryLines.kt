/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/** A history of words typed before a measurement, as record rows: lines of `text` or `text<TAB>reading`, each committed once today. Shared by `ime-eval --history` and EngineEvalRunner so their timings count the same words; the APK cannot depend on :lib:ime-eval. */
object HistoryLines {

    fun forEachWord(data: PinyinData, visit: (word: Int, reading: String, weight: Float) -> Unit) {
        val dictionary = data.dictionary
        val path = ArrayList<String>()
        fun walk(node: Int) {
            if (path.isNotEmpty()) {
                val reading = path.joinToString(" ")
                for (w in 0 until dictionary.wordCount(node)) visit(dictionary.word(node, w), reading, dictionary.weight(node, w))
            }
            val first = dictionary.firstChild(node)
            for (child in first until first + dictionary.childCount(node)) {
                path += Syllables.spelling(dictionary.syllable(child))
                walk(child)
                path.removeAt(path.size - 1)
            }
        }
        walk(dictionary.root)
    }

    fun rows(user: UserModel, data: PinyinData, lines: List<String>): List<Row> {
        val wanted = lines.filter { it.isNotBlank() }.map { it.split('\t') }
        val readings = HashMap<String, ArrayList<String>>()
        wanted.filter { it.size == 1 }.forEach { readings[it[0]] = ArrayList(1) }
        if (readings.isNotEmpty()) forEachWord(data) { word, reading, _ -> readings[data.vocabulary.word(word)]?.add(reading) }
        val rows = ArrayList<Row>()
        for (line in wanted) {
            for (reading in if (line.size > 1) listOf(line[1]) else readings.getValue(line[0])) {
                if (HabitCounts.entry(line[0], reading)?.let(user::known) ?: NO_WORD == NO_WORD) continue
                rows += Row(Row.PICK or Row.WHOLE, Code(Kinds.FULL, reading.replace(" ", "")), "", Words(listOf(Word(line[0], reading))), 'A', Row.DICTIONARY, "")
            }
        }
        return rows
    }
}
