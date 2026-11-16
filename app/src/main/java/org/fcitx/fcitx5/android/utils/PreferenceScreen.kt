/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import splitties.resources.drawable

fun PreferenceScreen.addCategory(title: String, block: PreferenceCategory.() -> Unit) {
    val category = PreferenceCategory(context).apply {
        setTitle(title)
    }
    addPreference(category)
    block.invoke(category)
}

fun PreferenceScreen.addCategory(@StringRes title: Int, block: PreferenceCategory.() -> Unit) {
    addCategory(context.getString(title), block)
}

fun Preference.setup(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null
) {
    isSingleLineTitle = false
    setTitle(title)
    setSummary(summary)
    if (icon == null) {
        isIconSpaceReserved = false
    } else {
        setIcon(context.drawable(icon)?.apply {
            setTint(context.styledColor(android.R.attr.colorControlNormal))
        })
    }
    onClick?.also {
        setOnPreferenceClickListener { _ ->
            it.invoke()
            true
        }
    }
}

/** A row; [key], where a settings search points at it (SearchablePage), the row's own id. */
fun PreferenceGroup.addPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    key: String? = null,
    onClick: (() -> Unit)? = null
) {
    addPreference(Preference(context).apply {
        setup(title, summary, icon, onClick)
        this.key = key
    })
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    summary: String,
    @DrawableRes icon: Int? = null,
    key: String? = null,
    onClick: (() -> Unit)? = null
) {
    addPreference(context.getString(title), summary, icon, key, onClick)
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    key: String? = null,
    onClick: (() -> Unit)? = null
) {
    addPreference(context.getString(title), summary?.let(context::getString), icon, key, onClick)
}

