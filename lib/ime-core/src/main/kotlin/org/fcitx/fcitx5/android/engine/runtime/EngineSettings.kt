/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.stroke.StrokeFilter
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableOptions

/** The user's input-method settings from the androidengine addon's config; keys are libime pinyin's so old settings carried over. */
data class EngineSettings(
    val shuangpin: String = ZIRANMA,
    /** 双拼's code shows the keys pressed, not the pinyin they read. */
    val shuangpinKeysShown: Boolean = false,
    val fuzzy: Set<Fuzzy> = emptySet(),
    /** Whether neighbour slips and common misspellings are read as meant. */
    val typos: Boolean = true,
    val prediction: Boolean = true,
    /** 空格上屏联想词: a space takes the first prediction shown, in pinyin and the tables. */
    val spacePicksPrediction: Boolean = false,
    val emoji: Boolean = true,
    /** niuniuniu 犇. */
    val chaizi: Boolean = true,
    /** rq, sj, xq (CustomPhrases.BUILT_IN). */
    val builtInPhrases: Boolean = true,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    /** 性能档位. */
    val modelLevel: ModelLevel = ModelLevel.HIGH,
    /** iphone for iPhone. */
    val latinWords: Boolean = true,
    /** By config group (`Wubi`). */
    val tables: Map<String, TableSettings> = emptyMap(),
    /** Set by the keyboard, not the addon's config. */
    val nineKeys: NineKeyLetters = NineKeyLetters.STANDARD,
    /** A layout's own letter groups replacing the 14/18 keys (`letters=` per key, rows space-separated), typed by [org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN_KEYS_OWN]; rows kept since tap lean goes by them. */
    val ownKeys: List<String>? = null,
    /** The keyboard's to know; the engine's copy is the addon's. */
    val englishSuggest: Boolean = true,
    /** 候选词注音. */
    val candidateReading: ReadingStyle = ReadingStyle.OFF,
    /** 首页单字: how many single characters a stroke moves right after the first candidate (StrokeFilter), 0 none. */
    val strokeSingles: Int = StrokeFilter.SINGLES,
) {
    val scheme: ShuangpinScheme get() = SCHEMES[shuangpin] ?: ShuangpinScheme.ZIRANMA

    /** Null for none, groups that make none, or the built-in 14/18 keys' groups (typed by their own session). */
    val ownKeyboard: NineKeyLetters? get() = ownKeys?.let(NineKeyLetters::ownOf)

    companion object {
        const val SHUANGPIN_PROFILE = "ShuangpinProfile"
        const val SHUANGPIN_KEYS_SHOWN = "ShuangpinKeysShown"
        const val PAGE_SIZE = "PageSize"
        const val PREDICTION = "Prediction"
        const val SPACE_PICKS_PREDICTION = "SpacePicksPrediction"
        const val EMOJI = "Emoji"
        const val CHAIZI = "Chaizi"
        const val BUILT_IN_PHRASES = "BuiltInPhrases"
        const val FUZZY = "Fuzzy"
        const val MODEL_LEVEL = "ModelLevel"
        const val LATIN_WORDS = "LatinWords"
        const val CANDIDATE_READING = "CandidateReading"
        const val STROKE_SINGLES = "StrokeSingles"
        /** Read by the addon: off, English gets no keys and they type themselves; the nine keys then type English a letter at a time (连按). */
        const val ENGLISH_SUGGEST = "EnglishSuggest"

        /** libime's key for its misspellings (gn for ng); here it turns slips on too. */
        const val TYPOS = "NG_GN"

        const val DEFAULT_PAGE_SIZE = 7
        val PAGE_SIZES = 3..10

        /** libime's and 0.4's default, which upgrades with none stored are pinned to ([SchemeMigration]); still this class's default, the app's is EngineOptions'. */
        const val ZIRANMA = "Ziranma"

        /** The app's default (EngineOptions), and what a dropped scheme is read as ([SchemeMigration]). */
        const val XIAOHE = "Xiaohe"

        const val MS = "MS"

        /** By libime's config names. */
        val SCHEMES: Map<String, ShuangpinScheme> = linkedMapOf(
            ZIRANMA to ShuangpinScheme.ZIRANMA,
            MS to ShuangpinScheme.MICROSOFT,
            XIAOHE to ShuangpinScheme.XIAOHE,
        )
    }
}

/** 性能档位, by config [key]: how much of the sentence models pinyin runs; 自动 is the host's to resolve (`ModelChoice`), so it never reaches an engine. */
enum class ModelLevel(val key: String) {
    /** The decoder's n-gram alone. */
    LOW("Low"),

    /** Plus the small sentence model at every key. */
    MEDIUM("Medium"),

    /** Plus the large one where the small one is unsure, within the key. */
    HIGH("High"),
}

/** A table's settings under libime table's keys; null keeps the table's own [TableOptions]. */
data class TableSettings(
    val autoSelect: Boolean? = null,
    val hint: Boolean? = null,
    val orderByUse: Boolean? = null,
    val keyLabels: KeyLabels? = null,
    /** 逐字打出后给出组词. */
    val offerWords: Boolean? = null,
) {
    /** Key labels only where the table has roots. */
    fun applyTo(options: TableOptions) = options.copy(
        autoSelect = autoSelect ?: options.autoSelect,
        hint = hint ?: options.hint,
        orderByUse = orderByUse ?: options.orderByUse,
        keyLabels = keyLabels?.takeIf { options.roots != null } ?: options.keyLabels,
        offerWords = offerWords ?: options.offerWords,
    )

    companion object {
        const val AUTO_SELECT = "AutoSelect"
        const val HINT = "Hint"
        const val ORDER_POLICY = "OrderPolicy"

        const val KEY_LABELS = "KeyLabels"
        const val OFFER_WORDS = "OfferWords"

        /** 调频 on (Fast orders by use too). */
        const val ORDER_BY_USE = "Freq"

        /** 自动造词's two keys are gone: an older config's are ignored and fcitx drops them at its next save. */
        val KEYS = setOf(AUTO_SELECT, HINT, ORDER_POLICY, OFFER_WORDS)
    }
}
