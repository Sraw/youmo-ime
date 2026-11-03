/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.KeyboardPair
import org.fcitx.fcitx5.android.engine.table.TableOptions

/**
 * The input methods the engine has, by name, and what the host and the keyboard need to know of each
 * without a session: which are listed and how, the tables, the keys each types with, which glide; and
 * the data files the engine reads from the app's assets. Fixed: nothing here is made or changes at run
 * time, so the host (`host/`) and the keyboard's decisions (`input/`) read it with no `Engines` made.
 */
object InputMethodCatalog {
    const val PINYIN = "engine-pinyin"

    /**
     * Pinyin on the 14 keys: no fcitx input method, the session [PINYIN] hands the keys' codes
     * to (NineKeyLetters.G14), named for what is not for it (InputMethodSettings' notFor). Never
     * in an answer: the 14 keys' are [PINYIN]'s, the input method the router has active.
     */
    const val PINYIN_KEYS = "engine-pinyin#keys"

    /** Pinyin on the 18 keys (NineKeyLetters.G18, 双拼's groups, `l;` read as `l`): a session [PINYIN] hands its codes to, as [PINYIN_KEYS]. */
    const val PINYIN_KEYS18 = "engine-pinyin#keys18"

    /** Pinyin typed in zhuyin, 注音九键 and 注音全键盘 (大千, ZhuyinKeys): sessions [PINYIN] hands their codes to, as [PINYIN_KEYS]. */
    const val ZHUYIN_NINE = "engine-pinyin#zhuyin9"
    const val ZHUYIN = "engine-pinyin#zhuyin"

    const val SHUANGPIN = "engine-shuangpin"

    /**
     * 双拼 on the 18 keys and on the nine keys (NineKeyLetters.G18, NINE_SP): as [PINYIN_KEYS],
     * sessions [SHUANGPIN] hands the keys' codes to, names for settings' "not for", never in an
     * answer (those are [SHUANGPIN]'s).
     */
    const val SHUANGPIN_KEYS18 = "engine-shuangpin#keys18"
    const val SHUANGPIN_NINE = "engine-shuangpin#nine"

    /** 双拼 on the 14 keys (NineKeyLetters.G14), as [SHUANGPIN_KEYS18]; no scheme with ; of its own (微软) offers it. */
    const val SHUANGPIN_KEYS14 = "engine-shuangpin#keys14"

    /**
     * Pinyin on a phone's nine keys (九键): a session [PINYIN] hands the nine keys' codes to
     * (Keyboard.PINYIN_NINE), as [PINYIN_KEYS]. 0.4's input method of its own by this name, which a
     * user's list may still hold (NineKeysMerge turns it into [PINYIN]); never listed again.
     */
    const val T9 = "engine-t9"

    const val WUBI = "engine-wubi"

    const val CANGJIE = "engine-cangjie"
    const val ZIRANMA = "engine-ziranma"
    const val ERBI = "engine-erbi"
    const val WUBI_PINYIN = "engine-wubipinyin"

    /** Handwriting: characters written one at a time on a pad, read as a sentence. */
    const val HANDWRITING = "engine-handwriting"

    /**
     * The input methods but the tables ([TABLES], which read it too) whose sessions read pinyin's
     * data and word index as they are made: the pinyins, and handwriting, which reads its sentences by them.
     */
    internal val READS_PINYIN_DATA = setOf(PINYIN, SHUANGPIN, T9, HANDWRITING)

    /** Offered first to be added: the most asked for after pinyin, which is there already (its nine keys a keyboard of it). */
    private val ADDED_FIRST = listOf(WUBI)

    /** [methods] in the order they are offered to be added: [ADDED_FIRST], then the rest as they came. */
    fun <T> addOrder(methods: Collection<T>, name: (T) -> String): List<T> =
        methods.sortedBy { ADDED_FIRST.indexOf(name(it)).takeIf { i -> i >= 0 } ?: ADDED_FIRST.size }

