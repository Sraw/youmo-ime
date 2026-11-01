/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.SchemeMigration

/**
 * 双拼's schemes named, by their config names: each of [EngineSettings.SCHEMES] and each 0.5 dropped
 * ([SchemeMigration.RETIRED]), as what was learned under one is still listed by it and the user is told
 * of it (KotlinHost). Here, not on the settings page, as the host names them too.
 */
object ShuangpinNames {

    private val NAMES = mapOf(
        EngineSettings.ZIRANMA to R.string.shuangpin_ziranma,
        EngineSettings.MS to R.string.shuangpin_ms,
        EngineSettings.XIAOHE to R.string.shuangpin_xiaohe,
        "Ziguang" to R.string.shuangpin_ziguang,
        "ABC" to R.string.shuangpin_abc,
        "Zhongwenzhixing" to R.string.shuangpin_zhongwenzhixing,
        "PinyinJiajia" to R.string.shuangpin_pinyinjiajia,
        "GB Standard" to R.string.shuangpin_gb,
    )

    /** The name of the scheme [key], null for a key of no scheme. */
    fun of(context: Context, key: String): String? = NAMES[key]?.let(context::getString)
}
