/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/**
 * 双拼's scheme as a stored config names it, read as 0.5 has the schemes (the user's 2026-11-01):
 * 小鹤, 微软 and 自然码 only, 小鹤 the default (the option's: a new install's, a config with none, a
 * reset). Run at every read of the config, an upgrade's and a backup restored alike, decided by its
 * inputs alone, so a config not written back is read the same way again.
 *
 * - a scheme 0.5 dropped ([RETIRED]): 小鹤, the user told which;
 * - none stored on an [upgrade] (an install from before 0.5, or a backup of one restored: 自然码 was
 *   their default, never written), or one empty or not known, which 0.4 read as its default too:
 *   自然码, written so, nothing told;
 * - any other: as it is (none stored is 小鹤; one not known falls back as any bad value, EngineOptions).
 *
 * What was learned under a dropped scheme (`sp:Ziguang`, `spk:Ziguang:…`) stays as it is.
 */
object SchemeMigration {

    /** The schemes 0.4 offered and 0.5 does not, by libime's names for them. */
    val RETIRED: Set<String> = setOf("Ziguang", "ABC", "Zhongwenzhixing", "PinyinJiajia", "GB Standard")

    /** The scheme to set, [name]; [retired], the dropped one it replaces, for the user to be told. */
    data class Outcome(val name: String, val retired: String? = null)

    /** What [stored] (null: the config has no scheme) becomes; null, nothing to change. */
    fun settle(stored: String?, upgrade: Boolean): Outcome? = when {
        stored in RETIRED -> Outcome(EngineSettings.XIAOHE, retired = stored)
        upgrade && stored !in EngineSettings.SCHEMES -> Outcome(EngineSettings.ZIRANMA)
        else -> null
    }
}
