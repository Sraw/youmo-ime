/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserScorer
import java.io.File
import java.nio.ByteBuffer

/**
 * A table of [entries] ("code text") as the table tests build them: 五笔's keys, 码长 4 and its
 * 组词规则 for two, three and four characters or more, with [header] lines added or replaced.
 */
internal fun dictionary(vararg entries: String, header: Map<String, String> = emptyMap()) = CodeTable.Builder()
    .header("键码", "abcdefghijklmnopqrstuvwxy")
    .header("码长", "4")
    .apply { header.forEach { (k, v) -> header(k, v) } }
    .rule("e2", "p11+p12+p21+p22")
    .rule("e3", "p11+p21+p31+p32")
    .rule("a4", "p11+p21+p31+n11")
    .apply { entries.forEach { e -> e.split(' ').let { entry(it[0], it[1]) } } }
    .build()
    .toByteArray()
    .let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }

/**
 * A language over the fixture's word model learning into [learner], as 五笔's: what pins and blocks
 * under a code need. [habits] in place of the learner's (a test's that records or stands in), and
 * [blockedUnder] as [TableLanguage] has it.
 */
internal fun learning(
    learner: Learner = Learner(handwritingData),
    habits: Habits = learner.habits,
    blockedUnder: (Code) -> Set<String> = habits::blockedUnder,
): TableLanguage {
    val d = handwritingData
    val readings = Predictor(d.model, d.vocabulary, d.dictionary, learner.model)
    return TableLanguage(
        TextWords(d.model, d.wordIndex, UserScorer(d.model, learner.model)),
        learner.model,
        reading = { text -> d.wordIndex.find(text).takeIf { it != NO_WORD }?.let(readings::reading) },
        habits = habits,
        kind = Kinds.table("wubi"),
        blockedUnder = blockedUnder,
    )
}

/** A learning record holding what an older build saved in [im]: [phrases] as code and text (自动造词 is gone, U-28 C46). */
internal fun savedLog(vararg phrases: Pair<String, String>, im: String = "engine-wubi") = LearnLog(null).apply {
    absorb(phrases.mapIndexed { i, (code, text) -> LearnRecord.TableWordRow(im, code, text, i + 1) })
}

/** This table's saved phrases read from [log], a forget written back to it. */
internal fun TableDictionary.savedIn(log: LearnLog, im: String = "engine-wubi") = SavedPhrases(this, im, { log.state }, log::dropTableWords)

/** What an older build saved in this table: [phrases] as code and text. */
internal fun TableDictionary.savedBefore(vararg phrases: Pair<String, String>, im: String = "engine-wubi") = savedIn(savedLog(*phrases, im = im), im)

// the index on the disk: its file in a directory of the user's
internal fun CodedIndex.Companion.build(table: TableDictionary, dictionary: DictionaryFile, dest: File, key: String, wanted: () -> Boolean = { true }) =
    build(table, dictionary, JvmFiles(dest.parentFile), dest.name, key, wanted)

internal fun CodedIndex.Companion.open(file: File, key: String) = open(JvmFile(file), key)

/** Types [keys], returning every snapshot's commit joined and the last snapshot. */
internal fun Session.type(keys: String): Pair<String, Snapshot> {
    var commit = ""
    var last: Snapshot? = null
    for (c in keys) last = apply(Key(c)).also { commit += it.commit }
    return commit to last!!
}

/**
 * A table of [chars] characters from 一 on, each under a code of four of a…y, and every word of
 * two of them as a dictionary line (`text pin'yin weight`): many words a table codes, for the
 * merged dictionary's index ([CodedIndex]).
 */
internal fun manyWords(chars: Int): Pair<TableDictionary, Sequence<String>> {
    fun code(i: Int) = String(CharArray(4) { k -> 'a' + (i / pow25(k)) % 25 })
    val han = List(chars) { (0x4E00 + it).toChar() }
    // readings spread over the syllables: the compiler holds one reading's words at a time
    val syllables = (0 until Syllables.count).map(Syllables::spelling).filter { it[0].isLowerCase() }
    val reading = han.associateWith { syllables[it.code % syllables.size] }
    val table = dictionary(*Array(chars) { "${code(it)} ${han[it]}" })
    val words = han.asSequence().flatMap { a -> han.asSequence().map { b -> "$a$b ${reading[a]}'${reading[b]} ${1 + (a.code * 31 + b.code) % 1000}" } }
    return table to words
}

private fun pow25(k: Int): Int = (0 until k).fold(1) { p, _ -> p * 25 }