    /**
     * Whether [im] types with [c], no letter, as with a letter: the grouped keyboards' codes (the
     * nine keys' too: their digits are codes, [Keyboard.PINYIN_NINE]), a 双拼
     * [scheme]'s own keys (`;` is ing in 微软), and the pinyins' syllable separator `'`
     * while something is typed ([composing]), as PinyinSession.reads has them; and a table's
     * keys while something is typed, as its session reads them ([tableKeys], `Engines.tableKeys` as
     * read on fcitx's thread: asked only then). Any other an address
     * field takes as it is (PendingInputPolicy.typesAsIs); 微软's `;` taken so cut the syllable
     * being typed, `'` put `xi'an` in as `xi'` and `an`, and 二笔's `;` cut its code.
     */
    fun readsAsKey(
        im: String,
        scheme: ShuangpinScheme,
        c: Int,
        composing: Boolean = false,
        tableKeys: () -> Set<Char> = { emptySet() },
    ): Boolean = when (im) {
        // the 18, 14 and nine keys' codes too, never typed as they are
        SHUANGPIN -> c <= Char.MAX_VALUE.code && scheme.types(c.toChar()) || readsCode(SHUANGPIN, c) || separates(c, composing)
        // the 14, 18 and nine keys' codes: never typed as they are, nor reaching the field (design §1.2)
        PINYIN -> readsCode(PINYIN, c) || separates(c, composing)
        // with nothing typed a table's is text, as ' is: through the table 二笔's / alone would be 、
        else -> composing && c <= Char.MAX_VALUE.code && c.toChar() in tableKeys()
    }

    /**
     * Whether [im] reads the grouped keyboard code [c] ([isCode]): pinyin the 14 keys' (a tap's
     * with its place too), the 18 keys' and the nine keys' of its own ([Keyboard.KEYED_CODES]);
     * 双拼 the 18 keys', the nine keys' 双拼's and its 14 keys'. A code is one keyboard's alone, so
     * one another input method gets (the keyboard shown behind a switch) is dropped, never read.
     */
    fun readsCode(im: String, c: Int): Boolean = when {
        c in Keyboard.TAPS || c in Keyboard.PINYIN_NINE || c in Keyboard.PINYIN_18 || c in Keyboard.ZHUYIN_NINE || c in Keyboard.ZHUYIN_FULL -> im == PINYIN
        c in Keyboard.SHUANGPIN_14 -> im == SHUANGPIN
        else -> when (NineKeyLetters.ofCode(c)) {
            null -> false
            NineKeyLetters.G14 -> im == PINYIN
            else -> im == SHUANGPIN
        }
    }

    /** Whether [c] is a grouped keyboard's code, some input method's to read ([readsCode]): never text for a field. */
    fun isCode(c: Int): Boolean = c in NineKeyLetters.CODES || c in Keyboard.TAPS || c in Keyboard.KEYED_CODES

    // with nothing typed ' is the app's: through the engine it would be a mark, ‘
    private fun separates(c: Int, composing: Boolean) = composing && c == SyllableGraph.SEPARATOR.code

    /**
     * Whether input method [name] is typed with a keyboard's keys: all but handwriting, made for a
     * touch screen (pinyin's nine keys a keyboard of pinyin, which a hardware keyboard types 全拼 on).
     */
    fun takesKeyboard(name: String): Boolean = name != HANDWRITING

    /**
     * Whether input method [name] reads a glide across its keys (滑行输入): English, the pinyins and
     * the tables, not handwriting; pinyin's nine keys' session [T9] too, which pinyin hands a glide
     * over them (KeyboardPair). Each is made a session that reads one (SessionFactory.make's
     * `when`): one added here is added there.
     */
    fun glides(name: String): Boolean = name == ENGLISH || name == PINYIN || name == SHUANGPIN || name == T9 || name in TABLES

    /**
     * Whether input method [name] filters its candidates by a stroke drawn on its keyboard (笔画筛选):
     * pinyin and 双拼 on every keyboard they have, not the tables, English or handwriting. Each is made
     * a session that does (SessionFactory.make's [StrokeFilter][org.fcitx.fcitx5.android.engine.stroke.StrokeFilter]).
     */
    fun filtersByStrokes(name: String): Boolean = name == PINYIN || name == SHUANGPIN || name in KeyboardPair.KEYED

    /** English: fcitx's own name for it, which fcitx falls back to, so a user's lists keep it. */
    const val ENGLISH = "keyboard-us"

    /** Its words as written and a trigram model over them, counted from FineWeb's English pages, in the app's assets. */
    const val ENGLISH_DATA = "engine/english.data"

    const val PINYIN_DATA = "engine/pinyin.data"

