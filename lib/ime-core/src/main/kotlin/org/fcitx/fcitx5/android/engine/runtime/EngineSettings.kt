/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableOptions

/**
 * What the user set for the input methods, in the androidengine addon's config. Its keys are
 * those of libime's pinyin config, so a user's old settings carried over as they were (0.4 moved
 * them).
 */
data class EngineSettings(
    val shuangpin: String = ZIRANMA,
    val fuzzy: Set<Fuzzy> = emptySet(),
    /** Whether a key slipped onto a neighbour, or a common misspelling, is read as meant. */
    val typos: Boolean = true,
    /** Whether words that may follow are offered after a commit. */
    val prediction: Boolean = true,
    /** Whether a word's emoji are offered among its candidates (EmojiCandidates). */
    val emoji: Boolean = true,
    /** Whether full pinyin offers a character by the pinyin of its parts (Chaizi): niuniuniu 犇. */
    val chaizi: Boolean = true,
    /** Whether full pinyin offers the date, the time and the weekday under rq, sj and xq (CustomPhrases.BUILT_IN). */
    val builtInPhrases: Boolean = true,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    /** How much the sentence models weigh pinyin's readings again: the 性能档位. */
    val modelLevel: ModelLevel = ModelLevel.HIGH,
    /** Whether full pinyin reads the Latin words typed in it (iphone for iPhone). */
    val latinWords: Boolean = true,
    /** What the user set for each table input method, by its group in the config (`Wubi`). */
    val tables: Map<String, TableSettings> = emptyMap(),
    /** The nine keys' letters: the app's, set on the keyboard's side, not in the addon's config. */
    val nineKeys: NineKeyLetters = NineKeyLetters.STANDARD,
    /**
     * The letter groups of a layout of its own the screen shows in place of the 14 or 18 keys (a theme's
     * 17 keys, 060-render-p3 T9b), each a key's `letters=` as written (rows apart by a space), null for
     * none: what [org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN_KEYS_OWN] types on
     * ([ownKeyboard]). The keyboard's, as [nineKeys]; the rows kept, as where a tap leans goes by them.
     */
    val ownKeys: List<String>? = null,
    /** Whether English suggests words ([ENGLISH_SUGGEST]): the keyboard's to know, the engine's is the addon's. */
    val englishSuggest: Boolean = true,
    /** 候选词注音: what is shown above or beside each character of a Chinese candidate. */
    val candidateReading: ReadingStyle = ReadingStyle.OFF,
) {
    val scheme: ShuangpinScheme get() = SCHEMES[shuangpin] ?: ShuangpinScheme.ZIRANMA

    /** The keyboard [ownKeys] make (NineKeyLetters.custom), null for none or groups that make none (NineKeyLetters.problem). */
    val ownKeyboard: NineKeyLetters? get() = ownKeys?.takeIf { NineKeyLetters.problem(it) == null }?.let(NineKeyLetters::custom)

    companion object {
        const val SHUANGPIN_PROFILE = "ShuangpinProfile"
        const val PAGE_SIZE = "PageSize"
        const val PREDICTION = "Prediction"
        const val EMOJI = "Emoji"
        const val CHAIZI = "Chaizi"
        const val BUILT_IN_PHRASES = "BuiltInPhrases"
        const val FUZZY = "Fuzzy"
        const val MODEL_LEVEL = "ModelLevel"
        const val LATIN_WORDS = "LatinWords"
        const val CANDIDATE_READING = "CandidateReading"
        /**
         * Read by the addon itself: off, English hands the session no key, and they type themselves.
         * The nine keys read it too: there they type English a letter at a time (连按).
         */
        const val ENGLISH_SUGGEST = "EnglishSuggest"

        /** libime's key for its common misspellings (gn for ng); here it turns slips on too. */
        const val TYPOS = "NG_GN"

        const val DEFAULT_PAGE_SIZE = 7
        val PAGE_SIZES = 3..10

        /**
         * 自然码: libime's default and 0.4's, an upgrade with none stored pinned to it ([SchemeMigration]);
         * still this class's default (the engine's, its tests'), the app's is EngineOptions'.
         */
        const val ZIRANMA = "Ziranma"

        /** 小鹤: the app's default since 0.5 (EngineOptions), and what a scheme 0.5 dropped is read as ([SchemeMigration]). */
        const val XIAOHE = "Xiaohe"

        /** 微软. */
        const val MS = "MS"

        /** The schemes by the names libime's config gives them. */
        val SCHEMES: Map<String, ShuangpinScheme> = linkedMapOf(
            ZIRANMA to ShuangpinScheme.ZIRANMA,
            MS to ShuangpinScheme.MICROSOFT,
            XIAOHE to ShuangpinScheme.XIAOHE,
        )
    }
}

/**
 * The 性能档位, by the names the addon's config keeps ([key]): how much of the sentence models the
 * pinyins run, from the decoder alone to the most right and the most work.
 */
enum class ModelLevel(val key: String) {
    /** 低档: the decoder alone, its n-gram model. */
    LOW("Low"),

    /** 中档: and the small sentence model, at every key, picking among the decoder's sentences. */
    MEDIUM("Medium"),

    /** 高档: and the large one too, once the user pauses (PinyinSession's refiner). */
    HIGH("High"),
}

/**
 * What the user set for a table input method, under libime table's keys; what is null stays as
 * the table's own [TableOptions] have it.
 */
data class TableSettings(
    val autoSelect: Boolean? = null,
    val hint: Boolean? = null,
    val orderByUse: Boolean? = null,
    val keyLabels: KeyLabels? = null,
) {
    /** [options] with what was set here instead; key labels only where the table has roots. */
    fun applyTo(options: TableOptions) = options.copy(
        autoSelect = autoSelect ?: options.autoSelect,
        hint = hint ?: options.hint,
        orderByUse = orderByUse ?: options.orderByUse,
        keyLabels = keyLabels?.takeIf { options.roots != null } ?: options.keyLabels,
    )

    companion object {
        const val AUTO_SELECT = "AutoSelect"
        const val HINT = "Hint"
        const val ORDER_POLICY = "OrderPolicy"

        /** What a table with roots ([TableOptions.roots]) shows on its keys: [KeyLabels]'s keys. */
        const val KEY_LABELS = "KeyLabels"

        /** What 调频 on is written as (Fast orders by use alike). */
        const val ORDER_BY_USE = "Freq"

        /**
         * The keys a table's group has. 自动造词's two (AutoPhraseLength, SaveAutoPhraseAfter) are
         * gone (U-28 C46): an older config's are read by nothing, and fcitx drops them at its next save.
         */
        val KEYS = setOf(AUTO_SELECT, HINT, ORDER_POLICY)
    }
}
