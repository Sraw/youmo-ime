/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp

/**
 * [FcitxAPI] whose engine calls go to a real [engines]: what a settings page reads and changes
 * (readings, the user's words, English's words, imports, learning) is the engine's own answer, not
 * a table a test fills in, as [FakeFcitxAPI.readings] is. Everything else is [fake]'s: the config,
 * the input methods, the keys (recorded in [FakeFcitxAPI.calls]), the panel; typing through the
 * engine is lib/ime-eval's EngineReplay. [im] is the input method a learning op acts in, as
 * Fcitx's does in the one in use.
 */
class EngineBackedFcitxAPI(
    val engines: Engines,
    val fake: FakeFcitxAPI = FakeFcitxAPI(),
    var im: String = Engines.PINYIN,
) : FcitxAPI by fake {

    override suspend fun reloadEngine() {
        fake.reloadEngine()
        engines.reload()
    }

    override suspend fun userWords() = engines.userWords()
    override suspend fun userWords(kind: Engines.UserWord.Kind) = engines.userWords(kind)
    override suspend fun userWordCounts() = engines.userWordCounts()
    override suspend fun sameCodes(texts: List<String>, ims: List<String>) = engines.sameCodes(texts, ims)
    override suspend fun pinyinOf(text: String) = engines.pinyinOf(text)
    override suspend fun wordBoundaries(texts: List<String>) = engines.wordBoundaries(texts)
    override suspend fun logProbs(texts: List<String>) = engines.logProbs(texts)
    override suspend fun addUserWord(text: String, pinyin: String) = engines.addWord(text, pinyin)
    override suspend fun blockUserWord(text: String, pinyin: String) = engines.blockWord(text, pinyin)
    override suspend fun removeUserWords(words: List<Engines.UserWord>) = engines.removeWords(words)
    override suspend fun englishWords() = engines.englishWords()
    override suspend fun addEnglishWord(text: String) = engines.addEnglishWord(text)
    override suspend fun forgetEnglishWords(texts: List<String>) = engines.forgetEnglishWords(texts)
    override suspend fun importUserWords(lines: Sequence<String>, fileName: String) = engines.importWords(lines, fileName)
    override suspend fun importBatches() = engines.importBatches()
    override suspend fun importedWords(batch: Int?) = engines.importedWords(batch)
    override suspend fun dropImport(batch: Int) = engines.dropImport(batch)
    override suspend fun restoreImport(batch: Int) = engines.restoreImport(batch)
    override suspend fun tableKeys(im: String) = engines.tableKeys(im)
    override suspend fun learning(op: LearningOp) = engines.learning(im, op)

    override suspend fun engineContext(
        before: String,
        learning: Boolean,
        cleared: Boolean,
        learnFlags: Int,
        started: Boolean,
        after: String?,
        selected: Boolean,
        said: Boolean,
    ) {
        fake.engineContext(before, learning, cleared, learnFlags, started, after, selected, said)
        engines.context(im, before, learning, cleared, learnFlags, started, after, selected, said)
    }
}
