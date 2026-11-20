/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** A 模糊音 pair: either spelling also matches the other's syllables. Applies to a whole initial or final, not chained: with L_N and L_R, `n` does not reach `r`. */
enum class Fuzzy(internal val a: String, internal val b: String, val onInitial: Boolean) {
    Z_ZH("z", "zh", true),
    C_CH("c", "ch", true),
    S_SH("s", "sh", true),
    L_N("l", "n", true),
    L_R("l", "r", true),
    F_H("f", "h", true),
    AN_ANG("an", "ang", false),
    EN_ENG("en", "eng", false),
    IN_ING("in", "ing", false),
    IAN_IANG("ian", "iang", false),
    UAN_UANG("uan", "uang", false),
    U_OU("u", "ou", false),

    /** lü and lu, nü and nu */
    V_U("v", "u", false),
    ;

    fun appliesAfter(initial: String): Boolean = when (this) {
        // the u of ju, qu, xu, yu is ü: jou is not a slip for it
        U_OU -> initial !in SpellingIndex.U_IS_V
        // only l and n have both lü and lu
        V_U -> initial == "l" || initial == "n"
        else -> true
    }

    fun partner(part: String): String? = when (part) {
        a -> b
        b -> a
        else -> null
    }
}
