/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxUtils

/** fcitx's INI as `iniparser.cpp` reads and writes it; nothing trimmed around `=`. */
object IniFile {

    /** As `readFromIni`: lines trimmed, `#` lines skipped (a `# Key=Value` default is absent), split at the first `=`, the value unquoted, a line whose quote closes early skipped. */
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

    /** As `writeAsIni`, without description comments (fcitx never reads them); implicit nodes' lines and headers written with `# `. */
    fun write(tree: ConfigTree): String = StringBuilder().also { write(tree, "", tree.implicit, it) }.toString()

    // as RawConfig::isImplicit asks its parents
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
