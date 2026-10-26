/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.host.ConfigTree

/** fcitx's `RawConfig` as the app's [RawConfig] ([name] the top node's), every leaf with no sub-items as JNI made it. */
fun ConfigTree.toRawConfig(name: String = this.name): RawConfig =
    if (!hasSubItems) RawConfig(name, "", value, null) else RawConfig(name, "", value, subItems.map { it.toRawConfig() }.toTypedArray())

/** The app's [RawConfig] as fcitx's, as `jobjectFillRawConfig` read it: a node's value only where it has no sub-items. */
fun RawConfig.toConfigTree(into: ConfigTree = ConfigTree(name)): ConfigTree {
    val items = subItems
    if (items == null) {
        into.value = value
    } else {
        for (item in items) item.toConfigTree(into[item.name])
    }
    return into
}
