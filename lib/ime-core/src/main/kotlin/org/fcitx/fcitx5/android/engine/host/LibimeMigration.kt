/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.ENGLISH_SUGGEST
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.FUZZY
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.PAGE_SIZE
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.PREDICTION
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.SHUANGPIN_PROFILE
import org.fcitx.fcitx5.android.engine.host.EngineSettings.Companion.TYPOS
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.table.IniText

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
    ) + Engines.TABLES.entries.associate { (im, method) -> method.libime to im }

    const val ENABLED_IM = "EnabledIM"
    private const val TABLE = "Table"

    private class Section(val name: String, val lines: MutableList<String>)

    private val ITEM = Regex("""Groups/(\d+)/Items/\d+""")
    private val GROUP = Regex("""Groups/\d+""")

    /**
     * [profile] with libime's input methods replaced by the engine's, each group listing one
     * once (one already migrated beside libime's); null if it names none of them.
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
        val items = list.lines.filter { line -> IniText.key(line)?.all(Char::isDigit) == true }
        val names = items.map(IniText::value)
        if (names.none { it in INPUT_METHODS }) return null
        list.lines.removeAll(items)
        names.map { INPUT_METHODS[it] ?: it }.distinct().forEachIndexed { i, name -> list.lines += "$i=$name" }
        return write(sections)
    }

    /**
     * libime's tables (the [TableMethod.libime] of each of [Engines.TABLES]) whose settings
     * carry over, kept as `table/<name>.conf` in fcitx's config home, each under the engine's
     * table it became.
     */
    val TABLE_CONFIGS: List<String> = Engines.TABLES.values.map { it.libime }

    /**
     * The androidengine addon's config holding what [pinyinConfig], libime pinyin's, set of it,
     * and what each table's config of [tableConfigs] (by libime's name, as in [TABLE_CONFIGS]) set
     * of what the engine's tables do.
     */
    fun settings(pinyinConfig: String, tableConfigs: Map<String, String> = emptyMap()): String {
        val sections = sections(pinyinConfig)
        val top = sections.firstOrNull { it.name.isEmpty() }
        val fuzzy = sections.firstOrNull { it.name == FUZZY }
        val keys = Fuzzy.entries.map { it.name } + TYPOS
        val out = ArrayList<Section>()
        out += Section("", listOf(SHUANGPIN_PROFILE, PAGE_SIZE, PREDICTION).mapNotNullTo(ArrayList()) { key -> top?.let { line(it, key) } })
        out += Section(FUZZY, keys.mapNotNullTo(ArrayList()) { key -> fuzzy?.let { line(it, key) } })
        out += tableSections(tableConfigs)
        return write(out)
    }

    /**
     * [config], the addon's, with the settings of [tableConfigs] (as for [settings]) for each
     * table it has no group of yet; null if there is none. For a config written before tables
     * had settings: fcitx writes every group once the user saves it.
     */
    fun withTables(config: String, tableConfigs: Map<String, String>): String? {
        val have = sections(config).mapTo(HashSet()) { it.name }
        val missing = tableSections(tableConfigs).filter { it.name !in have }
        if (missing.isEmpty()) return null
        val base = config.trimEnd()
        return (if (base.isEmpty()) "" else base + "\n\n") + write(missing)
    }

    /** androidkeyboard's switch for its word hints: fcitx's English, which English's session replaced. */
    private const val WORD_HINT = "EnableWordHint"

    /**
     * [config], the androidengine addon's ("" for none yet), with English's suggestions off where
     * [keyboardConfig], the androidkeyboard addon's, had its word hints off; null where it had them
     * on, or [config] says already (once the user saved the English page).
     */
    fun englishSuggest(config: String, keyboardConfig: String): String? {
        val hint = sections(keyboardConfig).first().let { value(it, WORD_HINT) }
        if (!hint.equals("False", ignoreCase = true)) return null
        val sections = sections(config)
        val top = sections.first()
        if (line(top, ENGLISH_SUGGEST) != null) return null
        top.lines += "$ENGLISH_SUGGEST=False"
        return write(sections)
    }

    /**
     * [config], the androidengine addon's ("" for none yet), with 调频 kept on for [ims], the
     * tables someone typed with before it came off on a new install (U-28 D7): written where their
     * group does not say, as the old default had it on, or where it did not say in [unset] ([orderUnset]
     * of the config before: what was written since is fcitx's default, not the user's). Null where
     * nothing changes; a table whose preset never ordered by use (仓颉) is left as it is.
     */
    fun keepOrderByUse(config: String, ims: Collection<String>, unset: Set<String> = emptySet()): String? {
        val sections = sections(config).toMutableList()
        var changed = false
        for (im in ims) {
            val group = Engines.TABLES[im]?.takeIf { it.options.orderByUse }?.group ?: continue
            val section = sections.firstOrNull { it.name == group } ?: Section(group, ArrayList()).also { sections += it }
            val found = value(section, TableSettings.ORDER_POLICY)
            if (found == null) {
                section.lines += "${TableSettings.ORDER_POLICY}=${TableSettings.ORDER_BY_USE}"
                changed = true
            } else if (im in unset && found != TableSettings.ORDER_BY_USE) {
                set(section, TableSettings.ORDER_POLICY, TableSettings.ORDER_BY_USE)
                changed = true
            }
        }
        return if (changed) write(sections) else null
    }

    /** The tables whose group in [config], the androidengine addon's ("" for none yet), does not say whether to order by use. */
    fun orderUnset(config: String): Set<String> {
        val sections = sections(config)
        return Engines.TABLES.filterValues { table ->
            sections.firstOrNull { it.name == table.group }?.let { line(it, TableSettings.ORDER_POLICY) } == null
        }.keys.toSet()
    }

    // each group's from the first of TABLE_CONFIGS that set any of it
    private fun tableSections(tableConfigs: Map<String, String>): List<Section> {
        val out = LinkedHashMap<String, Section>()
        for (name in TABLE_CONFIGS) {
            val group = Engines.TABLES.getValue(INPUT_METHODS.getValue(name)).group
            val table = tableConfigs[name]?.let { config -> sections(config).firstOrNull { it.name == TABLE } }
            val lines = table?.let { TableSettings.KEYS.mapNotNullTo(ArrayList()) { key -> line(it, key) } }
            if (group !in out && !lines.isNullOrEmpty()) out[group] = Section(group, lines)
        }
        return out.values.toList()
    }

    private fun sections(text: String): List<Section> =
        IniText.sections(text).map { (name, lines) -> Section(name, lines.toMutableList()) }

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

    private fun line(section: Section, key: String): String? = section.lines.firstOrNull { IniText.key(it) == key }

    private fun value(section: Section, key: String): String? = line(section, key)?.let(IniText::value)

    private fun set(section: Section, key: String, value: String) {
        val i = section.lines.indexOfFirst { IniText.key(it) == key }
        section.lines[i] = "$key=$value"
    }
}
