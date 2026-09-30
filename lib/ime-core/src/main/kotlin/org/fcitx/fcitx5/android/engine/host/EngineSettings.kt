/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme

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
) {
    val scheme: ShuangpinScheme get() = SCHEMES[shuangpin] ?: ShuangpinScheme.ZIRANMA

    companion object {
        const val SHUANGPIN_PROFILE = "ShuangpinProfile"
        const val PAGE_SIZE = "PageSize"
        const val PREDICTION = "Prediction"
        const val FUZZY = "Fuzzy"
        const val SENTENCE_MODEL = "SentenceModel"

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
            val default = EngineSettings()
            return EngineSettings(
                shuangpin = values[SHUANGPIN_PROFILE]?.takeIf { it in SCHEMES } ?: default.shuangpin,
                fuzzy = Fuzzy.entries.filterTo(HashSet()) { flag("$FUZZY/${it.name}") == true },
                typos = flag("$FUZZY/$TYPOS") ?: default.typos,
                prediction = flag(PREDICTION) ?: default.prediction,
                pageSize = values[PAGE_SIZE]?.toIntOrNull()?.takeIf { it in PAGE_SIZES } ?: default.pageSize,
                sentenceModel = flag(SENTENCE_MODEL) ?: default.sentenceModel,
            )
        }
    }
}
