/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.FUZZY
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.PAGE_SIZE
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.PREDICTION
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.SHUANGPIN_PROFILE
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.TYPOS
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy

/**
 * The config of those who used libime's pinyin and tables, as the engine that replaced them
 * reads it: fcitx's profile names its input methods, and pinyin's config holds what the
 * androidengine addon's now does. Both files are fcitx's INI: `[section]` headers, `key=value`.
 */
object LibimeMigration {

    /** libime's input methods, each with the engine's that replaces it. */
    val INPUT_METHODS: Map<String, String> = mapOf(
        "pinyin" to Engines.PINYIN,
        "shuangpin" to Engines.SHUANGPIN,
        "wbx" to "engine-wubi",
        // 五笔拼音: the engine's 五笔 looks characters up by pinyin after z
        "wbpy" to "engine-wubi",
        "cangjie" to "engine-cangjie",
        "zrm" to "engine-ziranma",
        "erbi" to "engine-erbi",
    )

    const val ENABLED_IM = "EnabledIM"

    private class Section(val name: String, val lines: MutableList<String>)

    private val ITEM = Regex("""Groups/(\d+)/Items/\d+""")
    private val GROUP = Regex("""Groups/\d+""")

    /**
     * [profile] with libime's input methods replaced by the engine's, each group listing one
     * once (五笔 and 五笔拼音 are both the engine's 五笔 now); null if it names none of them.
     */
    fun profile(profile: String): String? {
        val sections = sections(profile)
        var changed = false
        val seen = HashMap<String, MutableSet<String>>()
        val next = HashMap<String, Int>()
        val out = ArrayList<Section>()
        for (section in sections) {
            val item = ITEM.matchEntire(section.name)
            when {
                item != null -> {
                    val group = item.groupValues[1]
                    val name = value(section, "Name")
                    val renamed = INPUT_METHODS[name]
                    if (renamed != null) {
                        set(section, "Name", renamed)
                        changed = true
                    }
                    if (seen.getOrPut(group) { HashSet() }.add(renamed ?: name.orEmpty())) {
                        val index = next[group] ?: 0
                        next[group] = index + 1
                        out += Section("Groups/$group/Items/$index", section.lines)
                    }
                }
                GROUP.matches(section.name) -> {
                    INPUT_METHODS[value(section, "DefaultIM")]?.let {
                        set(section, "DefaultIM", it)
                        changed = true
                    }
                    out += section
                }
                else -> out += section
            }
        }
        return if (changed) write(out) else null
    }

    /**
     * [config] with the list of input methods in [section] (`0=pinyin`, `1=wbx`, as fcitx writes
     * a list) naming the engine's instead of libime's, each once; null if it names none of them.
     * For chttrans's `EnabledIM`: those who had 繁體 on for pinyin keep it.
     */
    fun inputMethodList(config: String, section: String = ENABLED_IM): String? {
        val sections = sections(config)
        val list = sections.firstOrNull { it.name == section } ?: return null
        // the items are 0=, 1=, …: a comment in among them is not one
        val items = list.lines.filter { line -> key(line).let { it.isNotEmpty() && it.all(Char::isDigit) } }
        val names = items.map { it.substringAfter('=').trim().removeSurrounding("\"") }
        if (names.none { it in INPUT_METHODS }) return null
        list.lines.removeAll(items)
        names.map { INPUT_METHODS[it] ?: it }.distinct().forEachIndexed { i, name -> list.lines += "$i=$name" }
        return write(sections)
    }

    /** The androidengine addon's config holding what [pinyinConfig], libime pinyin's, set of it. */
    fun settings(pinyinConfig: String): String {
        val sections = sections(pinyinConfig)
        val top = sections.firstOrNull { it.name.isEmpty() }
        val fuzzy = sections.firstOrNull { it.name == FUZZY }
        val keys = Fuzzy.entries.map { it.name } + TYPOS
        val out = ArrayList<Section>()
        out += Section("", listOf(SHUANGPIN_PROFILE, PAGE_SIZE, PREDICTION).mapNotNullTo(ArrayList()) { key -> top?.let { line(it, key) } })
        out += Section(FUZZY, keys.mapNotNullTo(ArrayList()) { key -> fuzzy?.let { line(it, key) } })
        return write(out)
    }

    private fun sections(text: String): List<Section> {
        val out = arrayListOf(Section("", ArrayList()))
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                out += Section(trimmed.substring(1, trimmed.length - 1), ArrayList())
            } else if (trimmed.isNotEmpty()) {
                out.last().lines += line
            }
        }
        return out
    }

    private fun write(sections: List<Section>): String = buildString {
        for (section in sections) {
            if (section.lines.isEmpty()) continue
            if (section.name.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append('[').append(section.name).append("]\n")
            }
            section.lines.forEach { append(it).append('\n') }
        }
    }

    private fun key(line: String) = line.substringBefore('=', "").trim()

    private fun line(section: Section, key: String): String? = section.lines.firstOrNull { key(it) == key }

    // fcitx quotes a value only when it must; the names here never need it
    private fun value(section: Section, key: String): String? =
        line(section, key)?.substringAfter('=')?.trim()?.removeSurrounding("\"")

    private fun set(section: Section, key: String, value: String) {
        val i = section.lines.indexOfFirst { key(it) == key }
        section.lines[i] = "$key=$value"
    }
}
