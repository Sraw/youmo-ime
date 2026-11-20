/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** fcitx's `config/profile` (`InputMethodConfig`), with fcitx's semantics, not its bytes: either writes a file the other reads the same. */
class Profile private constructor(val groups: List<Group>, val order: List<String>) {

    data class Item(val name: String, val layout: String = "")

    data class Group(val name: String, val defaultLayout: String, val defaultInputMethod: String, val items: List<Item>)

    val current: Group get() = groups.first { it.name == order.first() }

    fun currentGroup(): ImGroup = current.let { ImGroup.of(it.name, it.items.map(Item::name), it.defaultInputMethod) }

    /** As fcitx's `setGroup` keeps a group: its items' layouts, by name; its default layout. */
    fun with(group: ImGroup): Profile {
        val old = current
        val layouts = old.items.associate { it.name to it.layout }
        val updated = Group(old.name, old.defaultLayout, group.defaultInputMethod, group.inputMethods.map { Item(it, layouts[it].orEmpty()) })
        return Profile(groups.map { if (it.name == old.name) updated else it }, order)
    }

    data class Retired(val profile: Profile, val changed: Boolean, val moved: Boolean)

    /** 0.6.0's handwriting gone from every group, pinyin first where it was first; [load] must keep [HandwritingRetire.OLD] for this to see it. */
    fun retireHandwriting(): Retired {
        var changed = false
        var moved = false
        val retired = groups.map { group ->
            val out = HandwritingRetire.group(group.items.map(Item::name), group.defaultInputMethod) ?: return@map group
            changed = true
            moved = moved || out.moved
            val layouts = group.items.associate { it.name to it.layout }
            group.copy(defaultInputMethod = out.defaultIm, items = out.items.map { Item(it, layouts[it].orEmpty()) })
        }
        return Retired(Profile(retired, order), changed, moved)
    }

    /** `writeAsIni`: a value equal to its default ("" for all) is implicit, written as a comment (`# Layout=`); descriptions left out. */
    fun text(): String {
        val tree = ConfigTree()
        fun ConfigTree.put(key: String, value: String) = get(key).also {
            it.value = value
            it.implicit = value.isEmpty()
        }
        order.map { name -> groups.first { it.name == name } }.forEachIndexed { g, group ->
            val node = tree["$GROUPS/$g"]
            node.put(NAME, group.name)
            node.put(DEFAULT_LAYOUT, group.defaultLayout)
            node.put(DEFAULT_IM, group.defaultInputMethod)
            group.items.forEachIndexed { i, item ->
                val it = node["$ITEMS/$i"]
                it.put(NAME, item.name)
                it.put(LAYOUT, item.layout)
            }
        }
        val groupOrder = tree[GROUP_ORDER]
        order.forEachIndexed { i, name -> groupOrder.put(i.toString(), name) }
        return IniFile.write(tree)
    }

    companion object {
        private const val GROUPS = "Groups"
        private const val ITEMS = "Items"
        private const val NAME = "Name"
        private const val LAYOUT = "Layout"
        private const val DEFAULT_LAYOUT = "Default Layout"
        private const val DEFAULT_IM = "DefaultIM"
        private const val GROUP_ORDER = "GroupOrder"

        /** `native-lib`'s layout for every group it makes, and fcitx's default group's. */
        const val DEFAULT_LAYOUT_US = "us"

        /** `loadConfig`: invalid groups and unavailable items dropped, empty groups removed; no valid group: [defaultName] holding `keyboard-us`. */
        @Suppress("LoopWithTooManyJumpStatements")
        fun load(text: String?, available: Set<String>, defaultName: String): Profile {
            val values = text?.let(::read).orEmpty()
            val groups = LinkedHashMap<String, Group>()
            for (g in indexes(values, GROUPS)) {
                val path = "$GROUPS/$g"
                val name = values["$path/$NAME"].orEmpty()
                val layout = values["$path/$DEFAULT_LAYOUT"].orEmpty()
                if (name.isEmpty() || layout.isEmpty()) continue
                val read = indexes(values, "$path/$ITEMS").map { i ->
                    val im = values["$path/$ITEMS/$i/$NAME"].orEmpty()
                    val ours = InputMethodCatalog.fromUpstream(im)
                    Item(ours, values["$path/$ITEMS/$i/$LAYOUT"].orEmpty()) to (ours != im)
                }
                // a second group of the name is the first's: `groups_.emplace` keeps the first, the second's items appended, its layout and default over
                val existing = groups[name]
                val merged = ArrayList(existing?.items.orEmpty())
                // upstream's name beside the engine's own (a list half moved over), either first: listed once
                val renamed = read.filter { it.second }.mapTo(HashSet()) { it.first.name }
                for ((item, _) in read) {
                    if (item.name in available && !(item.name in renamed && merged.any { it.name == item.name })) merged += item
                }
                if (merged.isEmpty()) {
                    groups.remove(name)
                    continue
                }
                val default = ImGroup.of(name, merged.map(Item::name), InputMethodCatalog.fromUpstream(values["$path/$DEFAULT_IM"].orEmpty())).defaultInputMethod
                groups[name] = Group(name, layout, default, merged)
            }
            if (groups.isEmpty()) return default(defaultName)
            val named = indexes(values, GROUP_ORDER).mapNotNull { values["$GROUP_ORDER/$it"] }.filter { it in groups }.distinct()
            return Profile(groups.values.toList(), named + groups.keys.filter { it !in named })
        }

        /** `buildDefaultGroup`. */
        fun default(name: String) = Profile(listOf(Group(name, DEFAULT_LAYOUT_US, "", listOf(Item(ImGroup.KEYBOARD_US)))), listOf(name))

        // a vector option's items, `0`, `1`, … till the first missing
        private fun indexes(values: Map<String, String>, path: String): List<Int> =
            generateSequence(0) { it + 1 }.takeWhile { i -> values.keys.any { it.startsWith("$path/$i/") || it == "$path/$i" } }.toList()

        // `readFromIni`: values by path
        internal fun read(text: String): Map<String, String> = IniFile.read(text).leaves()
    }
}
