/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem

/**
 * The scroll lists the user set, by category: [HEADER] then `category<TAB>kind|text…` lines, kind `t` a text, `r` one
 * typed as it is (不转中文标点), `c` a command's key, escaped as [KeyShortcuts] does. Apart from `key_lists`, whose
 * items are plain texts cut to its 16 by any older version's save. [parse] never throws.
 */
class ScrollListStore private constructor(
    private val lists: Map<String, List<ScrollItem>>,
    /** A later version's: shown as the defaults and never written over. */
    val newer: Boolean,
    private val newerText: String? = null,
) {

    /** As stored, a command this version does not offer included; the default where nothing is stored. */
    fun stored(id: String): List<ScrollItem> = lists[id] ?: ScrollDefaults.of(id).orEmpty()

    /** What the keyboard shows and the editor edits: the default where none stored is offered. */
    operator fun get(id: String): List<ScrollItem> = stored(id).filter(::offered).ifEmpty { ScrollDefaults.of(id).orEmpty() }

    fun isSet(id: String): Boolean = id in lists

    /** [items] as [id]'s shown ones, the commands not offered kept after them; none, or the default's, is no setting. */
    fun with(id: String, items: List<ScrollItem>): ScrollListStore {
        if (newer) return this
        val shown = cleaned(items)
        val all = cleaned(shown + stored(id).filterNot(::offered))
        val t = LinkedHashMap(lists)
        if (shown.isEmpty() || all == ScrollDefaults.of(id)) t.remove(id) else t[id] = all
        return ScrollListStore(t, newer = false)
    }

    /** 恢复默认. */
    fun without(ids: Collection<String>): ScrollListStore = if (newer) this else ScrollListStore(lists.filterKeys { it !in ids }, newer = false)

    /** The long press menu's: [i] of the shown ones. */
    fun removed(id: String, i: Int): ScrollListStore = with(id, get(id).filterIndexed { j, _ -> j != i })

    fun toFront(id: String, i: Int): ScrollListStore = get(id).let { l -> with(id, listOfNotNull(l.getOrNull(i)) + l.filterIndexed { j, _ -> j != i }) }

    fun serialize(): String = newerText ?: buildString {
        append(HEADER)
        for ((id, items) in lists) {
            append('\n').append(KeyShortcuts.escape(id))
            for (item in items) {
                append('\t').append(
                    when (item) {
                        is ScrollItem.Text -> (if (item.asIs) AS_IS else TEXT) + KeyShortcuts.escape(item.text)
                        is ScrollItem.Command -> COMMAND + KeyShortcuts.escape(item.key)
                    },
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is ScrollListStore && other.lists == lists && other.newer == newer

    override fun hashCode(): Int = lists.hashCode()

    override fun toString(): String = "ScrollListStore($lists${if (newer) ", newer" else ""})"

    companion object {
        const val HEADER = "@1"

        /** More than a long press's 16: the list scrolls, and the address one has 13 already. */
        const val MAX_ITEMS = 24

        private const val TEXT = "t|"
        private const val AS_IS = "r|"
        private const val COMMAND = "c|"
        private val VERSION = Regex("@([0-9]+)")

        val Empty = ScrollListStore(emptyMap(), newer = false)

        /** Not this format, or nothing: [Empty]. */
        fun parse(text: String?): ScrollListStore {
            val lines = text?.trimStart { it.isWhitespace() || it == '﻿' }?.lines() ?: return Empty
            val digits = VERSION.matchEntire(lines.first().trimEnd())?.groupValues?.get(1) ?: return Empty
            // past an Int is still a later version's, never one to write over
            val version = digits.toIntOrNull() ?: Int.MAX_VALUE
            if (version > 1) return ScrollListStore(emptyMap(), newer = true, newerText = text)
            val t = LinkedHashMap<String, List<ScrollItem>>()
            for (line in lines.drop(1)) {
                val parts = line.trimEnd('\r').split('\t')
                val id = KeyShortcuts.unescape(parts[0])
                val items = cleaned(parts.drop(1).mapNotNull(::itemOf))
                // a later line of the same category wins, as key_lists' does
                if (id.isNotEmpty() && items.isNotEmpty()) t[id] = items
            }
            return ScrollListStore(t, newer = false)
        }

        /** Only the commands of KeyShortcut's swipe list a tap can run: no 划动选择, no strokes. */
        fun offered(item: ScrollItem): Boolean = when (item) {
            is ScrollItem.Text -> true
            is ScrollItem.Command -> KeyCommand.ofKey(item.key)?.let { it.group != KeyCommand.Group.Stroke && it != KeyCommand.SwipeSelect } == true
        }

        /** What 加命令… offers. */
        val COMMANDS: List<KeyCommand> get() = KeyCommand.entries.filter { offered(ScrollItem.Command(it.key)) }

        /** Only these two convert marks, so only their items can be 不转中文标点. */
        fun keepsAsIs(id: String): Boolean = id == ScrollDefaults.NINE || id == ScrollDefaults.NINE_ENGLISH

        // no typed item (PopupOverrides.tokens) nor stored text starts with it, so a text named as a command stays a text
        private const val EDITOR_COMMAND = "\u0000"

        /** As PopupEditor holds it: a text as itself, a command marked off from any text. */
        fun editorItem(item: ScrollItem): String = when (item) {
            is ScrollItem.Text -> item.text
            is ScrollItem.Command -> EDITOR_COMMAND + item.key
        }

        /** Null for a text. */
        fun commandIn(editorItem: String): String? = editorItem.takeIf { it.startsWith(EDITOR_COMMAND) }?.drop(EDITOR_COMMAND.length)

        /** [items]' editor strings and their marks. */
        fun editing(items: List<ScrollItem>): Pair<List<String>, Map<String, ItemMark>> =
            items.map(::editorItem) to items.filter { it is ScrollItem.Text && it.asIs }.associate { editorItem(it) to ItemMark.AsIs }

        /** Back from the editor: [ItemMark.AsIs] is the one mark a scroll list keeps. */
        fun edited(items: List<String>, marks: Map<String, ItemMark>): List<ScrollItem> = items.map { item ->
            commandIn(item)?.let { ScrollItem.Command(it) } ?: ScrollItem.Text(item, asIs = marks[item] == ItemMark.AsIs)
        }

        private fun itemOf(stored: String): ScrollItem? {
            val rest = KeyShortcuts.unescape(stored.drop(TEXT.length))
            return when {
                stored.startsWith(TEXT) -> ScrollItem.Text(rest)
                stored.startsWith(AS_IS) -> ScrollItem.Text(rest, asIs = true)
                stored.startsWith(COMMAND) -> ScrollItem.Command(rest)
                else -> null
            }
        }

        // a text clipped as a long press's item; a command's key never, a later version's kept whole to write back
        private fun cleaned(items: List<ScrollItem>): List<ScrollItem> {
            var shown = 0
            return items.asSequence()
                .map { if (it is ScrollItem.Text) it.copy(text = KeyLists.clip(it.text)) else it }
                .filter {
                    when (it) {
                        is ScrollItem.Text -> it.text.isNotBlank() && !PopupOverrides.rejects(it.text)
                        is ScrollItem.Command -> it.key.isNotBlank()
                    }
                }
                // a text `copy` and the command copy are two
                .distinctBy {
                    when (it) {
                        is ScrollItem.Text -> TEXT + it.text
                        is ScrollItem.Command -> COMMAND + it.key
                    }
                }
                // only what this version shows counts: a later version's commands are written back past the limit
                .filter { !offered(it) || shown++ < MAX_ITEMS }
                .toList()
        }
    }
}
