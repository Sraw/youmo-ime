/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * fcitx's `config/profile`, the input method groups (`InputMethodConfig`,
 * `fcitx/inputmethodconfig_p.h`): each group's name, layout, default input method and items, and
 * the group order, whose first is the current group. Read and written with fcitx's semantics, not
 * its bytes (design §2.1), so a file either writes is read the same by the other.
 *
 * [load] is `InputMethodManagerPrivate::loadConfig` (`fcitx/inputmethodmanager.cpp:82-140`); [text]
 * is `InputMethodManager::save` (`:427-454`).
 */
class Profile private constructor(val groups: List<Group>, val order: List<String>) {

    data class Item(val name: String, val layout: String = "")

    data class Group(val name: String, val defaultLayout: String, val defaultInputMethod: String, val items: List<Item>)

    /** The current group, the order's first. */
    val current: Group get() = groups.first { it.name == order.first() }

    /** The current group as the host switches on it. */
    fun currentGroup(): ImGroup = current.let { ImGroup.of(it.name, it.items.map(Item::name), it.defaultInputMethod) }

    /**
     * This with the current group's input methods and default as [group] has them: what fcitx's
     * `setGroup` keeps of a group (its items' layouts, by name; its default layout) and saves.
     */
    fun with(group: ImGroup): Profile {
        val old = current
        val layouts = old.items.associate { it.name to it.layout }
        val updated = Group(old.name, old.defaultLayout, group.defaultInputMethod, group.inputMethods.map { Item(it, layouts[it].orEmpty()) })
        return Profile(groups.map { if (it.name == old.name) updated else it }, order)
    }

    /**
     * The file as fcitx writes it (`writeAsIni`, `fcitx-config/iniparser.cpp:102-148`), through
     * [IniFile]: a value equal to its option's default (the empty string for all of these) is
     * implicit, written as a comment (`# Layout=`), so it reads as unset; the descriptions fcitx
     * writes above each are left out, as fcitx skips every comment it reads. Groups in their order,
     * then the order.
     */
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

        /** `native-lib`'s layout for every group it makes (`native-lib.cpp:199`) and fcitx's default group's. */
        const val DEFAULT_LAYOUT_US = "us"

        /**
         * `loadConfig`: [text] (null: no file) read; a group with no name or no default layout is
         * skipped, an item not [available] dropped, a group left empty removed, each group's default
         * set by [ImGroup]'s rule; the order is the file's (groups it does not name after it, unknown
         * names dropped), or the groups' when it has none. No valid group: fcitx's default one,
         * [defaultName] (its translated "Default") holding `keyboard-us` (`buildDefaultGroup`, `:142-162`).
         */
        @Suppress("LoopWithTooManyJumpStatements")
        fun load(text: String?, available: Set<String>, defaultName: String): Profile {
            val values = text?.let(::read).orEmpty()
            val groups = LinkedHashMap<String, Group>()
            for (g in indexes(values, GROUPS)) {
                val path = "$GROUPS/$g"
                val name = values["$path/$NAME"].orEmpty()
                val layout = values["$path/$DEFAULT_LAYOUT"].orEmpty()
                if (name.isEmpty() || layout.isEmpty()) continue
                val items = indexes(values, "$path/$ITEMS").map { i ->
                    Item(values["$path/$ITEMS/$i/$NAME"].orEmpty(), values["$path/$ITEMS/$i/$LAYOUT"].orEmpty())
                }.filter { it.name in available }
                // a second group of the name is the first's: `groups_.emplace` keeps the first, the
                // second's items appended to its list, its layout and default set over the first's
                val existing = groups[name]
                val merged = (existing?.items.orEmpty()) + items
                if (merged.isEmpty()) {
                    groups.remove(name)
                    continue
                }
                val default = ImGroup.of(name, merged.map(Item::name), values["$path/$DEFAULT_IM"].orEmpty()).defaultInputMethod
                groups[name] = Group(name, layout, default, merged)
            }
            if (groups.isEmpty()) return default(defaultName)
            val named = indexes(values, GROUP_ORDER).mapNotNull { values["$GROUP_ORDER/$it"] }.filter { it in groups }.distinct()
            return Profile(groups.values.toList(), named + groups.keys.filter { it !in named })
        }

        /** fcitx's default group (`buildDefaultGroup`, `inputmethodmanager.cpp:147-162`): [name], `keyboard-us` alone. */
        fun default(name: String) = Profile(listOf(Group(name, DEFAULT_LAYOUT_US, "", listOf(Item(ImGroup.KEYBOARD_US)))), listOf(name))

        // a vector option's items, `0`, `1`, … till the first missing (`marshallfunction.h:70-88`)
        private fun indexes(values: Map<String, String>, path: String): List<Int> =
            generateSequence(0) { it + 1 }.takeWhile { i -> values.keys.any { it.startsWith("$path/$i/") || it == "$path/$i" } }.toList()

        // `readFromIni` (`fcitx-config/iniparser.cpp:58-101`), [IniFile]'s: values by path
        internal fun read(text: String): Map<String, String> = IniFile.read(text).leaves()
    }
}