    /** Characters by their strokes (rime-stroke), for pinyin's `u` lookup; read if the app has it. */
    const val STROKE_DATA = "engine/stroke.data"

    /** The emoji panel's groups and the words emoji stand for (EmojiData); read if the app has it. */
    const val EMOJI_DATA = "engine/emoji.data"

    /** Characters by the pinyin of their parts (Chaizi), for full pinyin; read if the app has it. */
    const val CHAIZI_DATA = "engine/chaizi.data"
    /** Read if the app has one; without it the engine types by the decoder alone. */
    const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
    /** Six times the work of [SENTENCE_MODEL], and right more often: see [PinyinSession]'s refiner. */
    const val REFINING_MODEL = "engine/sentence-model-large.safetensors"
    const val TABLE_DIR = "engine/table"

    /** Whether [im] is a table input method, the only kind [tableKeys] has keys for. */
    fun hasTableKeys(im: String) = im in TABLES

    /** Each table input method. */
    val TABLES: Map<String, TableMethod> = mapOf(
        WUBI to TableMethod("wbx.data", "Wubi", TableOptions.WUBI, "wbx"),
        CANGJIE to TableMethod("cj.data", "Cangjie", TableOptions.CANGJIE, "cangjie"),
        ZIRANMA to TableMethod("zrm.data", "Ziranma", TableOptions.ZIRANMA, "zrm"),
        ERBI to TableMethod("erbi.data", "Erbi", TableOptions.ERBI, "erbi"),
        WUBI_PINYIN to TableMethod("wbpy.data", "WubiPinyin", TableOptions.WUBI_PINYIN, "wbpy"),
    )

    /**
     * The input methods the host lists, in this order (`androidengine.cpp:337-358`, which the
     * native addon had): what the input method page and the picker show of each, its icon,
     * its language.
     */
    val LISTED: List<ListedInputMethod> = listOf(
        ListedInputMethod(PINYIN, "拼", "拼音", "fcitx-pinyin", ListedInputMethod.CHINESE),
        ListedInputMethod(SHUANGPIN, "双", "双拼", "fcitx-shuangpin", ListedInputMethod.CHINESE),
        ListedInputMethod(WUBI, "五", "五笔", "fcitx-wubi", ListedInputMethod.CHINESE),
        ListedInputMethod(CANGJIE, "倉", "仓颉", "fcitx-cangjie", ListedInputMethod.CHINESE),
        ListedInputMethod(ZIRANMA, "自", "自然码", "fcitx-ziranma", ListedInputMethod.CHINESE),
        ListedInputMethod(ERBI, "二", "二笔", "fcitx-erbi", ListedInputMethod.CHINESE),
        ListedInputMethod(WUBI_PINYIN, "五", "五笔拼音", "fcitx-wubi", ListedInputMethod.CHINESE),
        ListedInputMethod(HANDWRITING, "手", "手写", "draw-freehand", ListedInputMethod.CHINESE, composesText = true),
        ListedInputMethod(ENGLISH, "En", "English", "input-keyboard", "en", composesText = true),
    )

    /** The one of [LISTED] named [im]; null for any other. */
    fun listed(im: String): ListedInputMethod? = LISTED.firstOrNull { it.name == im }
}

/**
 * An input method as the host lists it: its [name], the short [label] the input method name
 * overlay and the status area show, the [text] the lists show, its [icon] and [language].
 * [composesText]: what it has typed is the text itself (English's word, handwriting's characters),
 * shown in the app as composing text; a code (pinyin's, a table's) is shown above the candidates
 * (`composesText`, `androidengine.cpp:375-383`).
 */
data class ListedInputMethod(
    val name: String,
    val label: String,
    val text: String,
    val icon: String,
    val language: String,
    val composesText: Boolean = false,
) {
    /** It types Chinese: Chinese marks for its punctuation keys, and the status area's toggles (`typesChinese`, `:368-373`). */
    val chinese get() = language == CHINESE

    companion object {
        const val CHINESE = "zh_CN"
    }
}

/**
 * A table input method: its [file] under [InputMethodCatalog.TABLE_DIR], the [group] of its settings in the
 * addon's config (see [EngineSettings.tables]), and how it behaves unless those say otherwise.
 * [libime] the name libime's table input method had.
 */
class TableMethod(val file: String, val group: String, val options: TableOptions, val libime: String)
