/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

fun MenuItem.setup(
    @DrawableRes icon: Int,
    @ColorInt iconTint: Int,
    showAsAction: Boolean,
    onClick: Function0<Any?>?
): MenuItem {
    if (icon != 0 && iconTint != 0) {
        iconTintList = ColorStateList.valueOf(iconTint)
        setIcon(icon)
    }
    if (showAsAction) {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    if (onClick != null) {
        setOnMenuItemClickListener {
            // return false only when the actual callback returns false
            onClick.invoke() != false
        }
    }
    return this
}

fun Menu.item(
    @StringRes title: Int,
    @DrawableRes icon: Int = 0,
    @ColorInt iconTint: Int = 0,
    showAsAction: Boolean = false,
    onClick: Function0<Any?>? = null
): MenuItem {
    val item = add(title).setup(icon, iconTint, showAsAction, onClick)
    return item
}

fun Menu.item(
    title: CharSequence,
    @DrawableRes icon: Int = 0,
    @ColorInt iconTint: Int = 0,
    showAsAction: Boolean = false,
    onClick: Function0<Any?>? = null
): MenuItem {
    val item = add(title).setup(icon, iconTint, showAsAction, onClick)
    return item
}

fun Menu.subMenu(
    @StringRes title: Int,
    @DrawableRes icon: Int,
    @ColorInt iconTint: Int,
    showAsAction: Boolean = false,
    initSubMenu: SubMenu.() -> Unit
): SubMenu {
    val sub = addSubMenu(title)
    sub.item.setup(icon, iconTint, showAsAction, null)
    initSubMenu.invoke(sub)
    return sub
}
