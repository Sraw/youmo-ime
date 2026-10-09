/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.TinyModel
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** What [EnginesTest] and [EnginesTablesTest] type with: the data, served as the app's assets serve it ([load]). */
open class EnginesFixture {

    protected fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

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
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "妤")
        .apply { "一二三四五六七".forEachIndexed { i, c -> entry("va" + ('a' + i) + "a", c.toString()) } }
        .build()
        .toByteArray()

    private var model: ByteArray? = TinyModel().bytes()
    private var refining: ByteArray? = TinyModel().bytes()

    protected var strokes: ByteArray? = Strokes.read("...\n你\tpsnpzsn\n好\tzphzsh\n".reader().buffered(), "stroke")
        .build().toByteArray()

    protected val loaded = ArrayList<String>()

    protected fun load(path: String): ByteBuffer {
        loaded += path
        val bytes = when (path) {
            Engines.PINYIN_DATA -> pinyin
            "${Engines.TABLE_DIR}/wbx.data" -> wubi
            Engines.SENTENCE_MODEL -> model
            Engines.REFINING_MODEL -> refining
            Engines.STROKE_DATA -> strokes
            else -> throw IllegalArgumentException(path)
        }
        // as the app's assets do of a file it has not got
        return ByteBuffer.wrap(bytes ?: throw FileNotFoundException(path))
    }

    protected fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()
}
