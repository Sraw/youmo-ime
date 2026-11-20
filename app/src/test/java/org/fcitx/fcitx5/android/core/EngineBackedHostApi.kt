/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.reading.CardReading
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones

/** [HostApi] whose lexicon and learning calls go to a real [engines]; the rest is [fake]'s. [im] is the input method a learning op acts in. */
class EngineBackedHostApi(
    val engines: Engines,
    val fake: FakeHostApi = FakeHostApi(),
    var im: String = InputMethodCatalog.PINYIN,
) : HostApi by fake {

    override suspend fun reloadEngine() {
        fake.reloadEngine()
        engines.reload()
    }

    override suspend fun userWords() = engines.userWords()
    override suspend fun userWords(kind: UserWord.Kind) = engines.userWords(kind)
    override suspend fun userWordCounts() = engines.userWordCounts()
    override suspend fun sameCodes(texts: List<String>, ims: List<String>) = engines.sameCodes(texts, ims)
    override suspend fun pinyinOf(text: String) = engines.pinyinOf(text)
    override suspend fun rareHeld(texts: Collection<String>) = engines.rareHeld(texts)
    override suspend fun rareGrid() = engines.rareGrid()
    override suspend fun pinyinsOf(texts: List<String>) = engines.pinyinsOf(texts)
    override suspend fun candidateCard(index: Int, text: String, paged: Boolean) = engines.card(im, index, text, paged)
    override suspend fun charCardOf(char: String, reading: CardReading?) = engines.charCardOf(char, reading)
    override suspend fun wordBoundaries(texts: List<String>) = engines.wordBoundaries(texts)
    override suspend fun logProbs(texts: List<String>) = engines.logProbs(texts)
    override suspend fun voiceHomophones(words: List<UserWord>) = engines.voiceHomophones(words)
    override suspend fun voicePick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>) = engines.voicePick(homophones, text, before, blocked)
    override suspend fun voiceStretches(texts: List<String>) = engines.voiceStretches(texts)
    override suspend fun addUserWord(text: String, pinyin: String) = engines.addWord(text, pinyin)
    override suspend fun voiceSaid(text: String, before: String?, hinted: Boolean) = engines.voiceSaid(text, before, hinted)
    override suspend fun voicePutIn(text: String) = engines.voicePutIn(text)
    override suspend fun voiceConfirm(before: String?, after: String?) = engines.voiceConfirm(before, after)
    override suspend fun voiceEditsEnd() = engines.voiceEditsEnd()
    override suspend fun voiceRemember(ask: VoiceEdits.Ask) = engines.voiceRemember(ask)
    override suspend fun voiceForget(ask: VoiceEdits.Ask) = engines.voiceForget(ask)
    override suspend fun blockUserWord(text: String, pinyin: String) = engines.blockWord(text, pinyin)
    override suspend fun removeUserWords(words: List<UserWord>) = engines.removeWords(words)
    override suspend fun englishWords() = engines.englishWords()
    override suspend fun addEnglishWord(text: String) = engines.addEnglishWord(text)
    override suspend fun forgetEnglishWords(texts: List<String>) = engines.forgetEnglishWords(texts)
    override suspend fun importUserWords(lines: Sequence<String>, fileName: String) = engines.importWords(lines, fileName)
    override suspend fun importBatches() = engines.importBatches()
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
