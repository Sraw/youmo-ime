/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.ModelLevel
import org.fcitx.fcitx5.android.engine.host.TableSettings

/**
 * The androidengine addon's config, `conf/androidengine.conf`: the option table of
 * `androidengine.h:27-198` (`AndroidEngineConfig` and the pages over it) with fcitx's
 * `Configuration` semantics (`fcitx-config/configuration.cpp:109-139`, `option.h:434-443`). Reading
 * and saving the file, each input method's page, the page's partial save and the flat settings the
 * engine reads ([EngineSettings.parse]) are all here; the host only moves text and trees.
 *
 * A value that does not read (a bad boolean, a number out of range or not one, a name not known)
 * falls back to its default, not to the nearest value. A key the table does not have is dropped at
 * the next save, as fcitx's `save()` writes only its options.
 */
class EngineOptions {

    /** How a value is read: the canonical text of a value that reads, null for one that does not. */
    sealed interface Kind {
        fun read(value: String): String?
    }

    /** `marshallfunction.cpp:21-32`: `True` or `False` exactly. */
    data object Flag : Kind {
        override fun read(value: String) = value.takeIf { it == "True" || it == "False" }
    }

    /** `Option<int, IntConstrain>`: `std::from_chars` (a leading `-`, digits, the rest ignored), then the range (`option.h:434-443`). */
    class Number(val range: IntRange) : Kind {
        override fun read(value: String): String? {
            var end = if (value.startsWith('-')) 1 else 0
            while (end < value.length && value[end] in '0'..'9') end++
            return value.substring(0, end).toIntOrNull()?.takeIf { it in range }?.toString()
        }
    }

    /** An enum by its names (`enum.h:53-63`). */
    class Names(val names: List<String>) : Kind {
        override fun read(value: String) = value.takeIf { it in names }
    }

    class Option(val key: String, val kind: Kind, val default: String)

    /**
     * A sub-configuration option (`Option<EngineFuzzyConfig>`, a table's `Option<EngineTableConfig>`).
     * Its [options]' defaults are those of a fresh sub-configuration: what an item missing or bad in a
     * group that is there falls back to, and what an item is written as implicit against. [defaults]
     * are the option's own (`engineTable(hint)`): what the whole group is when the file has no
     * `[group]`, and what the group is implicit against.
     */
    class Group(val key: String, val options: List<Option>, val defaults: Map<String, String> = emptyMap()) {
        fun default(option: Option) = defaults[option.key] ?: option.default
    }

    // androidengine.h's options, each written once and shared by the pages, as there
    private val shuangpin = Option(EngineSettings.SHUANGPIN_PROFILE, Names(EngineSettings.SCHEMES.keys.toList()), EngineSettings.ZIRANMA)
    private val pageSize = Option(EngineSettings.PAGE_SIZE, Number(EngineSettings.PAGE_SIZES), EngineSettings.DEFAULT_PAGE_SIZE.toString())
    private val prediction = Option(EngineSettings.PREDICTION, Flag, TRUE)
    private val emoji = Option(EngineSettings.EMOJI, Flag, TRUE)
    private val chaizi = Option(EngineSettings.CHAIZI, Flag, TRUE)
    private val builtInPhrases = Option(EngineSettings.BUILT_IN_PHRASES, Flag, TRUE)
    private val modelLevel = Option(EngineSettings.MODEL_LEVEL, Names(ModelLevel.entries.map { it.key }), ModelLevel.HIGH.key)
    private val latinWords = Option(EngineSettings.LATIN_WORDS, Flag, TRUE)
    private val englishSuggest = Option(EngineSettings.ENGLISH_SUGGEST, Flag, TRUE)
    private val fuzzy = Group(
        EngineSettings.FUZZY,
        listOf(Option(EngineSettings.TYPOS, Flag, TRUE)) + FUZZY_KEYS.map { Option(it, Flag, FALSE) },
    )

    // ENGINE_TABLE_OPTIONS: libime table's keys; the engine orders by use alike for Freq and Fast
    private val tableOptions = listOf(
        Option(TableSettings.AUTO_SELECT, Flag, TRUE),
        Option(TableSettings.HINT, Flag, TRUE),
        Option(TableSettings.ORDER_POLICY, Names(listOf("No", "Freq", "Fast")), "No"),
    )

    // each table's group, named as androidengine.cpp lists them; engineTable(hint) of the preset
    private val tables: Map<String, Group> = Engines.TABLES.mapValues { (_, method) ->
        Group(method.group, tableOptions, mapOf(TableSettings.HINT to flag(method.options.hint)))
    }

    /** `AndroidEngineConfig`'s options in order (its two `ExternalOption`s are never saved, `option.cpp:73`). */
    private val entries: List<Any> = listOf(
        shuangpin, pageSize, prediction, emoji, chaizi, builtInPhrases, modelLevel, latinWords, fuzzy, englishSuggest,
    ) + tables.values

    private val values = HashMap<String, String>()

    init {
        reset()
    }

    private fun reset() {
        values.clear()
        for (entry in entries) {
            when (entry) {
                is Option -> values[entry.key] = entry.default
                is Group -> entry.options.forEach { values[path(entry, it)] = entry.default(it) }
            }
        }
    }

    /** The value at [path] (`PageSize`, `Fuzzy/L_N`, `Wubi/Hint`), null for a path the table has none at. */
    operator fun get(path: String): String? = values[path]

    /** `AndroidEngineConfig::pageSize`, what the router pages punctuation's marks by. */
    val pageSizeValue: Int get() = values.getValue(pageSize.key).toInt()

