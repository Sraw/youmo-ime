/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** fcitx's `RawConfig`: children in the order made, reached by `/` paths. [implicit] is `Configuration::save`'s mark for an option at its default, written as a comment. */
class ConfigTree(val name: String = "", var value: String = "") {
    var implicit: Boolean = false
    private val children = LinkedHashMap<String, ConfigTree>()

    val subItems: Collection<ConfigTree> get() = children.values
    val hasSubItems: Boolean get() = children.isNotEmpty()

    /** `RawConfig::get(path)`. */
    fun find(path: String): ConfigTree? {
        var at = this
        for (part in path.split('/')) at = at.children[part] ?: return null
        return at
    }

    /** `RawConfig::get(path, true)`. */
    operator fun get(path: String): ConfigTree {
        var at = this
        for (part in path.split('/')) at = at.children.getOrPut(part) { ConfigTree(part) }
        return at
    }

    /** Every childless node's value by path, in order. */
    fun leaves(): Map<String, String> = LinkedHashMap<String, String>().also { out ->
        fun walk(node: ConfigTree, prefix: String) {
            for (child in node.children.values) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.hasSubItems) walk(child, path) else out[path] = child.value
            }
        }
        walk(this, "")
    }

    companion object {
        fun of(leaves: Map<String, String>): ConfigTree = ConfigTree().also { tree ->
            leaves.forEach { (path, value) -> tree[path].value = value }
        }
    }
}
