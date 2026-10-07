/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
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
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    /** Whether pinyin's readings are weighed again as whole sentences, by the sentence model. */
    val sentenceModel: Boolean = true,
    /** Whether full pinyin reads the Latin words typed in it (iphone for iPhone). */
    val latinWords: Boolean = true,
    /** What the user set for each table input method, by its group in the config (`Wubi`). */
    val tables: Map<String, TableSettings> = emptyMap(),
) {
    val scheme: ShuangpinScheme get() = SCHEMES[shuangpin] ?: ShuangpinScheme.ZIRANMA

    companion object {
        const val SHUANGPIN_PROFILE = "ShuangpinProfile"
        const val PAGE_SIZE = "PageSize"
        const val PREDICTION = "Prediction"
        const val FUZZY = "Fuzzy"
        const val SENTENCE_MODEL = "SentenceModel"
        const val LATIN_WORDS = "LatinWords"

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
            fun count(key: String, range: IntRange) = values[key]?.toIntOrNull()?.takeIf { it in range }
            val tables = values.keys.mapNotNullTo(LinkedHashSet()) { key ->
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
                    autoPhraseLength = count("$group/${TableSettings.AUTO_PHRASE_LENGTH}", TableSettings.AUTO_PHRASE_LENGTHS),
                    saveAutoPhraseAfter = count("$group/${TableSettings.SAVE_AUTO_PHRASE_AFTER}", TableSettings.SAVES_AFTER),
                )
            }
            val default = EngineSettings()
            return EngineSettings(
                shuangpin = values[SHUANGPIN_PROFILE]?.takeIf { it in SCHEMES } ?: default.shuangpin,
                fuzzy = Fuzzy.entries.filterTo(HashSet()) { flag("$FUZZY/${it.name}") == true },
                typos = flag("$FUZZY/$TYPOS") ?: default.typos,
                prediction = flag(PREDICTION) ?: default.prediction,
                pageSize = values[PAGE_SIZE]?.toIntOrNull()?.takeIf { it in PAGE_SIZES } ?: default.pageSize,
                sentenceModel = flag(SENTENCE_MODEL) ?: default.sentenceModel,
                latinWords = flag(LATIN_WORDS) ?: default.latinWords,
                tables = tables,
            )
        }
    }
}

/**
 * What the user set for a table input method, under libime table's keys; what is null stays as
 * the table's own [TableOptions] have it.
 */
data class TableSettings(
    val autoSelect: Boolean? = null,
    val hint: Boolean? = null,
    val orderByUse: Boolean? = null,
    val autoPhraseLength: Int? = null,
    val saveAutoPhraseAfter: Int? = null,
) {
    /** [options] with what was set here instead. */
    fun applyTo(options: TableOptions) = options.copy(
        autoSelect = autoSelect ?: options.autoSelect,
        hint = hint ?: options.hint,
        orderByUse = orderByUse ?: options.orderByUse,
        autoPhraseLength = autoPhraseLength ?: options.autoPhraseLength,
        saveAutoPhraseAfter = saveAutoPhraseAfter ?: options.saveAutoPhraseAfter,
    )

    companion object {
        const val AUTO_SELECT = "AutoSelect"
        const val HINT = "Hint"
        const val ORDER_POLICY = "OrderPolicy"
        const val AUTO_PHRASE_LENGTH = "AutoPhraseLength"
        const val SAVE_AUTO_PHRASE_AFTER = "SaveAutoPhraseAfter"
        val KEYS = setOf(AUTO_SELECT, HINT, ORDER_POLICY, AUTO_PHRASE_LENGTH, SAVE_AUTO_PHRASE_AFTER)

        val AUTO_PHRASE_LENGTHS = -1..8
        val SAVES_AFTER = -1..10
    }
}
