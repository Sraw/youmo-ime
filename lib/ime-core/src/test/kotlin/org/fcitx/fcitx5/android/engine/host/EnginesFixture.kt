/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.TinyModel
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** What [EnginesTest] and the like type with: the data, served as the app's assets serve it ([load]). */
open class EnginesFixture {

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -3f, 0f)
        .unigram("好", -2.5f, 0f)
        .bigram("好", "拟", -0.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        .build()
        .toByteArray()

    private val wubi = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "妤")
        .apply { "一二三四五六七".forEachIndexed { i, c -> entry("va" + ('a' + i) + "a", c.toString()) } }
        .build()
        .toByteArray()

    private val english = EnglishDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram(EnglishText.START, -9f, -0.5f)
        .unigram("the", -1.3f, 0f)
        .unigram("you", -1.8f, 0f)
        .build()
        .toByteArray()

    private var model: ByteArray? = TinyModel().bytes()
    private var refining: ByteArray? = TinyModel().bytes()

    protected var strokes: ByteArray? = Strokes.read("...\n你\tpsnpzsn\n好\tzphzsh\n".reader().buffered(), "stroke")
        .build().toByteArray()

    // none of a word: a test of them puts its own
    protected var emoji: ByteArray? = EmojiData.format(emptyList(), emptyMap()).toByteArray()

    protected val loaded = ArrayList<String>()

    // Engines' warm-ups run as they are handed over, on the test's thread: what they load is in
    // [loaded] before a test looks, and no thread is left appending to it after the test
    protected val inPlace = Executor { it.run() }

    // 五笔 shown, not a code's one candidate committing itself and leaving none to look at
    protected val wubiShown = EngineSettings(tables = mapOf("Wubi" to TableSettings(autoSelect = false)))

    protected fun load(path: String): ByteBuffer {
        loaded += path
        val bytes = when (path) {
            Engines.PINYIN_DATA -> pinyin
            "${Engines.TABLE_DIR}/wbx.data" -> wubi
            Engines.SENTENCE_MODEL -> model
            Engines.REFINING_MODEL -> refining
            Engines.STROKE_DATA -> strokes
            Engines.ENGLISH_DATA -> english
            Engines.EMOJI_DATA -> emoji
            else -> throw IllegalArgumentException(path)
        }
        // as the app's assets do of a file it has not got
        return ByteBuffer.wrap(bytes ?: throw FileNotFoundException(path))
    }
}

internal fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

internal fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()