    /** `englishSuggest`: whether English offers words (`AndroidEngine::suggestsEnglish`). */
    val suggestsEnglish: Boolean get() = values.getValue(englishSuggest.key) == TRUE

    /**
     * Reads [text], the file, as the addon does at start (`readAsIni` → `Configuration::load(raw)`,
     * not partial): what it does not say is the default.
     */
    fun load(text: String) {
        reset()
        load(IniFile.read(text), partial = false)
    }

    // Configuration::load (configuration.cpp:109-124) over entries
    private fun load(tree: ConfigTree, partial: Boolean) {
        for (entry in entries) {
            when (entry) {
                is Option -> tree.find(entry.key)?.let { node ->
                    values[entry.key] = entry.kind.read(node.value) ?: entry.default
                }
                is Group -> tree.find(entry.key)?.let { node ->
                    // not partial, Option::unmarshall starts from a fresh sub-configuration (option.h:435-438)
                    if (!partial) freshGroup(entry)
                    loadGroup(entry, node)
                }
            }
        }
    }

    private fun freshGroup(group: Group) = group.options.forEach { values[path(group, it)] = it.default }

    // a sub-configuration's own Configuration::load, partial or not as its caller's: an item
    // missing keeps what is there (the fresh default, unless partial), a bad one resets to the fresh default
    private fun loadGroup(group: Group, node: ConfigTree) {
        for (option in group.options) {
            val item = node.find(option.key) ?: continue
            values[path(group, option)] = option.kind.read(item.value) ?: option.default
        }
    }

    /** The config as `Configuration::save` makes it (`configuration.cpp:126-139`), each item implicit at its default. */
    fun save(): ConfigTree = ConfigTree().also { tree ->
        for (entry in entries) {
            when (entry) {
                is Option -> tree[entry.key].also {
                    it.value = values.getValue(entry.key)
                    it.implicit = it.value == entry.default
                }
                is Group -> {
                    val node = tree[entry.key]
                    for (option in entry.options) {
                        node[option.key].also {
                            it.value = values.getValue(path(entry, option))
                            it.implicit = it.value == option.default
                        }
                    }
                    node.implicit = entry.options.all { values.getValue(path(entry, it)) == entry.default(it) }
                }
            }
        }
    }

    /** The file's text, as `safeSaveAsIni` writes it but for the description comments. */
    fun text(): String = IniFile.write(save())

    /**
     * The settings as the addon pushes them to the engine (`androidengine.cpp:473-491`): every
     * value, defaults too, `key=value` a line, a group's under `group/` — [EngineSettings.parse]'s input.
     */
    fun flat(): String = buildString {
        for ((path, value) in save().leaves()) append(path).append('=').append(value).append('\n')
    }

    /**
     * [im]'s page as `getConfigForInputMethod` gives it (`androidengine.cpp:421-450`): the options
     * it shows under the same keys, a table's own at the top beside the shared ones; null for an
     * input method the addon has no page for.
     */
    fun page(im: String): ConfigTree? {
        val tree = ConfigTree()
        val table = tables[im]
        val shown = pageOf(im) ?: return null
        for (entry in shown) {
            when (entry) {
                is Option -> tree[entry.key].also {
                    it.value = values.getValue(entry.key)
                    it.implicit = it.value == entry.default
                }
                is Group -> {
                    val node = tree[entry.key]
                    entry.options.forEach { option ->
                        node[option.key].also {
                            it.value = values.getValue(path(entry, option))
                            it.implicit = it.value == option.default
                        }
                    }
                    node.implicit = entry.options.all { node[it.key].implicit }
                }
            }
        }
        // ENGINE_TABLE_OPTIONS on EngineTablePage: implicit against a fresh table config's defaults
        table?.options?.forEach { option ->
            tree[option.key].also {
                it.value = values.getValue(path(table, option))
                it.implicit = it.value == option.default
            }
        }
        return tree
    }

    /**
     * [page], what the app saves of [im]'s page (only what it shows), loaded partially as
     * `setConfigForInputMethod` does (`androidengine.cpp:452-470`): a table's own keys into its group,
     * then every key the config has at its path; a bad value resets that option. False, nothing
     * changed, for an input method with no page.
     */
    fun setPage(im: String, page: ConfigTree): Boolean {
        if (pageOf(im) == null) return false
        tables[im]?.let { loadGroup(it, page) }
        load(page, partial = true)
        return true
    }

    // the entries of androidengine.h's page for [im]: EnginePinyinPage and the others
    private fun pageOf(im: String): List<Any>? = when (im) {
        Engines.PINYIN, Engines.T9 -> listOf(pageSize, prediction, emoji, chaizi, builtInPhrases, modelLevel, latinWords, fuzzy)
        Engines.SHUANGPIN -> listOf(shuangpin, pageSize, prediction, emoji, modelLevel, fuzzy)
        Engines.ENGLISH -> listOf(englishSuggest)
        Engines.HANDWRITING -> listOf(pageSize, prediction, modelLevel)
        in tables -> listOf(pageSize, prediction, emoji, modelLevel)
        else -> null
    }

    private fun path(group: Group, option: Option) = "${group.key}/${option.key}"

    companion object {
        const val PATH = "conf/androidengine.conf"
        private const val TRUE = "True"
        private const val FALSE = "False"

        // EngineFuzzyConfig's order after NG_GN (androidengine.h:36-51)
        private val FUZZY_KEYS = listOf(
            "V_U", "AN_ANG", "EN_ENG", "IAN_IANG", "IN_ING", "U_OU", "UAN_UANG", "C_CH", "F_H", "L_N", "L_R", "S_SH", "Z_ZH",
        )

        private fun flag(on: Boolean) = if (on) TRUE else FALSE
    }
}
