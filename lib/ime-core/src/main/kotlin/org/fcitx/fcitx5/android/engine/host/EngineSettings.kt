/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.table.TableOptions

/**
 * What the user set for the input methods, in the androidengine addon's config. Its keys are
 * those of libime's pinyin config, so a user's old settings carry over as they are (see
 * [LibimeMigration]).
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
    /** Whether English suggests words ([ENGLISH_SUGGEST]): the keyboard's to know, the engine's is the addon's. */
    val englishSuggest: Boolean = true,
) {
    val scheme: ShuangpinScheme get() = SCHEMES[shuangpin] ?: ShuangpinScheme.ZIRANMA

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
        /**
         * Read by the addon itself: off, English hands the session no key, and they type themselves.
         * The nine keys read it too: there they type English a letter at a time (连按).
         */
        const val ENGLISH_SUGGEST = "EnglishSuggest"

        /** libime's key for its common misspellings (gn for ng); here it turns slips on too. */
        const val TYPOS = "NG_GN"

        const val DEFAULT_PAGE_SIZE = 7
        val PAGE_SIZES = 3..10

        const val ZIRANMA = "Ziranma"

        /** The schemes by the names libime's config gives them. */
        val SCHEMES: Map<String, ShuangpinScheme> = linkedMapOf(
            ZIRANMA to ShuangpinScheme.ZIRANMA,
            "MS" to ShuangpinScheme.MICROSOFT,
            "Ziguang" to ShuangpinScheme.ZIGUANG,
            "ABC" to ShuangpinScheme.ABC,
            "Zhongwenzhixing" to ShuangpinScheme.ZHONGWENZHIXING,
            "PinyinJiajia" to ShuangpinScheme.PINYINJIAJIA,
            "Xiaohe" to ShuangpinScheme.XIAOHE,
            "GB Standard" to ShuangpinScheme.GB,
        )

        /**
         * The settings in [config], `key=value` a line, a group's keys after its name and a slash
         * (`Fuzzy/L_N=True`), as the addon flattens its config. What is missing or unreadable
         * stays as by default.
         */
        // each table's settings in [values], by its group: those of a group with any of [TableSettings.KEYS]
        private fun tables(values: Map<String, String>, flag: (String) -> Boolean?): Map<String, TableSettings> {
            return values.keys.mapNotNullTo(LinkedHashSet()) { key ->
                key.substringBefore('/', "").takeIf { it.isNotEmpty() && key.substringAfter('/') in TableSettings.KEYS }
            }.associateWith { group ->
                TableSettings(
                    autoSelect = flag("$group/${TableSettings.AUTO_SELECT}"),
                    hint = flag("$group/${TableSettings.HINT}"),
                    orderByUse = when (values["$group/${TableSettings.ORDER_POLICY}"]) {
                        "Freq", "Fast" -> true
                        "No" -> false
                        else -> null
                    },
                )
            }
        }

        fun parse(config: String): EngineSettings {
            val values = config.lineSequence().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim() else null
            }.toMap()
            fun flag(key: String) = when {
                values[key].equals("True", ignoreCase = true) -> true
                values[key].equals("False", ignoreCase = true) -> false
                else -> null
            }
            val default = EngineSettings()
            return EngineSettings(
                shuangpin = values[SHUANGPIN_PROFILE]?.takeIf { it in SCHEMES } ?: default.shuangpin,
                fuzzy = Fuzzy.entries.filterTo(HashSet()) { flag("$FUZZY/${it.name}") == true },
                typos = flag("$FUZZY/$TYPOS") ?: default.typos,
                prediction = flag(PREDICTION) ?: default.prediction,
                emoji = flag(EMOJI) ?: default.emoji,
                chaizi = flag(CHAIZI) ?: default.chaizi,
                builtInPhrases = flag(BUILT_IN_PHRASES) ?: default.builtInPhrases,
                pageSize = values[PAGE_SIZE]?.toIntOrNull()?.takeIf { it in PAGE_SIZES } ?: default.pageSize,
                modelLevel = ModelLevel.entries.firstOrNull { it.key == values[MODEL_LEVEL] } ?: default.modelLevel,
                latinWords = flag(LATIN_WORDS) ?: default.latinWords,
                tables = tables(values, ::flag),
                englishSuggest = flag(ENGLISH_SUGGEST) ?: default.englishSuggest,
            )
        }
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
) {
    /** [options] with what was set here instead. */
    fun applyTo(options: TableOptions) = options.copy(
        autoSelect = autoSelect ?: options.autoSelect,
        hint = hint ?: options.hint,
        orderByUse = orderByUse ?: options.orderByUse,
    )

    companion object {
        const val AUTO_SELECT = "AutoSelect"
        const val HINT = "Hint"
        const val ORDER_POLICY = "OrderPolicy"

        /** What 调频 on is written as (Fast orders by use alike). */
        const val ORDER_BY_USE = "Freq"

        /**
         * The keys a table's group has. 自动造词's two (AutoPhraseLength, SaveAutoPhraseAfter) are
         * gone (U-28 C46): an older config's are read by nothing, and fcitx drops them at its next save.
         */
        val KEYS = setOf(AUTO_SELECT, HINT, ORDER_POLICY)
    }
}
