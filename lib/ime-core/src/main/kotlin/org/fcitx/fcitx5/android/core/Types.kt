/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

data class InputMethodSubMode(val name: String, val label: String, val icon: String) {
    constructor() : this("", "", "")
}

data class InputMethodEntry(
    val uniqueName: String,
    val name: String,
    val icon: String,
    val nativeName: String,
    val label: String,
    val languageCode: String,
    val addon: String,
    val isConfigurable: Boolean,
    val subMode: InputMethodSubMode
) {
    constructor(
        uniqueName: String,
        name: String,
        icon: String,
        nativeName: String,
        label: String,
        languageCode: String,
        addon: String,
        isConfigurable: Boolean
    ) : this(
        uniqueName,
        name,
        icon,
        nativeName,
        label,
        languageCode,
        addon,
        isConfigurable,
        InputMethodSubMode()
    )

    constructor(
        uniqueName: String,
        name: String,
        icon: String,
        nativeName: String,
        label: String,
        languageCode: String,
        addon: String,
        isConfigurable: Boolean,
        subMode: String,
        subModeLabel: String,
        subModeIcon: String
    ) : this(
        uniqueName,
        name,
        icon,
        nativeName,
        label,
        languageCode,
        addon,
        isConfigurable,
        InputMethodSubMode(subMode, subModeLabel, subModeIcon)
    )

    constructor(name: String) : this("", name, "", "", "×", "", "", false)

    val displayName: String
        get() = name.ifEmpty { uniqueName }
}

data class Action(
    val id: Int,
    val isSeparator: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val name: String,
    val icon: String,
    val shortText: String,
    val longText: String,
    val menu: Array<Action>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Action

        if (id != other.id) return false
        if (name != other.name) return false
        if (menu != null) {
            if (other.menu == null) return false
            if (!menu.contentEquals(other.menu)) return false
        } else if (other.menu != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + name.hashCode()
        result = 31 * result + (menu?.contentHashCode() ?: 0)
        return result
    }
}

data class CandidateAction(
    val id: Int,
    val text: String,
    val isSeparator: Boolean,
    val icon: String,
    val isCheckable: Boolean,
    val isChecked: Boolean
)
