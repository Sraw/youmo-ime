/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/**
 * 双拼's stored scheme read as 0.5 has the schemes (小鹤, 微软, 自然码; 小鹤 default). Runs on every config read
 * and depends only on its inputs, so a config not written back reads the same again: a dropped scheme ([RETIRED])
 * becomes 小鹤 with the user told; none stored on an [upgrade], empty or unknown becomes 自然码 (0.4's unwritten
 * default) silently; anything else stays. Learning under a dropped scheme stays as is.
 */
object SchemeMigration {

    /** Offered by 0.4, dropped in 0.5, by libime's names. */
    val RETIRED: Set<String> = setOf("Ziguang", "ABC", "Zhongwenzhixing", "PinyinJiajia", "GB Standard")

    /** [retired]: the dropped scheme it replaces, for the user to be told. */
    data class Outcome(val name: String, val retired: String? = null)

    /** [stored] null: none in the config; returns null for nothing to change. */
    fun settle(stored: String?, upgrade: Boolean): Outcome? = when {
        stored in RETIRED -> Outcome(EngineSettings.XIAOHE, retired = stored)
        upgrade && stored !in EngineSettings.SCHEMES -> Outcome(EngineSettings.ZIRANMA)
        else -> null
    }
}
