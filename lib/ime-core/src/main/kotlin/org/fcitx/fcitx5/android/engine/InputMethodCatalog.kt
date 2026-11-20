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
 * The input methods by name and what host and keyboard need of each without a session. Fixed at build
 * time, so `host/` and `input/` read it with no `Engines` made.
 */
object InputMethodCatalog {
    const val PINYIN = "engine-pinyin"

    /** Pinyin on the 14 keys: no fcitx input method; [PINYIN]'s session reads these codes. Never in an answer. */
    const val PINYIN_KEYS = "engine-pinyin#keys"

    /** Pinyin on the 18 keys (`l;` read as `l`), as [PINYIN_KEYS]. */
    const val PINYIN_KEYS18 = "engine-pinyin#keys18"

    /** Pinyin on a layout's own letter groups (NineKeyLetters.custom), as [PINYIN_KEYS]. */
    const val PINYIN_KEYS_OWN = "engine-pinyin#keysOwn"

    /** Pinyin typed in zhuyin (注音九键, 大千), as [PINYIN_KEYS]. */
    const val ZHUYIN_NINE = "engine-pinyin#zhuyin9"
    const val ZHUYIN = "engine-pinyin#zhuyin"

    const val SHUANGPIN = "engine-shuangpin"

    /** 双拼 on the 18 and nine keys, as [PINYIN_KEYS]: names for settings' "not for", never in an answer. */
    const val SHUANGPIN_KEYS18 = "engine-shuangpin#keys18"
    const val SHUANGPIN_NINE = "engine-shuangpin#nine"

    /** 双拼 on the 14 keys; a scheme with its own `;` (微软) does not offer it. */
    const val SHUANGPIN_KEYS14 = "engine-shuangpin#keys14"

    /** Pinyin's nine keys: once an input method of its own, never listed again; [PINYIN]'s session reads its codes. */
    const val T9 = "engine-t9"

    const val WUBI = "engine-wubi"

    const val CANGJIE = "engine-cangjie"
    const val ZIRANMA = "engine-ziranma"
    const val ERBI = "engine-erbi"
    const val WUBI_PINYIN = "engine-wubipinyin"

    /** The retired handwriting input method: a user's list may still hold it (HandwritingRetire). */
    const val HANDWRITING_RETIRED = "engine-handwriting"

    /** The non-table input methods whose sessions read pinyin's data and word index when made. */
    internal val READS_PINYIN_DATA = setOf(PINYIN, SHUANGPIN, T9)

    private val ADDED_FIRST = listOf(WUBI)

    fun <T> addOrder(methods: Collection<T>, name: (T) -> String): List<T> =
        methods.sortedBy { ADDED_FIRST.indexOf(name(it)).takeIf { i -> i >= 0 } ?: ADDED_FIRST.size }

    /**
     * Whether [im] takes non-letter [c] as a key: grouped keyboards' codes, a 双拼 [scheme]'s own keys,
     * the separator `'` and a table's keys while [composing] (as PinyinSession.reads has them). [tableKeys]
     * is fcitx-thread only, so asked only then. Anything else an address field takes as it is
     * (PendingInputPolicy.typesAsIs).
     */
    fun readsAsKey(
        im: String,
        scheme: ShuangpinScheme,
        c: Int,
        composing: Boolean = false,
        tableKeys: () -> Set<Char> = { emptySet() },
    ): Boolean = when (im) {
        SHUANGPIN -> c <= Char.MAX_VALUE.code && scheme.types(c.toChar()) || readsCode(SHUANGPIN, c) || separates(c, composing)
        // never typed as they are, nor reaching the field
        PINYIN -> readsCode(PINYIN, c) || separates(c, composing)
        // with nothing typed a table key is text: through the table 二笔's / would be 、
        else -> composing && c <= Char.MAX_VALUE.code && c.toChar() in tableKeys()
    }

    /**
     * Whether [im] reads grouped keyboard code [c]. A code belongs to one keyboard, so one another input
     * method gets (the keyboard behind a switch) is dropped.
     */
    fun readsCode(im: String, c: Int): Boolean = when {
        Keyboard.isTap(c) || c in NineKeyLetters.CUSTOM_BLOCK || c in Keyboard.PINYIN_NINE || c in Keyboard.PINYIN_18 || c in Keyboard.ZHUYIN_NINE || c in Keyboard.ZHUYIN_FULL -> im == PINYIN
        c in Keyboard.SHUANGPIN_14 -> im == SHUANGPIN
        else -> when (NineKeyLetters.ofCode(c)) {
            null -> false
            NineKeyLetters.G14 -> im == PINYIN
            else -> im == SHUANGPIN
        }
    }

    /** A grouped keyboard's code: never text for a field. */
    fun isCode(c: Int): Boolean = c in NineKeyLetters.CODES || c in NineKeyLetters.CUSTOM_BLOCK || Keyboard.isTap(c) || c in Keyboard.KEYED_CODES

