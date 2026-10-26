/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * fcitx's `RawConfig` (`fcitx-config/rawconfig.cpp`): a named node with a value and children in
 * the order they were made, reached by a path of names joined by `/`. [implicit] is what
 * `Configuration::save` sets for an option at its default (`configuration.cpp:126-139`): written as
 * a comment, so a file says nothing of it. The app's `RawConfig` is converted to and from this at
 * the host's edge.
 */
class ConfigTree(val name: String = "", var value: String = "") {
    var implicit: Boolean = false
    private val children = LinkedHashMap<String, ConfigTree>()

    val subItems: Collection<ConfigTree> get() = children.values
    val hasSubItems: Boolean get() = children.isNotEmpty()

    /** The node at [path] (`a/b`), null if there is none (`RawConfig::get(path)`). */
    fun find(path: String): ConfigTree? {
        var at = this
        for (part in path.split('/')) at = at.children[part] ?: return null
        return at
    }

    /** The node at [path], made on the way if it is not there (`RawConfig::get(path, true)`). */
    operator fun get(path: String): ConfigTree {
        var at = this
        for (part in path.split('/')) at = at.children.getOrPut(part) { ConfigTree(part) }
        return at
    }

    /** Values by path (`Fuzzy/L_N`), every node with no children, in order: what a comparison of two trees needs. */
    fun leaves(): Map<String, String> = LinkedHashMap<String, String>().also { out ->
        fun walk(node: ConfigTree, prefix: String) {
            for (child in node.children.values) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.hasSubItems) walk(child, path) else out[path] = child.value
            }
        }
        walk(this, "")
    }
}
