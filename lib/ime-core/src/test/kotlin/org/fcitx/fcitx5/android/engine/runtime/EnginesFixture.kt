/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.learn.Mine
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.rerank.TinyModel
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** What [EnginesTest] and the like type with: the data, served as the app's assets serve it ([load]). */
open class EnginesFixture {

    protected var pinyin: ByteArray = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -3f, 0f)
        .unigram("好", -2.5f, 0f)
        .bigram("好", "拟", -0.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        .charTone('你'.code, Syllables.id("ni"), 3)
        .charTone('拟'.code, Syllables.id("ni"), 3)
        .charTone('好'.code, Syllables.id("hao"), 3)
        .charTone('好'.code, Syllables.id("hao"), 4)
        // a word often misread (Misreadings): under its true reading and, weighed down, its misreading
        .unigram("角色", -3f, 0f)
        .entry("角色", syl("jue", "se"))
        .entry("角色", syl("jiao", "se"), Misreadings.WEIGHT)
        .unigram("角", -4f, 0f).entry("角", syl("jiao")).entry("角", syl("jue"))
        .unigram("色", -4f, 0f).entry("色", syl("se"))
        .charTone('角'.code, Syllables.id("jue"), 2)
        .charTone('角'.code, Syllables.id("jiao"), 3)
        .charTone('色'.code, Syllables.id("se"), 4)
        .build(mapOf(Misreadings.META_PREFIX + "角色" to Misreadings.meta(Misreadings("角色", "jué sè", listOf("jiao'se")))))
        .toByteArray()

    protected var wubiData: ByteArray = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "妤")
        .entry("jjad", "日期")
        // alone under the code any two of 一…七 make: a word of them the table cannot take
        .entry("vava", "七上八下")
        // two 重码 past the 简码 kept in order: what 调频 reorders (EnginesTest)
        .entry("kkkk", "叫")
        .entry("kkkk", "员")
        .apply { "一二三四五六七".forEachIndexed { i, c -> entry("va" + ('a' + i) + "a", c.toString()) } }
        .build()
        .toByteArray()

    protected var english: ByteArray = englishWith()

    // English's data of the two words, and [more]
    protected fun englishWith(vararg more: String): ByteArray = EnglishDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram(EnglishText.START, -9f, -0.5f)
        .unigram("the", -1.3f, 0f)
        .unigram("you", -1.8f, 0f)
        .apply { for (w in more) unigram(w, -3f, 0f) }
        .build()
        .toByteArray()

    private var model: ByteArray? = TinyModel().bytes()
    private var large: ByteArray? = TinyModel().bytes()

    protected var strokes: ByteArray? = Strokes.read("...\n你\tpsnpzsn\n好\tzphzsh\n".reader().buffered(), "stroke")
        .build().toByteArray()

    // none of a word: a test of them puts its own
    protected var emoji: ByteArray? = EmojiData.format(emptyList(), emptyMap()).toByteArray()
    protected var chaizi: ByteArray? = Chaizi.format(emptyMap()).toByteArray()

    // none: a test of the characters' cards puts its own
    protected var charInfo: ByteArray? = null

    // none: 生僻字屏蔽's tests put TestCharLevels' (an app without them has the feature off)
    protected var charLevels: ByteArray? = null

    protected val loaded = ArrayList<String>()

    // warm-ups run in place: [loaded] is complete before a test looks
    protected val inPlace = Executor { it.run() }

    // 调频 [on] for Wubi, what kkkk shows then
    protected fun byUse(engines: Engines, on: Boolean): List<String> {
        engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.RESET, 0)
        engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Wubi" to TableSettings(orderByUse = on)))
        return engines.type(InputMethodCatalog.WUBI, "kkkk").candidates.also { engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.RESET, 0) }
    }

    // Engines over [files] with [settings], [im]'s session reset in an empty field
    protected fun ready(im: String, files: UserFiles? = null, settings: EngineSettings = EngineSettings()) =
        Engines(::load, files, warmUps = inPlace).also {
            it.settings = settings
            it.onEvent(im, EngineEvent.RESET, 0)
            it.context(im, "")
        }

    protected fun load(path: String): ByteBuffer {
        loaded += path
        val bytes = when (path) {
            InputMethodCatalog.PINYIN_DATA -> pinyin
            "${InputMethodCatalog.TABLE_DIR}/wbx.data" -> wubiData
            InputMethodCatalog.SENTENCE_MODEL -> model
            InputMethodCatalog.LARGE_MODEL -> large
            InputMethodCatalog.STROKE_DATA -> strokes
            InputMethodCatalog.ENGLISH_DATA -> english
            InputMethodCatalog.EMOJI_DATA -> emoji
            InputMethodCatalog.CHAIZI_DATA -> chaizi
            InputMethodCatalog.CHAR_INFO_DATA -> charInfo
            InputMethodCatalog.CHAR_LEVELS_DATA -> charLevels
            else -> throw IllegalArgumentException(path)
        }
        return ByteBuffer.wrap(bytes ?: throw FileNotFoundException(path))
    }
}

// the phrases (code, text) an older build saved in 五笔 (自动造词 is gone), as converted into the record under [dir]
internal fun olderPhrases(dir: UserFiles, vararg phrases: Pair<String, String>, own: Map<String, String> = emptyMap()) {
    LearnLog(dir.file(LearnLog.FILE)).apply {
        open()
        absorb(phrases.map { (code, text) -> LearnRecord.TableWordRow(InputMethodCatalog.WUBI, code, text, 1) })
        // what pinyin lacked of them, its own words as the conversion made them: text to reading
        absorb(own.entries.mapIndexed { i, (text, reading) -> LearnRecord.MineRow(text, Mine(reading, Mine.CONVERTED, 1, i + 1L, 1, 0, 0)) })
        close()
    }
}

// the learning record under [dir], each record decoded
internal fun records(dir: UserFiles): List<LearnRecord> {
    val records = ArrayList<LearnRecord>()
    val reader = LearnRecords.Reader()
    LearnRecords.FORMAT.read(dir.file(LearnLog.FILE).read()) { type, input -> reader.decode(type, input)?.let { records += it } }
    return records
}

internal fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

internal fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()

// 五笔 shown, not a code's one candidate committing itself and leaving none to look at
internal val wubiShown = EngineSettings(tables = mapOf("Wubi" to TableSettings(autoSelect = false)))