    // with nothing typed ' is the app's: through the engine it would become ‘
    private fun separates(c: Int, composing: Boolean) = composing && c == SyllableGraph.SEPARATOR.code

    /** Whether [name] reads a glide (滑行输入). Must match SessionFactory.make's `when`. */
    fun glides(name: String): Boolean = name == ENGLISH || name == PINYIN || name == SHUANGPIN || name == T9 || name in TABLES

    /** Whether [name] filters candidates by a drawn stroke (笔画筛选). Must match SessionFactory.make. */
    fun filtersByStrokes(name: String): Boolean = name == PINYIN || name == SHUANGPIN || name in KeyboardPair.KEYED

    /** Whether [name] may be written on over its keys (手写): every Chinese one. Must match SessionFactory.make. */
    fun writesOnKeys(name: String): Boolean = name.isNotEmpty() && name != ENGLISH

    /** fcitx's own name, which fcitx falls back to, so users' lists keep it. */
    const val ENGLISH = "keyboard-us"

    const val ENGLISH_DATA = "engine/english.data"

    const val PINYIN_DATA = "engine/pinyin.data"

    /** Read if the app has it. */
    const val STROKE_DATA = "engine/stroke.data"

    /** Read if the app has it. */
    const val EMOJI_DATA = "engine/emoji.data"

    /** Read if the app has it. */
    const val CHAIZI_DATA = "engine/chaizi.data"

    /** Read if the app has it. */
    const val CHAR_INFO_DATA = "engine/charinfo.data"

    /** Without it 生僻字屏蔽 is off. */
    const val CHAR_LEVELS_DATA = "engine/charlevels.data"
    /** Optional; without it the engine types by the decoder alone. */
    const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
    const val REFINING_MODEL = "engine/sentence-model-large.safetensors"
    const val TABLE_DIR = "engine/table"

    fun hasTableKeys(im: String) = im in TABLES

    val TABLES: Map<String, TableMethod> = mapOf(
        WUBI to TableMethod("wbx.data", "Wubi", TableOptions.WUBI, "wbx"),
        CANGJIE to TableMethod("cj.data", "Cangjie", TableOptions.CANGJIE, "cangjie"),
        ZIRANMA to TableMethod("zrm.data", "Ziranma", TableOptions.ZIRANMA, "zrm"),
        ERBI to TableMethod("erbi.data", "Erbi", TableOptions.ERBI, "erbi"),
        WUBI_PINYIN to TableMethod("wbpy.data", "WubiPinyin", TableOptions.WUBI_PINYIN, "wbpy"),
    )

    /** Upstream Fcitx5's names for the input methods this replaces, as its backups have them (unknown names are dropped). */
    private val UPSTREAM: Map<String, String> =
        mapOf("pinyin" to PINYIN, "shuangpin" to SHUANGPIN) + TABLES.entries.associate { (im, method) -> method.libime to im }

    fun fromUpstream(name: String): String = UPSTREAM[name] ?: name

    /** In the order the native addon listed them (`androidengine.cpp:337-358`). */
    val LISTED: List<ListedInputMethod> = listOf(
        ListedInputMethod(PINYIN, "拼", "拼音", "fcitx-pinyin", ListedInputMethod.CHINESE),
        ListedInputMethod(SHUANGPIN, "双", "双拼", "fcitx-shuangpin", ListedInputMethod.CHINESE),
        ListedInputMethod(WUBI, "五", "五笔", "fcitx-wubi", ListedInputMethod.CHINESE),
        ListedInputMethod(CANGJIE, "倉", "仓颉", "fcitx-cangjie", ListedInputMethod.CHINESE),
        ListedInputMethod(ZIRANMA, "自", "自然码", "fcitx-ziranma", ListedInputMethod.CHINESE),
        ListedInputMethod(ERBI, "二", "二笔", "fcitx-erbi", ListedInputMethod.CHINESE),
        ListedInputMethod(WUBI_PINYIN, "五", "五笔拼音", "fcitx-wubi", ListedInputMethod.CHINESE),
        ListedInputMethod(ENGLISH, "En", "English", "input-keyboard", "en", composesText = true),
    )

    fun listed(im: String): ListedInputMethod? = LISTED.firstOrNull { it.name == im }
}

/**
 * An input method as the host lists it. [composesText]: what it has typed is the text itself
 * (English, handwriting), shown as composing text; a code (pinyin, tables) is shown above the candidates.
 */
data class ListedInputMethod(
    val name: String,
    val label: String,
    val text: String,
    val icon: String,
    val language: String,
    val composesText: Boolean = false,
) {
    val chinese get() = language == CHINESE

    companion object {
        const val CHINESE = "zh_CN"
    }
}

/** A table input method: [file] under [InputMethodCatalog.TABLE_DIR], [group] its settings group, [libime] libime's old name. */
class TableMethod(val file: String, val group: String, val options: TableOptions, val libime: String)
