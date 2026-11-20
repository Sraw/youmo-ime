/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.editing.EditorTraits

/** A theme's scenes: `@im=` the kind (`pinyin`, `shuangpin`, `table`, `english`) and a table's name; `@field=` password, `url`, `email` or number; the words a layout's `when=ime=` uses. */
object SceneTyping {

    private const val ENGINE = "engine-"

    /** Its own keyboard after `#` aside. */
    fun scenes(uniqueName: String?, field: String?): Set<String> = buildSet {
        im(uniqueName).forEach { add("im=$it") }
        field?.let { add("field=$it") }
    }

    /** None for one this does not know. */
    fun im(uniqueName: String?): List<String> {
        val name = uniqueName?.substringBefore('#') ?: return emptyList()
        return when {
            name == InputMethodCatalog.PINYIN || name == InputMethodCatalog.T9 -> listOf("pinyin")
            name == InputMethodCatalog.SHUANGPIN -> listOf("shuangpin")
            InputMethodCatalog.hasTableKeys(name) -> listOf("table", name.removePrefix(ENGINE))
            name == InputMethodCatalog.ENGLISH -> listOf("english")
            else -> emptyList()
        }
    }

    /** A password first (the service's reading of variations), then URL, email, number; null for text. */
    fun field(traits: EditorTraits, password: Boolean): String? = when {
        password -> "password"
        traits.isUri -> "url"
        traits.isEmail -> "email"
        traits.isNumber -> "number"
        else -> null
    }
}
