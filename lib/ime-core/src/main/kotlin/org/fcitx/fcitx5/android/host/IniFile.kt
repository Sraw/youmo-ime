/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxUtils

/**
 * fcitx's INI as `fcitx-config/iniparser.cpp` reads and writes it, so a file either side wrote reads
 * the same on the other. Unlike `engine.table.IniText` (which trims around `=`), a key and its value
 * are taken as written between the trimmed line's ends.
 */
object IniFile {

    /**
     * [text] as `readFromIni` (`iniparser.cpp:59-104`) reads it: each line trimmed; empty and `#`
     * lines skipped (so `# Key=Value`, a default, is as if not there); `[a/b]` starts a group and
     * makes its nodes; `key=value` splits at the first `=`, nothing trimmed around it, the value
     * unquoted (`unescapeForValue`), a line whose quote closes early skipped.
     */
    fun read(text: String, into: ConfigTree = ConfigTree()): ConfigTree {
        var group = ""
        // std::getline splits at \n alone; a \r before it goes with the trim
        for (raw in text.split('\n')) {
            val line = raw.trim(::isFcitxSpace)
            val eq = line.indexOf('=')
            when {
                line.isEmpty() || line[0] == '#' -> {}
                line[0] == '[' && line.last() == ']' -> {
                    group = line.substring(1, line.length - 1)
                    into[group]
                }
                eq >= 0 -> FcitxUtils.unescapeForValue(line.substring(eq + 1))?.let { value ->
                    val name = line.substring(0, eq)
                    into[if (group.isEmpty()) name else "$group/$name"].value = value
                }
            }
        }
        return into
    }

    /**
     * [tree] as `writeAsIni` (`iniparser.cpp:106-148`) writes it, without the description comments
     * (`Configuration::save` puts a translated one over each option; fcitx never reads them): a
     * node's leaves as `Key=Value` (`# Key=Value` if it or a node above it is
     * [ConfigTree.implicit]), under its `[path]` (`# [path]` if the node or one above is implicit), a blank line after; then each child the same way.
     */
    fun write(tree: ConfigTree): String = StringBuilder().also { write(tree, "", tree.implicit, it) }.toString()

    // [implicit]: the node or one above it is, as RawConfig::isImplicit asks its parents (rawconfig.cpp:232-241)
    private fun write(node: ConfigTree, path: String, implicit: Boolean, out: StringBuilder) {
        if (node.hasSubItems) {
            val values = StringBuilder()
            for (child in node.subItems) {
                if (child.hasSubItems && child.value.isEmpty()) continue
                if (implicit || child.implicit) values.append("# ")
                values.append(child.name).append('=').append(FcitxUtils.escapeForValue(child.value)).append('\n')
            }
            if (values.isNotEmpty()) {
                if (path.isNotEmpty()) out.append(if (implicit) "# [" else "[").append(path).append("]\n")
                out.append(values).append('\n')
            }
        }
        for (child in node.subItems) {
            write(child, if (path.isEmpty()) child.name else "$path/${child.name}", implicit || child.implicit, out)
        }
    }

    // fcitx's trimView: FCITX_WHITESPACE, "\f\n\r\t\v "
    private fun isFcitxSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
}
