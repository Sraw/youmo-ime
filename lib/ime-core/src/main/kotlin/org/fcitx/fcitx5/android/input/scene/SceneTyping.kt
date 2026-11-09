/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.editing.EditorTraits

/**
 * What is typed as a theme's scenes (060-render-p3 T5, [org.fcitx.fcitx5.android.scene.engine.Env.typed]):
 * `@im=` the input method by its kind (`pinyin`, `shuangpin`, `table`, `handwriting`, `english`) and a
 * table also by its name (`wubi`, `cangjie`, `ziranma`, `erbi`, `wubipinyin`); `@field=` a password,
 * an address (`url`, `email`) or a number's field; a text field none. The kinds are the words a
 * layout's `when=ime=` uses, so a theme and a layout name an input method alike.
 */
object SceneTyping {

    private const val ENGINE = "engine-"

    /** The theme's scenes of input method [uniqueName] (its own keyboard after `#` aside) in [field] ([field] of it). */
    fun scenes(uniqueName: String?, field: String?): Set<String> = buildSet {
        im(uniqueName).forEach { add("im=$it") }
        field?.let { add("field=$it") }
    }

    /** The words of input method [uniqueName]: its kind, and a table's name; none, one this does not know. */
    fun im(uniqueName: String?): List<String> {
        val name = uniqueName?.substringBefore('#') ?: return emptyList()
        return when {
            name == InputMethodCatalog.PINYIN || name == InputMethodCatalog.T9 -> listOf("pinyin")
            name == InputMethodCatalog.SHUANGPIN -> listOf("shuangpin")
            InputMethodCatalog.hasTableKeys(name) -> listOf("table", name.removePrefix(ENGINE))
            name == InputMethodCatalog.HANDWRITING -> listOf("handwriting")
            name == InputMethodCatalog.ENGLISH -> listOf("english")
            else -> emptyList()
        }
    }

    /**
     * The field as a scene: a password first (the service's, which reads the input type's variations),
     * then a URL's, an email address's, a number's; null, a text field.
     */
    fun field(traits: EditorTraits, password: Boolean): String? = when {
        password -> "password"
        traits.isUri -> "url"
        traits.isEmail -> "email"
        traits.isNumber -> "number"
        else -> null
    }
}
